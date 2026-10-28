package com.splashskip.a11y

import android.accessibilityservice.AccessibilityService
import android.content.res.Configuration
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import com.splashskip.core.engine.AppMode
import com.splashskip.core.engine.Decision
import com.splashskip.core.engine.EngineConfig
import com.splashskip.core.engine.EngineHost
import com.splashskip.core.engine.HitRecord
import com.splashskip.core.engine.SkipEngine
import com.splashskip.core.match.RuleIndex
import com.splashskip.core.model.Action
import com.splashskip.core.model.AppRule
import com.splashskip.core.model.NodeSnapshot
import com.splashskip.core.model.ScreenSize
import com.splashskip.core.model.Step
import com.splashskip.rules.RuleRepository
import com.splashskip.rules.RuleRepositoryProvider

/**
 * 无障碍服务 —— 整个 App 的唯一入口。
 *
 * 线程模型（这是最容易写错的地方）：
 *
 *  主线程（系统回调线程）
 *    onAccessibilityEvent  ← 必须 1ms 内返回，只做包名/类型过滤后 post
 *         │
 *         ▼
 *  skip-worker（HandlerThread，单线程串行）
 *    evaluate → SkipEngine.onWindow → NodeTreeWalker.walk → 点击
 *
 * 为什么必须切线程：遍历一个节点树要读几百次 IPC（每次 getChild 都是一次
 * binder 调用），在主线程做必然掉帧甚至 ANR。而串行执行又保证了
 * 「同一时刻只有一个匹配在跑」，不需要给引擎加锁。
 */
class SplashSkipService : AccessibilityService(), EngineHost {

    private lateinit var rules: RuleRepository
    private lateinit var config: AppConfigStore
    private lateinit var hits: HitLogStore
    private lateinit var snapshots: SnapshotStore
    private lateinit var engine: SkipEngine
    private lateinit var workerThread: HandlerThread
    private lateinit var worker: Handler
    private lateinit var scheduler: RetryScheduler

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var screen = ScreenSize(0, 0, 1f)

    private var lastPkg: String? = null
    private var lastActivity: String? = null
    private var activeTree: A11yTree? = null
    private var lastEventAt = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        rules = RuleRepositoryProvider.get(this)
        config = AppConfigStore.get(this)
        hits = HitLogStore.get(this)
        snapshots = SnapshotStore(this)

        workerThread = HandlerThread("skip-worker").apply { start() }
        worker = Handler(workerThread.looper)
        scheduler = RetryScheduler(worker)
        engine = SkipEngine(this, EngineConfig())

        screen = refreshScreen()
        INSTANCE = this
        Log.i(TAG, "无障碍服务已连接，规则 ${rules.currentIndex().size} 条")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        val pkg = e.packageName?.toString() ?: return

        // 自己的界面不处理，否则会在设置页里自己点自己
        if (pkg == packageName) return

        when (e.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                // 只有 WINDOW_STATE_CHANGED 的 className 才是 Activity 全名，
                // CONTENT_CHANGED 的 className 是 View 类名，绝不能混用
                lastPkg = pkg
                lastActivity = e.className?.toString()
                engine.reset()
                scheduler.cancelAll()
                engine.pruneCooldown(now())
                submit(pkg, lastActivity, fromEvent = true)
            }

            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            -> {
                // 包名与当前窗口不一致时（输入法、悬浮窗、系统弹层）直接丢弃
                if (pkg != lastPkg) return
                submit(pkg, lastActivity, fromEvent = true)
            }
        }
    }

    override fun onInterrupt() {
        if (::scheduler.isInitialized) scheduler.cancelAll()
        if (::engine.isInitialized) engine.reset()
    }

    /** 旋转屏幕 / 分屏切换后，区域护栏的归一化基准会变，必须重算。 */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::engine.isInitialized) screen = refreshScreen()
    }

    override fun onDestroy() {
        // onDestroy 可能在 onServiceConnected 之前被调用（服务被系统立即踢掉），
        // 直接碰 lateinit 会崩，必须判空
        if (::scheduler.isInitialized) scheduler.cancelAll()
        if (::workerThread.isInitialized) workerThread.quitSafely()
        INSTANCE = null
        super.onDestroy()
    }

    // ---------------- 内部流程 ----------------

    private fun submit(pkg: String, activity: String?, fromEvent: Boolean) {
        if (!config.globalEnabled) return
        if (!::scheduler.isInitialized) return

        if (fromEvent) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastEventAt < EVENT_THROTTLE_MS) {
                // 节流窗口内：用 debounce 补最后一次，防止「刚好在节流期内变化」被漏掉
                scheduler.debounce(EVENT_THROTTLE_MS) { evaluate(pkg, activity) }
                return
            }
            lastEventAt = now
        }
        worker.post { evaluate(pkg, activity) }
    }

    private fun evaluate(pkg: String, activity: String?) {
        val decision = engine.onWindow(pkg, activity, screen) {
            val tree = NodeTreeWalker.walk(
                root = rootInActiveWindow,
                service = this,
                maxNodes = MAX_NODES,
                maxDepth = MAX_DEPTH,
                // GKD 规则靠父子/兄弟关系定位节点，纯容器节点剪掉就会把树剪断，
                // 所以有 GKD 规则时保留完整树。
                keepStructural = needsStructuralTree(pkg),
            )
            activeTree = tree
            tree.nodes
        }

        // 快照用完立刻归还系统节点，避免低版本 Android 节点池耗尽
        activeTree?.close()
        activeTree = null

        when (decision) {
            is Decision.Retry -> scheduler.schedule(decision.afterMs) { evaluate(pkg, activity) }
            is Decision.Exhausted -> Log.d(TAG, "放弃 $pkg/${activity}：${decision.reason}")
            is Decision.NeedsConfirm -> ConfirmNotifier.show(this, decision.rule)
            Decision.Skipped -> Log.d(TAG, "已跳过 $pkg")
            Decision.Idle -> Unit
        }
    }

    /**
     * 当前包是否需要保留完整的节点树。
     *
     * 只有「这个包专属的 GKD 规则」才需要。
     * GKD 的全局规则（packages = "*"）用的是简单选择器，
     * 不值得让所有 App 都背上完整树的遍历开销。
     */
    private fun needsStructuralTree(pkg: String): Boolean =
        rules.currentIndex().candidates(pkg).any { rule ->
            rule.steps.isNotEmpty() &&
                rule.packages.any { p ->
                    p != "*" && (p == pkg || (p.endsWith(".*") && pkg.startsWith(p.dropLast(1))))
                }
        }

    private fun refreshScreen(): ScreenSize {        val wm = getSystemService(WindowManager::class.java)
        val density = resources.displayMetrics.density
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val b = wm.currentWindowMetrics.bounds
                ScreenSize(b.width(), b.height(), density)
            } else {
                val dm = DisplayMetrics()
                @Suppress("DEPRECATION")
                wm.defaultDisplay.getRealMetrics(dm)
                ScreenSize(dm.widthPixels, dm.heightPixels, dm.density)
            }
        }.getOrDefault(ScreenSize(0, 0, density))
    }

    // ---------------- EngineHost ----------------

    override fun currentRules(): RuleIndex = rules.currentIndex()

    override fun modeOf(pkg: String): AppMode = config.modeOf(pkg)

    override fun isConfirmed(pkg: String): Boolean = rules.isConfirmed(pkg)

    override fun now(): Long = SystemClock.elapsedRealtime()

    override fun onHit(record: HitRecord) {
        // 引擎内部用 elapsedRealtime 做时序计算（单调，不受系统改时间影响），
        // 但落盘给用户看的时间必须是墙钟时间，这里做一次换算
        hits.log(record.copy(at = System.currentTimeMillis()))
        Log.d(
            TAG,
            "命中 ${record.ruleId} '${record.matchedText}' success=${record.success} ${record.note}",
        )
    }

    override fun onConfirmNeeded(rule: AppRule) {
        ConfirmNotifier.show(this, rule)
    }

    override fun perform(node: NodeSnapshot, step: Step): Boolean {
        val handle = node.handle ?: return false

        if (step.postDelayMs > 0) {
            // 当前在 skip-worker 线程，sleep 是安全的；主线程绝不允许这么干
            runCatching { Thread.sleep(step.postDelayMs) }
        }

        // Shizuku 通道优先：直接注入坐标，绕开无障碍的事件分发，延迟更低
        if (shouldUseShizuku()) {
            val b = node.bounds
            if (b.width > 0 && b.height > 0) {
                if (ShizukuBridge.tap(b.centerX, b.centerY)) return true
                Log.w(TAG, "Shizuku 点击失败，回退无障碍通道")
            }
        }

        return when (step.action) {
            // 关键降级链：原生点击失败就用手势点中心
            Action.CLICK_NODE -> if (handle.click()) true else handle.clickCenter()
            Action.CLICK_CENTER -> handle.clickCenter()
            Action.BACK -> handle.back()
            Action.NONE -> true
        }
    }

    /** AUTO 语义：Shizuku 就绪就用它，否则回退无障碍。 */
    private fun shouldUseShizuku(): Boolean = when (config.clickMode) {
        ClickMode.ACCESSIBILITY -> false
        ClickMode.SHIZUKU, ClickMode.AUTO -> ShizukuBridge.isReady(this)
    }

    // ---------------- 快照抓取 ----------------

    /**
     * 延时抓取当前前台窗口的完整节点树。
     *
     * 为什么是「延时」而非立即：在 App 里点完按钮时目标界面还没起来，
     * 留 5 秒给用户切过去，抓到的才是目标 App 的树。
     *
     * 抓取和写盘都在 skip-worker 线程，回调切回主线程（调用方通常是 UI）。
     */
    fun scheduleSnapshot(delayMs: Long, onDone: (SnapshotDoc?, String?) -> Unit) {
        if (!::worker.isInitialized) {
            onDone(null, "无障碍服务尚未就绪")
            return
        }
        worker.postDelayed({
            var doc: SnapshotDoc? = null
            var error: String? = null
            try {
                val root = rootInActiveWindow
                if (root == null) {
                    error = "当前没有可读窗口，确认目标 App 在前台且无障碍已开启"
                } else {
                    val d = RawTreeDumper.dump(root, "", lastActivity, screen)
                    snapshots.save(d)
                    doc = d
                }
            } catch (e: Exception) {
                error = "抓取异常：${e.message}"
            }
            val result = doc
            val err = error
            mainHandler.post { onDone(result, err) }
        }, delayMs.coerceAtLeast(0L))
    }

    /**
     * 给 UI 用的快照仓库。
     * 返回 null 表示服务还没连上（无障碍没开），此时 UI 应该引导用户去开。
     */
    fun snapshotStore(): SnapshotStore? =
        if (::snapshots.isInitialized) snapshots else null

    companion object {
        private const val TAG = "SplashSkipService"
        private const val EVENT_THROTTLE_MS = 40L
        private const val MAX_NODES = 1200
        private const val MAX_DEPTH = 26

        @Volatile
        var INSTANCE: SplashSkipService? = null
            private set

        val isRunning: Boolean get() = INSTANCE != null
    }
}
