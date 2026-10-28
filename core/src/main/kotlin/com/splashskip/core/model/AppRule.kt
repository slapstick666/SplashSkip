package com.splashskip.core.model

/**
 * 单条 App 规则。
 *
 * 字段设计参考了 GKD 的 RawRuleProps（matchDelay / actionCd / actionMaximum / activityIds），
 * 但把「会话」概念显式化了：一次开屏 = 一个 Session，超时即销毁，
 * 避免规则在用户正常浏览时被反复触发。
 */
data class AppRule(
    /** 全局唯一，建议用 "包名.场景" 形式。 */
    val id: String,
    val name: String,

    /** 目标包名，支持 "*" 通配（如 "com.tencent.*"）。 */
    val packages: List<String>,

    /**
     * Activity 全名或正则。空列表 = 任意 Activity 都参与匹配（不推荐，耗电）。
     * 开屏广告通常有独立 Activity（SplashActivity / WelcomeActivity / AdActivity），
     * 用 Activity 作用域能把匹配开销砍掉一个数量级。
     */
    val activities: List<String> = emptyList(),

    /** 排除的 Activity，优先级高于 activities。 */
    val excludeActivities: List<String> = emptyList(),

    /** 数值越大越优先。同包名多条规则同时命中时取大的。 */
    val priority: Int = 0,

    /** 窗口出现后延迟多久才开始匹配，给广告 View 的异步渲染留时间。 */
    val matchDelayMs: Long = 0L,

    /** 会话存活时长。超时后不再重试，交给用户手动操作。 */
    val timeoutMs: Long = 3000L,

    /** 同一规则两次触发之间的最小间隔，防抖动。 */
    val actionCooldownMs: Long = 1500L,

    /** 单次会话内最多尝试次数。 */
    val maxTriggers: Int = 3,

    /** 有序动作链，每个 step 持一个 GKD 选择器。 */
    val steps: List<Step> = emptyList(),

    /** 规则作者备注，UI 里展示，方便排查。 */
    val note: String = "",
) {
    fun matchesPackage(pkg: String): Boolean = packages.any { pattern ->
        when {
            pattern == "*" -> true
            pattern.endsWith(".*") -> pkg.startsWith(pattern.dropLast(1))
            else -> pkg == pattern
        }
    }

    fun matchesActivity(activity: String?): Boolean {
        if (activity.isNullOrEmpty()) return activities.isEmpty()
        if (excludeActivities.any { it.matchesPattern(activity) }) return false
        if (activities.isEmpty()) return true
        return activities.any { it.matchesPattern(activity) }
    }

    /**
     * Activity 模式的三种写法，按此优先级判定（去掉开头的 "." 之后再判断是否含元字符）：
     *
     *   1. 含正则元字符  →  整串正则匹配
     *      例：".*Splash.*" 命中 "com.foo.SplashActivity"
     *   2. 以 "." 开头（且无其它元字符）→  包名后缀匹配
     *      例：".SplashActivity" 命中 "com.foo.SplashActivity"
     *   3. 其它  →  全等，或补一个点后的后缀匹配
     *      例："com.foo.MainActivity" / "MainActivity"
     *
     * 注意：判断元字符时要先把开头的点排除掉，否则 ".SplashActivity"
     * 会被那个点误判成正则（点本身在正则里是通配符），语义就崩了。
     */
    private fun String.matchesPattern(target: String): Boolean {
        if (isEmpty()) return false
        val body = if (startsWith(".")) substring(1) else this
        return when {
            body.any { it in REGEX_META } ->
                runCatching { Regex(this).matches(target) }.getOrDefault(false)
            startsWith(".") -> target.endsWith(this)
            else -> target == this || target.endsWith(".$this")
        }
    }

    private companion object {
        /** 刻意不含 '.' —— 点在 Activity 全名里太常见，当元字符会误伤。 */
        val REGEX_META = charArrayOf('*', '+', '?', '[', ']', '(', ')', '{', '}', '|', '^', '$', '\\')
    }
}

/** 一份规则订阅包。 */
data class Subscription(
    val id: String,
    val name: String,
    /** 单调递增整数，用于增量判断。 */
    val version: Long,
    /** 规则引擎最低版本要求，低于此值整包拒收。 */
    val minEngine: Int = 1,
    val updatedAt: String = "",
    val rules: List<AppRule> = emptyList(),
)
