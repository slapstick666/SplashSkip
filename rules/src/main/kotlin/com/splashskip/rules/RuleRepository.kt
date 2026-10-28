package com.splashskip.rules

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.splashskip.core.match.RuleIndex
import com.splashskip.core.model.AppRule
import com.splashskip.core.model.Subscription
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * 规则仓库：多订阅合并 + 内存快照 + 分类开关。
 *
 * 规则的唯一来源是 GKD 格式订阅（1.2.0 起删掉了自研的扁平 JSON 格式和
 * user.json 手写规则 —— GKD 生态里现成有几千条社区维护的规则，
 * 自造格式的维护成本远大于收益）。
 *
 * 多份订阅按 rule.id 合并，撞 id 的由 GkdCodec 生成时去重。
 *
 * ## 全量解析，按分类执行
 *
 * 1.3.0 起订阅全量解析（3800+ 条），但**只有被勾选的分类才进 RuleIndex**。
 * 原因：GKD 订阅里除了「开屏广告」，还有「局部广告」「功能类」「更新提示」等
 * 十个分类，全放出去会在用户正常刷信息流、点设置的时候自动动手 —— 实测挨骂。
 * 默认只开订阅作者标 `enable: true` 的分类（Lin-arm 订阅就是「开屏广告」），
 * 用户在订阅页可以自己加开。
 *
 * 这样「订阅里有 3864 条」和「实际生效 298 条」两个数字都看得到，
 * 既不丢规则也不乱点。
 *
 * 所有读取路径都通过 [index] 这个 volatile 快照 —— 无障碍事件是高频调用，
 * 绝不能每次都去碰文件系统。写操作在自己的锁里完成，写完整体替换快照。
 */
class RuleRepository(private val context: Context) {

    private val root = File(context.filesDir, "rules")
    private val remoteDir = File(root, "remote")
    private val confirmFile = File(root, "confirmed.json")
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_CATEGORIES, Context.MODE_PRIVATE)

    private val lock = Any()
    private val gson = Gson()

    /**
     * 订阅解析是纯 CPU 活：1.3MB JSON5 + 四千多条选择器，冷启动要 1~2 秒。
     * 放在主线程里做就是一次启动白屏，所以初始化只建目录，
     * 解析丢到单线程执行器里，完成后原子替换快照并通知 UI。
     */
    private val loader = Executors.newSingleThreadExecutor { r ->
        Thread(r, "rules-loader").apply { isDaemon = true }
    }
    private val readyListeners = CopyOnWriteArrayList<() -> Unit>()

    @Volatile
    var isReady: Boolean = false
        private set

    @Volatile
    private var index: RuleIndex = RuleIndex.EMPTY

    @Volatile
    private var rulesById: Map<String, AppRule> = emptyMap()

    /** 排好序的快照，避免每次 UI 刷新都对几千条规则重排。 */
    @Volatile
    private var sortedRules: List<AppRule> = emptyList()

    @Volatile
    private var confirmations: Set<String> = emptySet()

    /** 当前启用的分类。空集表示一条规则都不生效（用户全关了）。 */
    @Volatile
    private var enabledCategories: Set<String> = emptySet()

    /** 是否已初始化过分类开关 —— 区分「用户全关」和「还没定过默认值」。 */
    @Volatile
    private var categoriesInitialized: Boolean = false

    @Volatile
    var lastErrors: List<String> = emptyList()
        private set

    /** 订阅文件解析缓存，键是 (路径, mtime, 长度)。只在锁内访问。 */
    private val parseCache = HashMap<CacheKey, ParseResult>()

    private data class CacheKey(val path: String, val modified: Long, val length: Long)

    init {
        ensureDirs()
        loader.execute {
            synchronized(lock) { reloadLocked() }
            isReady = true
            readyListeners.forEach { runCatching { it() } }
            readyListeners.clear()
        }
    }

    /**
     * 规则首次加载完成后回调（在 loader 线程上）。已经加载完时立刻执行。
     * UI 侧需要自己切回主线程。
     */
    fun onReady(listener: () -> Unit) {
        if (isReady) {
            listener()
            return
        }
        readyListeners.add(listener)
        // 竞态兜底：注册前一刻刚好加载完，上面的 flag 读到 false 但回调已经发过了
        if (isReady && readyListeners.remove(listener)) listener()
    }

    // ---------------- 读 ----------------

    /** 热路径入口，必须无锁无 IO。 */
    fun currentIndex(): RuleIndex = index

    fun ruleById(id: String): AppRule? = rulesById[id]

    fun allRules(): List<AppRule> = sortedRules

    /** 学习模式按包名授权，不是按规则 id —— 见 EngineHost.isConfirmed。 */
    fun isConfirmed(pkg: String): Boolean = confirmations.contains(pkg)

    /** 已落盘的某个订阅的版本号，不存在返回 null。用于增量更新判断。 */
    fun versionOf(subId: String): Long? = runCatching {
        val f = File(remoteDir, "$subId.json")
        if (!f.exists()) return null
        GkdCodec.parseSubscription(f.readText()).subscription?.version
    }.getOrNull()

    // ---------------- 写 ----------------

    /** 保存从远程拉取的 GKD 订阅。rawJson 原样落盘，保留可再次解析的原文。 */
    fun saveRemote(rawJson: String, sub: Subscription): Boolean {
        synchronized(lock) {
            return try {
                ensureDirs()
                File(remoteDir, "${sub.id}.json").writeText(rawJson)
                reloadLocked()
                true
            } catch (e: Exception) {
                Log.e(TAG, "保存远程订阅失败", e)
                false
            }
        }
    }

    fun setConfirmed(pkg: String, confirmed: Boolean) {
        synchronized(lock) {
            confirmations = if (confirmed) confirmations + pkg else confirmations - pkg
            runCatching { confirmFile.writeText(gson.toJson(confirmations)) }
        }
    }

    fun removeRemote(subId: String) {
        synchronized(lock) {
            runCatching { File(remoteDir, "$subId.json").delete() }
            reloadLocked()
        }
    }

    /**
     * 列出所有已落盘的订阅。
     *
     * 走 parseCached 而不是每次都重新解析 —— 一份 1.3MB 的订阅解析要几百毫秒，
     * 规则页每次 onResume 都重新解析会明显卡顿。
     */
    fun listSubscriptions(): List<Subscription> = synchronized(lock) {
        remoteFiles().mapNotNull { f ->
            runCatching { parseCached(f).subscription }.getOrNull()
        }
    }

    fun reload() {
        synchronized(lock) { reloadLocked() }
    }

    // ---------------- 分类开关 ----------------

    /**
     * 每个分类的规则数与启用状态，顺序跟订阅里声明的 categories 一致。
     * UI 直接拿去渲染开关列表。
     */
    fun categoryStats(): List<CategoryStat> {
        val counts = sortedRules.groupingBy { it.category.ifEmpty { UNCATEGORIZED } }.eachCount()
        if (counts.isEmpty()) return emptyList()

        val order = synchronized(lock) {
            remoteFiles()
                .flatMap { f -> runCatching { parseCached(f).subscription?.categories.orEmpty() }.getOrDefault(emptyList()) }
                .map { it.name }
                .distinct()
        }

        val enabled = enabledCategories
        val ordered = order.filter { counts.containsKey(it) } +
            counts.keys.filter { it !in order }.sorted()

        return ordered.map { name ->
            CategoryStat(
                name = name,
                ruleCount = counts[name] ?: 0,
                enabled = name in enabled,
            )
        }
    }

    fun setCategoryEnabled(name: String, enabled: Boolean) {
        synchronized(lock) {
            enabledCategories = if (enabled) enabledCategories + name else enabledCategories - name
            categoriesInitialized = true
            prefs.edit().putStringSet(KEY_ENABLED_CATEGORIES, enabledCategories).apply()
            rebuildIndexLocked()
            Log.i(TAG, "分类开关：$name -> $enabled，生效规则 ${index.size} 条")
        }
    }

    // ---------------- 内部 ----------------

    private fun reloadLocked() {
        val errors = mutableListOf<String>()
        val merged = LinkedHashMap<String, AppRule>()
        val subs = mutableListOf<Subscription>()

        remoteFiles().forEach { f ->
            Log.i(TAG, "加载远程订阅文件：${f.name}（${f.length()} 字节）")
            val r = runCatching { parseCached(f) }
                .getOrElse {
                    Log.e(TAG, "读取 ${f.name} 失败", it)
                    ParseResult(null, emptyList(), "读取 ${f.name} 失败：${it.message}")
                }
            r.fatal?.let { errors += "远程 ${f.name}：$it" }
            errors += r.warnings.map { "远程 ${f.name}：$it" }
            r.subscription?.let { sub ->
                subs += sub
                sub.rules.forEach { merged[it.id] = it }
            }
        }

        confirmations = runCatching {
            if (!confirmFile.exists()) emptySet()
            else gson.fromJson<Set<String>>(
                confirmFile.readText(),
                object : TypeToken<Set<String>>() {}.type,
            ) ?: emptySet()
        }.getOrDefault(emptySet())

        // 分类开关：用户没动过就用订阅作者声明的默认值（Lin-arm 订阅只有「开屏广告」）
        if (!categoriesInitialized) {
            val saved = prefs.getStringSet(KEY_ENABLED_CATEGORIES, null)
            if (saved != null) {
                enabledCategories = saved
                categoriesInitialized = true
            } else {
                enabledCategories = defaultCategories(subs)
            }
        }

        rulesById = merged
        // 全量导入后规则上千条，按包名分组再按名称排 —— 用户找某个 App 的规则时
        // 看到的是连续一段，而不是散落在几千条里
        sortedRules = merged.values.sortedWith(
            compareBy({ it.packages.firstOrNull().orEmpty() }, { it.name }),
        )
        rebuildIndexLocked()
        lastErrors = errors
    }

    /**
     * 默认启用的分类：订阅里 `enable: true` 的那几个。
     *
     * 兜底链：订阅没声明默认项 → 用「开屏广告」（本 App 的本职）；
     * 订阅里连开屏分类都没有 → 用它声明的第一个分类；
     * 老订阅根本没有 categories → 返回空集，规则自身 category 为空会被全放行。
     */
    private fun defaultCategories(subs: List<Subscription>): Set<String> {
        val declared = subs.flatMap { it.categories }
            .filter { it.enabledByDefault }
            .map { it.name }
            .toSet()
        if (declared.isNotEmpty()) return declared

        val names = subs.flatMap { it.categories }.map { it.name }
        return when {
            GkdCodec.SPLASH_CATEGORY in names -> setOf(GkdCodec.SPLASH_CATEGORY)
            names.isNotEmpty() -> setOf(names.first())
            else -> emptySet()
        }
    }

    /** 按当前分类开关重建匹配索引。全量规则仍留在 [rulesById] 里给 UI 看。 */
    private fun rebuildIndexLocked() {
        val enabled = enabledCategories
        val active = rulesById.values.filter { rule ->
            // 没有分类信息的（老订阅）一律放行
            rule.category.isEmpty() || rule.category in enabled
        }
        index = RuleIndex.of(active)
    }

    private fun remoteFiles(): List<File> =
        remoteDir.listFiles { f -> f.isFile && f.name.endsWith(".json") }?.toList().orEmpty()

    /**
     * 带缓存的解析。
     *
     * 为什么需要缓存：GKD 订阅动辄 1.3MB 的 JSON5，解析一次要几百毫秒。
     * 而 reload() 会在每次订阅增删改、每次 App 启动时都跑一遍，
     * 不缓存的话光是启动就要多等一秒。
     *
     * 缓存键带上 mtime 与文件长度，文件一变自然失效。
     */
    private fun parseCached(file: File): ParseResult {
        val key = CacheKey(file.absolutePath, file.lastModified(), file.length())
        parseCache[key]?.let { return it }
        val result = GkdCodec.parseSubscription(file.readText())
        // 只留最近的几份，防止长期运行把内存吃光
        if (parseCache.size > 8) parseCache.clear()
        parseCache[key] = result
        return result
    }

    private fun ensureDirs() {
        if (!root.exists()) root.mkdirs()
        if (!remoteDir.exists()) remoteDir.mkdirs()
    }

    private companion object {
        const val TAG = "RuleRepository"
        const val PREFS_CATEGORIES = "splashskip.categories"
        const val KEY_ENABLED_CATEGORIES = "enabled"
        const val UNCATEGORIZED = "未分类"
    }
}

/**
 * 一个分类的展示信息。
 *
 * @param ruleCount 该分类下解析成功的规则数（不是订阅原文里的条数）
 * @param enabled   当前是否参与匹配
 */
data class CategoryStat(
    val name: String,
    val ruleCount: Int,
    val enabled: Boolean,
)
