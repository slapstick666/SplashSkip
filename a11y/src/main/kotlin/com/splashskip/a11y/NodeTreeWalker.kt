package com.splashskip.a11y

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.splashskip.core.model.IntRect
import com.splashskip.core.model.NodeSnapshot

/**
 * 一次遍历的结果，同时持有需要回收的系统节点。
 *
 * 为什么要把「节点列表」和「需要回收的节点」绑在一起？
 * 因为 Android 11 及以前，AccessibilityNodeInfo 是池化的，
 * 拿到的节点不还回去会导致后续 getChild 返回 null（节点池耗尽），
 * 表现就是「规则一开始好用，用一会儿就跳不过了」。
 * 这类问题极难排查，所以生命周期必须在类型上强制管理。
 */
internal class A11yTree(
    val nodes: List<NodeSnapshot>,
    private val owned: List<AccessibilityNodeInfo>,
) : AutoCloseable {

    override fun close() {
        owned.forEach { A11yNodeHandle.recycleQuietly(it) }
    }

    companion object {
        val EMPTY = A11yTree(emptyList(), emptyList())
    }
}

/**
 * 节点树扁平化（广度优先）。
 *
 * 三个关键优化，都是为了让单次遍历的开销可控：
 *
 * 1. **不可见子树直接剪掉**：isVisibleToUser 为 false 的节点，其子孙也不可见，
 *    没必要往下走。开屏广告页里大量 View 是 GONE 的，这一刀能砍掉一半节点。
 *
 * 2. **只保留「有信息量」的节点**：文本、描述、viewId 三者全空的节点
 *    （纯布局容器）不生成快照。1200 个节点里通常只有 100~200 个是这种，
 *    直接减少 80% 的对象分配，GC 压力肉眼可见地降低。
 *
 * 3. **节点数 + 深度双上限**：防止个别 App（比如带超长列表的）把遍历拖成秒级。
 *
 * 代价：只靠 className 匹配的规则会失效。这个取舍是明确的 ——
 * 开屏跳过场景里几乎没有只靠类名就能锁定的按钮。
 *
 * ## 关于 keepStructural
 *
 * GKD 格式的规则靠关系选择器定位节点（`A > B` 是父子、`A + B` 是兄弟），
 * 而剪掉纯容器节点会把树剪断，`>` 的语义随之失真。
 * 所以当目标 App 有 GKD 规则时，遍历会带上 keepStructural=true，
 * 把容器节点也留下来，让树保持完整。
 * 没有 GKD 规则的 App 走默认路径，行为和性能都不变。
 */
internal object NodeTreeWalker {

    private class Frame(
        val node: AccessibilityNodeInfo,
        val depth: Int,
        /** 最近的可点击祖先所在深度，-1 表示没有。 */
        val clickableDepth: Int,
        /** 最近一个被保留的祖先的快照索引，-1 表示还没有。 */
        val parentSnapshot: Int,
        /** 本节点在真实父节点子列表中的序号。 */
        val indexInParent: Int,
    )

    fun walk(
        root: AccessibilityNodeInfo?,
        service: android.accessibilityservice.AccessibilityService,
        maxNodes: Int,
        maxDepth: Int,
        keepStructural: Boolean = false,
    ): A11yTree {
        if (root == null) return A11yTree.EMPTY

        val out = ArrayList<NodeSnapshot>(96)
        val owned = ArrayList<AccessibilityNodeInfo>(96)
        // 子节点索引单独攒着，最后再挂回快照 —— 边遍历边 append 会不断重建 List
        val childLists = ArrayList<ArrayList<Int>>(96)
        // 用 ArrayList 当队列 + 游标，比 ArrayDeque 少一层装箱
        val queue = ArrayList<Frame>(128)
        queue.add(Frame(root, 0, -1, -1, 0))
        owned.add(root)

        var cursor = 0
        var visited = 0

        while (cursor < queue.size && visited < maxNodes) {
            val frame = queue[cursor++]
            visited++
            val node = frame.node

            // 优化 1：不可见子树整棵剪掉
            if (!node.isVisibleToUser) continue

            val text = node.text?.toString().orEmpty()
            val desc = node.contentDescription?.toString().orEmpty()
            val viewIdFull = node.viewIdResourceName.orEmpty()
            val viewId = viewIdFull.substringAfterLast('/')

            // 优化 2：只为有信息量的节点建快照（keepStructural 时全留）
            val informative = text.isNotEmpty() || desc.isNotEmpty() || viewId.isNotEmpty()
            var mySnapshot = frame.parentSnapshot
            // 子节点数必须无条件取 —— 剪掉的容器节点虽然没有快照，但它的子树仍要往下走
            val count = runCatching { node.childCount }.getOrDefault(0)

            if (informative || keepStructural) {
                val rect = Rect().also { node.getBoundsInScreen(it) }
                val handle = A11yNodeHandle(node, service)
                val snapshot = NodeSnapshot(
                    text = text,
                    desc = desc,
                    viewId = viewId,
                    viewIdFull = viewIdFull,
                    className = node.className?.toString().orEmpty(),
                    bounds = IntRect(rect.left, rect.top, rect.right, rect.bottom),
                    clickable = node.isClickable,
                    enabled = node.isEnabled,
                    visible = true,
                    depth = frame.depth,
                    clickableAncestorDepth = frame.clickableDepth,
                    handle = handle,
                )
                handle.bind(snapshot)
                snapshot.parentIndex = frame.parentSnapshot
                snapshot.indexInParent = frame.indexInParent
                snapshot.childCount = count
                mySnapshot = out.size
                out.add(snapshot)
                childLists.add(ArrayList(4))
                if (frame.parentSnapshot >= 0) {
                    childLists[frame.parentSnapshot].add(mySnapshot)
                }
            }

            // 优化 3：深度上限
            if (frame.depth >= maxDepth) continue

            val selfClickableDepth = if (node.isClickable) frame.depth else frame.clickableDepth
            for (i in 0 until count) {
                if (queue.size >= maxNodes) break
                val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
                owned.add(child)
                queue.add(Frame(child, frame.depth + 1, selfClickableDepth, mySnapshot, i))
            }
        }

        queue.clear()

        // 挂回子节点索引，并按深度优先先序编号
        for (i in out.indices) {
            out[i].childIndices = childLists[i]
        }
        assignDfsOrder(out)

        return A11yTree(out, owned)
    }

    /**
     * 深度优先先序编号。
     *
     * GKD 的 `<<` 关系算符按先序序号定位子孙节点，所以这个序号必须是
     * 「先序」而不是「层序」—— 遍历器本身是广度优先，编号得单独算一遍。
     */
    private fun assignDfsOrder(nodes: List<NodeSnapshot>) {
        if (nodes.isEmpty()) return
        // 找到根（parentIndex == -1）。可能有多个（剪枝造成），逐个遍历
        val roots = nodes.indices.filter { nodes[it].parentIndex < 0 }
        var order = 0
        val stack = ArrayDeque<Int>()
        // 倒序入栈，保证弹出顺序是「先序」
        for (r in roots.asReversed()) stack.addLast(r)
        val visited = BooleanArray(nodes.size)
        while (stack.isNotEmpty()) {
            val idx = stack.removeLast()
            if (visited[idx]) continue
            visited[idx] = true
            nodes[idx].dfsOrder = order++
            val kids = nodes[idx].childIndices
            for (k in kids.indices.reversed()) {
                stack.addLast(kids[k])
            }
        }
        // 理论上不会有孤立节点，但保险起见把没编号的补上
        for (i in nodes.indices) {
            if (!visited[i]) nodes[i].dfsOrder = order++
        }
    }
}
