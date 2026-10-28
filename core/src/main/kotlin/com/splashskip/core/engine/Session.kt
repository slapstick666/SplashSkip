package com.splashskip.core.engine

import com.splashskip.core.model.AppRule

/**
 * 一次「开屏」的生命周期。
 *
 * 为什么需要它？因为开屏广告不是瞬间就绪的：
 *   T+0ms    SplashActivity 起来，节点树里只有一个空 FrameLayout
 *   T+300ms  广告 SDK 异步回包，插入 ImageView + 倒计时 TextView
 *   T+500ms  「跳过 3」按钮才真正可点
 * 如果只在 WINDOW_STATE_CHANGED 时匹配一次，90% 的 App 都跳不过。
 * 所以用 Session 承载「持续尝试」的语义，而不是一次性判断。
 */
internal class Session(
    val pkg: String,
    val activity: String?,
    val rule: AppRule,
    val startedAt: Long,
) {
    var attempts: Int = 0
    var lastAttemptAt: Long = Long.MIN_VALUE

    fun isSameWindow(pkg: String, activity: String?): Boolean =
        this.pkg == pkg && this.activity == activity
}
