package com.splashskip.app.ui

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.splashskip.app.R

/**
 * 主容器：底部 4 个 tab + 一个 Fragment 容器。
 *
 * 两个关键点：
 *
 * 1. **安全区域**。targetSdk 35 在 Android 15 上会强制 edge-to-edge，
 *    内容默认绘制到状态栏和导航栏下面 —— 不处理 inset 的话，
 *    页面顶部文字会直接跟状态栏的时钟、电量图标重叠。
 *    这里统一在根布局吃掉 top/left/right inset，底部交给 BottomNavigationView 自己吃，
 *    这样导航栏的白色背景能延伸到系统导航条后面，视觉上不留色块接缝。
 *
 * 2. **Fragment 用 hide/show 而不是 replace**。replace 会销毁上一个页面，
 *    切回来时滚动位置、列表状态全丢。hide/show 保留所有页面的 View 状态，
 *    代价是 4 个页面同时占内存 —— 对这种配置型 App 完全可以接受。
 */
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val root = findViewById<View>(R.id.root)
        val bottomNav = findViewById<BottomNavigationView>(R.id.bottom_nav)

        applyWindowInsets(root, bottomNav)

        bottomNav.setOnItemSelectedListener { item ->
            switchTo(item.itemId)
            true
        }

        // 首次进入才默认选中首页；旋转屏幕重建时 FragmentManager 已恢复原有选中页
        if (savedInstanceState == null) {
            bottomNav.selectedItemId = R.id.tab_home
        }
    }

    private fun applyWindowInsets(root: View, bottomNav: BottomNavigationView) {
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            v.setPadding(bars.left, bars.top, bars.right, 0)
            bottomNav.updatePadding(bottom = bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
    }

    private fun switchTo(itemId: Int) {
        val fm = supportFragmentManager
        val tag = TAG_PREFIX + itemId
        val tx = fm.beginTransaction()

        // 统一 hide 全部再 show 目标。不判断 isHidden 是因为
        // 进程重建后 FragmentManager 恢复的可见性状态不一定符合预期，
        // 无脑 hide 一遍最稳。
        fm.fragments.toList().forEach { tx.hide(it) }

        val existing = fm.findFragmentByTag(tag)
        if (existing == null) {
            tx.add(R.id.fragment_container, createFragment(itemId), tag)
        } else {
            tx.show(existing)
        }
        tx.commit()
    }

    private fun createFragment(itemId: Int): Fragment = when (itemId) {
        R.id.tab_subs -> SubsFragment()
        R.id.tab_apps -> AppsFragment()
        R.id.tab_settings -> SettingsFragment()
        else -> HomeFragment()
    }

    private companion object {
        const val TAG_PREFIX = "tab_"
    }
}
