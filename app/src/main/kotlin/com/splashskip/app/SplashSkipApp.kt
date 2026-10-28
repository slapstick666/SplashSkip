package com.splashskip.app

import android.app.Application
import com.splashskip.app.util.UpdateScheduler
import com.splashskip.rules.RuleRepositoryProvider

class SplashSkipApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // 提前把规则加载进内存，避免第一次开屏时在无障碍回调里做磁盘 IO
        RuleRepositoryProvider.get(this)
        UpdateScheduler.schedulePeriodic(this)
    }
}
