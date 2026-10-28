package com.splashskip.core.gkd

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 全量真实订阅的选择器解析率回归。
 *
 * 价值在覆盖率：解析器支持的语法越全，真正能生效的规则就越多。
 * 目前约 99.7%（4115 条里 13 条失败，基本都是 `->` 算符）。
 * 订阅文件不在本机时自动跳过。
 */
class ParseAllStatsTest {

    private val file = File("C:/Users/13023939532/WorkBuddy/2026-10-27-11-00-09/_gkd.json5")

    @Test
    fun stats() {
        if (!file.exists()) {
            println("文件不存在，跳过")
            return
        }
        val root = Json5.parse(file.readText()) as Map<*, *>

        @Suppress("UNCHECKED_CAST")
        val apps = root["apps"] as? List<Map<String, Any?>> ?: emptyList()

        var total = 0
        var ok = 0
        var singleOk = 0
        val failures = mutableMapOf<String, Int>()
        val samples = mutableMapOf<String, String>()

        fun scan(rules: Any?) {
            val list = rules as? List<*> ?: return
            for (r in list) {
                val rule = r as? Map<*, *> ?: continue
                val m = rule["matches"]
                val texts = when (m) {
                    is String -> listOf(m)
                    is List<*> -> m.filterIsInstance<String>()
                    else -> continue
                }
                for (text in texts) {
                    total++
                    val all = GkdSelectorParser.parseAllOrNull(text)
                    if (all != null) {
                        ok++
                        if (all.size == 1) singleOk++
                    } else {
                        val key = runCatching { GkdSelectorParser.parseAll(text) }
                            .exceptionOrNull()?.message?.take(60) ?: "未知"
                        failures.merge(key, 1, Int::plus)
                        samples.putIfAbsent(key, text.take(110))
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
        val global = root["globalGroups"] as? List<Map<String, Any?>> ?: emptyList()
        for (g in global) scan(g["rules"])

        println("选择器总数：$total  parseAll 成功：$ok  失败：${total - ok} (${(total - ok) * 100.0 / total}%)")
        failures.entries.sortedByDescending { it.value }.forEach { (k, v) ->
            println("  [$v] $k")
            println("      例：${samples[k]}")
        }

        assertTrue("订阅里没有选择器，文件可能不完整", total > 1000)
        val rate = ok * 100.0 / total
        assertTrue("选择器解析率跌到 $rate%，解析器有回归", rate >= 99.0)
    }
}
