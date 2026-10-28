package com.splashskip.app.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Toast
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

    /** 已挂上「解析完成」回调，避免每次 render 重复注册。 */
    private var waitingRules = false

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
        val subs = repo.listSubscriptions()
        val sourceRules = subs.sumOf { it.sourceRuleCount }
        val sourceGroups = subs.sumOf { it.sourceGroupCount }
        val cats = if (repo.isReady) repo.categoryStats() else emptyList()
        val catsEnabledCount = cats.count { it.enabled }

        // ---- 概览 ----
        val overview = ctx.card()
        overview.addView(ctx.title("规则概览"))
        overview.addView(ctx.gap(4))
        overview.addRow(
            "已解析规则",
            "来自 ${subs.size} 个订阅，覆盖 ${rules.map { it.packages.firstOrNull() }.filterNotNull().distinct().size} 个应用",
            "${rules.size} 条",
        )
        overview.addDivider()
        overview.addRow(
            "当前生效",
            if (catsEnabledCount > 0) "只执行勾选的分类，见下方「规则分类」"
            else "一个分类都没开，当前不会点击任何东西",
            "${repo.currentIndex().size} 条",
        )
        if (sourceGroups > 0) {
            overview.addDivider()
            overview.addRow(
                "订阅原始规模",
                if (sourceRules > rules.size) "其余 ${sourceRules - rules.size} 条解析失败，见下方告警"
                else "全部解析成功",
                "$sourceRules 条 / $sourceGroups 组",
            )
        }
        if (!repo.isReady) {
            overview.addDivider()
            overview.addView(ctx.caption("正在解析订阅…"))
            if (!waitingRules) {
                waitingRules = true
                repo.onReady {
                    waitingRules = false
                    activity?.runOnUiThread { if (isAdded) container?.let { render(it) } }
                }
            }
        }
        if (rules.isEmpty() && repo.isReady) {
            overview.addDivider()
            overview.addView(ctx.caption(
                "目前没有任何规则。在下方添加一个 GKD 订阅源（如 " +
                    "gcore.jsdelivr.net/gh/Lin-arm/GKD_subscription@main/dist/gkd.json5），" +
                    "否则不会跳过任何广告。",
            ))
        }
        col.addView(overview)

        // ---- 分类开关 ----
        if (cats.isNotEmpty()) {
            val catCard = ctx.card()
            catCard.addView(ctx.title("规则分类"))
            catCard.addView(ctx.gap(4))
            catCard.addView(ctx.caption(
                "订阅里的规则按 GKD 的分类分组，只有勾选的分类会真的去点。" +
                    "默认只开订阅作者标为启用的那一类 —— 全开会在你正常刷信息流、" +
                    "点设置的时候自动动手。",
                11.5f,
            ))
            catCard.addView(ctx.gap(2))
            cats.forEachIndexed { i, stat ->
                if (i > 0) catCard.addDivider()
                val row = catCard.addRow(stat.name, "${stat.ruleCount} 条")
                row.addView(SwitchMaterial(ctx).apply {
                    isChecked = stat.enabled
                    setOnCheckedChangeListener { _, checked ->
                        if (checked == stat.enabled) return@setOnCheckedChangeListener
                        RuleRepositoryProvider.get(ctx).setCategoryEnabled(stat.name, checked)
                        container?.let { render(it) }
                    }
                })
            }
            col.addView(catCard)
        }

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
            val loadedById = subs.associateBy { it.id }
            entries.forEachIndexed { i, e ->
                if (i > 0) subsCard.addDivider()
                // 优先用实际解析出来的条数，entry.ruleCount 是上次拉取时写的，
                // 老用户升级到全量导入后这个数字还是旧的
                val (title, subtitle, badge) = describe(ctx, e, loadedById[e.id]?.rules?.size)
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
            warnCard.addView(ctx.caption(
                if (errs.size > 8) "共 ${errs.size} 条，只列前 8 条" else "共 ${errs.size} 条",
                11f,
            ))
            errs.take(8).forEach { warnCard.addView(ctx.caption("· $it", 11f)) }
            col.addView(warnCard)
        }
    }

    private fun describe(
        ctx: Context,
        e: SubscriptionEntry,
        actualCount: Int? = null,
    ): Triple<String, String, String> {
        val title = e.displayName
        val count = actualCount ?: e.ruleCount
        val subtitle = buildString {
            if (e.version > 0) append("v").append(e.version).append("　")
            if (count > 0) append(count).append(" 条　")
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
        GlassDialog.Builder(ctx)
            .title(entry.displayName)
            .items(items.toList()) { which ->
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
        GlassDialog.Builder(ctx)
            .title("删除订阅")
            .message("将从列表中移除「${entry.displayName}」，并删除本地缓存的那份规则。\n\n其他订阅和内置规则不受影响。")
            .negative("取消")
            .positive("删除") {
                SubscriptionStore.get(ctx).remove(entry.id)
                RuleRepositoryProvider.get(ctx).removeRemote(entry.id)
                toast("已删除 ${entry.displayName}")
                container?.let { render(it) }
            }
            .show()
    }

    // ---------------- 添加 ----------------

    private fun showAddDialog() {
        val ctx = requireContext()

        val urlInput = ctx.input("https://example.com/rules.json").apply {
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine()
        }
        val checkInput = ctx.input("可选，返回纯文本版本号").apply {
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine()
        }

        // 内边距交给 GlassDialog 的玻璃卡片，这里只排内容
        val body = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, ctx.dp(2), 0, 0)
            addView(ctx.caption("订阅 URL"))
            addView(urlInput)
            addView(ctx.gap(12))
            addView(ctx.caption("版本检查 URL"))
            addView(checkInput)
            addView(ctx.gap(12))
            addView(ctx.caption(
                "填 GKD 格式的订阅地址，例如：\n" +
                    "gcore.jsdelivr.net/gh/Lin-arm/GKD_subscription@main/dist/gkd.json5\n\n" +
                    "订阅里的全部分类都会导入（开屏广告、全屏广告、局部广告、更新提示等）。" +
                    "开屏分类的规则优先级更高，会先于其它分类尝试；非开屏规则会在对应界面" +
                    "自动点击，误点风险比只导开屏高。",
            ))
        }

        GlassDialog.Builder(ctx)
            .title("添加 GKD 订阅")
            .content(body)
            .negative("取消")
            .positive("添加") {
                doAdd(urlInput.text.toString(), checkInput.text.toString())
            }
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
                GlassDialog.Builder(ctx)
                    .title("添加失败")
                    .message(err ?: "未知错误")
                    .positive("知道了")
                    .show()
            }
        }
    }

    // ---------------- 编辑 ----------------

    private fun showEditDialog(entry: SubscriptionEntry) {
        val ctx = requireContext()

        val nameInput = ctx.input("订阅名称").apply {
            setText(entry.name)
            setSingleLine()
        }
        val checkInput = ctx.input("可选，返回纯文本版本号").apply {
            setText(entry.checkUrl ?: "")
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine()
        }
        val body = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, ctx.dp(2), 0, 0)
            addView(ctx.caption("名称"))
            addView(nameInput)
            addView(ctx.gap(12))
            addView(ctx.caption("版本检查 URL"))
            addView(checkInput)
            addView(ctx.gap(12))
            addView(ctx.caption("订阅 URL（不可修改，要换地址请删除后重新添加）"))
            addView(ctx.body(entry.updateUrl, 12f).apply { setPadding(0, ctx.dp(4), 0, 0) })
        }

        GlassDialog.Builder(ctx)
            .title("编辑订阅")
            .content(body)
            .negative("取消")
            .positive("保存") {
                val updated = entry.copy(
                    name = nameInput.text.toString().trim(),
                    checkUrl = checkInput.text.toString().trim().ifEmpty { null },
                )
                SubscriptionStore.get(ctx).upsert(updated)
                toast("已保存")
                container?.let { render(it) }
            }
            .show()
    }

    private fun toast(msg: String) {
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
    }
}
