package com.splashskip.rules

import com.splashskip.core.gkd.GkdSelectorParser
import com.splashskip.core.gkd.Json5
import com.splashskip.core.model.Action
import com.splashskip.core.model.AppRule
import com.splashskip.core.model.GkdSelector
import com.splashskip.core.model.Step
import com.splashskip.core.model.Subscription

/** 单个订阅的解析结果。warnings 不为空说明有规则被跳过或无法解析。 */
data class ParseResult(
    val subscription: Subscription?,
    val warnings: List<String>,
    val fatal: String? = null,
)

/**
 * GKD 格式订阅的适配器。
 *
 * ## 为什么需要单独一套
 *
 * GKD 订阅是 JSON5，选择器是树形关系表达式（`@A > B - C << D`）。
 * 本项目唯一支持的规则来源就是 GKD 订阅：这里把 GKD 的规则翻译成
 * AppRule/Step，Step.gkd 里装解析好的选择器 AST，由 GkdMatcher 在运行时求值。
 * 1.2.0 之前还有一套自研的扁平 JSON 规则格式，已删 —— GKD 生态里现成
 * 有几千条社区维护的规则，自造格式的维护成本远大于收益。
 *
 * ## 只导入开屏相关分类
 *
 * GKD 订阅里除了「开屏广告」还有「更新提示」「青少年模式」「全屏广告-弹窗广告」
 * 等分类。后几类关掉的可能是用户**想看**的东西（比如更新弹窗），
 * 自动关掉属于越权。所以默认只导入名称含「开屏」的分组。
 *
 * 整个订阅 3907 条规则里，开屏相关只有 299 条、覆盖 179 个 App。
 * 少导入 92% 的规则，既省内存又降低误点风险。
 */
object GkdCodec {

    /** 只导入分组名里包含这些关键字的规则。 */
    private val CATEGORY_KEYWORDS = listOf("开屏")

    /** GKD 的 action 到本项目 Action 的映射。 */
    private fun mapAction(raw: String?): Action? = when (raw?.lowercase()) {
        null, "", "click" -> Action.CLICK_NODE
        "clicknode", "clicktext" -> Action.CLICK_NODE
        "clickcenter" -> Action.CLICK_CENTER
        "back" -> Action.BACK
        "none" -> Action.NONE
        // 长按与滑动本项目没有对应实现，长按降级成点击，滑动直接放弃
        "longclick", "longclicknode", "longclicktext", "longclickcenter" -> Action.CLICK_NODE
        "swipe" -> null
        else -> Action.CLICK_NODE
    }

    /**
     * 判断一段文本是不是 GKD 格式订阅。
     *
     * 判据：能按 JSON5 解析，顶层有 `apps` 或 `globalGroups`，且没有本项目原生的 `rules`。
     */
    fun looksLikeGkd(text: String): Boolean {
        val root = runCatching { Json5.parse(text) }.getOrNull() as? Map<*, *> ?: return false
        val hasGkdKeys = root.containsKey("apps") || root.containsKey("globalGroups")
        return hasGkdKeys && !root.containsKey("rules")
    }

    fun parseSubscription(text: String): ParseResult {
        val root = runCatching { Json5.parse(text) }.getOrNull() as? Map<*, *>
            ?: return ParseResult(null, emptyList(), "不是合法的 JSON5 内容")

        val warnings = mutableListOf<String>()
        val rules = ArrayList<AppRule>()
        // 规则 id 必须全局唯一：仓库用 id 作 Map 的键去重，撞 id 的规则会被静默覆盖。
        // GKD 里同一个分组的 rule key 并不保证唯一（实测真实订阅里就有 1 组冲突），
        // 所以这里显式做去重，冲突的追加 #2、#3 后缀。
        val usedIds = HashSet<String>()

        // ---- App 自己的分组 ----
        @Suppress("UNCHECKED_CAST")
        val apps = root["apps"] as? List<Map<String, Any?>> ?: emptyList()
        var skippedApps = 0
        for (app in apps) {
            val pkg = app["id"] as? String
            if (pkg.isNullOrBlank()) continue
            val appName = app["name"] as? String ?: pkg
            @Suppress("UNCHECKED_CAST")
            val groups = app["groups"] as? List<Map<String, Any?>> ?: continue
            for (group in groups) {
                val gname = group["name"] as? String ?: ""
                if (!isWantedCategory(gname)) {
                    skippedApps++
                    continue
                }
                rules += parseGroup(pkg, appName, group, warnings, usedIds)
            }
        }

        // ---- 全局分组 ----
        // globalGroups[].apps 是一份「排除名单」，enable=false 表示该 App 不适用。
        @Suppress("UNCHECKED_CAST")
        val globalGroups = root["globalGroups"] as? List<Map<String, Any?>> ?: emptyList()
        for (group in globalGroups) {
            val gname = group["name"] as? String ?: ""
            if (!isWantedCategory(gname)) continue
            val excluded = excludedPackages(group)
            rules += parseGroup(
                pkg = null,
                appName = "全局",
                group = group,
                warnings = warnings,
                excludedPackages = excluded,
                usedIds = usedIds,
            )
        }

        if (rules.isEmpty()) {
            return ParseResult(
                null,
                warnings + "GKD 订阅里没有找到开屏广告相关的规则（已跳过 ${skippedApps} 个其它分类的分组）",
                "GKD 订阅中没有可用的开屏规则",
            )
        }

        val subId = "gkd-${root["id"] ?: root["name"] ?: "subscription"}"
        return ParseResult(
            Subscription(
                id = subId,
                name = root["name"] as? String ?: "GKD 订阅",
                version = (root["version"] as? Number)?.toLong() ?: 0L,
                minEngine = 1,
                updatedAt = "",
                rules = rules,
            ),
            warnings + "已导入开屏规则 ${rules.size} 条（订阅中其余 ${skippedApps} 个分组未导入）",
        )
    }

    private fun isWantedCategory(groupName: String): Boolean =
        CATEGORY_KEYWORDS.any { groupName.contains(it) }

    /** globalGroups[].apps 里 enable=false 的就是排除名单。 */
    private fun excludedPackages(group: Map<String, Any?>): Set<String> {
        @Suppress("UNCHECKED_CAST")
        val apps = group["apps"] as? List<Map<String, Any?>> ?: return emptySet()
        return apps.filter { (it["enable"] as? Boolean) != true }
            .mapNotNull { it["id"] as? String }
            .toSet()
    }

    /**
     * 把一个 GKD 分组翻译成若干条 AppRule。
     *
     * @param pkg 为 null 表示全局规则，packages 用 `*`
     */
    private fun parseGroup(
        pkg: String?,
        appName: String,
        group: Map<String, Any?>,
        warnings: MutableList<String>,
        excludedPackages: Set<String> = emptySet(),
        usedIds: MutableSet<String> = HashSet(),
    ): List<AppRule> {
        val out = ArrayList<AppRule>()
        val groupName = group["name"] as? String ?: ""
        val groupKey = group["key"]?.toString() ?: "0"
        val groupActivityIds = stringList(group["activityIds"])
        val groupMatchTime = (group["matchTime"] as? Number)?.toLong()
        val groupActionMax = (group["actionMaximum"] as? Number)?.toInt()

        @Suppress("UNCHECKED_CAST")
        val rules = group["rules"] as? List<Map<String, Any?>> ?: return out

        for ((index, rule) in rules.withIndex()) {
            val ruleKey = rule["key"]?.toString() ?: index.toString()
            val baseId = if (pkg != null) {
                "gkd:$pkg:$groupKey:$ruleKey"
            } else {
                "gkd:global:$groupKey:$ruleKey"
            }
            // GKD 的 rule key 不保证唯一，撞 id 的规则在仓库里会被静默覆盖，
            // 所以这里显式加后缀区分开，宁可 id 难看也不能丢规则。
            var ruleId = baseId
            var dup = 2
            while (!usedIds.add(ruleId)) {
                ruleId = "$baseId#$dup"
                dup++
            }
            if (ruleId != baseId) {
                warnings.add("规则 $baseId 出现重复 id，已改用 $ruleId")
            }

            if (pkg != null && pkg in excludedPackages) continue

            // excludeMatches 的语义是「这些节点存在时规则不生效」，本项目的 Step 模型
            // 没有排除位，硬塞会误点，所以整条规则放弃。
            if (rule["excludeMatches"] != null) {
                warnings.add("规则 $ruleId 含 excludeMatches，暂不支持，已跳过")
                continue
            }

            val selectors = mutableListOf<GkdSelector>()
            for (text in stringList(rule["matches"]) + stringList(rule["anyMatches"])) {
                val sel = GkdSelectorParser.parseOrNull(text)
                if (sel == null) {
                    warnings.add("规则 $ruleId 的选择器无法解析：${text.take(40)}")
                    continue
                }
                selectors.add(sel)
            }
            if (selectors.isEmpty()) continue

            val action = mapAction(rule["action"] as? String)
            if (action == null) {
                warnings.add("规则 $ruleId 的动作 ${rule["action"]} 不支持，已跳过")
                continue
            }

            val activities = stringList(rule["activityIds"]).ifEmpty { groupActivityIds }
            val excludeActivities = stringList(rule["excludeActivityIds"])
            val matchDelay = (rule["matchDelay"] as? Number)?.toLong() ?: 0L
            val actionCd = (rule["actionCd"] as? Number)?.toLong()
            val actionMax = (rule["actionMaximum"] as? Number)?.toInt() ?: groupActionMax
            val matchTime = (rule["matchTime"] as? Number)?.toLong() ?: groupMatchTime

            out.add(
                AppRule(
                    id = ruleId,
                    name = rule["name"] as? String ?: "$appName · $groupName",
                    packages = listOfNotNull(pkg ?: "*"),
                    activities = activities,
                    excludeActivities = excludeActivities,
                    priority = 0,
                    matchDelayMs = matchDelay.coerceIn(0L, 5000L),
                    timeoutMs = (matchTime ?: 10_000L).coerceIn(500L, 15_000L),
                    actionCooldownMs = (actionCd ?: 1500L).coerceIn(0L, 60_000L),
                    maxTriggers = (actionMax ?: 1).coerceIn(1, 20),
                    steps = selectors.map { sel ->
                        Step(
                            gkd = sel,
                            action = action,
                            postDelayMs = (rule["actionDelay"] as? Number)?.toLong() ?: 0L,
                        )
                    },
                    note = "来自 GKD 订阅：$appName / $groupName",
                ),
            )
        }
        return out
    }

    /** 字段可能是字符串，也可能是字符串数组。 */
    private fun stringList(value: Any?): List<String> = when (value) {
        null -> emptyList()
        is String -> listOf(value)
        is List<*> -> value.filterIsInstance<String>()
        else -> emptyList()
    }
}
