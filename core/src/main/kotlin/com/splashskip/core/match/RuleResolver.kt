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
     * Activity 作用域的收益：
     * 只对声明了 Activity 的规则做匹配，能避免在用户正常浏览页面时反复遍历节点树。
     * 这是 GKD 里 activityIds 那套的思路。
     */
    fun hasActivityScope(rule: AppRule): Boolean = rule.activities.isNotEmpty()
}
