package com.splashskip.app.util

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.splashskip.a11y.SplashSkipService

/**
 * 无障碍服务状态检测与各种权限引导。
 *
 * 为什么不用 AccessibilityManager.getEnabledAccessibilityServiceList()？
 * 那个方法在部分 ROM 上会因为服务刚启动而返回旧数据，
 * 直接读 Settings.Secure 的字符串更准，GKD 也是这么做的。
 */
object A11yUtils {

    fun isServiceEnabled(context: Context): Boolean = runCatching {
        val expected = ComponentName(context, SplashSkipService::class.java)
        val raw = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false

        val enabledFlag = Settings.Secure.getInt(
            context.contentResolver,
            Settings.Secure.ACCESSIBILITY_ENABLED,
            0,
        ) == 1
        if (!enabledFlag) return false

        raw.split(':').any { entry ->
            val cn = ComponentName.unflattenFromString(entry) ?: return@any false
            cn.packageName == expected.packageName && cn.className == expected.className
        }
    }.getOrDefault(false)

    /** 跳系统无障碍设置页。各 ROM 的入口不同，但 ACTION_ACCESSIBILITY_SETTINGS 基本都能到。 */
    fun openAccessibilitySettings(context: Context) {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    fun openAppDetails(context: Context) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.fromParts("package", context.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(PowerManager::class.java) ?: return false
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * 请求加入电池优化白名单。
     *
     * 注意：这个对话框在部分 ROM 上会被改写甚至直接不弹。
     * 如果启动失败，回退到「应用详情页」让用户手动设置省电策略为「无限制」。
     */
    fun requestIgnoreBatteryOptimizations(activity: Activity) {
        val ok = runCatching {
            activity.startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.fromParts("package", activity.packageName, null)),
            )
            true
        }.getOrDefault(false)
        if (!ok) openAppDetails(activity)
    }

    /** 通知权限只影响学习模式的确认提示，不给也能用（只是收不到询问）。 */
    fun notificationGranted(context: Context): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.POST_NOTIFICATIONS,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
}
