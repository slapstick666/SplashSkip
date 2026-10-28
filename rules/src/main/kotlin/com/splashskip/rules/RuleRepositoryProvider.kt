package com.splashskip.rules

import android.content.Context

/**
 * RuleRepository 的进程内单例。
 *
 * 为什么必须有这个？因为仓库内部持有一份 volatile 规则快照，
 * 如果无障碍服务和 UI 各 new 一个，后台更新完规则之后服务端那份还是旧的，
 * 用户会看到「规则更新了但没生效」这种极难排查的问题。
 */
object RuleRepositoryProvider {

    @Volatile
    private var instance: RuleRepository? = null

    fun get(context: Context): RuleRepository =
        instance ?: synchronized(this) {
            instance ?: RuleRepository(context.applicationContext).also { instance = it }
        }
}
