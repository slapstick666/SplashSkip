package com.splashskip.a11y

import android.os.Handler

/**
 * 重试调度器。
 *
 * 核心语义是「合并」：同一个窗口在短时间内产生的事件风暴，
 * 只应该触发一次匹配。做法是每次 request 都先撤销上一个待执行任务，
 * 再挂新的 —— 也就是典型的 debounce。
 *
 * 但要注意一个反直觉的点：重试**不能**用 debounce 的语义。
 * 如果一个 App 的开屏页在 2 秒内不停地刷 contentChanged（广告倒计时就会这样），
 * debounce 会导致回调永远不执行（一直被推后），
 * 表现就是「广告倒计时一直在走，规则就是不点」。
 *
 * 所以这里分成两条路径：
 *   - [debounce]：事件驱动，可以合并、可以被后续事件推迟
 *   - [schedule]：引擎驱动的重试，到点必执行，不被后续事件推迟
 */
internal class RetryScheduler(private val handler: Handler) {

    private var pendingDebounce: Runnable? = null
    private var pendingRetry: Runnable? = null

    /** 事件驱动：合并抖动，最后一次事件之后 [delayMs] 执行。 */
    fun debounce(delayMs: Long, action: () -> Unit) {
        pendingDebounce?.let { handler.removeCallbacks(it) }
        val r = Runnable {
            pendingDebounce = null
            action()
        }
        pendingDebounce = r
        handler.postDelayed(r, delayMs.coerceAtLeast(0L))
    }

    /** 引擎驱动：到点必执行。新的重试会取代旧的（同一窗口只需要一个重试）。 */
    fun schedule(delayMs: Long, action: () -> Unit) {
        pendingRetry?.let { handler.removeCallbacks(it) }
        val r = Runnable {
            pendingRetry = null
            action()
        }
        pendingRetry = r
        handler.postDelayed(r, delayMs.coerceAtLeast(0L))
    }

    fun cancelAll() {
        pendingDebounce?.let { handler.removeCallbacks(it) }
        pendingRetry?.let { handler.removeCallbacks(it) }
        pendingDebounce = null
        pendingRetry = null
    }
}
