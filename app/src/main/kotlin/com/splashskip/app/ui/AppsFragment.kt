package com.splashskip.app.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.splashskip.a11y.AppConfigStore
import com.splashskip.app.R
import com.splashskip.core.engine.AppMode

/**
 * 应用页：搜索 + 逐 App 配置 + 批量。
 *
 * 交互设计：
 *   · **Switch 控制启用/禁用** —— 这是最高频的操作（某个 App 误点了赶紧关掉），
 *     必须一步到位，不能藏在菜单里。
 *   · **点整行弹出三态选择**（自动 / 学习 / 禁用）—— 学习模式不是二态，
 *     Switch 表达不了，放在这里做精细设置。
 *   · **批量** —— 「除了微信支付宝全都不管」这类场景，一条条点太累。
 */
class AppsFragment : Fragment() {

    private lateinit var config: AppConfigStore
    private lateinit var adapter: SimpleAdapter

    /** 全量列表，搜索时从这里面过滤。 */
    private var allEntries: List<Entry> = emptyList()

    /** 当前展示的列表（过滤后）。 */
    private var entries: List<Entry> = emptyList()

    private var query: String = ""

    private var hint: TextView? = null
    private var batchBtn: TextView? = null
    private var actionBar: LinearLayout? = null
    private var selectAllChip: TextView? = null
    private var searchInput: EditText? = null

    private data class Entry(val pkg: String, val label: String)

    override fun onCreateView(
        inflater: LayoutInflater,
        parent: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        config = AppConfigStore.get(requireContext())
        val ctx = requireContext()

        // ---- 搜索框 ----
        searchInput = EditText(ctx).apply {
            hint = "搜索应用名或包名"
            textSize = 13f
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT
            setPadding(ctx.dp(14), ctx.dp(9), ctx.dp(14), ctx.dp(9))
            background = GradientDrawable().apply {
                cornerRadius = ctx.dp(12).toFloat()
                setColor(0x59FFFFFF)
                setStroke(ctx.dp(1), 0x66FFFFFF)
            }
            addTextChangedListener { text ->
                query = text?.toString().orEmpty()
                applyFilter()
            }
        }
        val searchWrap = LinearLayout(ctx).apply {
            setPadding(ctx.dp(12), ctx.dp(2), ctx.dp(12), ctx.dp(8))
            addView(
                searchInput,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT),
            )
        }

        // ---- 说明 + 批量开关 ----
        hint = ctx.body("", 12f)
        batchBtn = ctx.caption("批量", 12f).apply {
            setTextColor(ACCENT_BLUE)
            setPadding(ctx.dp(14), ctx.dp(4), 0, ctx.dp(4))
            setOnClickListener { toggleSelectionMode() }
        }
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ctx.dp(12), 0, ctx.dp(12), ctx.dp(8))
            addView(hint, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(batchBtn)
        }

        // ---- 批量操作栏 ----
        selectAllChip = chip(ctx, "全选") { adapter.toggleSelectAll() }
        actionBar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding(ctx.dp(12), 0, ctx.dp(12), ctx.dp(10))
            addView(selectAllChip)
            addView(hSpacer(ctx, 8))
            addView(chip(ctx, "设为自动") { applyToSelected(AppMode.AUTO) })
            addView(hSpacer(ctx, 6))
            addView(chip(ctx, "设为学习") { applyToSelected(AppMode.LEARN) })
            addView(hSpacer(ctx, 6))
            addView(chip(ctx, "设为禁用") { applyToSelected(AppMode.DISABLED) })
        }

        // ---- 列表 ----
        val rv = RecyclerView(ctx).apply {
            layoutManager = LinearLayoutManager(ctx)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            isVerticalScrollBarEnabled = false
            addItemDecoration(HairlineDivider(ctx))
        }
        adapter = SimpleAdapter(
            emptyList(),
            selectable = true,
            showSwitch = true,
            onSwitchChanged = { item, checked -> onSwitchToggled(item, checked) },
            onSelectionChanged = { updateSelectionUi() },
            onClick = { item -> (item.payload as? Entry)?.let { showModeDialog(it) } },
        )
        rv.adapter = adapter

        val card = ctx.card().apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f,
            ).apply {
                setMargins(ctx.dp(14), ctx.dp(4), ctx.dp(14), ctx.dp(6))
            }
            setPadding(ctx.dp(6), ctx.dp(12), ctx.dp(6), ctx.dp(6))
            addView(searchWrap)
            addView(header)
            addView(actionBar)
            addView(rv)
        }

        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_aurora)
            setPadding(0, ctx.dp(4), 0, 0)
            addView(card)
        }
    }

    override fun onResume() {
        super.onResume()
        if (allEntries.isEmpty()) allEntries = loadLauncherApps()
        applyFilter()
    }

    /** hide/show 不触发 onResume，切回来要重新扫一遍列表（用户可能装了新 App）。 */
    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) {
            allEntries = loadLauncherApps()
            applyFilter()
        }
    }

    override fun onDestroyView() {
        hint = null
        batchBtn = null
        actionBar = null
        selectAllChip = null
        searchInput = null
        super.onDestroyView()
    }

    // ---------------- 搜索 ----------------

    private fun applyFilter() {
        val q = query.trim()
        entries = if (q.isEmpty()) {
            allEntries
        } else {
            allEntries.filter {
                it.label.contains(q, ignoreCase = true) || it.pkg.contains(q, ignoreCase = true)
            }
        }
        render()
    }

    // ---------------- 渲染 ----------------

    private fun render() {
        val overrides = config.overrides()

        adapter.submit(
            entries.map { e ->
                val mode = overrides[e.pkg] ?: config.globalMode
                SimpleAdapter.Item(
                    title = e.label,
                    // 模式信息放副标题，给右侧 Switch 让位
                    subtitle = "${e.pkg} · ${modeText(mode)}",
                    badge = "",
                    payload = e,
                    key = e.pkg,
                    checked = mode != AppMode.DISABLED,
                )
            },
        )
        updateSelectionUi()
        if (!adapter.selectionMode) hint?.text = hintText(overrides.size)
    }

    private fun hintText(customized: Int): String = when {
        allEntries.isEmpty() -> "没有读取到可配置的应用。"
        entries.isEmpty() -> "没有匹配「$query」的应用。"
        query.isNotEmpty() -> "找到 ${entries.size} 个匹配，共 ${allEntries.size} 个应用。"
        else -> "共 ${allEntries.size} 个应用，已单独配置 $customized 个。开关控制启用，" +
            "点整行选模式：自动 / 学习 / 禁用"
    }

    private fun updateSelectionUi() {
        val on = adapter.selectionMode
        actionBar?.visibility = if (on) View.VISIBLE else View.GONE
        batchBtn?.text = if (on) "取消" else "批量"
        selectAllChip?.text = if (adapter.isAllSelected()) "全不选" else "全选"
        if (on) hint?.text = "已选 ${adapter.selectionCount()} 项"
    }

    // ---------------- 单条操作 ----------------

    /** Switch：开 = 自动，关 = 禁用。学习模式走点整行的弹窗。 */
    private fun onSwitchToggled(item: SimpleAdapter.Item, checked: Boolean) {
        val entry = item.payload as? Entry ?: return
        config.setMode(entry.pkg, if (checked) AppMode.AUTO else AppMode.DISABLED)
        render()
    }

    /** 点整行：三态选择（Switch 表达不了三态，学习模式放这里）。 */
    private fun showModeDialog(entry: Entry) {
        val ctx = requireContext()
        val current = config.overrides()[entry.pkg] ?: config.globalMode
        val modes = listOf(
            AppMode.AUTO to "自动（命中即点）",
            AppMode.LEARN to "学习（首次询问一次）",
            AppMode.DISABLED to "禁用（不干预）",
        )
        val labels = modes.map { (m, label) -> if (m == current) "✓ $label" else "　 $label" }

        AlertDialog.Builder(ctx)
            .setTitle(entry.label)
            .setItems(labels.toTypedArray()) { _, which ->
                config.setMode(entry.pkg, modes[which].first)
                render()
            }
            .show()
    }

    // ---------------- 批量 ----------------

    private fun toggleSelectionMode() {
        adapter.selectionMode = !adapter.selectionMode
        if (!adapter.selectionMode) {
            hint?.text = hintText(config.overrides().size)
        }
    }

    private fun applyToSelected(mode: AppMode) {
        val keys = adapter.selectedKeys()
        if (keys.isEmpty()) {
            Toast.makeText(requireContext(), "先勾选要设置的应用", Toast.LENGTH_SHORT).show()
            return
        }
        keys.forEach { config.setMode(it, mode) }
        Toast.makeText(
            requireContext(),
            "已将 ${keys.size} 个应用设为${modeText(mode)}",
            Toast.LENGTH_SHORT,
        ).show()
        adapter.selectionMode = false
        render()
    }

    private fun modeText(mode: AppMode): String = when (mode) {
        AppMode.AUTO -> "自动"
        AppMode.LEARN -> "学习"
        AppMode.DISABLED -> "禁用"
    }

    // ---------------- 小组件 ----------------

    private fun chip(ctx: Context, text: String, onClick: () -> Unit): TextView =
        ctx.caption(text, 12f).apply {
            setTextColor(ACCENT_BLUE)
            setPadding(ctx.dp(10), ctx.dp(6), ctx.dp(10), ctx.dp(6))
            background = GradientDrawable().apply {
                cornerRadius = ctx.dp(9).toFloat()
                setColor(ACCENT_SOFT)
            }
            setOnClickListener { onClick() }
        }

    private fun hSpacer(ctx: Context, widthDp: Int): View = View(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(ctx.dp(widthDp), 1)
    }

    private fun loadLauncherApps(): List<Entry> {
        val ctx = requireContext()
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return runCatching {
            ctx.packageManager
                .queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
                .mapNotNull { it.activityInfo?.applicationInfo }
                .distinctBy { it.packageName }
                .filter { it.packageName != ctx.packageName }
                .map {
                    val label = runCatching {
                        ctx.packageManager.getApplicationLabel(it).toString()
                    }.getOrDefault(it.packageName)
                    Entry(it.packageName, label)
                }
                .sortedBy { it.label }
        }.getOrDefault(emptyList())
    }

    private companion object {
        const val ACCENT_BLUE = 0xFF0A84FF.toInt()
        const val ACCENT_SOFT = 0x1F0A84FF.toInt()
    }
}

/**
 * 列表行的发丝分隔线。
 *
 * 用 8% 黑而不是实色灰 —— 卡片本身是半透明的，实色线会在透出的背景色上显得脏。
 * 左右各缩进 12dp，避免线条顶到卡片圆角。
 */
private class HairlineDivider(context: Context) : RecyclerView.ItemDecoration() {

    private val paint = Paint().apply { color = 0x14000000 }
    private val inset = context.dp(12)

    override fun onDraw(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        val count = parent.childCount
        for (i in 0 until count - 1) {
            val child = parent.getChildAt(i) ?: continue
            c.drawRect(
                (child.left + inset).toFloat(),
                child.bottom.toFloat(),
                (child.right - inset).toFloat(),
                child.bottom + 1f,
                paint,
            )
        }
    }
}
