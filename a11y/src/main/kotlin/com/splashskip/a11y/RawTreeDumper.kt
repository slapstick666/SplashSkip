package com.splashskip.a11y

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.splashskip.core.model.ScreenSize

/**
 * 完整节点树导出。
 *
 * 与 [NodeTreeWalker] 的区别：那边为了性能把「无文本无 id 的容器」全剪掉了，
 * 这边一个都不能少 —— 写规则的人需要看到完整层级才能判断
 * 「这个跳过按钮挂在哪个容器下面」。
 *
 * 成本控制：
 *   - 节点上限 3000、深度上限 40，超了就标记 truncated 而不是静默丢数据
 *   - 这是低频操作（用户手动触发），不做 recycle 优化，交给 GC
 */
internal object RawTreeDumper {

    private const val MAX_NODES = 3000
    private const val MAX_DEPTH = 40

    fun dump(
        root: AccessibilityNodeInfo?,
        pkg: String,
        activity: String?,
        screen: ScreenSize,
    ): SnapshotDoc {
        if (root == null) {
            return SnapshotDoc(pkg = pkg, activity = activity)
        }

        var count = 0
        var truncated = false

        fun build(node: AccessibilityNodeInfo, depth: Int): SnapshotNode? {
            if (count >= MAX_NODES || depth > MAX_DEPTH) {
                truncated = true
                return null
            }
            count++

            val rect = Rect().also { node.getBoundsInScreen(it) }
            val children = ArrayList<SnapshotNode>()

            val n = runCatching { node.childCount }.getOrDefault(0)
            for (i in 0 until n) {
                val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
                build(child, depth + 1)?.let(children::add)
            }

            return SnapshotNode(
                cls = node.className?.toString().orEmpty(),
                text = node.text?.toString()?.takeIf { it.isNotEmpty() },
                desc = node.contentDescription?.toString()?.takeIf { it.isNotEmpty() },
                vid = node.viewIdResourceName?.substringAfterLast('/'),
                l = rect.left,
                t = rect.top,
                r = rect.right,
                b = rect.bottom,
                clickable = node.isClickable,
                enabled = node.isEnabled,
                visible = node.isVisibleToUser,
                children = children,
            )
        }

        val tree = build(root, 0)

        return SnapshotDoc(
            pkg = pkg.ifEmpty { root.packageName?.toString().orEmpty() },
            activity = activity,
            screenWidth = screen.width,
            screenHeight = screen.height,
            density = screen.density,
            nodeCount = count,
            truncated = truncated,
            root = tree,
        )
    }
}
