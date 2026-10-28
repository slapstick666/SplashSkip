package com.splashskip.core.model

import kotlin.math.abs

/**
 * 框架无关的整数矩形。刻意不用 android.graphics.Rect，
 * 目的是让 :core 能在纯 JVM 单元测试里跑（不引 Robolectric）。
 */
data class IntRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width: Int get() = (right - left).coerceAtLeast(0)
    val height: Int get() = (bottom - top).coerceAtLeast(0)
    val area: Long get() = width.toLong() * height.toLong()
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2

    fun contains(x: Int, y: Int): Boolean = x in left..right && y in top..bottom

    fun contains(other: IntRect): Boolean =
        other.left >= left && other.top >= top && other.right <= right && other.bottom <= bottom

    fun distanceTo(cx: Int, cy: Int): Int = abs(centerX - cx) + abs(centerY - cy)

    companion object {
        val EMPTY = IntRect(0, 0, 0, 0)
    }
}

/**
 * 屏幕尺寸（像素）+ 屏幕密度，用于归一化区域换算、面积护栏、dp→px 换算。
 * density 默认 1f，方便单元测试里直接按 dp 当 px 算。
 */
data class ScreenSize(
    val width: Int,
    val height: Int,
    val density: Float = 1f,
) {
    val area: Long get() = width.toLong() * height.toLong()

    fun dpToPx(dp: Int): Int = (dp * density).toInt()
}
