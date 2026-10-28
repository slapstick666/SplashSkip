package com.splashskip.app.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.switchmaterial.SwitchMaterial
import com.splashskip.app.util.UpdateScheduler
import com.splashskip.rules.RuleRepositoryProvider
import com.splashskip.rules.RuleUpdateWorker
import com.splashskip.rules.SubscriptionEntry
import com.splashskip.rules.SubscriptionStore
import com.splashskip.rules.SubscriptionUpdater
import com.splashskip.rules.SyncResult
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 订阅页：管理规则来源。
 *
 * 应用不再内置任何规则，所有规则都来自用户添加的订阅源（或手写导入）。
 * 优先级 USER > REMOTE，页面上按这个顺序展示，用户一眼能看出某条规则被谁覆盖了。
 *
 * 订阅管理的交互取舍：
 *   · **URL 不可编辑**。订阅的身份就是 URL，改了等于换了一个订阅 ——
 *     与其做「改 URL 时重新拉取 + 迁移 id」的复杂逻辑，不如让用户删了重加。
 *   · 名称、版本检查地址、验签开关、启用状态可以随时改。
 */
class SubsFragment : Fragment() {

    private var container: LinearLayout? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        parent: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val ctx = requireContext()
        return ctx.pageScroll { col ->
            container = col
            render(col)
        }
    }

    override fun onResume() {
        super.onResume()
        container?.let { render(it) }
    }

    /** hide/show 不触发 onResume，切回来要手动重绘，否则看到的还是旧列表。 */
    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) container?.let { render(it) }
    }

    override fun onDestroyView() {
        container = null
        super.onDestroyView()
    }

    // ---------------- 渲染 ----------------

    private fun render(col: LinearLayout) {
        val ctx = context ?: return
        col.removeAllViews()

        val repo = RuleRepositoryProvider.get(ctx)
        val store = SubscriptionStore.get(ctx)
        val rules = repo.allRules()

        // ---- 概览 ----
        val overview = ctx.card()
        overview.addView(ctx.title("规则概览"))
        overview.addView(ctx.gap(4))
        overview.addRow(
            "开屏规则",
            "来自 GKD 订阅（已启用源），覆盖 ${rules.map { it.packages.firstOrNull() }.filterNotNull().distinct().size} 个应用",
            "${rules.size} 条",
        )
        if (rules.isEmpty()) {
            overview.addDivider()
            overview.addView(ctx.caption(
                "目前没有任何规则。在下方添加一个 GKD 订阅源（如 " +
                    "gcore.jsdelivr.net/gh/Lin-arm/GKD_subscription@main/dist/gkd.json5），" +
                    "否则不会跳过任何广告。",
            ))
        }
        col.addView(overview)

        // ---- 订阅源 ----
        val subsCard = ctx.card()
        val header = ctx.row()
        header.addView(ctx.title("订阅源").apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        header.addView(ctx.pill("${store.list().size} 个"))
        subsCard.addView(header)
        subsCard.addView(ctx.gap(2))

        val entries = store.list()
        if (entries.isEmpty()) {
            subsCard.addView(ctx.caption("还没有添加远程订阅。点下方按钮填一个 URL 就能加。"))
        } else {
            entries.forEachIndexed { i, e ->
                if (i > 0) subsCard.addDivider()
                val (title, subtitle, badge) = describe(ctx, e)
                subsCard.addRow(title, subtitle, badge) { showActions(e) }
            }
        }

        subsCard.addView(ctx.gap(10))
        subsCard.addView(ctx.action("＋ 添加订阅") { showAddDialog() })
        col.addView(subsCard)

        // ---- 操作 ----
        val actionCard = ctx.card()
        actionCard.addView(ctx.title("操作"))
        actionCard.addView(ctx.gap(4))

        val lastUpdate = ctx.getSharedPreferences(RuleUpdateWorker.PREFS, Context.MODE_PRIVATE)
            .getLong(RuleUpdateWorker.KEY_LAST_UPDATE, 0L)
        actionCard.addView(
            ctx.caption(
                if (lastUpdate == 0L) "上次检查：从未"
                else "上次检查：" + SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(lastUpdate)),
            ),
        )
        actionCard.addView(ctx.gap(8))
        actionCard.addView(ctx.action("立即检查全部更新") {
            UpdateScheduler.runNow(ctx)
            Toast.makeText(ctx, "已加入更新队列", Toast.LENGTH_SHORT).show()
        })
        actionCard.addView(ctx.gap(8))
        actionCard.addView(ctx.action("查看全部规则") {
            startActivity(Intent(ctx, RuleListActivity::class.java))
        })
        col.addView(actionCard)

        // ---- 解析告警 ----
        val errs = repo.lastErrors
        if (errs.isNotEmpty()) {
            val warnCard = ctx.card()
            warnCard.addView(ctx.title("解析告警", 14f))
            warnCard.addView(ctx.gap(4))
            errs.take(8).forEach { warnCard.addView(ctx.caption("· $it", 11f)) }
            col.addView(warnCard)
        }
    }

    private fun describe(ctx: Context, e: SubscriptionEntry): Triple<String, String, String> {
        val title = e.displayName
        val subtitle = buildString {
            if (e.version > 0) append("v").append(e.version).append("　")
            if (e.ruleCount > 0) append(e.ruleCount).append(" 条　")
            if (e.lastFetchAt > 0) {
                append("更新于 ")
                append(SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(e.lastFetchAt)))
            }
        }.trim()
        val badge = when {
            !e.enabled -> "已禁用"
            e.lastError != null -> "失败"
            else -> "启用"
        }
        return Triple(title, subtitle.ifEmpty { e.updateUrl }, badge)
    }

    // ---------------- 操作菜单 ----------------

    private fun showActions(entry: SubscriptionEntry) {
        val ctx = requireContext()
        val items = arrayOf(
            "立即更新",
            if (entry.enabled) "禁用" else "启用",
            "编辑",
            "删除",
        )
        AlertDialog.Builder(ctx)
            .setTitle(entry.displayName)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> syncNow(entry)
                    1 -> {
                        SubscriptionStore.get(ctx).setEnabled(entry.id, !entry.enabled)
                        container?.let { render(it) }
                    }
                    2 -> showEditDialog(entry)
                    3 -> confirmDelete(entry)
                }
            }
            .show()
    }

    private fun syncNow(entry: SubscriptionEntry) {
        val ctx = requireContext()
        toast("正在更新 ${entry.displayName}…")
        viewLifecycleOwner.lifecycleScope.launch {
            val store = SubscriptionStore.get(ctx)
            val updater = SubscriptionUpdater(RuleRepositoryProvider.get(ctx))
            when (val r = updater.sync(entry)) {
                is SyncResult.Updated -> {
                    store.markResult(entry.id, null, r.version, r.ruleCount)
                    toast("已更新到 v${r.version}，${r.ruleCount} 条规则")
                }
                SyncResult.UpToDate -> {
                    store.markResult(entry.id, null)
                    toast("已是最新")
                }
                is SyncResult.Failed -> {
                    store.markResult(entry.id, r.reason)
                    toast("更新失败：${r.reason}")
                }
            }
            container?.let { render(it) }
        }
    }

    private fun confirmDelete(entry: SubscriptionEntry) {
        val ctx = requireContext()
        AlertDialog.Builder(ctx)
            .setTitle("删除订阅")
            .setMessage("将从列表中移除「${entry.displayName}」，并删除本地缓存的那份规则。\n\n其他订阅和内置规则不受影响。")
            .setPositiveButton("删除") { _, _ ->
                SubscriptionStore.get(ctx).remove(entry.id)
                RuleRepositoryProvider.get(ctx).removeRemote(entry.id)
                toast("已删除 ${entry.displayName}")
                container?.let { render(it) }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ---------------- 添加 ----------------

    private fun showAddDialog() {
        val ctx = requireContext()
        val pad = ctx.dp(20)

        val urlInput = EditText(ctx).apply {
            hint = "https://example.com/rules.json"
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine()
        }
        val checkInput = EditText(ctx).apply {
            hint = "可选，返回纯文本版本号"
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine()
        }

        val body = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, ctx.dp(8), pad, 0)
            addView(ctx.caption("订阅 URL"))
            addView(urlInput)
            addView(ctx.gap(12))
            addView(ctx.caption("版本检查 URL"))
            addView(checkInput)
            addView(ctx.gap(12))
            addView(ctx.caption(
                "填 GKD 格式的订阅地址，例如：\n" +
                    "gcore.jsdelivr.net/gh/Lin-arm/GKD_subscription@main/dist/gkd.json5\n\n" +
                    "只会导入其中的「开屏广告」分类规则，其余分类（更新提示、青少年模式等）" +
                    "不动 —— 那些弹窗可能是你想看的。",
            ))
        }

        AlertDialog.Builder(ctx)
            .setTitle("添加 GKD 订阅")
            .setView(ScrollView(ctx).apply { addView(body) })
            .setPositiveButton("添加") { _, _ ->
                doAdd(urlInput.text.toString(), checkInput.text.toString())
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun doAdd(url: String, checkUrl: String) {
        val ctx = requireContext()
        toast("正在拉取订阅…")
        viewLifecycleOwner.lifecycleScope.launch {
            val updater = SubscriptionUpdater(RuleRepositoryProvider.get(ctx))
            val (entry, err) = updater.addByUrl(url, checkUrl.ifBlank { null })
            if (entry != null) {
                SubscriptionStore.get(ctx).upsert(entry)
                toast("已添加「${entry.displayName}」，${entry.ruleCount} 条规则")
                container?.let { render(it) }
            } else {
                AlertDialog.Builder(ctx)
                    .setTitle("添加失败")
                    .setMessage(err ?: "未知错误")
                    .setPositiveButton("知道了", null)
                    .show()
            }
        }
    }

    // ---------------- 编辑 ----------------

    private fun showEditDialog(entry: SubscriptionEntry) {
        val ctx = requireContext()
        val pad = ctx.dp(20)

        val nameInput = EditText(ctx).apply {
            hint = "订阅名称"
            setText(entry.name)
            setSingleLine()
        }
        val checkInput = EditText(ctx).apply {
            hint = "可选，返回纯文本版本号"
            setText(entry.checkUrl ?: "")
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine()
        }
        val body = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, ctx.dp(8), pad, 0)
            addView(ctx.caption("名称"))
            addView(nameInput)
            addView(ctx.gap(12))
            addView(ctx.caption("版本检查 URL"))
            addView(checkInput)
            addView(ctx.gap(12))
            addView(ctx.caption("订阅 URL（不可修改，要换地址请删除后重新添加）"))
            addView(ctx.body(entry.updateUrl, 12f).apply { setPadding(0, ctx.dp(4), 0, 0) })
        }

        AlertDialog.Builder(ctx)
            .setTitle("编辑订阅")
            .setView(ScrollView(ctx).apply { addView(body) })
            .setPositiveButton("保存") { _, _ ->
                val updated = entry.copy(
                    name = nameInput.text.toString().trim(),
                    checkUrl = checkInput.text.toString().trim().ifEmpty { null },
                )
                SubscriptionStore.get(ctx).upsert(updated)
                toast("已保存")
                container?.let { render(it) }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun toast(msg: String) {
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
    }
}
