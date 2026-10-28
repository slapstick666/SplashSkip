package com.splashskip.core.match

import com.splashskip.core.model.AppRule

/**
 * 规则解析：给定 (包名, Activity)，找出应当生效的规则。
 * 走 RuleIndex 倒排表，只对候选集做 Activity 判断，命中多条时按 priority 降序取第一条。
 */
object RuleResolver {

    fun resolve(index: RuleIndex, pkg: String, activity: String?): AppRule? =
        resolve(index.candidates(pkg), pkg, activity)

    fun resolve(rules: List<AppRule>, pkg: String, activity: String?): AppRule? =
        rules.asSequence()
            .filter { it.matchesPackage(pkg) && it.matchesActivity(activity) }
            .maxByOrNull { it.priority }

    /**
     * 取出该 (包名, Activity) 下**全部**适用的规则，按优先级降序。
     *
     * 全量导入 GKD 订阅后，一个 App 通常有 2~10 个分组（开屏、全屏广告、更新提示…），
     * 只取一条会让其余规则永远不生效。引擎按这个顺序逐条尝试，第一条命中即点。
     */
    fun resolveAll(index: RuleIndex, pkg: String, activity: String?): List<AppRule> =
        resolveAll(index.candidates(pkg), pkg, activity)

    fun resolveAll(rules: List<AppRule>, pkg: String, activity: String?): List<AppRule> =
        rules.asSequence()
            .filter { it.matchesPackage(pkg) && it.matchesActivity(activity) }
            .sortedByDescending { it.priority }
            .toList()

    /**
     * Activity 作用域的收益：
     * 只对声明了 Activity 的规则做匹配，能避免在用户正常浏览页面时反复遍历节点树。
     * 这是 GKD 里 activityIds 那套的思路。
     */
    fun hasActivityScope(rule: AppRule): Boolean = rule.activities.isNotEmpty()
}
