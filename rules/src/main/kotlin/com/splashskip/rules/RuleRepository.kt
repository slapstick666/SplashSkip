package com.splashskip.rules

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.splashskip.core.match.RuleIndex
import com.splashskip.core.model.AppRule
import com.splashskip.core.model.Subscription
import java.io.File

/**
 * 规则仓库：多订阅合并 + 内存快照。
 *
 * 规则的唯一来源是 GKD 格式订阅（1.2.0 起删掉了自研的扁平 JSON 格式和
 * user.json 手写规则 —— GKD 生态里现成有几千条社区维护的规则，
 * 自造格式的维护成本远大于收益）。
 *
 * 多份订阅按 rule.id 合并，撞 id 的由 GkdCodec 生成时去重。
 *
 * 所有读取路径都通过 [index] 这个 volatile 快照 —— 无障碍事件是高频调用，
 * 绝不能每次都去碰文件系统。写操作在自己的锁里完成，写完整体替换快照。
 */
class RuleRepository(private val context: Context) {

    private val root = File(context.filesDir, "rules")
    private val remoteDir = File(root, "remote")
    private val confirmFile = File(root, "confirmed.json")

    private val lock = Any()
    private val gson = Gson()

    @Volatile
    private var index: RuleIndex = RuleIndex.EMPTY

    @Volatile
    private var rulesById: Map<String, AppRule> = emptyMap()

    @Volatile
    private var confirmations: Set<String> = emptySet()

    @Volatile
    var lastErrors: List<String> = emptyList()
        private set

    /** 订阅文件解析缓存，键是 (路径, mtime, 长度)。只在锁内访问。 */
    private val parseCache = HashMap<CacheKey, ParseResult>()

    private data class CacheKey(val path: String, val modified: Long, val length: Long)

    init {
        ensureDirs()
        reload()
    }

    // ---------------- 读 ----------------

    /** 热路径入口，必须无锁无 IO。 */
    fun currentIndex(): RuleIndex = index

    fun ruleById(id: String): AppRule? = rulesById[id]

    fun allRules(): List<AppRule> = rulesById.values.sortedBy { it.name }

    fun isConfirmed(ruleId: String): Boolean = confirmations.contains(ruleId)

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

    fun setConfirmed(ruleId: String, confirmed: Boolean) {
        synchronized(lock) {
            confirmations = if (confirmed) confirmations + ruleId else confirmations - ruleId
            runCatching { confirmFile.writeText(gson.toJson(confirmations)) }
        }
    }

    fun removeRemote(subId: String) {
        synchronized(lock) {
            runCatching { File(remoteDir, "$subId.json").delete() }
            reloadLocked()
        }
    }

    fun listSubscriptions(): List<Subscription> {
        val out = mutableListOf<Subscription>()
        remoteFiles().forEach { f ->
            runCatching {
                GkdCodec.parseSubscription(f.readText()).subscription?.let(out::add)
            }
        }
        return out
    }

    fun reload() {
        synchronized(lock) { reloadLocked() }
    }

    // ---------------- 内部 ----------------

    private fun reloadLocked() {
        val errors = mutableListOf<String>()
        val merged = LinkedHashMap<String, AppRule>()

        remoteFiles().forEach { f ->
            Log.i(TAG, "加载远程订阅文件：${f.name}（${f.length()} 字节）")
            val r = runCatching { parseCached(f) }
                .getOrElse {
                    Log.e(TAG, "读取 ${f.name} 失败", it)
                    ParseResult(null, emptyList(), "读取 ${f.name} 失败：${it.message}")
                }
            r.fatal?.let { errors += "远程 ${f.name}：$it" }
            errors += r.warnings.map { "远程 ${f.name}：$it" }
            r.subscription?.rules?.forEach { merged[it.id] = it }
        }

        confirmations = runCatching {
            if (!confirmFile.exists()) emptySet()
            else gson.fromJson<Set<String>>(
                confirmFile.readText(),
                object : TypeToken<Set<String>>() {}.type,
            ) ?: emptySet()
        }.getOrDefault(emptySet())

        rulesById = merged
        index = RuleIndex.of(merged.values.toList())
        lastErrors = errors
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
    }
}
