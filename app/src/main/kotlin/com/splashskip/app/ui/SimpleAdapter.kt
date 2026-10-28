package com.splashskip.app.ui

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.recyclerview.widget.RecyclerView

/**
 * 多个列表页共用的轻量适配器。
 * 条目结构：「[复选框] + 标题 + 副标题 + [右侧标签 | Switch]」。
 *
 * 两种可选控件互斥，都默认 GONE：
 *   · [selectable]   → 批量模式下显示复选框
 *   · [showSwitch]   → 普通模式下显示开关（用于「启用/禁用」这类二态操作）
 *
 * 注意 [onClick] 必须是**最后一个参数** —— 调用方普遍写成
 * `SimpleAdapter(items) { item -> ... }` 的尾随 lambda 形式，
 * Kotlin 的尾随 lambda 只会绑定到最后一个参数，挪了位置所有调用点会静默失效。
 */
class SimpleAdapter(
    items: List<Item>,
    private val selectable: Boolean = false,
    private val showSwitch: Boolean = false,
    private val onSwitchChanged: ((Item, Boolean) -> Unit)? = null,
    private val onSelectionChanged: (() -> Unit)? = null,
    private val onClick: (Item) -> Unit = {},
) : RecyclerView.Adapter<SimpleAdapter.VH>() {

    private val data = ArrayList<Item>(items)
    private val selected = LinkedHashSet<String>()

    /** 批量选择模式。退出时自动清空选中。 */
    var selectionMode: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            if (!value) selected.clear()
            notifyDataSetChanged()
            onSelectionChanged?.invoke()
        }

    fun submit(newItems: List<Item>) {
        data.clear()
        data.addAll(newItems)
        // 列表刷新后把已经不存在的 key 剔掉，避免选中集合里留幽灵
        val keys = newItems.mapTo(HashSet()) { it.key }
        selected.retainAll(keys)
        notifyDataSetChanged()
    }

    fun selectedKeys(): Set<String> = selected.toSet()

    fun selectionCount(): Int = selected.size

    fun isAllSelected(): Boolean = data.isNotEmpty() && selected.size == data.size

    /** 全选 / 全不选。 */
    fun toggleSelectAll() {
        if (isAllSelected()) {
            selected.clear()
        } else {
            data.forEach { selected.add(it.key) }
        }
        notifyDataSetChanged()
        onSelectionChanged?.invoke()
    }

    data class Item(
        val title: String,
        val subtitle: String,
        val badge: String = "",
        val payload: Any? = null,
        /** 选中标识，批量模式下用它记录选中项。 */
        val key: String = "",
        /** Switch 的选中态，仅在 [showSwitch] 时使用。 */
        val checked: Boolean = false,
    )

    class VH(
        val root: LinearLayout,
        val check: CheckBox,
        val switchView: SwitchCompat,
        val title: TextView,
        val subtitle: TextView,
        val badge: TextView,
    ) : RecyclerView.ViewHolder(root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val ctx: Context = parent.context
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ctx.dp(16), ctx.dp(12), ctx.dp(16), ctx.dp(12))
            layoutParams = RecyclerView.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        }
        val check = CheckBox(ctx).apply {
            visibility = View.GONE
            // 点击交给整行处理，避免复选框和行各自响应导致状态错乱
            isClickable = false
            isFocusable = false
            setPadding(0, 0, ctx.dp(8), 0)
        }
        val texts = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        }
        val title = TextView(ctx).apply {
            textSize = 14f
            setTextColor(0xFF1C1C1E.toInt())
        }
        val subtitle = TextView(ctx).apply {
            textSize = 11f
            setTextColor(0xFF8E8E93.toInt())
            setPadding(0, ctx.dp(3), 0, 0)
        }
        val badge = TextView(ctx).apply {
            textSize = 12f
            setTextColor(0xFF0A84FF.toInt())
            setPadding(ctx.dp(8), 0, 0, 0)
        }
        val switchView = SwitchCompat(ctx).apply {
            visibility = View.GONE
            isClickable = true
        }
        texts.addView(title)
        texts.addView(subtitle)
        root.addView(check)
        root.addView(texts)
        root.addView(badge)
        root.addView(switchView)
        return VH(root, check, switchView, title, subtitle, badge)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = data[position]
        holder.title.text = item.title
        holder.subtitle.text = item.subtitle

        val showCheck = selectable && selectionMode
        val showSw = showSwitch && !selectionMode

        // 复选框
        holder.check.visibility = if (showCheck) View.VISIBLE else View.GONE
        if (showCheck) holder.check.isChecked = selected.contains(item.key)

        // Switch
        holder.switchView.visibility = if (showSw) View.VISIBLE else View.GONE
        if (showSw) {
            // 必须先清监听再设状态。RecyclerView 复用 ViewHolder 时，
            // 直接 setChecked 会触发上一个 item 残留的监听器，导致数据被写错。
            holder.switchView.setOnCheckedChangeListener(null)
            holder.switchView.isChecked = item.checked
            holder.switchView.setOnCheckedChangeListener { _, isChecked ->
                onSwitchChanged?.invoke(item, isChecked)
            }
        }

        // 右侧标签：有 Switch 时让位（模式信息已经在 subtitle 里）
        val badgeText = item.badge
        if (showSw || badgeText.isEmpty()) {
            holder.badge.visibility = View.GONE
        } else {
            holder.badge.visibility = View.VISIBLE
            holder.badge.text = badgeText
        }

        // 选中行淡色高亮
        holder.root.background = if (showCheck && selected.contains(item.key)) {
            GradientDrawable().apply {
                cornerRadius = holder.root.context.dp(10).toFloat()
                setColor(0x260A84FF)
            }
        } else {
            null
        }

        holder.root.setOnClickListener {
            if (selectionMode) toggle(item) else onClick(item)
        }
    }

    private fun toggle(item: Item) {
        if (!selected.remove(item.key)) selected.add(item.key)
        notifyDataSetChanged()
        onSelectionChanged?.invoke()
    }

    override fun getItemCount(): Int = data.size
}
