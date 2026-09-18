package com.trae.simplebrowser

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.trae.simplebrowser.core.HistoryEntry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 浏览历史页（F-15）：点击重访（回到主窗口加载），长按删除单条，支持一键清空。
 */
class HistoryActivity : AppCompatActivity() {

    private var entries: List<HistoryEntry> = emptyList()
    private lateinit var listView: ListView
    private lateinit var adapter: ArrayAdapter<HistoryEntry>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.history_title)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }
        val clear = Button(this).apply {
            text = getString(R.string.history_clear)
            setOnClickListener { confirmClearAll() }
        }
        root.addView(
            clear,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(dp(12), dp(8), dp(12), dp(4)) }
        )
        listView = ListView(this).apply {
            dividerHeight = 1
            divider = android.graphics.drawable.ColorDrawable(Color.parseColor("#DDDDDD"))
        }
        // simple_list_item_2 根布局是 TwoLineListItem 而非单个 TextView，
        // 必须重写 getView 手动绑定，否则 ArrayAdapter 渲染即崩
        val timeFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        adapter = object : ArrayAdapter<HistoryEntry>(this, android.R.layout.simple_list_item_2) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val row = convertView ?: layoutInflater.inflate(
                    android.R.layout.simple_list_item_2, parent, false
                )
                val entry = entries.getOrNull(position) ?: return row
                row.findViewById<TextView>(android.R.id.text1).text = entry.title
                row.findViewById<TextView>(android.R.id.text2).text = timeFmt.format(Date(entry.ts))
                return row
            }

            override fun getCount(): Int = entries.size
        }
        listView.adapter = adapter
        listView.setOnItemClickListener { _, _, position, _ ->
            entries.getOrNull(position)?.let { entry ->
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(entry.url), this, MainActivity::class.java)
                startActivity(intent)
                finish()
            }
        }
        listView.setOnItemLongClickListener { _, _, position, _ ->
            entries.getOrNull(position)?.let { entry ->
                AppPrefs.removeHistory(this, entry.url)
                reload()
                Toast.makeText(this, R.string.history_removed, Toast.LENGTH_SHORT).show()
            }
            true
        }
        root.addView(
            listView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        val empty = TextView(this).apply {
            text = getString(R.string.history_empty)
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#888888"))
            textSize = 15f
            setPadding(0, dp(40), 0, 0)
            setTypeface(typeface, Typeface.ITALIC)
        }
        addContentView(
            empty,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        listView.emptyView = empty
        setContentView(root)
        reload()
    }

    private fun reload() {
        entries = AppPrefs.getHistory(this)
        adapter.notifyDataSetChanged()
    }

    private fun confirmClearAll() {
        AlertDialog.Builder(this)
            .setTitle(R.string.history_clear)
            .setMessage(R.string.history_clear_confirm)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                AppPrefs.clearHistory(this)
                reload()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
