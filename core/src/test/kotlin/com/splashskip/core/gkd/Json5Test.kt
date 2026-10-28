package com.splashskip.core.gkd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class Json5Test {

    @Test
    fun `解析普通对象`() {
        val v = Json5.parse("""{"a": 1, "b": "x"}""") as Map<*, *>
        assertEquals(1L, v["a"])
        assertEquals("x", v["b"])
    }

    @Test
    fun `解析无引号 key`() {
        val v = Json5.parse("{name: 'gKD', version: 619}") as Map<*, *>
        assertEquals("gKD", v["name"])
        assertEquals(619L, v["version"])
    }

    @Test
    fun `解析单引号字符串`() {
        val v = Json5.parse("['a', \"b\"]") as List<*>
        assertEquals(listOf("a", "b"), v)
    }

    @Test
    fun `允许尾随逗号`() {
        val v = Json5.parse("""{"a": [1, 2,], "b": 3,}""") as Map<*, *>
        assertEquals(listOf(1L, 2L), v["a"])
        assertEquals(3L, v["b"])
    }

    @Test
    fun `忽略行注释与块注释`() {
        val text = """
            {
              // 这是行注释
              "a": 1, /* 这是块
              注释 */
              "b": 2,
            }
        """.trimIndent()
        val v = Json5.parse(text) as Map<*, *>
        assertEquals(1L, v["a"])
        assertEquals(2L, v["b"])
    }

    @Test
    fun `解析十六进制与符号数字`() {
        val v = Json5.parse("{a: 0xFF, b: -12, c: 1.5, d: +7}") as Map<*, *>
        assertEquals(255L, v["a"])
        assertEquals(-12L, v["b"])
        assertEquals(1.5, v["c"] as Double, 1e-9)
        assertEquals(7L, v["d"])
    }

    @Test
    fun `解析转义字符`() {
        val v = Json5.parse("""{"a": "x\ny\t\"z\"\\", "b": "\u4e2d"}""") as Map<*, *>
        assertEquals("x\ny\t\"z\"\\", v["a"])
        assertEquals("中", v["b"])
    }

    @Test
    fun `解析嵌套结构`() {
        val v = Json5.parse("""{apps: [{id: 'com.x', groups: [{rules: [{matches: '[text="跳过"]'}]}]}]}""")
        val app = (v as Map<*, *>)["apps"] as List<*>
        val groups = (app[0] as Map<*, *>)["groups"] as List<*>
        val rules = (groups[0] as Map<*, *>)["rules"] as List<*>
        assertEquals("[text=\"跳过\"]", (rules[0] as Map<*, *>)["matches"])
    }

    @Test
    fun `解析 null 与布尔`() {
        val v = Json5.parse("{a: null, b: true, c: false}") as Map<*, *>
        assertNull(v["a"])
        assertEquals(true, v["b"])
        assertEquals(false, v["c"])
    }

    @Test
    fun `中文 key 可用`() {
        val v = Json5.parse("{开屏广告: 1}") as Map<*, *>
        assertEquals(1L, v["开屏广告"])
    }

    @Test
    fun `语法错误要报行号`() {
        try {
            Json5.parse("{\n  \"a\": 1,\n  \"b\" 2\n}")
            fail("应当抛出 Json5Exception")
        } catch (e: Json5Exception) {
            assertTrue("实际信息：${e.message}", e.message!!.contains("第 3 行"))
        }
    }

    @Test
    fun `末尾多余内容要报错`() {
        try {
            Json5.parse("{} {}")
            fail("应当抛出 Json5Exception")
        } catch (e: Json5Exception) {
            assertTrue("实际信息：${e.message}", e.message!!.contains("多余字符"))
        }
    }
}
