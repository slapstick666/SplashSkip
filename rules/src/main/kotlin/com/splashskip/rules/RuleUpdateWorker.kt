package com.splashskip.rules

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf

/**
 * 规则定时更新。
 *
 * 用 WorkManager 而不是 AlarmManager 的原因：
 * 1. 不需要精确时刻，12 小时一次足够；
 * 2. 系统会做批量调度，比自建闹钟省电；
 * 3. 不用申请精确闹钟权限（Android 12+ 那个 SCHEDULE_EXACT_ALARM 很麻烦）。
 *
 * 注意 WorkManager 的最小周期是 15 分钟，我们用 12 小时。
 */
class RuleUpdateWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val repo = RuleRepositoryProvider.get(applicationContext)
        val updater = SubscriptionUpdater(repo)
        val store = SubscriptionStore.get(applicationContext)
        val prefs = applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        // 只同步用户启用的订阅。内置规则不走网络，用户规则是本地文件。
        val entries = store.list().filter { it.enabled }
        if (entries.isEmpty()) return Result.success()

        var updated = 0
        var failed = 0

        for (entry in entries) {
            when (val r = updater.sync(entry)) {
                is SyncResult.Updated -> {
                    updated++
                    store.markResult(entry.id, null, r.version, r.ruleCount)
                    Log.i(TAG, "订阅 ${entry.displayName} 更新到 v${r.version}，${r.ruleCount} 条规则")
                }
                SyncResult.UpToDate -> {
                    // 也算检查过了，刷新时间戳让用户在列表里看到「刚刚检查」
                    store.markResult(entry.id, null)
                }
                is SyncResult.Failed -> {
                    failed++
                    store.markResult(entry.id, r.reason)
                    Log.w(TAG, "订阅 ${entry.displayName} 更新失败：${r.reason}")
                }
            }
        }

        prefs.edit()
            .putLong(KEY_LAST_UPDATE, System.currentTimeMillis())
            .apply()

        return Result.success(
            workDataOf(KEY_UPDATED to updated, KEY_FAILED to failed),
        )
    }

    companion object {
        private const val TAG = "RuleUpdateWorker"
        const val PREFS = "splashskip_rules"
        const val KEY_LAST_UPDATE = "last_update_at"
        const val KEY_UPDATED = "updated"
        const val KEY_FAILED = "failed"
        const val UNIQUE_NAME = "rule-update-periodic"
    }
}
