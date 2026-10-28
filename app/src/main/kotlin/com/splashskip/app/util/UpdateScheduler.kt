package com.splashskip.app.util

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.splashskip.rules.RuleUpdateWorker
import java.util.concurrent.TimeUnit

/**
 * 规则更新调度。
 *
 * 周期定 12 小时而不是更短：开屏广告规则的失效率本来就不高
 * （一个 App 的跳过按钮文案不会天天变），12 小时足够，
 * 又不会因为频繁唤醒被系统降权。
 */
object UpdateScheduler {

    fun schedulePeriodic(context: Context) {
        val request = PeriodicWorkRequestBuilder<RuleUpdateWorker>(12, TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .build()

        runCatching {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                RuleUpdateWorker.UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }

    fun runNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<RuleUpdateWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .build()
        runCatching { WorkManager.getInstance(context).enqueue(request) }
    }
}
