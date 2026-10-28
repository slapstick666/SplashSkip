package com.splashskip.app.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import com.splashskip.a11y.AppConfigStore
import com.splashskip.a11y.ClickMode
import com.splashskip.a11y.ShizukuBridge
import com.splashskip.a11y.SnapshotStore
import com.splashskip.app.BuildConfig
import com.splashskip.app.util.A11yUtils
import com.splashskip.rules.RuleRepositoryProvider

/**
 * 设置页：管权限、管数据、看版本。
 *
 * 「点击通道」是这一页最重要的部分 —— 它决定点击走哪条路：
 *   · 无障碍：兼容性最好，但要开系统权限，国产 ROM 常有「受限制的设置」拦截
 *   · Shizuku：走 shell 注入，延迟更低，不依赖无障碍常驻，但要用户手动装 Shizuku 并授权
 */
class SettingsFragment : Fragment() {

    private lateinit var config: AppConfigStore

    private var powerRow: LinearLayout? = null
    private var notifyRow: LinearLayout? = null
    private var accessibilityRow: LinearLayout? = null
    private var shizukuRow: LinearLayout? = null
    private var clickModeRow: LinearLayout? = null
    private var snapshotRow: LinearLayout? = null
    private var aboutText: android.widget.TextView? = null

    private val notifyLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { render() }

    /** Shizuku 授权结果回调。必须是稳定的引用，否则 add/remove 对不上。 */
    private val shizukuCallback: () -> Unit = { render() }

    override fun onCreateView(
        inflater: LayoutInflater,
        parent: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        config = AppConfigStore.get(requireContext())
        val ctx = requireContext()

        return ctx.pageScroll { col ->
            // ---- 授权方式 ----
            val authCard = ctx.card()
            authCard.addView(ctx.title("点击通道"))
            authCard.addView(ctx.gap(2))

            accessibilityRow = authCard.addRow("无障碍服务", "") {
                A11yUtils.openAccessibilitySettings(ctx)
            }
            authCard.addDivider()

            shizukuRow = authCard.addRow("Shizuku", "") { onShizukuClicked() }
            authCard.addDivider()

            clickModeRow = authCard.addRow("当前通道", "") { cycleClickMode() }

            authCard.addView(ctx.gap(6))
            authCard.addView(ctx.caption(
                "自动：Shizuku 就绪时优先用它，否则回退无障碍。" +
                    "Shizuku 走 shell 注入，延迟比无障碍低 100ms 左右。",
            ))
            col.addView(authCard)

            // ---- 权限与后台 ----
            val permCard = ctx.card()
            permCard.addView(ctx.title("权限与后台"))
            permCard.addView(ctx.gap(2))

            powerRow = permCard.addRow("电池优化白名单", "") {
                if (A11yUtils.isIgnoringBatteryOptimizations(ctx)) {
                    A11yUtils.openAppDetails(ctx)
                } else {
                    A11yUtils.requestIgnoreBatteryOptimizations(requireActivity())
                }
            }
            permCard.addDivider()
            notifyRow = permCard.addRow("通知权限", "") {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    !A11yUtils.notificationGranted(ctx)
                ) {
                    notifyLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    A11yUtils.openAppDetails(ctx)
                }
            }
            col.addView(permCard)

            // ---- 数据 ----
            val dataCard = ctx.card()
            dataCard.addView(ctx.title("数据"))
            dataCard.addView(ctx.gap(2))

            snapshotRow = dataCard.addRow("界面快照", "") {
                startActivity(Intent(ctx, SnapshotActivity::class.java))
            }
            dataCard.addDivider()
            dataCard.addRow("命中日志", "排查「为什么没跳过」的唯一依据") {
                startActivity(Intent(ctx, LogActivity::class.java))
            }
            dataCard.addDivider()
            dataCard.addRow("规则管理", "查看每条规则的匹配链路") {
                startActivity(Intent(ctx, RuleListActivity::class.java))
            }
            col.addView(dataCard)

            // ---- 关于 ----
            val aboutCard = ctx.card()
            aboutCard.addView(ctx.title("关于"))
            aboutCard.addView(ctx.gap(6))
            aboutText = ctx.body("")
            aboutCard.addView(aboutText)
            col.addView(aboutCard)

            col.addView(ctx.banner(
                "本工具仅供个人学习与自用。2023 年 8 月，多款同类工具因《反不正当竞争法》" +
                    "第二条、第十二条第二款第四项收到律师函并下架。本项目明确边界：" +
                    "不修改第三方应用、不拦截网络、不注入代码，只代替用户点击屏幕上已有的按钮。",
            ))
        }
    }

    override fun onResume() {
        super.onResume()
        ShizukuBridge.addPermissionResultListener(shizukuCallback)
        render()
    }

    override fun onPause() {
        ShizukuBridge.removePermissionResultListener(shizukuCallback)
        super.onPause()
    }

    /** hide/show 不触发 onResume，切回来要手动刷新（权限状态可能在别处改过）。 */
    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) render()
    }

    override fun onDestroyView() {
        powerRow = null
        notifyRow = null
        accessibilityRow = null
        shizukuRow = null
        clickModeRow = null
        snapshotRow = null
        aboutText = null
        super.onDestroyView()
    }

    private fun render() {
        val ctx = context ?: return
        val repo = RuleRepositoryProvider.get(ctx)

        accessibilityRow?.updateRowSubtitle(
            if (A11yUtils.isServiceEnabled(ctx)) "已开启" else "未开启，点击去系统设置",
        )

        shizukuRow?.updateRowSubtitle(shizukuText())

        clickModeRow?.updateRowSubtitle(clickModeText())

        powerRow?.updateRowSubtitle(
            if (A11yUtils.isIgnoringBatteryOptimizations(ctx)) "已加入白名单" else "未加入，可能被系统冻结",
        )
        notifyRow?.updateRowSubtitle(
            if (A11yUtils.notificationGranted(ctx)) "已授权" else "未授权，学习模式收不到询问",
        )
        snapshotRow?.let { row ->
            val store = SnapshotStore(ctx)
            row.updateRowSubtitle("已保存 ${store.list().size} 份，共 ${store.totalSize() / 1024} KB")
        }

        aboutText?.text = buildString {
            append("版本　").append(BuildConfig.VERSION_NAME).append('\n')
            append("规则　").append(repo.currentIndex().size).append(" 条\n")
            append("引擎　v1（ECDSA 规则签名校验已启用）\n\n")
            append("参考 GKD 的规则化思路实现。规则与引擎分离，")
            append("远程规则必须通过签名校验才会加载。")
        }
    }

    private fun shizukuText(): String {
        val ctx = context ?: return ""
        return when (ShizukuBridge.state(ctx)) {
            ShizukuBridge.State.NOT_INSTALLED -> "未安装，点击查看说明"
            ShizukuBridge.State.NOT_RUNNING -> "服务未运行，需用 adb 或 root 启动"
            ShizukuBridge.State.NO_PERMISSION -> "未授权，点击申请"
            ShizukuBridge.State.READY -> "已就绪"
        }
    }

    private fun clickModeText(): String = when (config.clickMode) {
        ClickMode.AUTO -> "自动（优先 Shizuku）"
        ClickMode.ACCESSIBILITY -> "仅无障碍"
        ClickMode.SHIZUKU -> "仅 Shizuku"
    }

    private fun onShizukuClicked() {
        val ctx = context ?: return
        when (ShizukuBridge.state(ctx)) {
            ShizukuBridge.State.NOT_INSTALLED ->
                toast("请先安装 Shizuku（GitHub: RikkaApps/Shizuku），再用 adb 或 root 启动其服务")

            ShizukuBridge.State.NOT_RUNNING ->
                toast("Shizuku 服务未运行：用 adb shell sh /sdcard/Android/data/moe.shizuku.privileged.api/start.sh 启动")

            ShizukuBridge.State.NO_PERMISSION ->
                ShizukuBridge.requestPermission(SHIZUKU_REQUEST_CODE)

            ShizukuBridge.State.READY ->
                toast("Shizuku 已就绪，当前通道：${clickModeText()}")
        }
    }

    private fun cycleClickMode() {
        config.clickMode = when (config.clickMode) {
            ClickMode.AUTO -> ClickMode.ACCESSIBILITY
            ClickMode.ACCESSIBILITY -> ClickMode.SHIZUKU
            ClickMode.SHIZUKU -> ClickMode.AUTO
        }
        toast("点击通道：${clickModeText()}")
        render()
    }

    private fun toast(msg: String) {
        Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()
    }

    private companion object {
        const val SHIZUKU_REQUEST_CODE = 0x5A1
    }
}
