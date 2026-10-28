package com.splashskip.app.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.widget.AppCompatButton
import com.splashskip.app.R

/**
 * 视觉层：iOS 柔光玻璃（glassmorphism）风格。
 *
 * 在 Android 上做「毛玻璃」有三种路子，这里选的是第三种：
 *
 *   1. RenderEffect 真模糊（API 31+）—— 要抓下层内容做 blur，实现复杂且低版本没有；
 *   2. Window.setBackgroundBlurRadius —— 只能模糊窗口背景，对卡片无效；
 *   3. **半透明 + 高光描边 + 大圆角 + 极淡阴影** —— 靠视觉暗示骗过眼睛。
 *
 * 第三种在真机上的观感已经足够接近，成本却低一个数量级。关键在三点：
 *   · 填充不能是纯白，要 88% 透明白，让底下那层渐变透上来
 *   · 必须有一圈半透明白描边，这是「玻璃边缘反光」，没有它卡片会显得扁
 *   · 阴影要极淡（elevation 2dp），玻璃不该有硬阴影
 *
 * 全部定义为顶层扩展函数 —— 写成 object 的成员扩展函数，其它文件调不到。
 */

private const val TEXT_PRIMARY = 0xFF1C1C1E.toInt()
private const val TEXT_SECONDARY = 0xFF5B5B60.toInt()
private const val TEXT_TERTIARY = 0xFF8E8E93.toInt()

private const val ACCENT = 0xFF0A84FF.toInt()
private const val ACCENT_SOFT = 0x1F0A84FF.toInt()

/**
 * 68% 白：留 32% 透明度让底层彩色光斑透上来。
 * 这是玻璃感的**唯一来源** —— 背景没有色彩变化时，这个值再低也只是「变灰」，不会变「透」。
 */
private const val GLASS_FILL = 0x8CFFFFFF.toInt()

/** 30% 白描边：只做极轻微的边缘提亮，重了会变成硬边。 */
private const val GLASS_STROKE = 0x4DFFFFFF.toInt()

private const val RADIUS_CARD = 24
private const val RADIUS_CONTROL = 14

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

/** 中粗字体。Android 没有 SF Pro，用 sans-serif-medium 最接近 iOS 的观感。 */
private fun mediumTypeface(): Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

/**
 * 玻璃背景：半透明底 + 描边 + 顶部受光渐变。
 *
 * 三层结构（下层先画）：
 *   1. 半透明白底 —— 让背景光斑透上来
 *   2. 1dp 极淡描边 —— 勾出玻璃边缘
 *   3. 顶部白色渐隐 —— 模拟玻璃上沿的反光
 *
 * 第三层是「玻璃」和「半透明塑料片」的分界：
 * 没有受光，卡片就只是一块均匀的色块，看着廉价。
 * 高光必须内缩 1dp，否则会盖住第 2 层的描边。
 */
private fun Context.glassBackground(radiusPx: Float): Drawable {
    val base = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radiusPx
        setColor(GLASS_FILL)
        setStroke(dp(1), GLASS_STROKE)
    }
    val sheen = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM,
        intArrayOf(0x4DFFFFFF, 0x12FFFFFF, 0x00FFFFFF),
    ).apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radiusPx
    }
    return LayerDrawable(arrayOf(base, sheen)).apply {
        setLayerInset(1, dp(1), dp(1), dp(1), dp(1))
    }
}

/**
 * 玻璃卡片。
 *
 * **刻意不加 elevation。** Android 的 elevation 阴影是硬投影，边缘没有过渡，
 * 在半透明卡片上会形成一圈明显的灰色硬边 —— 这就是「灰白过渡生硬」的根源。
 * 玻璃质感靠的是透明度、受光与色彩层次，不是阴影。
 */
fun Context.card(): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(dp(18), dp(16), dp(18), dp(16))
    background = glassBackground(dp(RADIUS_CARD).toFloat())
    elevation = 0f
    layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
        setMargins(dp(14), dp(5), dp(14), dp(5))
    }
}

fun Context.title(text: String, sizeSp: Float = 16f): TextView = TextView(this).apply {
    this.text = text
    textSize = sizeSp
    setTextColor(TEXT_PRIMARY)
    typeface = mediumTypeface()
}

fun Context.body(text: String, sizeSp: Float = 13f): TextView = TextView(this).apply {
    this.text = text
    textSize = sizeSp
    setTextColor(TEXT_SECONDARY)
    setLineSpacing(dp(3).toFloat(), 1.05f)
}

/** 次要说明文字，比 body 更淡。 */
fun Context.caption(text: String, sizeSp: Float = 12f): TextView = TextView(this).apply {
    this.text = text
    textSize = sizeSp
    setTextColor(TEXT_TERTIARY)
    setLineSpacing(dp(2).toFloat(), 1.05f)
}

/**
 * 胶囊按钮。
 *
 * 用 AppCompatButton 而不是 Button —— Material3 主题下 Button 会被自动替换成
 * MaterialButton，而 MaterialButton 会忽略 background 属性，自定义圆角直接失效。
 */
fun Context.action(text: String, primary: Boolean = false, onClick: () -> Unit): Button =
    AppCompatButton(this).apply {
        this.text = text
        isAllCaps = false
        textSize = 14f
        typeface = mediumTypeface()
        minHeight = 0
        minimumHeight = dp(44)
        setPadding(dp(16), dp(10), dp(16), dp(10))
        stateListAnimator = null
        backgroundTintList = null
        elevation = 0f
        background = GradientDrawable().apply {
            cornerRadius = dp(RADIUS_CONTROL).toFloat()
            setColor(if (primary) ACCENT else ACCENT_SOFT)
        }
        setTextColor(if (primary) Color.WHITE else ACCENT)
        setOnClickListener { onClick() }
    }

/** 淡色胶囊标签，用于列表右侧的状态。 */
fun Context.pill(text: String): TextView = TextView(this).apply {
    this.text = text
    textSize = 11f
    typeface = mediumTypeface()
    setTextColor(ACCENT)
    setPadding(dp(9), dp(4), dp(9), dp(4))
    background = GradientDrawable().apply {
        cornerRadius = dp(9).toFloat()
        setColor(ACCENT_SOFT)
    }
}

fun Context.row(): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
}

fun Context.gap(heightDp: Int): View = View(this).apply {
    layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(heightDp))
}

/**
 * 顶部提示条。柔光风格下不做实心色块，
 * 用冷色半透明底 + 圆角 —— 跟整体蓝紫调统一，暖色会显得跳。
 */
fun Context.banner(text: String): TextView = TextView(this).apply {
    this.text = text
    textSize = 11.5f
    setTextColor(0xFF4A5A7A.toInt())
    setPadding(dp(16), dp(12), dp(16), dp(12))
    setLineSpacing(dp(2).toFloat(), 1.1f)
    background = GradientDrawable().apply {
        cornerRadius = dp(16).toFloat()
        setColor(0x3D6E8CE0)
        setStroke(dp(1), 0x40FFFFFF)
    }
    layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
        setMargins(dp(14), dp(8), dp(14), dp(2))
    }
}

/**
 * Fragment 通用页面容器：柔光渐变底 + 可滚动 + 底部留白。
 *
 * 状态栏 inset 由 MainActivity 统一在根布局吃掉，各页面只管内容。
 */
fun Context.pageScroll(build: (LinearLayout) -> Unit): ScrollView {
    val column = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(10), 0, dp(28))
    }
    build(column)
    return ScrollView(this).apply {
        addView(column)
        setBackgroundResource(R.drawable.bg_aurora)
        isFillViewport = true
        isVerticalScrollBarEnabled = false
    }
}

/**
 * 卡片内的可点击行：标题 + 副标题 + 右侧胶囊标签。
 * 列表类内容统一走这个，避免每个 Fragment 重复搭 LinearLayout。
 */
fun LinearLayout.addRow(
    title: String,
    subtitle: String? = null,
    badge: String? = null,
    onClick: (() -> Unit)? = null,
): LinearLayout {
    val ctx = context
    val row = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, ctx.dp(11), 0, ctx.dp(11))
    }
    val texts = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
    }
    texts.addView(ctx.title(title, 14f))
    subtitle?.let {
        texts.addView(ctx.caption(it, 11.5f).apply { setPadding(0, ctx.dp(3), 0, 0) })
    }
    row.addView(texts)
    badge?.let { row.addView(ctx.pill(it)) }
    if (onClick != null) {
        row.isClickable = true
        row.setOnClickListener { onClick() }
    }
    addView(row)
    return row
}

/** 卡片内的分隔线。玻璃风格下要极淡，用黑 8% 而不是实色灰。 */
fun LinearLayout.addDivider() {
    addView(View(context).apply {
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 1)
        setBackgroundColor(0x14000000)
    })
}

/**
 * 更新 addRow 生成那一行的副标题。
 * 走这个而不是调用方自己 getChildAt 强转，View 结构改动时只改一处。
 */
fun LinearLayout.updateRowSubtitle(text: String) {
    val texts = getChildAt(0) as? LinearLayout ?: return
    (texts.getChildAt(1) as? TextView)?.text = text
}
