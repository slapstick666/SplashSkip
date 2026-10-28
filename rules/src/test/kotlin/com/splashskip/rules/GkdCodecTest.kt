package com.splashskip.rules

import com.splashskip.core.model.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class GkdCodecTest {

    /**
     * 真实 GKD 订阅文件。约 1.3MB，不入版本库，缺失时测试自动跳过。
     * 下载地址：
     * https://gcore.jsdelivr.net/gh/Lin-arm/GKD_subscription@main/dist/gkd.json5
     */
    private val realFile = File("C:/Users/13023939532/WorkBuddy/2026-10-27-11-00-09/_gkd.json5")

    // ---------------- 格式识别 ----------------

    @Test
    fun `识别 GKD 格式`() {
        assertTrue(GkdCodec.looksLikeGkd("""{id: 1, name: 'x', apps: []}"""))
        assertTrue(GkdCodec.looksLikeGkd("""{id: 1, globalGroups: []}"""))
    }

    @Test
    fun `非 GKD 格式一律识别为否`() {
        // 1.2.0 起自研的扁平 JSON 规则格式已删，非 GKD 内容都应该被拒绝
        assertFalse(GkdCodec.looksLikeGkd("""{"id":"x","version":1,"rules":[]}"""))
        assertFalse(GkdCodec.looksLikeGkd("[1,2,3]"))
        assertFalse(GkdCodec.looksLikeGkd("\"plain text\""))
    }

    @Test
    fun `非法内容不算 GKD`() {
        assertFalse(GkdCodec.looksLikeGkd("这不是 JSON"))
        assertFalse(GkdCodec.looksLikeGkd(""))
    }

    // ---------------- 转换 ----------------

    @Test
    fun `转换单条规则`() {
        val text = """
            {
              id: 1,
              name: '测试订阅',
              version: 7,
              apps: [
                {
                  id: 'com.demo.app',
                  name: '演示',
                  groups: [
                    {
                      key: 1,
                      name: '开屏广告',
                      matchTime: 8000,
                      rules: [
                        {
                          key: 0,
                          activityIds: '.SplashActivity',
                          matches: '@[text="跳过"]',
                          action: 'click',
                        }
                      ]
                    }
                  ]
                }
              ]
            }
        """.trimIndent()

        val result = GkdCodec.parseSubscription(text)
        val sub = result.subscription
        assertNotNull("解析失败：${result.fatal}", sub)
        assertEquals(1, sub!!.rules.size)

        val rule = sub.rules.first()
        assertEquals(listOf("com.demo.app"), rule.packages)
        assertEquals(listOf(".SplashActivity"), rule.activities)
        assertEquals(8000L, rule.timeoutMs)
        assertEquals(1, rule.steps.size)
        assertNotNull("GKD 选择器应已解析", rule.steps.first().gkd)
        assertEquals(Action.CLICK_NODE, rule.steps.first().action)
    }

    @Test
    fun `全量导入所有分类`() {
        val text = """
            {
              id: 2,
              name: '测试',
              version: 1,
              apps: [
                {
                  id: 'com.demo.app',
                  groups: [
                    { key: 1, name: '开屏广告', rules: [{ key: 0, matches: '@[text="跳过"]' }] },
                    { key: 2, name: '更新提示', rules: [{ key: 0, matches: '@[text="以后再说"]' }] },
                    { key: 3, name: '青少年模式', rules: [{ key: 0, matches: '@[text="我知道了"]' }] }
                  ]
                }
              ]
            }
        """.trimIndent()

        val sub = GkdCodec.parseSubscription(text).subscription
        assertNotNull(sub)
        assertEquals("三个分类都应导入", 3, sub!!.rules.size)
        assertEquals("开屏分类优先级最高", 1, sub.rules.count { it.priority > 0 })
        assertEquals(3, sub.sourceRuleCount)
    }

    @Test
    fun `excludeMatches 变成 step 的排除条件`() {
        val text = """
            {
              id: 4, name: 't', version: 1,
              apps: [{ id: 'com.demo.app', groups: [
                { key: 1, name: '开屏广告', rules: [
                  { key: 0, matches: '@[text="跳过"]', excludeMatches: '[text="青少年模式"]' }
                ] }
              ] }]
            }
        """.trimIndent()

        val result = GkdCodec.parseSubscription(text)
        val sub = result.subscription
        assertNotNull("含 excludeMatches 的规则应保留", sub)
        assertEquals(1, sub!!.rules.size)
        assertEquals(1, sub.rules.first().steps.first().excludes.size)
    }

    @Test
    fun `排除条件解析失败时整条规则跳过`() {
        val text = """
            {
              id: 6, name: 't', version: 1,
              apps: [{ id: 'com.demo.app', groups: [
                { key: 1, name: '开屏广告', rules: [
                  { key: 0, matches: '@[text="跳过"]', excludeMatches: '[text="a"] -> [text="b"]' }
                ] }
              ] }]
            }
        """.trimIndent()

        val result = GkdCodec.parseSubscription(text)
        assertTrue("应产生告警", result.warnings.any { it.contains("排除条件") })
        assertTrue("该规则应被跳过", result.subscription == null || result.subscription!!.rules.isEmpty())
    }

    @Test
    fun `顶层逻辑或展开成多条 step`() {
        val text = """
            {
              id: 7, name: 't', version: 1,
              apps: [{ id: 'com.demo.app', groups: [
                { key: 1, name: '开屏广告', rules: [
                  { key: 0, matches: '([vid="btn_skip"]) || ([text*="跳过"])' }
                ] }
              ] }]
            }
        """.trimIndent()

        val sub = GkdCodec.parseSubscription(text).subscription
        assertNotNull(sub)
        assertEquals("两段 OR 应各成一步", 2, sub!!.rules.first().steps.size)
    }

    @Test
    fun `全局规则按排除名单生效`() {
        val text = """
            {
              id: 3,
              name: '测试',
              version: 1,
              globalGroups: [
                {
                  key: 1,
                  name: '开屏广告-全局',
                  rules: [{ key: 0, matches: '@[text="跳过"]' }],
                  apps: [{ id: 'com.excluded.app', enable: false }]
                }
              ]
            }
        """.trimIndent()

        val sub = GkdCodec.parseSubscription(text).subscription
        assertNotNull(sub)
        assertEquals(1, sub!!.rules.size)
        assertEquals(listOf("*"), sub.rules.first().packages)
    }

    @Test
    fun `按订阅声明的分类给规则打标`() {
        val text = """
            {
              id: 8, name: 't', version: 1,
              categories: [
                { key: 0, name: '开屏广告', enable: true },
                { key: 9, name: '功能类', enable: false }
              ],
              apps: [{ id: 'com.demo.app', groups: [
                { key: 1, name: '开屏广告', rules: [{ key: 0, matches: '@[text="跳过"]' }] },
                { key: 2, name: '功能类-自动签到', rules: [{ key: 0, matches: '@[text="签到"]' }] },
                { key: 3, name: '局部广告', rules: [{ key: 0, matches: '@[text="关闭"]' }] }
              ] }]
            }
        """.trimIndent()

        val sub = GkdCodec.parseSubscription(text).subscription
        assertNotNull(sub)
        assertEquals(listOf("开屏广告", "功能类", "其他"), sub!!.rules.map { it.category })
        assertEquals("开屏广告", sub.categories.first().name)
        assertTrue("只有开屏广告默认启用", sub.categories.first().enabledByDefault)
        assertFalse(sub.categories[1].enabledByDefault)
        // 开屏分类仍然享受优先级加成
        assertEquals(1, sub.rules.count { it.priority > 0 })
    }

    @Test
    fun `没有 categories 的老订阅分类为空`() {
        val text = """
            {
              id: 9, name: 't', version: 1,
              apps: [{ id: 'com.demo.app', groups: [
                { key: 1, name: '开屏广告', rules: [{ key: 0, matches: '@[text="跳过"]' }] }
              ] }]
            }
        """.trimIndent()

        val sub = GkdCodec.parseSubscription(text).subscription
        assertNotNull(sub)
        assertEquals("", sub!!.rules.first().category)
    }

    @Test
    fun `解析失败的选择器只跳过单条规则`() {
        val text = """
            {
              id: 5, name: 't', version: 1,
              apps: [{ id: 'com.demo.app', groups: [
                { key: 1, name: '开屏广告', rules: [
                  { key: 0, matches: '[text="好的"]' },
                  { key: 1, matches: '这段不是选择器 ***' }
                ] }
              ] }]
            }
        """.trimIndent()

        val result = GkdCodec.parseSubscription(text)
        assertNotNull(result.subscription)
        assertEquals("只应保留 1 条", 1, result.subscription!!.rules.size)
    }

    // ---------------- 真实文件 ----------------

    @Test
    fun `解析真实 GKD 订阅`() {
        if (!realFile.exists()) return
        val result = GkdCodec.parseSubscription(realFile.readText())
        val sub = result.subscription
        assertNotNull("解析失败：${result.fatal}", sub)

        val rules = sub!!.rules
        assertTrue("应导入规则", rules.isNotEmpty())

        val packages = rules.map { it.packages.first() }.filter { it != "*" }.distinct()
        println("真实订阅导入规则 ${rules.size} 条，覆盖 ${packages.size} 个 App")

        // 每条规则都必须带解析好的 GKD 选择器，否则匹配器无从下手
        assertTrue("有规则没带上 GKD 选择器", rules.all { r -> r.steps.isNotEmpty() })

        // 包名形态校验。订阅里有一个 id 为 "android" 的伪应用（Android 系统弹窗），
        // 它确实会出现在事件里，所以只拦空串和以点开头的脏数据
        assertTrue(
            "包名形态异常：${packages.filter { it.isBlank() || it.startsWith('.') }.take(3)}",
            packages.all { it.isNotBlank() && !it.startsWith('.') },
        )

        // 规则 id 必须全局唯一 —— 仓库用 id 作 Map 的键去重，
        // 撞 id 的规则会被静默覆盖，表现为「规则莫名少了一条」
        val ids = rules.map { it.id }
        val duplicates = ids.groupingBy { it }.eachCount().filterValues { it > 1 }
        assertTrue("规则 id 重复：${duplicates.keys.take(3)}", duplicates.isEmpty())
    }
}
