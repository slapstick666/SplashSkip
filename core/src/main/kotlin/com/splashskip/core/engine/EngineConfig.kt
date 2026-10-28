package com.splashskip.core.engine

import com.splashskip.core.match.RuleIndex
import com.splashskip.core.model.Action
import com.splashskip.core.model.AppRule
import com.splashskip.core.model.NodeSnapshot
import com.splashskip.core.model.Step

/** 单个 App 的接管模式。 */
enum class AppMode {
    /** 全自动：命中即点。 */
    AUTO,

    /** 学习模式（半自动）：首次遇到时不点，先问用户「以后这个 App 自动跳吗」。 */
    LEARN,

    /** 完全不干预。 */
    DISABLED,
}

/** 引擎执行一次匹配后的决策，交给外层的 RetryScheduler 去落实。 */
sealed interface Decision {
    /** 当前界面无需处理。 */
    data object Idle : Decision

    /** 已执行点击。 */
    data object Skipped : Decision

    /** 需要 afterMs 后再试一次。 */
    data class Retry(val afterMs: Long) : Decision

    /** 本轮放弃，带原因（写日志用）。 */
    data class Exhausted(val reason: String) : Decision

    /** 学习模式需要用户确认。 */
    data class NeedsConfirm(val rule: AppRule) : Decision
}

/** 一条命中记录，落盘后可在 App 内查看，是排查「为什么没跳过」的唯一依据。 */
data class HitRecord(
    val at: Long,
    val pkg: String,
    val activity: String?,
    val ruleId: String,
    val ruleName: String,
    val action: Action?,
    val matchedText: String,
    val success: Boolean,
    val note: String = "",
)

fun interface HitLogger {
    fun log(record: HitRecord)
}

/**
 * 引擎与宿主（无障碍服务 / UI）之间的契约。
 * 把「取规则、取配置、报事件、执行动作」抽成接口，好处是 :core 不依赖 Context，
 * 单元测试里可以塞一个假 host 直接跑完整决策链。
 */
interface EngineHost {
    fun currentRules(): RuleIndex
    fun modeOf(pkg: String): AppMode
    fun isConfirmed(ruleId: String): Boolean
    fun now(): Long
    fun onHit(record: HitRecord)
    fun onConfirmNeeded(rule: AppRule)

    /**
     * 执行一步动作。实现方负责把 postDelayMs、CLICK_NODE→CLICK_CENTER 降级
     * 这些细节处理掉，引擎只关心成不成功。
     */
    fun perform(node: NodeSnapshot, step: Step): Boolean
}

/** 引擎调参。默认值是按几百次真机验证手感的保守值。 */
data class EngineConfig(
    /** 重试间隔（毫秒），累加约 2.0s，配合默认 timeout=3000ms。 */
    val retryDelaysMs: List<Long> = listOf(60L, 120L, 200L, 320L, 500L, 800L),
    /** 两次尝试之间的最小间隔，防止 contentChanged 风暴瞬间烧完尝试次数。 */
    val minAttemptIntervalMs: Long = 80L,
    /** 单次遍历的节点数上限，防卡顿。 */
    val maxNodes: Int = 1200,
    /** 遍历深度上限。 */
    val maxDepth: Int = 26,
)
