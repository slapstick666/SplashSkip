package com.splashskip.core.engine

import com.splashskip.core.gkd.GkdMatcher
import com.splashskip.core.match.RuleResolver
import com.splashskip.core.model.NodeSnapshot
import com.splashskip.core.model.ScreenSize

/**
 * 跳过引擎 —— 纯决策层，不认识 android 框架，也不管定时器。
 *
 * 对外只有两个入口：
 *   onWindow()          窗口变化 / 内容变化时调用
 *   reset()             用户切走或服务断开时调用
 * 内部状态只有「当前会话」和「各规则上次点击时间」，方便推理和测试。
 */
class SkipEngine(
    private val host: EngineHost,
    private val config: EngineConfig = EngineConfig(),
) {
    private var session: Session? = null
    private val lastClickAt = HashMap<String, Long>()

    /**
     * @param pkg        当前前台包名
     * @param activity   当前 Activity 全名，取不到时传 null
     * @param screen     屏幕尺寸 + 密度
     * @param nodesProvider 惰性取节点快照 —— 只有真的要匹配时才去遍历，
     *                      避免在无规则命中的界面白烧 CPU
     */
    fun onWindow(
        pkg: String,
        activity: String?,
        screen: ScreenSize,
        nodesProvider: () -> List<NodeSnapshot>,
    ): Decision {
        val now = host.now()

        var s = session
        if (s == null || !s.isSameWindow(pkg, activity)) {
            val mode = host.modeOf(pkg)
            if (mode == AppMode.DISABLED) {
                session = null
                return Decision.Idle
            }

            val rule = RuleResolver.resolve(host.currentRules(), pkg, activity)
            if (rule == null) {
                session = null
                return Decision.Idle
            }

            // 冷却：同一规则两次触发之间必须间隔够久
            val last = lastClickAt[rule.id]
            if (last != null && now - last < rule.actionCooldownMs) {
                session = null
                return Decision.Idle
            }

            // 学习模式：第一次遇到这个规则，先问用户
            if (mode == AppMode.LEARN && !host.isConfirmed(rule.id)) {
                session = null
                host.onConfirmNeeded(rule)
                return Decision.NeedsConfirm(rule)
            }

            s = Session(pkg, activity, rule, now)
            session = s
        }

        val active = s ?: return Decision.Idle
        return attempt(active, now, screen, nodesProvider)
    }

    fun reset() {
        session = null
    }

    /** 清理过期的冷却记录，避免 HashMap 无限增长。定期调用即可。 */
    fun pruneCooldown(now: Long = host.now(), keepMs: Long = 10 * 60_000L) {
        lastClickAt.entries.removeAll { now - it.value > keepMs }
    }

    private fun attempt(
        s: Session,
        now: Long,
        screen: ScreenSize,
        nodesProvider: () -> List<NodeSnapshot>,
    ): Decision {
        val elapsed = now - s.startedAt

        // 1. 会话超时
        if (elapsed > s.rule.timeoutMs) {
            session = null
            return Decision.Exhausted("会话超时 ${s.rule.timeoutMs}ms")
        }

        // 2. 还在 matchDelay 静默期
        if (elapsed < s.rule.matchDelayMs) {
            return Decision.Retry(s.rule.matchDelayMs - elapsed)
        }

        // 3. 尝试次数上限
        if (s.attempts >= s.rule.maxTriggers) {
            session = null
            return Decision.Exhausted("已达尝试上限 ${s.rule.maxTriggers} 次")
        }

        // 4. 最小间隔保护（contentChanged 抖动）
        if (s.attempts > 0 && now - s.lastAttemptAt < config.minAttemptIntervalMs) {
            return Decision.Retry(config.minAttemptIntervalMs - (now - s.lastAttemptAt))
        }

        s.attempts++
        s.lastAttemptAt = now

        val nodes = nodesProvider()
        if (nodes.isEmpty()) {
            return nextRetry(s)
        }

        // 5. 按 steps 顺序尝试，第一个成功的生效。
        // 所有 step 都是 GKD 选择器，匹配器已按「可点击优先、DFS 序靠前」排好序，
        // 第一个候选就是最优解。不套额外的启发式护栏 —— GKD 规则精确到具体节点
        //（连父子兄弟关系都写明了），套「右上角 + 短文本」这类规则只会误杀真规则。
        for (step in s.rule.steps) {
            val matched = GkdMatcher.match(step.gkd, nodes)
            val best = matched.firstOrNull()?.target ?: continue

            val ok = host.perform(best, step)
            if (ok) {
                lastClickAt[s.rule.id] = now
                session = null
                host.onHit(
                    HitRecord(
                        at = now,
                        pkg = s.pkg,
                        activity = s.activity,
                        ruleId = s.rule.id,
                        ruleName = s.rule.name,
                        action = step.action,
                        matchedText = best.textOrDesc(),
                        success = true,
                        note = "第 ${s.attempts} 次尝试命中",
                    ),
                )
                return Decision.Skipped
            }
        }

        return nextRetry(s)
    }

    private fun nextRetry(s: Session): Decision {
        val delay = config.retryDelaysMs.getOrNull(s.attempts - 1)
        return if (delay == null) {
            session = null
            Decision.Exhausted("重试序列耗尽（共 ${s.attempts} 次尝试）")
        } else {
            Decision.Retry(delay)
        }
    }
}
