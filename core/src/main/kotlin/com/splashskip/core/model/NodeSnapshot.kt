package com.splashskip.core.model

/**
 * 无障碍节点树的不可变快照。
 *
 * 为什么不直接持有 AccessibilityNodeInfo？
 * 1. 它的生命周期由系统管，跨线程/跨事件后可能已失效（stale），直接读会抛异常；
 * 2. 单元测试没法构造它；
 * 3. 匹配阶段要遍历上千节点，读 8 个属性就够，没必要留着系统对象。
 *
 * 真正要执行点击时，才通过 [handle] 回到系统节点。
 */
data class NodeSnapshot(
    val text: String,
    val desc: String,
    /** viewIdResourceName 的短名部分，如 "skip_btn"。 */
    val viewId: String,
    /**
     * viewIdResourceName 的完整形式，如 "com.foo:id/skip_btn"。
     *
     * GKD 的 `id` 属性用完整形式，`vid` 用短名，两个都要。
     */
    val viewIdFull: String = "",
    /** 全类名，如 "android.widget.TextView"。 */
    val className: String,
    val bounds: IntRect,
    val clickable: Boolean,
    val enabled: Boolean,
    val visible: Boolean,
    val depth: Int,
    /**
     * 最近的可点击祖先所在的树深度；-1 表示整条链都不可点。
     * 用深度而不是再挂一个 NodeHandle，是为了避免「快照持有句柄、句柄持有快照」的循环。
     */
    val clickableAncestorDepth: Int = -1,
    /** 回指系统节点，执行动作时用。 */
    val handle: NodeHandle? = null,
) {
    val hasClickableAncestor: Boolean get() = clickableAncestorDepth >= 0

    fun textOrDesc(): String = if (text.isNotEmpty()) text else desc

    // ------------------------------------------------------------------
    // 树结构索引
    //
    // 刻意放在 body 里而不是构造参数里：它们是遍历时填的运行时索引，
    // 不属于节点的「身份」，参与 data class 的 equals 会让快照比较失去意义。
    //
    // 没有这些索引就没法执行 GKD 的关系选择器 —— `A > B`、`A + B`、
    // `A << B` 全都要顺着树往上/往下/往旁边找。
    // ------------------------------------------------------------------

    /** 在快照列表中的父节点索引，-1 表示根节点或无父。 */
    var parentIndex: Int = -1

    /** 在快照列表中的子节点索引。 */
    var childIndices: List<Int> = emptyList()

    /** 本节点在父节点子列表中的序号（0 起）。GKD 的 `index` 属性用它。 */
    var indexInParent: Int = 0

    /** 深度优先先序遍历的序号。GKD 的 `<<` 关系算符用它做定位。 */
    var dfsOrder: Int = 0

    /** 真实的子节点总数（含被剪枝掉的），GKD 的 `childCount` 属性用它。 */
    var childCount: Int = 0
}

/**
 * 对系统无障碍节点的薄包装。:core 只认这个接口，
 * 具体实现在 :a11y 模块（A11yNodeHandle）。
 */
interface NodeHandle {
    val snapshot: NodeSnapshot

    /** 原生 ACTION_CLICK。返回是否被 App 接受。 */
    fun click(): Boolean

    /** 在节点中心派发一次真实手势点击。 */
    fun clickCenter(): Boolean

    /** 全局返回。 */
    fun back(): Boolean
}
