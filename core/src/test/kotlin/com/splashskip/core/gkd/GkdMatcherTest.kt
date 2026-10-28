package com.splashskip.core.gkd

import com.splashskip.core.model.*
import com.splashskip.core.model.IntRect
import com.splashskip.core.model.NodeSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GkdMatcherTest {

    // ---------------- 测试树 ----------------

    private data class Spec(
        val text: String = "",
        val desc: String = "",
        val vid: String = "",
        val id: String = "",
        val cls: String = "android.widget.FrameLayout",
        val clickable: Boolean = false,
        val children: List<Spec> = emptyList(),
    )

    /**
     * 搭一棵树：
     *
     *   root(FrameLayout, id=android:id/content)
     *   ├── ad(LinearLayout, text="广告位")
     *   │   ├── skip(TextView, text="跳过", 可点击)
     *   │   └── closeIcon(ImageView, vid=iv_close)
     *   └── footer(LinearLayout)
     *       ├── nav(TextView, text="首页")
     *       └── more(TextView, text="更多")
     */
    private val tree: List<NodeSnapshot> by lazy { build(treeSpec) }

    private val treeSpec = Spec(
        id = "android:id/content",
        cls = "android.widget.FrameLayout",
        children = listOf(
            Spec(
                text = "广告位",
                cls = "android.widget.LinearLayout",
                children = listOf(
                    Spec(text = "跳过", cls = "android.widget.TextView", clickable = true),
                    Spec(vid = "iv_close", cls = "android.widget.ImageView"),
                ),
            ),
            Spec(
                cls = "android.widget.LinearLayout",
                children = listOf(
                    Spec(text = "首页", cls = "android.widget.TextView"),
                    Spec(text = "更多", cls = "android.widget.TextView"),
                ),
            ),
        ),
    )

    private fun build(spec: Spec): List<NodeSnapshot> {
        val out = mutableListOf<NodeSnapshot>()
        fun add(s: Spec, parentIdx: Int, depth: Int, idxInParent: Int) {
            val n = NodeSnapshot(
                text = s.text,
                desc = s.desc,
                viewId = s.vid,
                viewIdFull = s.id.ifEmpty { if (s.vid.isEmpty()) "" else "com.x:id/${s.vid}" },
                className = s.cls,
                bounds = IntRect(0, 0, 100, 100),
                clickable = s.clickable,
                enabled = true,
                visible = true,
                depth = depth,
            )
            n.parentIndex = parentIdx
            n.indexInParent = idxInParent
            n.childCount = s.children.size
            val myIdx = out.size
            out.add(n)
            s.children.forEachIndexed { i, c -> add(c, myIdx, depth + 1, i) }
        }
        add(spec, -1, 0, 0)

        for (i in out.indices) {
            out[i].childIndices = out.indices.filter { out[it].parentIndex == i }
        }
        var order = 0
        fun dfs(idx: Int) {
            out[idx].dfsOrder = order++
            out[idx].childIndices.forEach { dfs(it) }
        }
        out.indices.firstOrNull { out[it].parentIndex < 0 }?.let { dfs(it) }
        return out
    }

    private fun match(selectorText: String): List<GkdMatch> {
        val sel = GkdSelectorParser.parse(selectorText)
        return GkdMatcher.match(sel, tree)
    }

    private fun targetText(selectorText: String): String? =
        match(selectorText).firstOrNull()?.target?.textOrDesc()

    /**
     * 取出匹配路径上各节点的可读标识，用来断言「关系算符的方向对不对」。
     *
     * 注意：GKD 的目标节点默认是**链尾**（最右），`@` 才会改写目标。
     * 所以断言关系语义时看路径，而不是看 target。
     */
    private fun pathTexts(selectorText: String): List<String> {
        val hit = match(selectorText).firstOrNull() ?: return emptyList()
        return hit.path.map { n ->
            when {
                n.text.isNotEmpty() -> n.text
                n.viewId.isNotEmpty() -> n.viewId
                else -> n.className.substringAfterLast('.')
            }
        }
    }

    // ---------------- 属性匹配 ----------------

    @Test
    fun `按文本精确匹配`() {
        assertEquals("跳过", targetText("""[text="跳过"]"""))
    }

    @Test
    fun `按文本包含匹配`() {
        assertEquals("跳过", targetText("""[text*="跳"]"""))
    }

    @Test
    fun `按类名匹配短名或全名`() {
        assertEquals("跳过", targetText("""TextView[text="跳过"]"""))
        assertEquals("跳过", targetText("""android.widget.TextView[text="跳过"]"""))
    }

    @Test
    fun `按 viewId 短名与全名分别匹配`() {
        assertEquals("iv_close", match("""[vid="iv_close"]""").firstOrNull()?.target?.viewId)
        assertEquals("iv_close", match("""[id="com.x:id/iv_close"]""").firstOrNull()?.target?.viewId)
    }

    @Test
    fun `文本长度比较`() {
        assertEquals("跳过", targetText("""[text="跳过"][text.length<=10]"""))
        assertTrue(match("""[text="跳过"][text.length<=1]""").isEmpty())
    }

    @Test
    fun `逻辑与或非`() {
        assertEquals("跳过", targetText("""[text="跳过" || text="首页"]"""))
        assertEquals("跳过", targetText("""[text="跳过"][clickable=true]"""))
        assertTrue(match("""[text="跳过"][clickable=false]""").isEmpty())
        // 取反后应当把「跳过」排除掉，但「首页」仍在结果里
        val negated = match("""[!(text="跳过")]""")
        assertTrue(negated.any { it.target.text == "首页" })
        assertTrue(negated.none { it.target.text == "跳过" })
    }

    @Test
    fun `index 与 childCount 属性`() {
        assertEquals("首页", targetText("""[text="首页"][index=0]"""))
        assertTrue(match("""[text="首页"][index=1]""").isEmpty())
        assertEquals("广告位", targetText("""[text="广告位"][childCount=2]"""))
    }

    @Test
    fun `parent 为 null 表示根节点`() {
        assertNotNull(match("""[id="android:id/content"][parent=null]""").firstOrNull())
        assertTrue(match("""[text="跳过"][parent=null]""").isEmpty())
    }

    // ---------------- 关系算符 ----------------

    @Test
    fun `大于号表示左侧是右侧的祖先`() {
        // 「跳过」是「广告位」的子节点，所以「广告位」是「跳过」的祖先
        assertEquals("跳过", targetText("""LinearLayout[text="广告位"] > [text="跳过"]"""))
    }

    @Test
    fun `小于号表示左侧是右侧的子节点`() {
        // 「跳过」是「广告位」的子节点 → 用 < 时左侧是子节点
        assertEquals(listOf("跳过", "广告位"), pathTexts("""[text="跳过"] < LinearLayout[text="广告位"]"""))
    }

    @Test
    fun `大于号带层数表示第 n 层祖先`() {
        // 跳过 的第 2 层祖先是 root
        assertEquals("跳过", targetText("""FrameLayout[id="android:id/content"] >2 [text="跳过"]"""))
        assertTrue(match("""LinearLayout[text="广告位"] >2 [text="跳过"]""").isEmpty())
    }

    @Test
    fun `大于号带 n 表示任意深度祖先`() {
        assertEquals("跳过", targetText("""FrameLayout[id="android:id/content"] >n [text="跳过"]"""))
    }

    @Test
    fun `加号表示前置兄弟`() {
        // 首页 在 更多 前面 → 左侧是右侧的前置兄弟
        assertEquals(listOf("首页", "更多"), pathTexts("""[text="首页"] + [text="更多"]"""))
        // 反向不成立
        assertTrue(match("""[text="更多"] + [text="首页"]""").isEmpty())
    }

    @Test
    fun `减号表示后置兄弟`() {
        // 更多 在 首页 后面 → 左侧是右侧的后置兄弟
        assertEquals(listOf("更多", "首页"), pathTexts("""[text="更多"] - [text="首页"]"""))
        assertTrue(match("""[text="首页"] - [text="更多"]""").isEmpty())
    }

    @Test
    fun `双小于号表示子孙节点`() {
        // << 的方向与 > 相反：左侧是右侧的**子孙**
        assertEquals(listOf("跳过", "广告位"), pathTexts("""[text="跳过"] << LinearLayout[text="广告位"]"""))
        assertTrue(match("""[text="首页"] << LinearLayout[text="广告位"]""").isEmpty())
    }

    @Test
    fun `双小于号带偏移量按先序序号定位`() {
        // 广告位 子树内的先序：skip(1), closeIcon(2)
        assertEquals(listOf("跳过", "广告位"), pathTexts("""[text="跳过"] <<(2) LinearLayout[text="广告位"]"""))
        assertEquals(
            listOf("iv_close", "广告位"),
            pathTexts("""[vid="iv_close"] <<(3) LinearLayout[text="广告位"]"""),
        )
    }

    @Test
    fun `多层关系链能正确串起来`() {
        val sel = GkdSelectorParser.parse(
            """FrameLayout[id="android:id/content"] > LinearLayout[text="广告位"] > [text="跳过"]""",
        )
        val hits = GkdMatcher.match(sel, tree)
        assertEquals(1, hits.size)
        assertEquals("跳过", hits[0].target.text)
    }

    // ---------------- @ 目标 ----------------

    @Test
    fun `at 标记决定目标节点`() {
        val sel = GkdSelectorParser.parse("""LinearLayout[text="广告位"] > @[text="跳过"]""")
        val hits = GkdMatcher.match(sel, tree)
        assertEquals("跳过", hits[0].target.text)
    }

    @Test
    fun `at 标记在左侧时目标是左侧节点`() {
        val sel = GkdSelectorParser.parse("""@LinearLayout[text="广告位"] > [text="跳过"]""")
        val hits = GkdMatcher.match(sel, tree)
        assertEquals("广告位", hits[0].target.text)
    }

    @Test
    fun `没有 at 时目标是链尾`() {
        val sel = GkdSelectorParser.parse("""LinearLayout[text="广告位"] > [text="跳过"]""")
        val hits = GkdMatcher.match(sel, tree)
        assertEquals("跳过", hits[0].target.text)
    }

    // ---------------- 真实规则形态 ----------------

    @Test
    fun `真实规则的复合条件能命中`() {
        assertEquals(
            "跳过",
            targetText("""[text*="跳过"][text.length<10][width<500 && height<300][visibleToUser=true]"""),
        )
    }

    @Test
    fun `getChild 与 minus 调用`() {
        // footer 的最后一个子节点是「更多」，命中的是那个 LinearLayout 本身
        val hit = match("""LinearLayout[childCount=2][getChild(childCount.minus(1)).text="更多"]""")
            .firstOrNull()
        assertNotNull(hit)
        assertEquals("android.widget.LinearLayout", hit!!.target.className)
        assertEquals(2, hit.target.childCount)
        val lastChild = hit.target.childIndices.lastOrNull()?.let { tree.getOrNull(it) }
        assertEquals("更多", lastChild?.text)
    }
}
