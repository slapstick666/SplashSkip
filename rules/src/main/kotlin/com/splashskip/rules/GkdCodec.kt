package com.splashskip.rules

import com.splashskip.core.gkd.GkdSelectorParser
import com.splashskip.core.gkd.Json5
import com.splashskip.core.model.Action
import com.splashskip.core.model.AppRule
import com.splashskip.core.model.GkdSelector
import com.splashskip.core.model.RuleCategory
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
 *
 * ## 为什么全量导入
 *
 * 1.2.x 早期版本只导「开屏广告」分类（299 条 / 179 个 App），理由是
 * 「更新提示、评价提示这些弹窗可能是用户想看的」。但实际用下来，
 * 用户要的是「和 GKD 一样」：订阅里有什么就该有什么，GKD 的 categories
 * 里本来就带 `enable` 开关，那是 GKD 的默认启用策略，不该由本 App 替用户
 * 砍掉 92% 的规则。
 *
 * 所以现在全量导入 apps[].groups[] 与 globalGroups[]，一个分组一个分类的
 * 概念只保留在 note 和告警文案里。开屏分类的规则优先（priority=100），
 * 保证开屏页上先试「跳过」而不是「关闭广告」。
 *
 * ## 真实的解析损耗
 *
 * 整个订阅 3880 条规则 / 4115 条选择器，选择器解析成功率约 99.7%，
 * 剩下的 0.3% 用了 `->` 算符（需要记住查询路径，当前回溯模型表达不了）。
 * 解析失败的规则会跳过并在订阅页列出告警，不影响其余规则。
 */
object GkdCodec {

    /** 开屏分类的优先级，保证开屏页优先试「跳过」而不是「关闭广告」。 */
    private const val PRIORITY_SPLASH = 100

    /** 开屏分类名，同时也是默认唯一启用的分类。 */
    const val SPLASH_CATEGORY = "开屏广告"

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
        // GKD 里同一个分组的 rule key 并不保证唯一（实测真实订阅里就有冲突），
        // 所以这里显式做去重，冲突的追加 #2、#3 后缀。
        val usedIds = HashSet<String>()
        val stats = Stats()
        val categories = parseCategories(root)

        // ---- App 自己的分组 ----
        @Suppress("UNCHECKED_CAST")
        val apps = root["apps"] as? List<Map<String, Any?>> ?: emptyList()
        for (app in apps) {
            val pkg = app["id"] as? String
            if (pkg.isNullOrBlank()) continue
            val appName = app["name"] as? String ?: pkg
            @Suppress("UNCHECKED_CAST")
            val groups = app["groups"] as? List<Map<String, Any?>> ?: continue
            for (group in groups) {
                stats.groups++
                rules += parseGroup(
                    pkg = pkg,
                    appName = appName,
                    group = group,
                    warnings = warnings,
                    usedIds = usedIds,
                    stats = stats,
                    categories = categories,
                )
            }
        }

        // ---- 全局分组 ----
        // globalGroups[].apps 是一份「排除名单」，enable=false 表示该 App 不适用。
        @Suppress("UNCHECKED_CAST")
        val globalGroups = root["globalGroups"] as? List<Map<String, Any?>> ?: emptyList()
        for (group in globalGroups) {
            stats.groups++
            val excluded = excludedPackages(group)
            rules += parseGroup(
                pkg = null,
                appName = "全局",
                group = group,
                warnings = warnings,
                excludedPackages = excluded,
                usedIds = usedIds,
                stats = stats,
                categories = categories,
            )
        }

        if (rules.isEmpty()) {
            return ParseResult(
                null,
                warnings,
                "GKD 订阅中没有可用的规则（共 ${stats.total} 条，全部解析失败）",
            )
        }

        val subId = "gkd-${root["id"] ?: root["name"] ?: "subscription"}"
        val appCount = rules.mapNotNull { r -> r.packages.firstOrNull()?.takeIf { it != "*" } }
            .distinct().size
        return ParseResult(
            Subscription(
                id = subId,
                name = root["name"] as? String ?: "GKD 订阅",
                version = (root["version"] as? Number)?.toLong() ?: 0L,
                minEngine = 1,
                updatedAt = "",
                rules = rules,
                sourceGroupCount = stats.groups,
                sourceRuleCount = stats.total,
                categories = categories,
            ),
            warnings + "已导入 ${rules.size} 条规则，覆盖 $appCount 个应用" +
                if (stats.skipped > 0) "，跳过 ${stats.skipped} 条（详见告警）" else "",
        )
    }

    /** 解析过程中的计数，用于在告警里给出「总共多少、丢了多少」。 */
    private class Stats {
        var total = 0
        var groups = 0
        var skipped = 0
        var duplicateIds = 0
    }

    /**
     * 读订阅顶层声明的分类表。
     *
     * GKD 的 group 没有 categories 字段，分类靠「分组名以分类名开头」这条约定
     * （实测 2477 个分组 100% 能匹配上）。所以分类表只在这里读一次，
     * 后面靠 [categoryOf] 按名字前缀反查。
     */
    private fun parseCategories(root: Map<*, *>): List<RuleCategory> {
        val raw = root["categories"] as? List<*> ?: return emptyList()
        return raw.mapNotNull { it as? Map<*, *> }.mapNotNull { c ->
            val name = c["name"] as? String ?: return@mapNotNull null
            RuleCategory(
                key = (c["key"] as? Number)?.toInt() ?: 0,
                name = name,
                enabledByDefault = c["enable"] == true,
            )
        }
    }

    /**
     * 按分组名反查分类。取最长匹配 —— 「开屏广告」和「开屏广告-全局」
     * 这种前缀重叠的情况，长名优先才对。
     */
    private fun categoryOf(groupName: String, categories: List<RuleCategory>): String {
        if (categories.isEmpty()) return ""
        return categories
            .filter { groupName == it.name || groupName.startsWith(it.name + "-") }
            .maxByOrNull { it.name.length }
            ?.name
            ?: "其他"
    }

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
        usedIds: MutableSet<String>,
        stats: Stats,
        categories: List<RuleCategory> = emptyList(),
    ): List<AppRule> {
        val out = ArrayList<AppRule>()
        // 订阅作者显式关闭的分组（实测有 4 个「请移步到另一个应用页」的占位规则）不导
        if (group["enable"] == false) return out

        val groupName = group["name"] as? String ?: ""
        val groupKey = group["key"]?.toString() ?: "0"
        val groupActivityIds = stringList(group["activityIds"])
        val groupMatchTime = (group["matchTime"] as? Number)?.toLong()
        val groupActionMax = (group["actionMaximum"] as? Number)?.toInt()
        val category = categoryOf(groupName, categories)
        // 老订阅没有 categories 表，退回按分组名判断，开屏规则照样享受优先级加成
        val isSplash = category == SPLASH_CATEGORY ||
            (category.isEmpty() && groupName.contains(SPLASH_CATEGORY))

        val rawRules = group["rules"]
        val rawList = rawRules as? List<*>
        if (rawRules != null && rawList == null) {
            // 真实订阅里有二十多个分组把 rules 写成了空串或对象，属于订阅侧的脏数据
            warnings.add("分组「$groupName」的 rules 不是数组，已跳过整个分组")
            return out
        }
        // 泛型擦除后 `as? List<Map<..>>` 不校验元素类型，必须逐个判 ——
        // 订阅里确实存在 rules: ['...'] 这种元素是字符串的分组
        val rules = rawList.orEmpty().mapNotNull { it as? Map<String, Any?> }
        if (rawList != null && rules.size < rawList.size) {
            warnings.add("分组「$groupName」有 ${rawList.size - rules.size} 条规则不是对象，已跳过")
        }

        for ((index, rule) in rules.withIndex()) {
            stats.total++
            val ruleKey = rule["key"]?.toString() ?: index.toString()
            val baseId = if (pkg != null) {
                "gkd:$pkg:$groupKey:$ruleKey"
            } else {
                "gkd:global:$groupKey:$ruleKey"
            }

            if (pkg != null && pkg in excludedPackages) continue

            // excludeMatches 的语义是「这些节点存在时规则不生效」。解析不了就整条放弃 ——
            // 少了排除条件会在明确不该点的界面上乱点，比少一条规则危险得多。
            val excludes = mutableListOf<GkdSelector>()
            var excludeBroken = false
            for (text in stringList(rule["excludeMatches"])) {
                val parsed = GkdSelectorParser.parseAllOrNull(text)
                if (parsed == null) {
                    excludeBroken = true
                    warnings.add("规则 $baseId 的排除条件无法解析：${text.take(40)}")
                    break
                }
                excludes += parsed
            }
            if (excludeBroken) {
                stats.skipped++
                continue
            }

            val selectors = mutableListOf<GkdSelector>()
            for (text in stringList(rule["matches"]) + stringList(rule["anyMatches"])) {
                val parsed = GkdSelectorParser.parseAllOrNull(text)
                if (parsed == null) {
                    // 带上失败原因，用户看到「暂不支持 `->` 算符」比只看到半截选择器有用
                    val reason = runCatching { GkdSelectorParser.parseAll(text) }
                        .exceptionOrNull()?.message
                        ?.substringAfter("处：", "")
                        ?.take(24)
                        ?.takeIf { it.isNotBlank() }
                    warnings.add(
                        "规则 $baseId 的选择器无法解析" +
                            (reason?.let { "（$it）" } ?: "") +
                            "：${text.take(40)}",
                    )
                    continue
                }
                selectors += parsed
            }
            if (selectors.isEmpty()) {
                stats.skipped++
                continue
            }

            val action = mapAction(rule["action"] as? String)
            if (action == null) {
                warnings.add("规则 $baseId 的动作 ${rule["action"]} 不支持，已跳过")
                stats.skipped++
                continue
            }

            // 校验都过了才占用 id，避免被跳过的规则白占一个后缀
            var ruleId = baseId
            var dup = 2
            while (!usedIds.add(ruleId)) {
                ruleId = "$baseId#$dup"
                dup++
            }
            if (ruleId != baseId) {
                stats.duplicateIds++
                warnings.add("规则 $baseId 出现重复 id，已改用 $ruleId")
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
                    priority = if (isSplash) PRIORITY_SPLASH else 0,
                    matchDelayMs = matchDelay.coerceIn(0L, 5000L),
                    timeoutMs = (matchTime ?: 10_000L).coerceIn(500L, 15_000L),
                    actionCooldownMs = (actionCd ?: 1500L).coerceIn(0L, 60_000L),
                    maxTriggers = (actionMax ?: 1).coerceIn(1, 20),
                    // 顶层 `||` 已经被 parseAll 拆成多条选择器，这里一条一个 step，
                    // 引擎「按顺序尝试、第一个命中生效」正好是 OR 语义
                    steps = selectors.map { sel ->
                        Step(
                            gkd = sel,
                            excludes = excludes,
                            action = action,
                            postDelayMs = (rule["actionDelay"] as? Number)?.toLong() ?: 0L,
                        )
                    },
                    note = "来自 GKD 订阅：$appName / $groupName",
                    category = category,
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
