package com.splashskip.app.ui

import android.graphics.Typeface
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.splashskip.a11y.SnapshotStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 快照列表。
 *
 * 这个页面存在的理由：写规则时最耗时的一步是「搞清楚目标界面的节点树长什么样」。
 * 没有它，只能靠猜文案、猜 id，改一次试一次。
 * 有了它，可以直接看到 `跳过` 按钮挂在哪个容器下、有没有 viewId、坐标在哪。
 */
class SnapshotActivity : AppCompatActivity() {

    private lateinit var store: SnapshotStore
    private lateinit var adapter: SimpleAdapter
    private lateinit var header: TextView

    private val stamp = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = SnapshotStore(this)

        header = body("").apply { setPadding(dp(16), dp(14), dp(16), dp(10)) }

        val rv = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@SnapshotActivity)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f,
            )
        }
        adapter = SimpleAdapter(emptyList()) { item ->
            (item.payload as? File)?.let { showDetail(it) }
        }
        rv.adapter = adapter

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), 0, dp(12), dp(12))
            addView(action("清空全部") { confirmClear() })
        }

        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(header)
                addView(rv)
                addView(actions)
            },
        )
        reload()
    }

    private fun reload() {
        val files = store.list()
        header.text = if (files.isEmpty()) {
            "还没有快照。\n\n回主界面点「抓取界面快照」，5 秒内切到目标 App，" +
                "就能把那个界面的完整节点树存下来。"
        } else {
            "共 ${files.size} 份快照，占用 ${store.totalSize() / 1024} KB。点击查看节点树。"
        }

        adapter.submit(
            files.map { f ->
                SimpleAdapter.Item(
                    title = prettyPkg(f),
                    subtitle = "${stamp.format(Date(f.lastModified()))}　${f.length() / 1024} KB",
                    badge = "查看",
                    payload = f,
                )
            },
        )
    }

    /** 文件名形如 com_taobao_taobao_20261027_115500.json，还原成包名。 */
    private fun prettyPkg(f: File): String {
        val base = f.nameWithoutExtension
        val parts = base.split('_')
        return if (parts.size > 2) {
            parts.dropLast(2).joinToString(".")
        } else {
            base
        }
    }

    private fun showDetail(file: File) {
        val doc = store.read(file)
        if (doc == null) {
            toast("读取失败，文件可能已损坏")
            return
        }

        val full = store.renderTree(doc)
        val lines = full.lines()
        val shown = if (lines.size > 1200) {
            lines.take(1200).joinToString("\n") + "\n\n... 余下 ${lines.size - 1200} 行已省略"
        } else {
            full
        }

        val textView = TextView(this).apply {
            text = shown
            textSize = 10f
            typeface = Typeface.MONOSPACE
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setTextIsSelectable(true)
        }

        AlertDialog.Builder(this)
            .setTitle(file.name)
            .setView(ScrollView(this).apply { addView(textView) })
            .setPositiveButton("关闭", null)
            .setNeutralButton("删除") { _, _ ->
                store.delete(file)
                reload()
            }
            .show()
    }

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setTitle("清空全部快照？")
            .setMessage("会删除所有已保存的节点树文件，不可恢复。")
            .setPositiveButton("清空") { _, _ ->
                store.clear()
                reload()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun toast(msg: String) {
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
    }
}
