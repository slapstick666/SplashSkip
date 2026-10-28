package com.splashskip.a11y

import android.content.Context
import com.splashskip.core.engine.AppMode

/**
 * 点击通道。
 *
 * AUTO 是默认值：Shizuku 可用就走 Shizuku（更快、不依赖无障碍常驻），
 * 不可用就回退无障碍。用户也可以强制指定某一种，方便排查问题。
 */
enum class ClickMode {
    AUTO,
    ACCESSIBILITY,
    SHIZUKU,
}

/**
 * 运行时配置存储。
 *
 * 两层配置，优先级从低到高：
 *   1. 全局开关 + 全局默认模式
 *   2. 单 App 覆盖（模式）
 *
 * 用 SharedPreferences 而不是 DataStore：这里全是标量、读取在 worker 线程、
 * 没有 Flow 需求，SharedPreferences 的同步读取反而更合适。
 * 在 init 里一次性把全量读进内存，热路径就完全没有 IO。
 */
class AppConfigStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Volatile
    var globalEnabled: Boolean = prefs.getBoolean(KEY_ENABLED, true)
        set(value) {
            field = value
            prefs.edit().putBoolean(KEY_ENABLED, value).apply()
        }

    /** 全局默认模式。AUTO = 全自动，LEARN = 半自动（首次询问）。 */
    @Volatile
    var globalMode: AppMode = readMode(prefs.getString(KEY_GLOBAL_MODE, null), AppMode.AUTO)
        set(value) {
            field = value
            prefs.edit().putString(KEY_GLOBAL_MODE, value.name).apply()
        }

    /** 点击通道选择。 */
    @Volatile
    var clickMode: ClickMode = runCatching {
        ClickMode.valueOf(prefs.getString(KEY_CLICK_MODE, null) ?: ClickMode.AUTO.name)
    }.getOrDefault(ClickMode.AUTO)
        set(value) {
            field = value
            prefs.edit().putString(KEY_CLICK_MODE, value.name).apply()
        }

    private val modeCache = HashMap<String, AppMode>()
    private val lock = Any()

    init {
        prefs.all.forEach { (k, v) ->
            if (k.startsWith(PREFIX_MODE) && v is String) {
                modeCache[k.removePrefix(PREFIX_MODE)] = readMode(v, AppMode.AUTO)
            }
        }
    }

    fun modeOf(pkg: String): AppMode {
        if (!globalEnabled) return AppMode.DISABLED
        synchronized(lock) { modeCache[pkg] }?.let { return it }
        return globalMode
    }

    fun setMode(pkg: String, mode: AppMode) {
        synchronized(lock) { modeCache[pkg] = mode }
        prefs.edit().putString(PREFIX_MODE + pkg, mode.name).apply()
    }

    fun clearOverride(pkg: String) {
        synchronized(lock) { modeCache.remove(pkg) }
        prefs.edit().remove(PREFIX_MODE + pkg).apply()
    }

    /** 有单 App 覆盖的包名 → 模式，给 UI 列表用。 */
    fun overrides(): Map<String, AppMode> = synchronized(lock) { HashMap(modeCache) }

    private fun readMode(raw: String?, def: AppMode): AppMode =
        raw?.let { runCatching { AppMode.valueOf(it) }.getOrNull() } ?: def

    companion object {
        private const val PREFS = "splashskip.cfg"
        private const val KEY_ENABLED = "global_enabled"
        private const val KEY_GLOBAL_MODE = "global_mode"
        private const val KEY_CLICK_MODE = "click_mode"
        private const val PREFIX_MODE = "mode:"

        @Volatile
        private var instance: AppConfigStore? = null

        /**
         * 进程内单例。
         *
         * 必须单例，否则会出现一个很隐蔽的 bug：用户在设置页关掉总开关，
         * 服务里那份 AppConfigStore 的内存副本还是「开」，广告照样被跳。
         * 因为本类把全量配置缓存在内存里（热路径不能读 IO），
         * 多实例之间的内存副本天然不同步。
         */
        fun get(context: Context): AppConfigStore =
            instance ?: synchronized(this) {
                instance ?: AppConfigStore(context.applicationContext).also { instance = it }
            }
    }
}
