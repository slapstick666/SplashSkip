package com.splashskip.rules

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * 订阅配置存储。
 *
 * 为什么不用 Room？这里的数据量是个位数、结构还会变（后续可能加字段），
 * 用 SharedPreferences 存一段 JSON 数组最省事，读写都在内存里做，
 * 也不需要为了几张表引入 Room + KSP。
 *
 * 全部读写走 [lock]，并且维护一份内存副本 —— SubsFragment 会频繁读列表渲染，
 * 每次都反序列化 JSON 没必要。
 */
class SubscriptionStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val gson = Gson()
    private val lock = Any()

    @Volatile
    private var cache: List<SubscriptionEntry>? = null

    fun list(): List<SubscriptionEntry> = synchronized(lock) {
        cache ?: loadLocked().also { cache = it }
    }

    fun get(id: String): SubscriptionEntry? = list().firstOrNull { it.id == id }

    /** 按 id 存在就覆盖，否则追加。 */
    fun upsert(entry: SubscriptionEntry) = synchronized(lock) {
        val current = (cache ?: loadLocked()).toMutableList()
        val idx = current.indexOfFirst { it.id == entry.id }
        if (idx >= 0) current[idx] = entry else current.add(entry)
        persistLocked(current)
    }

    fun remove(id: String) = synchronized(lock) {
        val current = (cache ?: loadLocked()).toMutableList()
        current.removeAll { it.id == id }
        persistLocked(current)
    }

    fun setEnabled(id: String, enabled: Boolean) = synchronized(lock) {
        val current = (cache ?: loadLocked()).map {
            if (it.id == id) it.copy(enabled = enabled) else it
        }
        persistLocked(current)
    }

    /** 拉取结束后回写状态。error 为 null 表示成功。 */
    fun markResult(
        id: String,
        error: String?,
        version: Long = 0L,
        ruleCount: Int = 0,
        at: Long = System.currentTimeMillis(),
    ) = synchronized(lock) {
        val current = (cache ?: loadLocked()).map {
            if (it.id != id) {
                it
            } else if (error == null) {
                it.copy(
                    lastError = null,
                    lastFetchAt = at,
                    version = if (version > 0) version else it.version,
                    ruleCount = if (ruleCount > 0) ruleCount else it.ruleCount,
                )
            } else {
                it.copy(lastError = error)
            }
        }
        persistLocked(current)
    }

    fun clear() = synchronized(lock) { persistLocked(emptyList()) }

    // ---------------- 内部 ----------------

    private fun loadLocked(): List<SubscriptionEntry> = runCatching {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        gson.fromJson<List<SubscriptionEntry>>(
            raw,
            object : TypeToken<List<SubscriptionEntry>>() {}.type,
        ) ?: emptyList()
    }.onFailure { Log.w(TAG, "读取订阅配置失败", it) }.getOrDefault(emptyList())

    private fun persistLocked(items: List<SubscriptionEntry>) {
        cache = items
        prefs.edit().putString(KEY, gson.toJson(items)).apply()
    }

    companion object {
        private const val TAG = "SubscriptionStore"
        private const val PREFS = "splashskip.subs"
        private const val KEY = "entries"

        @Volatile
        private var instance: SubscriptionStore? = null

        /**
         * 单例。UI 和 Worker 必须看同一份配置，
         * 否则后台更新完，前台列表还显示旧状态。
         */
        fun get(context: Context): SubscriptionStore =
            instance ?: synchronized(this) {
                instance ?: SubscriptionStore(context.applicationContext).also { instance = it }
            }
    }
}
