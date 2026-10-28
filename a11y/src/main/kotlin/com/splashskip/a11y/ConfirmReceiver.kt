package com.splashskip.a11y

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import com.splashskip.core.engine.AppMode
import com.splashskip.rules.RuleRepositoryProvider

/**
 * 处理通知栏上「以后自动跳 / 不用了」两个按钮。
 *
 * 这里不做任何 UI，纯改配置 —— 因为 BroadcastReceiver 的运行时间很短，
 * 弹 Activity 会被系统拦（Android 10+ 后台启动 Activity 限制）。
 */
class ConfirmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val ruleId = intent.getStringExtra(EXTRA_RULE_ID) ?: return
        val pkg = intent.getStringExtra(EXTRA_PKG) ?: return

        val repo = RuleRepositoryProvider.get(context)
        val config = AppConfigStore.get(context)

        when (intent.action) {
            ACTION_CONFIRM -> {
                repo.setConfirmed(pkg, true)
                config.setMode(pkg, AppMode.AUTO)
            }
            ACTION_IGNORE -> {
                // 标记为已确认 + 禁用，避免下次再弹
                repo.setConfirmed(pkg, true)
                config.setMode(pkg, AppMode.DISABLED)
            }
        }

        runCatching { NotificationManagerCompat.from(context).cancel(pkg.hashCode()) }
    }

    companion object {
        const val ACTION_CONFIRM = "com.splashskip.action.CONFIRM"
        const val ACTION_IGNORE = "com.splashskip.action.IGNORE"
        const val EXTRA_RULE_ID = "rule_id"
        const val EXTRA_PKG = "pkg"
    }
}
