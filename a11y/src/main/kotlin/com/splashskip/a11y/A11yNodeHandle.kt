package com.splashskip.a11y

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import com.splashskip.core.model.NodeHandle
import com.splashskip.core.model.NodeSnapshot

/**
 * 系统无障碍节点的包装。所有对 AccessiblityNodeInfo 的写操作都收在这里，
 * :core 拿到的始终只是不可变快照。
 */
internal class A11yNodeHandle(
    val node: AccessibilityNodeInfo,
    private val service: AccessibilityService,
) : NodeHandle {

    private var bound: NodeSnapshot? = null

    fun bind(snapshot: NodeSnapshot) {
        bound = snapshot
    }

    override val snapshot: NodeSnapshot
        get() = bound ?: error("NodeSnapshot 尚未绑定")

    /**
     * 原生点击。
     *
     * 注意一个坑：performAction 返回 true 只代表「事件被投递了」，
     * 不代表 App 真的处理了。很多自定义 View 没实现 performClick，
     * 返回 true 但界面纹丝不动。所以引擎侧必须保留 clickCenter 兜底。
     */
    override fun click(): Boolean = runCatching {
        val target = nearestClickable(node, MAX_ANCESTOR_WALK) ?: node
        target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }.getOrDefault(false)

    override fun clickCenter(): Boolean {
        val b = snapshot.bounds
        if (b.width <= 0 || b.height <= 0) return false
        return tap(b.centerX.toFloat(), b.centerY.toFloat())
    }

    override fun back(): Boolean =
        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)

    private fun tap(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            // 45ms 足够被识别为点击，又不会触发长按
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 45L))
            .build()
        return service.dispatchGesture(gesture, null, null)
    }

    private fun nearestClickable(start: AccessibilityNodeInfo, maxHops: Int): AccessibilityNodeInfo? {
        if (start.isClickable) return start
        var p = start.parent
        var guard = 0
        while (p != null && guard++ < maxHops) {
            if (p.isClickable) return p
            val next = p.parent
            recycleQuietly(p)
            p = next
        }
        return null
    }

    companion object {
        private const val MAX_ANCESTOR_WALK = 6

        /** API 33 起 recycle 已废弃且是空操作，低版本才需要手动回收。 */
        fun recycleQuietly(n: AccessibilityNodeInfo?) {
            if (n == null || Build.VERSION.SDK_INT >= 33) return
            @Suppress("DEPRECATION")
            runCatching { n.recycle() }
        }
    }
}
