package com.splashskip.app.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.google.android.material.switchmaterial.SwitchMaterial
import com.splashskip.a11y.AppConfigStore
import com.splashskip.a11y.SplashSkipService
import com.splashskip.app.util.A11yUtils
import com.splashskip.app.util.UpdateScheduler
import com.splashskip.core.engine.AppMode
import com.splashskip.rules.RuleRepository
import com.splashskip.rules.RuleRepositoryProvider

/**
 * 首页：只放「现在是什么状态」和「最常用的两个动作」。
 *
 * 设计上刻意克制 —— 首页不该堆功能入口，那些交给下面三个 tab。
 * 打开 App 时用户最想知道的是「服务开着没」「规则几条」，一眼看完就走。
 */
class HomeFragment : Fragment() {

    private lateinit var repo: RuleRepository
    private lateinit var config: AppConfigStore

    private var statusTitle: TextView? = null
    private var statusDetail: TextView? = null
    private var enableBtn: Button? = null
    private var modeBtn: Button? = null
    private var switchView: SwitchMaterial? = null

    /** 已经挂上「解析完成」回调，避免每次 refresh 重复注册。 */
    private var waitingRules = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        repo = RuleRepositoryProvider.get(requireContext())
        config = AppConfigStore.get(requireContext())
        val ctx = requireContext()

        return ctx.pageScroll { col ->
            col.addView(ctx.banner(
                "只在本机用无障碍能力点击「跳过」按钮，不修改任何应用、不拦截网络。仅供个人使用。",
            ))
            col.addView(ctx.gap(8))

            // ---- 运行状态 ----
            val statusCard = ctx.card()
            statusTitle = ctx.title("", 17f)
            statusDetail = ctx.body("")
            enableBtn = ctx.action("去开启无障碍服务") { A11yUtils.openAccessibilitySettings(ctx) }
            statusCard.addView(statusTitle)
            statusCard.addView(statusDetail)
            statusCard.addView(ctx.gap(10))
            statusCard.addView(enableBtn)
            col.addView(statusCard)

            // ---- 总开关 ----
            val switchCard = ctx.card()
            switchCard.addView(ctx.title("总开关"))
            switchCard.addView(ctx.gap(4))
            val switchRow = ctx.row()
            switchRow.addView(
                ctx.body("启用自动跳过").apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                },
            )
            switchView = SwitchMaterial(ctx).apply {
                isChecked = config.globalEnabled
                setOnCheckedChangeListener { _, checked ->
                    config.globalEnabled = checked
                    refresh()
                }
            }
            switchRow.addView(switchView)
            switchCard.addView(switchRow)

            modeBtn = ctx.action("") {
                config.globalMode =
                    if (config.globalMode == AppMode.AUTO) AppMode.LEARN else AppMode.AUTO
                refresh()
            }
            switchCard.addView(ctx.gap(8))
            switchCard.addView(
                ctx.body(
                    "全自动：命中即点。学习模式：每个 App 第一次遇到时先问你一次，确认后转自动。",
                    12f,
                ),
            )
            switchCard.addView(modeBtn)
            col.addView(switchCard)

            // ---- 常用动作 ----
            val actionCard = ctx.card()
            actionCard.addView(ctx.title("常用"))
            actionCard.addView(ctx.gap(2))
            actionCard.addRow("抓取界面快照", "5 秒内切到目标 App 的开屏页") { captureSnapshot() }
            actionCard.addDivider()
            actionCard.addRow("命中日志", "查看每次匹配成功或被拦下的原因") {
                startActivity(Intent(ctx, LogActivity::class.java))
            }
            actionCard.addDivider()
            actionCard.addRow("立即检查规则更新", "订阅每 12 小时自动检查一次") {
                UpdateScheduler.runNow(ctx)
                toast("已加入更新队列")
            }
            col.addView(actionCard)
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    /**
     * hide/show 切换 **不会触发 onResume**，只会触发 onHiddenChanged。
     * 不加这个的话，从设置页切回首页时显示的还是旧数据（比如刚加了订阅，
     * 规则数却停在 10 条），看起来像功能坏了。
     */
    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) refresh()
    }

    override fun onDestroyView() {
        // Fragment 的 view 会被销毁，但 Fragment 实例还在。
        // 不置空的话下次 onCreateView 前 onResume 会往已销毁的 View 上写，直接崩。
        statusTitle = null
        statusDetail = null
        enableBtn = null
        modeBtn = null
        switchView = null
        super.onDestroyView()
    }

    private fun refresh() {
        val ctx = context ?: return
        val enabled = A11yUtils.isServiceEnabled(ctx)
        val ruleCount = repo.currentIndex().size
        val ready = repo.isReady

        statusTitle?.text = if (enabled) "无障碍服务：已开启" else "无障碍服务：未开启"
        enableBtn?.text = if (enabled) "查看无障碍设置" else "去开启无障碍服务"

        statusDetail?.text = buildString {
            if (!ready) {
                // 订阅解析在后台线程做（1.3MB JSON5 + 四千条选择器），
                // 这里不能直接显示 0 条，否则看起来像规则丢了
                append("正在解析订阅规则…")
            } else {
                val total = repo.allRules().size
                append("已启用 ").append(ruleCount).append(" 条规则")
                if (total > ruleCount) {
                    append("（订阅共解析 ").append(total).append(" 条，其余分类未勾选）")
                }
                if (ruleCount == 0) {
                    append("\n没有规则可用 —— 去「订阅」页添加订阅源，并至少勾选一个分类")
                }
            }
            if (!config.globalEnabled) append("\n总开关已关闭，当前不会执行任何点击")
            // 首页只报数量，详情在订阅页 —— 一条选择器告警能有上百字，堆在这里没法看
            repo.lastErrors.size.takeIf { it > 0 }?.let {
                append("\n⚠ 有 ").append(it).append(" 条解析告警，去订阅页看详情")
            }
        }

        // 解析完自动重绘一次，用户不用手动切 tab
        if (!ready && !waitingRules) {
            waitingRules = true
            repo.onReady {
                waitingRules = false
                activity?.runOnUiThread { if (isAdded) refresh() }
            }
        }

        modeBtn?.text = when (config.globalMode) {
            AppMode.LEARN -> "当前：学习模式（半自动）"
            else -> "当前：全自动"
        }
        switchView?.isChecked = config.globalEnabled
    }

    private fun captureSnapshot() {
        val ctx = context ?: return
        val service = SplashSkipService.INSTANCE
        if (service == null) {
            toast("请先开启无障碍服务，否则读不到界面节点")
            return
        }
        toast("5 秒后抓取，现在请切到目标 App")
        service.scheduleSnapshot(5000L) { doc, err ->
            if (doc != null) {
                val suffix = if (doc.truncated) "（树太大，已截断）" else ""
                toast("已抓取 ${doc.nodeCount} 个节点$suffix，可在「设置 → 界面快照」查看")
            } else {
                toast(err ?: "抓取失败")
            }
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
    }
}
