package com.splashskip.a11y

/**
 * 界面快照的数据模型。
 *
 * 为什么要跟 NodeSnapshot 分开？两者目的完全不同：
 *   - NodeSnapshot 给匹配器用：剪枝后的扁平列表，只关心「能不能点中」
 *   - SnapshotDoc  给写规则的人用：完整树，保留层级关系和布局容器
 *
 * 写规则时最需要的恰恰是那些「没有文本、没有 id」的容器节点 ——
 * 它们决定了「跳过按钮的父容器 id 是什么」，而这正是抗改版规则的关键。
 * 匹配阶段为了性能把这些节点全扔了，所以快照必须另走一条路径。
 */
data class SnapshotDoc(
    val capturedAt: Long = System.currentTimeMillis(),
    val pkg: String = "",
    val activity: String? = null,
    val screenWidth: Int = 0,
    val screenHeight: Int = 0,
    val density: Float = 1f,
    val nodeCount: Int = 0,
    /** 是否因为超出节点/深度上限被截断，写规则时要知道树是不完整的。 */
    val truncated: Boolean = false,
    val root: SnapshotNode? = null,
)

data class SnapshotNode(
    val cls: String = "",
    val text: String? = null,
    val desc: String? = null,
    val vid: String? = null,
    val l: Int = 0,
    val t: Int = 0,
    val r: Int = 0,
    val b: Int = 0,
    val clickable: Boolean = false,
    val enabled: Boolean = true,
    val visible: Boolean = true,
    val children: List<SnapshotNode> = emptyList(),
) {
    val width: Int get() = (r - l).coerceAtLeast(0)
    val height: Int get() = (b - t).coerceAtLeast(0)

    /** 一行摘要，列表里展示用。 */
    fun brief(): String = buildString {
        append(cls.substringAfterLast('.'))
        text?.let { append("  text=\"").append(it.take(24)).append('"') }
        desc?.let { append("  desc=\"").append(it.take(24)).append('"') }
        vid?.let { append("  #").append(it) }
        if (clickable) append("  [可点]")
        if (!visible) append("  [不可见]")
    }
}
