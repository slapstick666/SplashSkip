package com.splashskip.a11y

import android.content.Context
import com.google.gson.Gson
import com.splashskip.core.engine.HitLogger
import com.splashskip.core.engine.HitRecord
import java.io.File

/**
 * 命中日志。
 *
 * 这东西看着不起眼，但它是整个项目「能用」和「好用」的分水岭。
 * 用户遇到「这个 App 跳不过」的时候，如果没有任何记录，
 * 排查只能靠抓包/抓无障碍事件，门槛极高。
 *
 * 用 JSONL（每行一条 JSON）而不是 Room：
 * 1. 追加写，不阻塞；
 * 2. 崩了最多丢最后一行，不会毁掉整个库；
 * 3. 用户可以直接把文件发给规则作者，一步到位。
 *
 * 容量策略：超过 512KB 就只保留最近 800 条重写一次，
 * 保证长期使用不会把用户存储吃满。
 */
class HitLogStore(context: Context) : HitLogger {

    private val dir = File(context.filesDir, "logs")
    private val file = File(dir, "hits.jsonl")
    private val gson = Gson()
    private val lock = Any()

    override fun log(record: HitRecord) {
        synchronized(lock) {
            runCatching {
                if (!dir.exists()) dir.mkdirs()
                if (file.length() > MAX_BYTES) rotate()
                file.appendText(gson.toJson(record) + "\n")
            }
        }
    }

    /** 倒序读取最近 [limit] 条。 */
    fun tail(limit: Int = 200): List<HitRecord> {
        synchronized(lock) {
            if (!file.exists()) return emptyList()
            return runCatching {
                file.readLines()
                    .asReversed()
                    .take(limit)
                    .mapNotNull { line ->
                        runCatching { gson.fromJson(line, HitRecord::class.java) }.getOrNull()
                    }
            }.getOrDefault(emptyList())
        }
    }

    fun clear() {
        synchronized(lock) { runCatching { file.delete() } }
    }

    fun exportTo(target: File): Boolean {
        synchronized(lock) {
            return runCatching {
                if (!file.exists()) return false
                target.writeBytes(file.readBytes())
                true
            }.getOrDefault(false)
        }
    }

    private fun rotate() {
        val keep = file.readLines().takeLast(KEEP_LINES)
        file.writeText(keep.joinToString("\n", postfix = if (keep.isEmpty()) "" else "\n"))
    }

    companion object {
        private const val MAX_BYTES = 512L * 1024
        private const val KEEP_LINES = 800

        @Volatile
        private var instance: HitLogStore? = null

        /**
         * 单例同样是为了锁生效 —— 服务在 worker 线程写、UI 在主线程序列化读，
         * 两份实例各持一把锁等于没锁，可能读到写了一半的行。
         */
        fun get(context: Context): HitLogStore =
            instance ?: synchronized(this) {
                instance ?: HitLogStore(context.applicationContext).also { instance = it }
            }
    }
}
