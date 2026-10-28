package com.splashskip.a11y

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import rikka.shizuku.Shizuku

/**
 * Shizuku 桥接：免无障碍的点击通道。
 *
 * 为什么需要它？无障碍点击有两个硬伤：
 *   1. 必须先开无障碍服务，而国产 ROM 对这个权限卡得很死（还有「受限制的设置」拦截）；
 *   2. 每次点击要走无障碍框架的事件分发，延迟 100~200ms，且常驻服务本身有功耗。
 *
 * Shizuku 走的是另一条路：它以 shell 身份运行（用户用 adb 或 root 启动），
 * 第三方 App 通过 binder 借它的权限执行命令 —— shell 天然有 `input` 命令的权限，
 * 于是可以像 `adb shell input tap` 一样直接注入触摸事件，完全绕开无障碍。
 *
 * 实现上刻意选了 `input tap` 子进程方案，而不是反射 `IInputManager.injectInputEvent`：
 *   · 反射隐藏 API 在不同 Android 版本上签名会变，维护成本高；
 *   · `input` 是 shell 命令，跨版本极其稳定；
 *   · 代价是每次点击要 fork 一个进程（约 100~300ms），对开屏这种低频场景完全够用。
 */
object ShizukuBridge {

    private const val TAG = "ShizukuBridge"
    const val SHIZUKU_PACKAGE = "moe.shizuku.manager"

    enum class State {
        /** 没装 Shizuku */
        NOT_INSTALLED,

        /** 装了但服务没跑（用户没执行 adb 启动命令，或重启后失效） */
        NOT_RUNNING,

        /** 服务在跑，但本应用没拿到授权 */
        NO_PERMISSION,

        /** 可用 */
        READY,
    }

    fun isInstalled(context: Context): Boolean = runCatching {
        context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
        true
    }.getOrDefault(false)

    /** binder 活着说明 Shizuku 服务在跑。 */
    fun isBinderAlive(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    fun hasPermission(): Boolean = runCatching {
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    fun requestPermission(requestCode: Int) {
        runCatching {
            // Shizuku v11 之前不支持运行时弹授权框
            if (Shizuku.isPreV11()) {
                Log.w(TAG, "Shizuku 版本过低，请在 Shizuku 管理器中手动授权")
                return
            }
            Shizuku.requestPermission(requestCode)
        }.onFailure { Log.w(TAG, "请求授权失败", it) }
    }

    /**
     * 授权结果监听。
     *
     * 刻意在 :a11y 里包一层而不是让 UI 直接引用 `rikka.shizuku.Shizuku` ——
     * Shizuku 只是 `implementation` 依赖，不向上传递；
     * 更重要的原因是 UI 层不该知道底层用哪个提权框架，换框架时只改这一个文件。
     *
     * 注册和注销必须用同一个 lambda 引用，否则 remove 会失效。
     */
    private val permissionListeners =
        mutableMapOf<() -> Unit, Shizuku.OnRequestPermissionResultListener>()

    fun addPermissionResultListener(callback: () -> Unit) {
        if (permissionListeners.containsKey(callback)) return
        val listener = Shizuku.OnRequestPermissionResultListener { _, _ -> callback() }
        permissionListeners[callback] = listener
        runCatching { Shizuku.addRequestPermissionResultListener(listener) }
    }

    fun removePermissionResultListener(callback: () -> Unit) {
        val listener = permissionListeners.remove(callback) ?: return
        runCatching { Shizuku.removeRequestPermissionResultListener(listener) }
    }

    fun state(context: Context): State = when {
        !isInstalled(context) -> State.NOT_INSTALLED
        !isBinderAlive() -> State.NOT_RUNNING
        !hasPermission() -> State.NO_PERMISSION
        else -> State.READY
    }

    fun isReady(context: Context): Boolean = state(context) == State.READY

    /**
     * `Shizuku.newProcess` 在 13.x 里被标成了 private（不是公开 API），
     * 所以只能反射调。这是本项目唯一一处反射调用隐藏实现的地方，
     * 失败时静默降级 —— 点不了就走无障碍，不能因为 Shizuku 不给用就整个不工作。
     *
     * proguard 里必须 keep `rikka.shizuku.Shizuku` 的成员名，否则 release 包反射必挂。
     */
    private val newProcessMethod: java.lang.reflect.Method? by lazy {
        runCatching {
            Shizuku::class.java
                .getDeclaredMethod(
                    "newProcess",
                    Array<String>::class.java,
                    Array<String>::class.java,
                    String::class.java,
                )
                .apply { isAccessible = true }
        }.onFailure { Log.w(TAG, "反射 newProcess 失败", it) }.getOrNull()
    }

    /**
     * 注入一次点击。会阻塞到子进程结束，**必须在后台线程调用**。
     *
     * @return true 表示 `input` 命令退出码为 0（注意：这只代表命令执行成功，
     *         不保证目标 App 一定响应了这个坐标，跟无障碍的 performAction 是一样的问题）
     */
    fun tap(x: Int, y: Int): Boolean {
        if (!isBinderAlive() || !hasPermission()) return false
        val method = newProcessMethod ?: return false
        return runCatching {
            val process = method.invoke(
                null,
                arrayOf("sh", "-c", "input tap $x $y"),
                null,
                null,
            ) as? Process ?: return@runCatching false
            val code = process.waitFor()
            Log.d(TAG, "input tap $x,$y -> exit=$code")
            code == 0
        }.onFailure { Log.w(TAG, "Shizuku 点击失败", it) }.getOrDefault(false)
    }

    /** 给 UI 用的状态文案。 */
    fun stateText(context: Context): String = when (state(context)) {
        State.NOT_INSTALLED -> "未安装 Shizuku"
        State.NOT_RUNNING -> "Shizuku 服务未运行"
        State.NO_PERMISSION -> "未授权"
        State.READY -> "已就绪"
    }
}
