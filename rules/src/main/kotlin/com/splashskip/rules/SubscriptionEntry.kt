package com.splashskip.rules

/**
 * 一条订阅的配置。
 *
 * 和 [com.splashskip.core.model.Subscription] 的区别：
 *   · Subscription 是**订阅包本身**（从 JSON 里解析出来的规则集合）
 *   · SubscriptionEntry 是**用户配置**（从哪拉、开不开）
 *
 * 两者用 [id] 关联。分开的原因是：订阅包可能还没拉下来（用户刚填了 URL 但拉取失败），
 * 此时只有配置、没有内容，但用户仍然希望这条记录留在列表里显示错误状态。
 *
 * 1.2.0 起删掉了 requireSignature / isGkd 字段 —— 规则来源只剩 GKD 格式订阅，
 * 而 GKD 生态本来就没有签名体系，验签和格式标记都没有存在的意义了。
 */
data class SubscriptionEntry(
    /** 订阅包里的 id，拉取成功后才确定。添加时若失败则为空串。 */
    val id: String,
    val name: String,
    /** 订阅 JSON5 的完整地址。 */
    val updateUrl: String,
    /** 轻量版本探测地址（约定返回纯文本版本号），可空。 */
    val checkUrl: String? = null,
    val enabled: Boolean = true,
    /** 最近一次成功拉取的时间戳。 */
    val lastFetchAt: Long = 0L,
    /** 最近一次拉取的失败原因，成功后清空。 */
    val lastError: String? = null,
    val version: Long = 0L,
    val ruleCount: Int = 0,
) {
    val displayName: String get() = name.ifEmpty { updateUrl.substringAfterLast('/').ifEmpty { updateUrl } }
}
