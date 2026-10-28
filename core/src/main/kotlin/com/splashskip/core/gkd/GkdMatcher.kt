package com.splashskip.core.gkd

import com.splashskip.core.model.*

/** 一次命中的结果：目标节点 + 整条路径。 */
data class GkdMatch(
    /** 要执行动作的节点（由选择器里的 `@` 决定）。 */
    val target: NodeSnapshot,
    /** 从链首到链尾的完整节点路径，用于排查与日志。 */
    val path: List<NodeSnapshot>,
)

/**
 * GKD 选择器匹配器。
 *
 * ## 匹配方向
 *
 * GKD 是**从右往左**匹配的：先在整棵树里找出满足链尾属性选择器的节点，
 * 再顺着关系算符往左回溯，逐级验证。
 * 官方原话：「FrameLayout > TextView，它是先从根节点找到 TextView，
 * 然后再判断 parent 是不是 FrameLayout」。
 *
 * ## 关系算符的方向
 *
 * 这一段最容易搞反，因为 `>` 与 CSS 的直觉相反。官方定义（gkd.li/guide/selector）：
 *
 *   A >  B   →  A 是 B 的祖先节点      →  从 B 往上找
 *   A <  B   →  A 是 B 的直接子节点    →  从 B 往下找
 *   A +  B   →  A 是 B 的前置兄弟节点  →  从 B 往前找
 *   A -  B   →  A 是 B 的后置兄弟节点  →  从 B 往后找
 *   A << B   →  A 是 B 的子孙节点      →  在 B 的子树里找
 *
 * 而 `A >n B` 里的 n 是**任意正整数**，意思是「任意深度的后代」，
 * 不是「第 n 层」。这个 n 的语义在匹配器里要单独处理。
 */
object GkdMatcher {

    /** 单条选择器最多保留多少条完整路径，防止组合爆炸。 */
    private const val MAX_PATHS = 64

    /** 单步关系展开最多产出多少候选。 */
    private const val MAX_EXPAND = 64

    /**
     * 在节点列表上匹配一条选择器。
     *
     * @param selector 解析好的选择器
     * @param nodes 节点快照列表（需带 parentIndex / childIndices / dfsOrder）
     * @return 命中的目标节点列表，按「自身可点击优先、DFS 序靠前」排序
     */
    fun match(selector: GkdSelector, nodes: List<NodeSnapshot>): List<GkdMatch> {
        if (nodes.isEmpty()) return emptyList()
        val n = selector.chain.size
        if (n == 0) return emptyList()
        val ctx = Ctx(nodes)

        // 1. 先定位链尾 —— 它通常是最有选择性的（精确 text / vid）
        val tail = selector.chain[n - 1]
        var paths: MutableList<Array<NodeSnapshot?>> = ArrayList(64)
        for (node in nodes) {
            if (!attrMatches(ctx, node, tail)) continue
            val arr = arrayOfNulls<NodeSnapshot>(n)
            arr[n - 1] = node
            paths.add(arr)
            if (paths.size >= MAX_PATHS) break
        }
        if (paths.isEmpty()) return emptyList()

        // 2. 从右往左逐级展开
        for (i in n - 2 downTo 0) {
            val relation = selector.relations.getOrNull(i) ?: break
            val attr = selector.chain[i]
            val next = ArrayList<Array<NodeSnapshot?>>(paths.size)
            for (arr in paths) {
                val anchor = arr[i + 1] ?: continue
                var produced = 0
                for (cand in relationCandidates(ctx, anchor, relation)) {
                    if (!attrMatches(ctx, cand, attr)) continue
                    val copy = arr.copyOf()
                    copy[i] = cand
                    next.add(copy)
                    produced++
                    if (produced >= MAX_EXPAND) break
                }
            }
            paths = next
            if (paths.isEmpty()) return emptyList()
            if (paths.size > MAX_PATHS) paths = paths.subList(0, MAX_PATHS)
        }

        // 3. 取出目标节点
        val targetIdx = selector.targetIndex.coerceIn(0, n - 1)
        val results = ArrayList<GkdMatch>(paths.size)
        for (arr in paths) {
            val target = arr[targetIdx] ?: continue
            results.add(GkdMatch(target, arr.filterNotNull()))
        }
        return results.sortedWith(
            compareByDescending<GkdMatch> { it.target.clickable }
                .thenBy { it.target.dfsOrder },
        )
    }

    /** 节点寻址上下文。避免在匹配器里用可变字段存节点列表。 */
    private class Ctx(val nodes: List<NodeSnapshot>) {
        fun nodeAt(index: Int): NodeSnapshot? = nodes.getOrNull(index)
        fun parentOf(node: NodeSnapshot): NodeSnapshot? =
            node.parentIndex.takeIf { it >= 0 }?.let { nodes.getOrNull(it) }
    }

    // ---------------- 关系算符展开 ----------------

    private fun relationCandidates(
        ctx: Ctx,
        anchor: NodeSnapshot,
        relation: GkdRelation,
    ): List<NodeSnapshot> = when (relation.op) {
        GkdRelOp.ANCESTOR -> ancestorsOf(ctx, anchor, relation.offsets)
        GkdRelOp.CHILD -> childrenOf(ctx, anchor, relation.offsets)
        GkdRelOp.PREV_SIBLING -> siblingsBefore(ctx, anchor, relation.offsets)
        GkdRelOp.NEXT_SIBLING -> siblingsAfter(ctx, anchor, relation.offsets)
        GkdRelOp.DESCENDANT -> descendantsOf(ctx, anchor, relation.offsets)
    }

    /** `A > B`：A 是 B 的祖先。从 B 往上走。 */
    private fun ancestorsOf(ctx: Ctx, anchor: NodeSnapshot, offsets: GkdOffsets): List<NodeSnapshot> {
        val chain = ancestorChain(ctx, anchor)
        if (offsets.infinite) return chain
        val out = ArrayList<NodeSnapshot>(offsets.finite.size)
        for (k in offsets.finite) {
            val idx = k - 1 // chain[0] 是父，chain[1] 是祖父
            if (idx in chain.indices) out.add(chain[idx])
        }
        return out
    }

    private fun ancestorChain(ctx: Ctx, anchor: NodeSnapshot): List<NodeSnapshot> {
        val out = ArrayList<NodeSnapshot>(8)
        var cur = anchor.parentIndex
        var guard = 0
        while (cur >= 0 && guard++ < 256) {
            val node = ctx.nodeAt(cur) ?: break
            out.add(node)
            cur = node.parentIndex
        }
        return out
    }

    /** `A < B`：A 是 B 的直接子节点，取第 k 个子节点。 */
    private fun childrenOf(ctx: Ctx, anchor: NodeSnapshot, offsets: GkdOffsets): List<NodeSnapshot> {
        val kids = anchor.childIndices
        if (kids.isEmpty()) return emptyList()
        val out = ArrayList<NodeSnapshot>(minOf(kids.size, 8))
        if (offsets.infinite) {
            for (idx in kids) ctx.nodeAt(idx)?.let(out::add)
            return out
        }
        for (k in offsets.finite) {
            // A.index 满足 a_m-1，所以第 k 个子节点是 kids[k-1]
            val idx = k - 1
            if (idx in kids.indices) ctx.nodeAt(kids[idx])?.let(out::add)
        }
        return out
    }

    /** `A + B`：A 在 B 前面。 */
    private fun siblingsBefore(ctx: Ctx, anchor: NodeSnapshot, offsets: GkdOffsets): List<NodeSnapshot> {
        val (parent, myIdx) = siblingPosition(ctx, anchor) ?: return emptyList()
        val kids = parent.childIndices
        val out = ArrayList<NodeSnapshot>()
        if (offsets.infinite) {
            for (i in myIdx - 1 downTo 0) ctx.nodeAt(kids[i])?.let(out::add)
        } else {
            for (k in offsets.finite) {
                val i = myIdx - k
                if (i >= 0) ctx.nodeAt(kids[i])?.let(out::add)
            }
        }
        return out
    }

    /** `A - B`：A 在 B 后面。 */
    private fun siblingsAfter(ctx: Ctx, anchor: NodeSnapshot, offsets: GkdOffsets): List<NodeSnapshot> {
        val (parent, myIdx) = siblingPosition(ctx, anchor) ?: return emptyList()
        val kids = parent.childIndices
        val out = ArrayList<NodeSnapshot>()
        if (offsets.infinite) {
            for (i in myIdx + 1 until kids.size) ctx.nodeAt(kids[i])?.let(out::add)
        } else {
            for (k in offsets.finite) {
                val i = myIdx + k
                if (i in kids.indices) ctx.nodeAt(kids[i])?.let(out::add)
            }
        }
        return out
    }

    /** 返回 anchor 的父节点，以及它在父节点子列表中的位置。 */
    private fun siblingPosition(ctx: Ctx, anchor: NodeSnapshot): Pair<NodeSnapshot, Int>? {
        val parent = ctx.parentOf(anchor) ?: return null
        var idx = -1
        for (i in parent.childIndices.indices) {
            if (parent.childIndices[i].let { ctx.nodeAt(it) } === anchor) {
                idx = i
                break
            }
        }
        if (idx < 0) return null
        return parent to idx
    }

    /**
     * `A << B`：A 是 B 的子孙。
     *
     * 偏移量在官方定义里是「B 子树内的深度优先先序序号」，
     * 所以 `(3)` 指子树里第 3 个（相对序号 2），`(n)` 指任意位置。
     * 子树在全局先序里是连续的，所以相对序号 = dfsOrder 之差，O(1) 就能算。
     */
    private fun descendantsOf(ctx: Ctx, anchor: NodeSnapshot, offsets: GkdOffsets): List<NodeSnapshot> {
        val all = subtreeOf(ctx, anchor)
        if (all.isEmpty()) return emptyList()
        if (offsets.infinite) return all
        val wanted = offsets.finite.map { it - 1 }.toSet()
        val exact = all.filter { (it.dfsOrder - anchor.dfsOrder) in wanted }
        // 精确序号匹配不到时退回「子树内任意位置」，宁可多给候选也不漏
        return if (exact.isNotEmpty()) exact else all
    }

    private fun subtreeOf(ctx: Ctx, anchor: NodeSnapshot): List<NodeSnapshot> {
        val out = ArrayList<NodeSnapshot>()
        val stack = ArrayDeque<Int>()
        for (k in anchor.childIndices.asReversed()) stack.addLast(k)
        var guard = 0
        while (stack.isNotEmpty() && guard++ < 4096) {
            val idx = stack.removeLast()
            val node = ctx.nodeAt(idx) ?: continue
            out.add(node)
            for (k in node.childIndices.asReversed()) stack.addLast(k)
        }
        out.sortBy { it.dfsOrder }
        return out
    }

    // ---------------- 属性匹配 ----------------

    private fun attrMatches(ctx: Ctx, node: NodeSnapshot, attr: GkdAttr): Boolean {
        val cls = attr.className
        if (cls != null && cls != "*") {
            if (node.className != cls && !node.className.endsWith(".$cls")) return false
        }
        val cond = attr.cond ?: return true
        return evalExpr(ctx, node, cond)
    }

    private fun evalExpr(ctx: Ctx, node: NodeSnapshot, expr: GkdExpr): Boolean = when (expr) {
        is GkdExpr.Cmp -> compare(expr.op, evalValue(ctx, node, expr.left), evalValue(ctx, node, expr.right))
        is GkdExpr.And -> evalExpr(ctx, node, expr.left) && evalExpr(ctx, node, expr.right)
        is GkdExpr.Or -> evalExpr(ctx, node, expr.left) || evalExpr(ctx, node, expr.right)
        is GkdExpr.Not -> !evalExpr(ctx, node, expr.inner)
    }

    /**
     * 属性求值。
     *
     * 返回 null 表示「取不到这个属性」，此时除了 `=`/`!=` 之外的比较一律为 false
     * （官方明确规定这条规则）。
     */
    private fun evalValue(ctx: Ctx, node: NodeSnapshot, value: GkdValue): Any? = when (value) {
        is GkdValue.Lit -> value.value
        is GkdValue.Var -> nodeProperty(ctx, node, value.name)
        is GkdValue.Member -> memberOf(ctx, evalValue(ctx, node, value.base), value.property)
        is GkdValue.Call -> callValue(ctx, node, value)
    }

    private fun nodeProperty(ctx: Ctx, node: NodeSnapshot, name: String): Any? = when (name) {
        "text" -> node.text
        "desc" -> node.desc
        "vid" -> node.viewId
        "id" -> node.viewIdFull
        "name" -> node.className
        "clickable" -> node.clickable
        "visibleToUser" -> node.visible
        "enabled" -> node.enabled
        "childCount" -> node.childCount
        "index" -> node.indexInParent
        "depth" -> node.depth
        "order" -> node.dfsOrder
        "width" -> node.bounds.width
        "height" -> node.bounds.height
        "left" -> node.bounds.left
        "top" -> node.bounds.top
        "right" -> node.bounds.right
        "bottom" -> node.bounds.bottom
        "parent" -> ctx.parentOf(node)
        else -> null
    }

    private fun memberOf(ctx: Ctx, base: Any?, property: String): Any? {
        if (base == null) return null
        return when (base) {
            is String -> if (property == "length") base.length else null
            is NodeSnapshot -> nodeProperty(ctx, base, property)
            else -> null
        }
    }

    private fun callValue(ctx: Ctx, node: NodeSnapshot, call: GkdValue.Call): Any? {
        val caller = call.caller
        return when (caller) {
            is GkdValue.Var -> when (caller.name) {
                "parent" -> ctx.parentOf(node)
                "getChild" -> {
                    val idx = (call.args.firstOrNull()?.let { evalValue(ctx, node, it) } as? Number)?.toInt()
                        ?: return null
                    node.childIndices.getOrNull(idx)?.let { ctx.nodeAt(it) }
                }
                else -> null
            }
            is GkdValue.Member -> {
                val base = evalValue(ctx, node, caller.base)
                when (caller.property) {
                    "minus" -> {
                        val b = base as? Number ?: return null
                        val a = (call.args.firstOrNull()?.let { evalValue(ctx, node, it) } as? Number)?.toInt() ?: return null
                        b.toInt() - a
                    }
                    "plus" -> {
                        val b = base as? Number ?: return null
                        val a = (call.args.firstOrNull()?.let { evalValue(ctx, node, it) } as? Number)?.toInt() ?: return null
                        b.toInt() + a
                    }
                    else -> null
                }
            }
            else -> null
        }
    }

    // ---------------- 比较 ----------------

    private fun compare(op: GkdCmpOp, left: Any?, right: Any?): Boolean {
        // 官方规则：除 = 与 != 之外，属性为 null 时表达式一律为 false
        if (left == null || right == null) {
            return when (op) {
                GkdCmpOp.EQ -> left == right
                GkdCmpOp.NE -> left != right
                else -> false
            }
        }

        return when (op) {
            GkdCmpOp.EQ -> valueEquals(left, right)
            GkdCmpOp.NE -> !valueEquals(left, right)
            GkdCmpOp.GT -> numeric(left, right)?.let { it.first > it.second } ?: false
            GkdCmpOp.GE -> numeric(left, right)?.let { it.first >= it.second } ?: false
            GkdCmpOp.LT -> numeric(left, right)?.let { it.first < it.second } ?: false
            GkdCmpOp.LE -> numeric(left, right)?.let { it.first <= it.second } ?: false
            GkdCmpOp.STARTS -> text(left, right)?.let { it.first.startsWith(it.second) } ?: false
            GkdCmpOp.NOT_STARTS -> text(left, right)?.let { !it.first.startsWith(it.second) } ?: false
            GkdCmpOp.CONTAINS -> text(left, right)?.let { it.first.contains(it.second) } ?: false
            GkdCmpOp.NOT_CONTAINS -> text(left, right)?.let { !it.first.contains(it.second) } ?: false
            GkdCmpOp.ENDS -> text(left, right)?.let { it.first.endsWith(it.second) } ?: false
            GkdCmpOp.NOT_ENDS -> text(left, right)?.let { !it.first.endsWith(it.second) } ?: false
            GkdCmpOp.MATCHES -> regexMatch(left, right, negate = false)
            GkdCmpOp.NOT_MATCHES -> regexMatch(left, right, negate = true)
        }
    }

    private fun valueEquals(left: Any, right: Any): Boolean = when {
        left is Number && right is Number -> left.toDouble() == right.toDouble()
        else -> left == right
    }

    private fun numeric(left: Any, right: Any): Pair<Double, Double>? {
        val l = left as? Number ?: return null
        val r = right as? Number ?: return null
        return l.toDouble() to r.toDouble()
    }

    private fun text(left: Any, right: Any): Pair<String, String>? {
        val l = left as? String ?: return null
        val r = right as? String ?: return null
        return l to r
    }

    private fun regexMatch(left: Any, right: Any, negate: Boolean): Boolean {
        val l = left as? String ?: return false
        val r = right as? String ?: return false
        val hit = runCatching { Regex(r).containsMatchIn(l) }.getOrDefault(false)
        return if (negate) !hit else hit
    }
}
