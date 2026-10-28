package com.splashskip.rules

import android.util.Log
import com.splashskip.core.model.Subscription
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/** 拉取结果。 */
sealed interface FetchResult {
    data class Ok(val subscription: Subscription, val rawJson: String) : FetchResult
    data class Err(val reason: String) : FetchResult
}

/** 一次同步的结果。 */
sealed interface SyncResult {
    data object UpToDate : SyncResult
    data class Updated(val version: Long, val ruleCount: Int) : SyncResult
    data class Failed(val reason: String) : SyncResult
}

/**
 * 订阅拉取器：下载 + 解析，不落盘。
 *
 * ## 关于「为什么没有验签」
 *
 * 1.1.0 及以前本项目有 ECDSA 验签（自研订阅格式的配套），1.2.0 删了：
 * 规则来源只剩 GKD 格式订阅，而 GKD 社区本来就没有签名体系，
 * 验签等于直接拒收所有现成订阅源。风险边界改为：只导入开屏分类 +
 * 引擎的 GKD 匹配器按选择器精确执行 + 界面上说明来源是社区订阅。
 * 哪天 GKD 生态有了签名机制，再接回来也不迟（实现已在 git 历史里）。
 */
class SubscriptionUpdater(private val repo: RuleRepository) {

    /**
     * 下载 + 解析。**不写盘**，调用方决定是否保存。
     */
    suspend fun fetch(updateUrl: String): FetchResult =
        withContext(Dispatchers.IO) {
            try {
                val payload = fetchBytes(updateUrl)
                    ?: return@withContext FetchResult.Err("下载失败，检查 URL 是否可访问")

                val json = String(payload, Charsets.UTF_8)
                if (!GkdCodec.looksLikeGkd(json)) {
                    return@withContext FetchResult.Err(
                        "这不是 GKD 格式的订阅（缺少 apps / globalGroups 字段）。" +
                            "本项目只支持 GKD 格式订阅，例如 " +
                            "https://gcore.jsdelivr.net/gh/Lin-arm/GKD_subscription@main/dist/gkd.json5",
                    )
                }

                val parsed = GkdCodec.parseSubscription(json)
                val sub = parsed.subscription
                    ?: return@withContext FetchResult.Err(parsed.fatal ?: "解析失败")

                FetchResult.Ok(sub, json)
            } catch (e: Exception) {
                Log.e(TAG, "拉取订阅异常", e)
                FetchResult.Err(e.message ?: "未知错误")
            }
        }

    /**
     * 添加新订阅。
     *
     * @return first = 成功时的配置（未入库，调用方负责 store.upsert），second = 失败原因
     */
    suspend fun addByUrl(
        updateUrl: String,
        checkUrl: String?,
    ): Pair<SubscriptionEntry?, String?> {
        val url = updateUrl.trim()
        if (url.isEmpty()) return null to "URL 不能为空"
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return null to "URL 必须以 http:// 或 https:// 开头"
        }

        return when (val r = fetch(url)) {
            is FetchResult.Ok -> {
                val sub = r.subscription
                if (!repo.saveRemote(r.rawJson, sub)) {
                    return null to "规则写入失败，检查存储空间"
                }
                SubscriptionEntry(
                    id = sub.id,
                    name = sub.name,
                    updateUrl = url,
                    checkUrl = checkUrl?.trim()?.takeIf { it.isNotEmpty() },
                    lastFetchAt = System.currentTimeMillis(),
                    version = sub.version,
                    ruleCount = sub.rules.size,
                ) to null
            }
            is FetchResult.Err -> null to r.reason
        }
    }

    /** 同步已有订阅。 */
    suspend fun sync(entry: SubscriptionEntry): SyncResult {
        val local = repo.versionOf(entry.id)

        // 1. 轻量版本探测：只拉几十字节的版本号，没变就不下全量
        val checkUrl = entry.checkUrl
        if (checkUrl != null && local != null) {
            val remote = fetchText(checkUrl)?.trim()?.toLongOrNull()
            if (remote != null && remote <= local) return SyncResult.UpToDate
        }

        // 2. 拉全量
        return when (val r = fetch(entry.updateUrl)) {
            is FetchResult.Ok -> {
                val sub = r.subscription
                if (sub.id != entry.id) {
                    return SyncResult.Failed("订阅 id 不匹配：期望 ${entry.id}，实际 ${sub.id}")
                }
                // 3. 版本回退保护：远程不高于本地就丢弃，防订阅源被回滚攻击
                if (local != null && sub.version <= local) return SyncResult.UpToDate

                if (repo.saveRemote(r.rawJson, sub)) {
                    SyncResult.Updated(sub.version, sub.rules.size)
                } else {
                    SyncResult.Failed("写入失败，已保留旧版本")
                }
            }
            is FetchResult.Err -> SyncResult.Failed(r.reason)
        }
    }

    // ---------------- 网络 ----------------

    private fun fetchText(url: String): String? = fetchBytes(url)?.let { String(it, Charsets.UTF_8) }

    private fun fetchBytes(url: String): ByteArray? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 15_000
                requestMethod = "GET"
                setRequestProperty("User-Agent", "SplashSkip/$UA_VERSION")
                instanceFollowRedirects = true
            }
            if (conn.responseCode !in 200..299) return null
            conn.inputStream.use { it.readBytes() }
        } catch (e: Exception) {
            Log.w(TAG, "请求失败 $url", e)
            null
        } finally {
            conn?.disconnect()
        }
    }

    private companion object {
        const val TAG = "SubscriptionUpdater"
        const val UA_VERSION = "1.2.0"
    }
}
