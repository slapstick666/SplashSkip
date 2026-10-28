package com.splashskip.core.model

/**
 * 命中后的动作。
 *
 * 关键点：CLICK_NODE 走 AccessibilityNodeInfo.performAction(ACTION_CLICK)，
 * 这是最「正统」的一条路，不会误触别的坐标；但它对自定义 View 常常无效
 * （App 自己处理触摸事件，没实现 performClick）。
 * 所以必须有 CLICK_CENTER 兜底 —— 用 dispatchGesture 在节点中心真实点一下。
 *
 * 这些取值对应 GKD 规则 `action` 字段的映射（见 GkdCodec.mapAction）。
 */
enum class Action {
    /** ACTION_CLICK，失败时引擎自动降级到 CLICK_CENTER。GKD 的默认动作 `click` 也映射到这里。 */
    CLICK_NODE,

    /** 直接在节点中心 dispatchGesture 点击。对应 GKD 的 `clickCenter`。 */
    CLICK_CENTER,

    /** 执行返回键。对应 GKD 的 `back`。 */
    BACK,

    /** 只记录不执行。对应 GKD 的 `none`。 */
    NONE,
}

/**
 * 一步动作：GKD 选择器 + 动作。
 * 一个规则的 steps 是有序的，引擎按顺序尝试，第一个命中的生效 ——
 * GKD 规则的 `matches` 与 `anyMatches` 就按这个顺序展开成多个 step。
 *
 * [gkd] 标了 [Transient]：GKD 的 AST 是 sealed interface 的多态结构，
 * Gson 反序列化不了，写进 JSON 只会得到一堆 null。规则落盘走的也不是它，
 * 而是订阅原文。
 */
data class Step(
    /** GKD 选择器 AST，运行时由 GkdMatcher 在节点树上求值。 */
    @Transient
    val gkd: GkdSelector,
    val action: Action = Action.CLICK_NODE,
    /** 命中后等待多久再执行（给 App 动画留时间）。 */
    val postDelayMs: Long = 0L,
)
