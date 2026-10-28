package com.splashskip.core.gkd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 用真实 GKD 订阅文件做全量解析回归。
 *
 * 这个测试的价值在于「覆盖率」：解析器支持的选择器子集越全，
 * 能真正生效的规则就越多。文件不在仓库里时自动跳过，避免 CI 失败。
 *
 * 想跑这个回归，把订阅文件放到下面这个路径：
 *   https://gcore.jsdelivr.net/gh/Lin-arm/GKD_subscription@main/dist/gkd.json5
 * 文件较大（约 1.3MB），不入版本库。
 */
class Json5RealFileTest {

    @Test
    fun `解析真实 GKD 订阅文件`() {
        val f = File("C:/Users/13023939532/WorkBuddy/2026-10-27-11-00-09/_gkd.json5")
        if (!f.exists()) return
        val text = f.readText()
        val root = Json5.parse(text) as Map<*, *>

        assertTrue("顶层字段缺失", root.containsKey("apps"))
        @Suppress("UNCHECKED_CAST")
        val apps = root["apps"] as List<Map<String, Any?>>
        // 订阅每月都在涨，别把具体数量写死 —— 这里只验证「解析出来的规模像一份完整订阅」
        assertTrue("apps 数量异常：${apps.size}", apps.size > 500)

        @Suppress("UNCHECKED_CAST")
        val app = apps.firstOrNull { it["id"] == "com.cto51.student" } ?: return
        @Suppress("UNCHECKED_CAST")
        val groups = app["groups"] as? List<Map<String, Any?>> ?: return
        assertTrue("应有分组", groups.isNotEmpty())
    }
}
