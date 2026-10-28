package com.splashskip.a11y

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.splashskip.core.model.AppRule

/**
 * 半自动模式的载体。
 *
 * 为什么用通知而不是悬浮窗？因为悬浮窗要 SYSTEM_ALERT_WINDOW 权限，
 * 这个权限在国产 ROM 上申请成功率很低，而且用户看到「要显示在其他应用上层」
 * 这个描述基本就劝退了。通知栏是零额外权限的方案（Android 13+ 只需通知权限）。
 */
object ConfirmNotifier {

    const val CHANNEL_ID = "splashskip.confirm"
    private const val MIN_INTERVAL_MS = 5 * 60_000L

    private val lastShownAt = HashMap<String, Long>()

    fun show(context: Context, rule: AppRule) {
        val now = SystemClock.elapsedRealtime()
        synchronized(lastShownAt) {
            val last = lastShownAt[rule.id]
            if (last != null && now - last < MIN_INTERVAL_MS) return
            lastShownAt[rule.id] = now
        }

        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        ensureChannel(context, nm)

        val pkg = rule.packages.firstOrNull().orEmpty()

        val confirmPi = PendingIntent.getBroadcast(
            context,
            rule.id.hashCode(),
            Intent(context, ConfirmReceiver::class.java).apply {
                action = ConfirmReceiver.ACTION_CONFIRM
                putExtra(ConfirmReceiver.EXTRA_RULE_ID, rule.id)
                putExtra(ConfirmReceiver.EXTRA_PKG, pkg)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val ignorePi = PendingIntent.getBroadcast(
            context,
            rule.id.hashCode() + 1,
            Intent(context, ConfirmReceiver::class.java).apply {
                action = ConfirmReceiver.ACTION_IGNORE
                putExtra(ConfirmReceiver.EXTRA_RULE_ID, rule.id)
                putExtra(ConfirmReceiver.EXTRA_PKG, pkg)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(context.applicationInfo.icon)
            .setContentTitle("检测到「${rule.name}」的开屏广告")
            .setContentText("启用后，以后打开这个 App 会自动跳过")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .addAction(0, "以后自动跳", confirmPi)
            .addAction(0, "不用了", ignorePi)
            .build()

        runCatching { nm.notify(rule.id.hashCode(), notification) }
    }

    private fun ensureChannel(context: Context, nm: NotificationManager) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "开屏跳过确认",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "学习模式下，发现新的可跳过开屏时询问你一次"
            },
        )
    }
}
