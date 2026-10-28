package com.splashskip.app.ui

import android.os.Bundle
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.splashskip.a11y.HitLogStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 命中日志页。这是排查问题的唯一入口 ——
 * 「为什么这个 App 没跳过」在这里能直接看到是被护栏拦了、还是压根没匹配上。
 */
class LogActivity : AppCompatActivity() {

    private val formatter = SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val store = HitLogStore.get(this)
        val records = store.tail(300)

        val summary = body("").apply { setPadding(dp(2), dp(2), dp(2), dp(2)) }
        summary.text = if (records.isEmpty()) {
            "还没有任何记录。\n\n开启无障碍服务后，打开任意带开屏广告的 App，这里会记录每一次匹配。"
        } else {
            val ok = records.count { it.success }
            "最近 ${records.size} 条记录：成功 $ok 条，失败/拦截 ${records.size - ok} 条"
        }

        val rv = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@LogActivity)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f,
            )
        }

        val adapter = SimpleAdapter(
            records.map {
                SimpleAdapter.Item(
                    title = "${it.ruleName}　「${it.matchedText}」",
                    subtitle = "${formatter.format(Date(it.at))}　${it.pkg}" +
                        if (it.note.isNotEmpty()) "\n${it.note}" else "",
                    badge = if (it.success) "已跳" else "未跳",
                )
            },
        )
        rv.adapter = adapter

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(14), dp(8), dp(14), dp(12))
            addView(
                action("清空日志") { confirmClear(store) },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
            )
        }

        val root = pageRoot("命中日志")
        root.addView(card().apply { addView(summary) })
        root.addView(rv)
        root.addView(actions)
        setContentView(root)
        applyPageInsets(root, actions)
    }

    /** 清空是不可恢复的，必须先问一句 —— 原来点一下就清，误触代价太大。 */
    private fun confirmClear(store: HitLogStore) {
        GlassDialog.Builder(this)
            .title("清空命中日志？")
            .message("会删除全部命中记录，不可恢复。")
            .negative("取消")
            .positive("清空") {
                store.clear()
                recreate()
            }
            .show()
    }
}
