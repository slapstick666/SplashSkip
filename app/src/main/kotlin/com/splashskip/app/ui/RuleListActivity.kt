package com.splashskip.app.ui

import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.splashskip.rules.RuleRepository
import com.splashskip.rules.RuleRepositoryProvider

/**
 * 规则浏览页。v1 只读 —— 编辑走「导入 JSON」这条路，
 * 内置一个可视化规则编辑器工程量至少翻倍，而真实使用中 90% 的需求是「看看到底有没有生效」。
 */
class RuleListActivity : AppCompatActivity() {

    private lateinit var repo: RuleRepository
    private lateinit var adapter: SimpleAdapter
    private lateinit var header: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = RuleRepositoryProvider.get(this)

        header = body("").apply { setPadding(dp(16), dp(14), dp(16), dp(10)) }

        val rv = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@RuleListActivity)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f,
            )
        }
        adapter = SimpleAdapter(emptyList()) { item -> showDetail(item) }
        rv.adapter = adapter

        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(header)
                addView(rv)
            },
        )
        reload()
    }

    private fun reload() {
        val rules = repo.allRules()
        val subs = repo.listSubscriptions()

        header.text = buildString {
            append("共 ${rules.size} 条规则，来自 ${subs.size} 个订阅\n")
            subs.forEach { append("· ${it.name}  v${it.version}  ${it.rules.size} 条\n") }
            repo.lastErrors.take(3).forEach { append("⚠ $it\n") }
        }.trimEnd()

        adapter.submit(
            rules.map { rule ->
                SimpleAdapter.Item(
                    title = rule.name,
                    subtitle = rule.packages.joinToString(", ") + "　" +
                        if (rule.activities.isEmpty()) "任意界面" else rule.activities.joinToString(" | "),
                    badge = "GKD",
                    payload = rule,
                )
            },
        )
    }

    private fun showDetail(item: SimpleAdapter.Item) {
        val rule = item.payload as? com.splashskip.core.model.AppRule ?: return
        val text = buildString {
            append("id：${rule.id}\n")
            append("包名：${rule.packages.joinToString()}\n")
            append("界面：${if (rule.activities.isEmpty()) "任意" else rule.activities.joinToString()}\n")
            append("排除：${if (rule.excludeActivities.isEmpty()) "无" else rule.excludeActivities.joinToString()}\n")
            append("优先级：${rule.priority}\n")
            append("延迟：${rule.matchDelayMs}ms　超时：${rule.timeoutMs}ms\n")
            append("冷却：${rule.actionCooldownMs}ms　最多尝试：${rule.maxTriggers}\n")
            append("\n动作链：\n")
            rule.steps.forEachIndexed { i, step ->
                append("${i + 1}. ${step.action}　延迟 ${step.postDelayMs}ms\n")
            }
            if (rule.note.isNotEmpty()) append("\n备注：${rule.note}")
        }

        AlertDialog.Builder(this)
            .setTitle(rule.name)
            .setMessage(text)
            .setPositiveButton("关闭", null)
            .show()
    }
}
