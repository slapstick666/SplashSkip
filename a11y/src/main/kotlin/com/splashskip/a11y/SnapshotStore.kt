package com.splashskip.a11y

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 快照仓库。
 *
 * 存成明文 JSON 而不是二进制：这份数据本来就是给人看、给规则作者抄的，
 * 明文可以直接拷到电脑上 grep，也不需要额外解码工具。
 *
 * 容量策略：最多留 50 份，超了删最旧的。一份完整树 JSON 通常 50~300 KB。
 */
class SnapshotStore(context: Context) {

    private val dir = File(context.filesDir, "snapshots")
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
    private val lock = Any()

    fun save(doc: SnapshotDoc): File? = synchronized(lock) {
        runCatching {
            if (!dir.exists()) dir.mkdirs()
            val safePkg = doc.pkg.ifEmpty { "unknown" }.replace('.', '_')
            val file = File(dir, "${safePkg}_${stamp.format(Date(doc.capturedAt))}.json")
            file.writeText(gson.toJson(doc))
            pruneIfNeeded()
            file
        }.onFailure { Log.e(TAG, "保存快照失败", it) }.getOrNull()
    }

    fun list(): List<File> = synchronized(lock) {
        dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()
    }

    fun read(file: File): SnapshotDoc? = runCatching {
        gson.fromJson(file.readText(), SnapshotDoc::class.java)
    }.onFailure { Log.e(TAG, "读取快照失败", it) }.getOrNull()

    fun delete(file: File): Boolean = synchronized(lock) {
        runCatching { file.delete() }.getOrDefault(false)
    }

    fun clear() = synchronized(lock) {
        dir.listFiles()?.forEach { runCatching { it.delete() } }
    }

    fun totalSize(): Long = dir.listFiles()?.sumOf { it.length() } ?: 0L

    private fun pruneIfNeeded() {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: return
        if (files.size <= MAX_FILES) return
        files.sortedBy { it.lastModified() }
            .take(files.size - MAX_FILES)
            .forEach { runCatching { it.delete() } }
    }

    /**
     * 渲染成缩进文本。
     *
     * 没用 JSON 原样展示，因为没人愿意在手机上读一坨 JSON。
     * 这个格式是照着「一眼能看出父子关系 + 能直接抄选择器」设计的。
     */
    fun renderTree(doc: SnapshotDoc): String {
        val sb = StringBuilder()
        sb.append("包名: ").append(doc.pkg).append('\n')
        doc.activity?.let { sb.append("界面: ").append(it).append('\n') }
        sb.append("屏幕: ").append(doc.screenWidth).append('x').append(doc.screenHeight)
        sb.append(" @").append(doc.density).append("x\n")
        sb.append("节点: ").append(doc.nodeCount)
        if (doc.truncated) sb.append("（已截断）")
        sb.append("\n\n")

        fun render(node: SnapshotNode, depth: Int) {
            repeat(depth) { sb.append("  ") }
            sb.append(node.cls.substringAfterLast('.'))
            node.vid?.let { sb.append(" #").append(it) }
            node.text?.let { sb.append("  text=\"").append(it.take(40)).append('"') }
            node.desc?.let { sb.append("  desc=\"").append(it.take(40)).append('"') }
            if (node.clickable) sb.append("  [可点]")
            if (!node.enabled) sb.append("  [禁用]")
            if (!node.visible) sb.append("  [不可见]")
            sb.append("  (").append(node.l).append(',').append(node.t).append(',')
                .append(node.r).append(',').append(node.b).append(")\n")
            node.children.forEach { render(it, depth + 1) }
        }

        doc.root?.let { render(it, 0) } ?: sb.append("(空树)")
        return sb.toString()
    }

    private companion object {
        const val TAG = "SnapshotStore"
        const val MAX_FILES = 50
    }
}
