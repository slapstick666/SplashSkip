package com.splashskip.core.engine

import com.splashskip.core.gkd.GkdSelectorParser
import com.splashskip.core.match.RuleIndex
import com.splashskip.core.model.Action
import com.splashskip.core.model.AppRule
import com.splashskip.core.model.IntRect
import com.splashskip.core.model.NodeSnapshot
import com.splashskip.core.model.ScreenSize
import com.splashskip.core.model.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 引擎决策链测试。规则一律用 GKD 选择器构造（项目唯一的规则来源）。
 */
class SkipEngineTest {

    private val screen = ScreenSize(1080, 2340, density = 2.75f)

    private fun textNode(
        text: String,
        left: Int = 900,
        top: Int = 60,
        right: Int = 1050,
        bottom: Int = 140,
        clickable: Boolean = true,
        enabled: Boolean = true,
        cls: String = "android.widget.TextView",
    ): NodeSnapshot {
        val n = NodeSnapshot(
            text = text,
            desc = "",
            viewId = "skip",
            viewIdFull = "com.demo.app:id/skip",
            className = cls,
            bounds = IntRect(left, top, right, bottom),
            clickable = clickable,
            enabled = enabled,
            visible = true,
            depth = 6,
            clickableAncestorDepth = -1,
            handle = null,
        )
        n.childCount = 0
        return n
    }

    private fun gkdStep(selector: String, action: Action = Action.CLICK_NODE) = Step(
        gkd = GkdSelectorParser.parse(selector),
        action = action,
    )

    private fun splashRule(vararg steps: Step) = AppRule(
        id = "test.splash",
        name = "测试开屏",
        packages = listOf("com.demo.app"),
        activities = listOf(".*Splash.*"),
        timeoutMs = 3000,
        actionCooldownMs = 0,
        maxTriggers = 3,
        steps = steps.toList(),
    )

    private class FakeHost(
        private val rules: List<AppRule>,
        private val mode: AppMode = AppMode.AUTO,
    ) : EngineHost {
        var time = 0L
        var performCount = 0
        val hits = mutableListOf<HitRecord>()

        override fun currentRules() = RuleIndex.of(rules)
        override fun modeOf(pkg: String) = mode
        override fun isConfirmed(ruleId: String) = true
        override fun now() = time
        override fun onHit(record: HitRecord) {
            hits += record
        }
        override fun onConfirmNeeded(rule: AppRule) = Unit
        override fun perform(node: NodeSnapshot, step: Step): Boolean {
            performCount++
            return true
        }
    }

    @Test
    fun `命中跳过按钮并点击`() {
        val host = FakeHost(listOf(splashRule(gkdStep("""@TextView[text*="跳过"]"""))))
        val engine = SkipEngine(host)
        val decision = engine.onWindow("com.demo.app", "com.demo.app.SplashActivity", screen) {
            listOf(textNode("跳过 3"))
        }
        assertEquals(Decision.Skipped, decision)
        assertEquals(1, host.performCount)
        assertTrue(host.hits.last().success)
    }

    @Test
    fun `GKD 类名匹配短名与全名`() {
        val host = FakeHost(listOf(splashRule(gkdStep("""TextView[text*="跳过"]"""))))
        val engine = SkipEngine(host)
        val decision = engine.onWindow("com.demo.app", "com.demo.app.SplashActivity", screen) {
            listOf(textNode("跳过"))
        }
        assertEquals(Decision.Skipped, decision)
    }

    @Test
    fun `关系选择器在引擎里生效`() {
        // 目标是「广告位容器的子节点」—— 需要节点树带父子关系
        val container = textNode("广告位", cls = "android.widget.LinearLayout")
        val skip = textNode("跳过", clickable = true)
        container.childIndices = listOf(1)
        container.childCount = 1
        skip.parentIndex = 0
        skip.indexInParent = 0

        val host = FakeHost(
            listOf(splashRule(gkdStep("""LinearLayout[text="广告位"] > @[text*="跳过"]"""))),
        )
        val engine = SkipEngine(host)
        val decision = engine.onWindow("com.demo.app", "com.demo.app.SplashActivity", screen) {
            listOf(container, skip)
        }
        assertEquals(Decision.Skipped, decision)
    }

    @Test
    fun `多 step 按顺序 第一个不命中用第二个`() {
        val host = FakeHost(
            listOf(
                splashRule(
                    gkdStep("""[vid="not_exist_id"]"""),
                    gkdStep("""[text*="跳过"]"""),
                ),
            ),
        )
        val engine = SkipEngine(host)
        val decision = engine.onWindow("com.demo.app", "com.demo.app.SplashActivity", screen) {
            listOf(textNode("跳过"))
        }
        assertEquals(Decision.Skipped, decision)
    }

    @Test
    fun `空节点树先重试 节点出现后命中`() {
        val host = FakeHost(listOf(splashRule(gkdStep("""[text*="跳过"]"""))))
        val engine = SkipEngine(host)

        val first = engine.onWindow("com.demo.app", "com.demo.app.SplashActivity", screen) {
            emptyList()
        }
        assertTrue(first is Decision.Retry)

        host.time = 100L
        val second = engine.onWindow("com.demo.app", "com.demo.app.SplashActivity", screen) {
            listOf(textNode("跳过 3"))
        }
        assertEquals(Decision.Skipped, second)
    }

    @Test
    fun `选择器不匹配时重试直至耗尽`() {
        val host = FakeHost(listOf(splashRule(gkdStep("""[text="不存在"]"""))))
        val engine = SkipEngine(host)
        // 模拟 RetryScheduler 的多轮重试：每轮推进时钟越过 minAttemptIntervalMs
        var decision: Decision = Decision.Idle
        repeat(12) {
            decision = engine.onWindow("com.demo.app", "com.demo.app.SplashActivity", screen) {
                listOf(textNode("跳过"))
            }
            if (decision is Decision.Exhausted) return@repeat
            host.time += 200L
        }
        assertTrue("重试序列应最终耗尽：$decision", decision is Decision.Exhausted)
        assertEquals(0, host.performCount)
    }

    @Test
    fun `Activity 不匹配时不动手`() {
        val host = FakeHost(listOf(splashRule(gkdStep("""[text*="跳过"]"""))))
        val engine = SkipEngine(host)
        val decision = engine.onWindow("com.demo.app", "com.demo.app.MainActivity", screen) {
            listOf(textNode("跳过"))
        }
        assertEquals(Decision.Idle, decision)
        assertEquals(0, host.performCount)
    }

    @Test
    fun `包名不匹配时不动手`() {
        val host = FakeHost(listOf(splashRule(gkdStep("""[text*="跳过"]"""))))
        val engine = SkipEngine(host)
        val decision = engine.onWindow("com.other.app", "com.other.app.SplashActivity", screen) {
            listOf(textNode("跳过"))
        }
        assertEquals(Decision.Idle, decision)
    }

    @Test
    fun `学习模式先询问不点击`() {
        val host = FakeHost(listOf(splashRule(gkdStep("""[text*="跳过"]"""))), mode = AppMode.LEARN)
        val strictHost = object : EngineHost by host {
            override fun isConfirmed(ruleId: String) = false
        }
        val engine = SkipEngine(strictHost)
        val decision = engine.onWindow("com.demo.app", "com.demo.app.SplashActivity", screen) {
            listOf(textNode("跳过"))
        }
        assertTrue(decision is Decision.NeedsConfirm)
        assertEquals(0, host.performCount)
    }

    @Test
    fun `会话超时后放弃`() {
        val host = FakeHost(listOf(splashRule(gkdStep("""[text*="跳过"]"""))))
        val engine = SkipEngine(host)
        // 第一轮创建会话（startedAt = 0），之后推进时钟越过 timeoutMs = 3000
        val first = engine.onWindow("com.demo.app", "com.demo.app.SplashActivity", screen) {
            emptyList()
        }
        assertTrue(first is Decision.Retry)

        host.time = 4000L
        val second = engine.onWindow("com.demo.app", "com.demo.app.SplashActivity", screen) {
            listOf(textNode("跳过"))
        }
        assertTrue(second is Decision.Exhausted)
        assertEquals(0, host.performCount)
    }
}
