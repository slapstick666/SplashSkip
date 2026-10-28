package com.splashskip.core.match

import com.splashskip.core.model.AppRule

/**
 * 规则索引。
 *
 * 为什么需要它？无障碍事件是高频的 —— 用户滑动一次列表可能产生几十个
 * WINDOW_CONTENT_CHANGED。如果每次事件都把 300 条规则从头到尾 filter 一遍，
 * 按每条 3 个包名算就是 900+ 次字符串比较，在低端机上直接掉帧。
 *
 * 按包名建倒排表后，常态下只需要一次 HashMap 查表。
 * 通配规则（com.tencent.*）单独放一个桶，数量通常是个位数。
 */
class RuleIndex private constructor(
    private val byPackage: Map<String, List<AppRule>>,
    private val globRules: List<AppRule>,
) {
    val size: Int get() = byPackage.values.sumOf { it.size } + globRules.size

    fun candidates(pkg: String): List<AppRule> {
        val exact = byPackage[pkg]
        return when {
            globRules.isEmpty() -> exact.orEmpty()
            exact == null -> globRules
            else -> exact + globRules
        }
    }

    companion object {
        val EMPTY = RuleIndex(emptyMap(), emptyList())

        fun of(rules: List<AppRule>): RuleIndex {
            if (rules.isEmpty()) return EMPTY
            val exact = HashMap<String, MutableList<AppRule>>()
            val globs = ArrayList<AppRule>()
            for (rule in rules) {
                var hasGlob = false
                for (p in rule.packages) {
                    if (p.contains('*')) {
                        hasGlob = true
                    } else {
                        exact.getOrPut(p) { ArrayList() }.add(rule)
                    }
                }
                if (hasGlob) globs.add(rule)
            }
            return RuleIndex(exact, globs)
        }
    }
}
