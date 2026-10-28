package com.splashskip.app.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatDialog
import com.splashskip.app.R

/**
 * 毛玻璃弹窗。
 *
 * 为什么不用 `AlertDialog`：Material 的对话框是**纯白底 + 直角 + 硬阴影**，
 * 跟整套柔光玻璃视觉完全两回事 —— 主界面是半透明卡片浮在渐变上，
 * 一点开弹窗就跳回「2018 年的系统对话框」，割裂感非常明显。
 *
 * 这里用 `AppCompatDialog` + 自定义 contentView 重做，三个关键点：
 *
 *   1. **窗口背景必须透明**（`Theme.SplashSkip.Dialog` 里设的），
 *      否则圆角外面会露出一圈方形白底，圆角等于白做；
 *   2. **复用 [glassBackground]** —— 同一套半透明填充 + 描边 + 顶部受光，
 *      弹窗和卡片才是同一种材质；
 *   3. **遮罩调淡到 35%** —— 默认 60% 的黑会把背后的渐变压死，
 *      玻璃透不出东西就变成一块灰塑料。
 *
 * API 31+ 额外申请 `FLAG_BLUR_BEHIND`，真机上弹窗背后会真的糊一层；
 * 不支持的机型静默忽略，不影响布局。
 */
class GlassDialog private constructor(context: Context) :
    AppCompatDialog(context, R.style.Theme_SplashSkip_Dialog) {

    class Builder(private val context: Context) {

        private var titleText: String = ""
        private var messageText: String? = null
        private var contentView: View? = null
        private var itemList: List<String>? = null
        private var onItemClick: ((Int) -> Unit)? = null

        private var positiveText: String? = null
        private var onPositive: (() -> Unit)? = null

        /**
         * 默认没有「取消」—— 只有真正需要「反悔」的弹窗（确认删除、编辑表单）
         * 才显式加。只有「关闭」一个动作的详情弹窗，多一个取消纯属噪音。
         */
        private var negativeText: String? = null
        private var neutralText: String? = null
        private var onNeutral: (() -> Unit)? = null
        private var dismissOnPositive = true

        fun title(text: String) = apply { titleText = text }

        fun message(text: String) = apply { messageText = text }

        /** 自定义内容，会包一层限高滚动容器。 */
        fun content(view: View) = apply { contentView = view }

        /** 列表选择模式：每行一个可点项，点击后自动关闭。 */
        fun items(list: List<String>, onClick: (Int) -> Unit) = apply {
            itemList = list
            onItemClick = onClick
        }

        fun positive(text: String, dismiss: Boolean = true, onClick: () -> Unit = {}) = apply {
            positiveText = text
            dismissOnPositive = dismiss
            onPositive = onClick
        }

        fun negative(text: String?) = apply { negativeText = text }

        /** 左侧的第三个动作，比如「删除」。 */
        fun neutral(text: String, onClick: () -> Unit) = apply {
            neutralText = text
            onNeutral = onClick
        }

        fun show(): GlassDialog {
            val dialog = GlassDialog(context)
            dialog.setContentView(build(dialog))
            dialog.setCanceledOnTouchOutside(true)
            dialog.setOnShowListener { dialog.applyWindowChrome() }
            dialog.show()
            return dialog
        }

        private fun build(dialog: GlassDialog): View {
            val ctx = context
            val card = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(ctx.dp(20), ctx.dp(20), ctx.dp(20), ctx.dp(16))
                background = ctx.glassBackground(ctx.dp(RADIUS_DIALOG).toFloat())
            }

            if (titleText.isNotEmpty()) {
                card.addView(ctx.title(titleText, 17f))
            }

            messageText?.let {
                card.addView(ctx.body(it, 13f).apply {
                    setPadding(0, if (titleText.isEmpty()) 0 else ctx.dp(8), 0, 0)
                })
            }

            contentView?.let { view ->
                val scroller = MaxHeightScrollView(ctx, (ctx.resources.displayMetrics.heightPixels * 0.58f).toInt())
                scroller.addView(
                    view,
                    FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT),
                )
                card.addView(
                    scroller,
                    LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                        topMargin = ctx.dp(12)
                    },
                )
            }

            itemList?.let { list ->
                val listCol = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
                list.forEachIndexed { index, text ->
                    if (index > 0) listCol.addDivider()
                    // 弹窗里的列表项比卡片行更疏（14dp 上下），
                    // 这是系统对话框的点击密度，手指不容易点错行
                    val item = TextView(ctx).apply {
                        this.text = text
                        textSize = 15f
                        setTextColor(0xFF1C1C1E.toInt())
                        setPadding(ctx.dp(2), ctx.dp(14), ctx.dp(2), ctx.dp(14))
                        isClickable = true
                        setOnClickListener {
                            dialog.dismiss()
                            onItemClick?.invoke(index)
                        }
                    }
                    listCol.addView(item, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
                }
                // 标题和列表之间拉一条淡线，视觉上把「这是哪个订阅」和「能做什么」分开
                card.addView(View(ctx).apply {
                    layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 1).apply {
                        topMargin = ctx.dp(12)
                        bottomMargin = ctx.dp(2)
                    }
                    setBackgroundColor(0x14000000)
                })
                card.addView(listCol, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            }

            val buttons = buildButtons(dialog)
            if (buttons.childCount > 0) card.addView(buttons)

            return card
        }

        private fun buildButtons(dialog: GlassDialog): LinearLayout {
            val ctx = context
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
                setPadding(0, ctx.dp(18), 0, 0)
            }

            fun add(text: String?, primary: Boolean, action: () -> Unit) {
                if (text.isNullOrEmpty()) return
                val btn: Button = ctx.action(text, primary) { action() }
                row.addView(
                    btn,
                    LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                        marginStart = ctx.dp(8)
                    },
                )
            }

            // 列表模式下不给「取消」—— 点空白或返回键就能关，
            // 底下再压一个按钮反而让弹窗显得重，还挤掉列表的呼吸空间
            val isList = itemList != null
            if (!isList) {
                add(neutralText, false) {
                    dialog.dismiss()
                    onNeutral?.invoke()
                }
                add(negativeText, false) { dialog.dismiss() }
            }
            add(positiveText, true) {
                if (dismissOnPositive) dialog.dismiss()
                onPositive?.invoke()
            }
            return row
        }
    }

    private fun applyWindowChrome() {
        val w = window ?: return
        w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        // 35% 遮罩：玻璃要能透出背后的渐变，压太黑就成塑料了
        w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        w.setDimAmount(0.35f)

        val metrics = context.resources.displayMetrics
        val width = minOf((metrics.widthPixels * 0.88f).toInt(), context.dp(420))
        w.setLayout(width, WRAP_CONTENT)

        // API 31+ 真模糊：系统支持就糊一层，不支持静默降级
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            w.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            w.attributes = w.attributes.apply { blurBehindRadius = context.dp(20) }
        }
    }

    /** 高度上限滚动容器，避免长内容把弹窗顶出屏幕。 */
    private class MaxHeightScrollView(context: Context, private val maxHeight: Int) : ScrollView(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val capped = MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST)
            super.onMeasure(widthMeasureSpec, capped)
        }
    }

    companion object {
        private const val RADIUS_DIALOG = 26
    }
}
