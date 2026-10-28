package com.splashskip.core.gkd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 用真实 GKD 订阅文件做全量解析回归。
 *
 * 这个测试的价值在于「覆盖率」：解析器支持的选择器子集越全，
 * 能真正生效的规则就越多。文件不在仓库里时自动跳过。
 */
class GkdSelectorRealFileTest {

    private val file = File("C:/Users/13023939532/WorkBuddy/2026-10-27-11-00-09/_gkd.json5")

    @Test
    fun `真实订阅里的选择器解析率`() {
        if (!file.exists()) return
        val root = Json5.parse(file.readText()) as Map<*, *>

        @Suppress("UNCHECKED_CAST")
        val apps = root["apps"] as List<Map<String, Any?>>

        var total = 0
        var ok = 0
        val failures = mutableMapOf<String, Int>()
        val samples = mutableMapOf<String, String>()

        fun scan(rules: Any?) {
            val list = when (rules) {
                is List<*> -> rules
                null -> return
                else -> return
            }
            for (r in list) {
                val rule = r as? Map<*, *> ?: continue
                val m = rule["matches"]
                val list2 = when (m) {
                    is String -> listOf(m)
                    is List<*> -> m.filterIsInstance<String>()
                    else -> continue
                }
                for (text in list2) {
                    total++
                    val sel = GkdSelectorParser.parseOrNull(text)
                    if (sel != null) {
                        ok++
                    } else {
                        val key = GkdSelectorParser.runCatching { GkdSelectorParser.parse(text) }
                            .exceptionOrNull()?.message?.take(60) ?: "未知"
                        failures.merge(key, 1, Int::plus)
                        samples.putIfAbsent(key, text.take(100))
                    }
                }
            }
        }

        for (app in apps) {
            @Suppress("UNCHECKED_CAST")
            val groups = app["groups"] as? List<Map<String, Any?>> ?: continue
            for (g in groups) scan(g["rules"])
        }
        @Suppress("UNCHECKED_CAST")
        val globalGroups = root["globalGroups"] as? List<Map<String, Any?>> ?: emptyList()
        for (g in globalGroups) scan(g["rules"])

        println("真实订阅选择器总数：$total")
        println("解析成功：$ok")
        println("解析失败：${total - ok}  (${(total - ok) * 100.0 / total}%)")
        failures.entries.sortedByDescending { it.value }.take(12).forEach { (msg, cnt) ->
            println("  [$cnt 次] $msg")
            println("        例：${samples[msg]}")
        }

        // 低于 90% 说明解析器漏了常见的语法形态，需要补
        assertTrue(
            "选择器解析率只有 ${ok * 100.0 / total}%，需要补语法支持",
            ok * 100.0 / total >= 90.0,
        )
    }
}
