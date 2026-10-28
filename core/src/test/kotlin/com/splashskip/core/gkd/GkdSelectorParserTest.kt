package com.splashskip.core.gkd

import com.splashskip.core.model.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GkdSelectorParserTest {

    // ---------------- 基础结构 ----------------

    @Test
    fun `解析单个属性选择器`() {
        val sel = GkdSelectorParser.parse("""[text="跳过"]""")
        assertEquals(1, sel.chain.size)
        assertEquals(0, sel.relations.size)
        assertNull(sel.chain[0].className)
        assertEquals(0, sel.targetIndex)
    }

    @Test
    fun `解析类名选择器`() {
        val sel = GkdSelectorParser.parse("""TextView[text="跳过"]""")
        assertEquals("TextView", sel.chain[0].className)
    }

    @Test
    fun `解析星号通配`() {
        val sel = GkdSelectorParser.parse("""*[text="跳过"]""")
        assertEquals("*", sel.chain[0].className)
    }

    @Test
    fun `多个方括号之间是且关系`() {
        val sel = GkdSelectorParser.parse("""[text="跳过"][clickable=true]""")
        assertTrue(sel.chain[0].cond is GkdExpr.And)
    }

    @Test
    fun `@ 标记记录目标位置`() {
        val sel = GkdSelectorParser.parse("""@View[text="a"] > [text="b"]""")
        assertEquals(0, sel.targetIndex)
    }

    @Test
    fun `没有 @ 时目标是链尾`() {
        val sel = GkdSelectorParser.parse("""View[text="a"] > [text="b"]""")
        assertEquals(1, sel.targetIndex)
    }

    @Test
    fun `@ 在链中间时记录对应位置`() {
        val sel = GkdSelectorParser.parse("""View[text="a"] > @[text="b"]""")
        assertEquals(1, sel.targetIndex)
    }

    // ---------------- 关系算符 ----------------

    @Test
    fun `解析关系算符方向`() {
        val sel = GkdSelectorParser.parse("""A > B""")
        assertEquals(1, sel.relations.size)
        assertEquals(GkdRelOp.ANCESTOR, sel.relations[0].op)
        assertEquals(listOf(1), sel.relations[0].offsets.finite)
    }

    @Test
    fun `小于号是直接子节点`() {
        val sel = GkdSelectorParser.parse("""A < B""")
        assertEquals(GkdRelOp.CHILD, sel.relations[0].op)
    }

    @Test
    fun `加号是前置兄弟减号是后置兄弟`() {
        assertEquals(GkdRelOp.PREV_SIBLING, GkdSelectorParser.parse("A + B").relations[0].op)
        assertEquals(GkdRelOp.NEXT_SIBLING, GkdSelectorParser.parse("A - B").relations[0].op)
    }

    @Test
    fun `双小于号是子孙节点`() {
        assertEquals(GkdRelOp.DESCENDANT, GkdSelectorParser.parse("A << B").relations[0].op)
    }

    // ---------------- 关系表达式 ----------------

    @Test
    fun `解析元组偏移量`() {
        val sel = GkdSelectorParser.parse("""A <(1,2) B""")
        assertEquals(listOf(1, 2), sel.relations[0].offsets.finite)
    }

    @Test
    fun `解析无限多项式 n`() {
        val off = GkdSelectorParser.parse("A <<(n) B").relations[0].offsets
        assertTrue(off.infinite)
        assertEquals(1, off.start)
        assertEquals(1, off.step)
    }

    @Test
    fun `解析无限多项式 2n-1`() {
        val off = GkdSelectorParser.parse("A <<(2n-1) B").relations[0].offsets
        assertTrue(off.infinite)
        assertEquals(1, off.start)
        assertEquals(2, off.step)
    }

    @Test
    fun `解析有限多项式 -n+4 等价于 1,2,3`() {
        val off = GkdSelectorParser.parse("A <<(-n+4) B").relations[0].offsets
        assertEquals(listOf(1, 2, 3), off.finite)
    }

    @Test
    fun `解析有限多项式 -3n+10 等价于 1,4,7`() {
        val off = GkdSelectorParser.parse("A <<(-3n+10) B").relations[0].offsets
        assertEquals(listOf(1, 4, 7), off.finite)
    }

    @Test
    fun `解析 b-n 形式`() {
        val off = GkdSelectorParser.parse("A <<(12-n) B").relations[0].offsets
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11), off.finite)
    }

    @Test
    fun `解析简写偏移量`() {
        assertEquals(listOf(3), GkdSelectorParser.parse("A +3 B").relations[0].offsets.finite)
        val off = GkdSelectorParser.parse("A +3n B").relations[0].offsets
        assertTrue(off.infinite)
        assertEquals(3, off.start)
        assertEquals(3, off.step)
    }

    // ---------------- 表达式 ----------------

    @Test
    fun `解析逻辑或`() {
        val sel = GkdSelectorParser.parse("""[text="a" || text="b"]""")
        assertTrue(sel.chain[0].cond is GkdExpr.Or)
    }

    @Test
    fun `解析逻辑与`() {
        val sel = GkdSelectorParser.parse("""[text="a" && clickable=true]""")
        assertTrue(sel.chain[0].cond is GkdExpr.And)
    }

    @Test
    fun `且的优先级高于或`() {
        // a || b && c  应解析成  a || (b && c)
        val sel = GkdSelectorParser.parse("""[text="a" || text="b" && clickable=true]""")
        val or = sel.chain[0].cond as GkdExpr.Or
        assertTrue(or.right is GkdExpr.And)
    }

    @Test
    fun `解析取反`() {
        val sel = GkdSelectorParser.parse("""[!(text="a")]""")
        assertTrue(sel.chain[0].cond is GkdExpr.Not)
    }

    @Test
    fun `取反里的或也正确解析`() {
        val sel = GkdSelectorParser.parse("""[!(text="a" || text="b")]""")
        val not = sel.chain[0].cond as GkdExpr.Not
        assertTrue(not.inner is GkdExpr.Or)
    }

    @Test
    fun `解析全���比较运算符`() {
        val ops = listOf(
            "=" to GkdCmpOp.EQ, "!=" to GkdCmpOp.NE,
            ">" to GkdCmpOp.GT, ">=" to GkdCmpOp.GE,
            "<" to GkdCmpOp.LT, "<=" to GkdCmpOp.LE,
            "^=" to GkdCmpOp.STARTS, "!^=" to GkdCmpOp.NOT_STARTS,
            "*=" to GkdCmpOp.CONTAINS, "!*=" to GkdCmpOp.NOT_CONTAINS,
            "$=" to GkdCmpOp.ENDS, "!$=" to GkdCmpOp.NOT_ENDS,
            "~=" to GkdCmpOp.MATCHES, "!~=" to GkdCmpOp.NOT_MATCHES,
        )
        for ((text, expected) in ops) {
            val sel = GkdSelectorParser.parse("""[text$text"x"]""")
            val cmp = sel.chain[0].cond as GkdExpr.Cmp
            assertEquals("运算符 $text", expected, cmp.op)
        }
    }

    @Test
    fun `解析成员表达式`() {
        val sel = GkdSelectorParser.parse("""[text.length<=10]""")
        val cmp = sel.chain[0].cond as GkdExpr.Cmp
        assertTrue(cmp.left is GkdValue.Member)
        assertEquals("length", (cmp.left as GkdValue.Member).property)
    }

    @Test
    fun `解析调用表达式`() {
        val sel = GkdSelectorParser.parse("""[getChild(0).text="x"]""")
        val cmp = sel.chain[0].cond as GkdExpr.Cmp
        assertTrue(cmp.left is GkdValue.Member)
        val call = (cmp.left as GkdValue.Member).base as GkdValue.Call
        assertEquals(GkdValue.Var("getChild"), call.caller)
        assertEquals(1, call.args.size)
    }

    @Test
    fun `解析链式调用`() {
        val sel = GkdSelectorParser.parse("""[index=parent.childCount.minus(1)]""")
        val cmp = sel.chain[0].cond as GkdExpr.Cmp
        val call = cmp.right as GkdValue.Call
        assertEquals(GkdValue.Member(GkdValue.Member(GkdValue.Var("parent"), "childCount"), "minus"), call.caller)
        assertEquals(1, call.args.size)
    }

    @Test
    fun `解析 null 字面量`() {
        val sel = GkdSelectorParser.parse("""[text=null]""")
        val cmp = sel.chain[0].cond as GkdExpr.Cmp
        assertEquals(null, (cmp.right as GkdValue.Lit).value)
    }

    @Test
    fun `解析布尔字面量`() {
        val sel = GkdSelectorParser.parse("""[clickable=true]""")
        val cmp = sel.chain[0].cond as GkdExpr.Cmp
        assertEquals(true, (cmp.right as GkdValue.Lit).value)
    }

    @Test
    fun `解析字符串转义`() {
        val sel = GkdSelectorParser.parse("""[text="a\nb"]""")
        val cmp = sel.chain[0].cond as GkdExpr.Cmp
        assertEquals("a\nb", (cmp.right as GkdValue.Lit).value)
    }

    @Test
    fun `反引号字符串可用`() {
        val sel = GkdSelectorParser.parse("""[id=`com.foo:id/skip`]""")
        val cmp = sel.chain[0].cond as GkdExpr.Cmp
        assertEquals("com.foo:id/skip", (cmp.right as GkdValue.Lit).value)
    }

    // ---------------- 真实规则回归 ----------------

    @Test
    fun `解析真实订阅里的选择器`() {
        val samples = listOf(
            """@[vid="iv_close"] - [vid="rl_ad"][visibleToUser=true]""",
            """[text*="跳过"][text.length<10][width<500 && height<300][visibleToUser=true]""",
            """@[clickable=true][width<130 && height<130] <(1,2) [childCount=2] >2 [text="跳过"][visibleToUser=true]""",
            """@ImageView[visibleToUser=true][width<156 && height<156] < * < [width>1000] +2 ViewGroup >2 [text="广告"]""",
            """@RelativeLayout[clickable=true][visibleToUser=true] - [text="精选必备好应用"] <2 RelativeLayout < LinearLayout < [id="android:id/content"]""",
            """@View[width<102 && height<102][clickable=true] <3 [childCount=5] <6 View <4 * << * <2 * <<2 [id="android:id/content"]""",
            """@[name$="ImageView" || clickable=true][width<90 && height<78] < FrameLayout[childCount=1] <3 FrameLayout[childCount>2]""",
            """@[clickable=true][getChild(0).desc="关闭"] - [text*="记忆容量"] <(1,2) View[childCount>2]""",
            """@(text="合并账户" || text="合并帐户")""",
            """[getChild(0).text="查找资料"] <2 @View[clickable=true] <n [childCount=3] <<(12-n) View[childCount>=3]""",
        )
        for (s in samples) {
            val sel = GkdSelectorParser.parseOrNull(s)
            assertNotNull("解析失败：$s", sel)
            assertTrue("链不应为空：$s", sel!!.chain.isNotEmpty())
        }
    }

    @Test
    fun `非法选择器要报错而不是静默通过`() {
        assertNull(GkdSelectorParser.parseOrNull(""))
        assertNull(GkdSelectorParser.parseOrNull("   "))
        assertNull(GkdSelectorParser.parseOrNull("""[text=]"""))
        assertNull(GkdSelectorParser.parseOrNull("""[text"x"]"""))
    }
}
