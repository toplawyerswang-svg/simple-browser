package com.trae.simplebrowser

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.util.Locale

/** 音视频识别要求页：生成分享链接前集中确认展示与回听要求、访问设置（取代旧的弹窗式选择）。
 *  选择用显式按钮组（支持/不支持、显示/隐藏），不用开关控件——MaterialSwitch 在部分鸿蒙设备上渲染/点击均不可靠。 */
class AsrShareOptionsActivity : AppCompatActivity() {

    private val ttlValues = intArrayOf(1, 24, 72, 168, 720)
    private val opensValues = intArrayOf(1, 5, 10, 0)
    private var allowPlay = true
    private var showNames = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_asr_share_options)

        val taskId = intent.getStringExtra("task_id")
        val hasDiarize = intent.getBooleanExtra("has_diarize", true)

        findViewById<TextView>(R.id.asr_req_recap).text = buildRecap(hasDiarize)

        val playYes = findViewById<Button>(R.id.req_segment_play_yes)
        val playNo = findViewById<Button>(R.id.req_segment_play_no)
        val namesYes = findViewById<Button>(R.id.req_show_names_yes)
        val namesNo = findViewById<Button>(R.id.req_show_names_no)

        playYes.setOnClickListener { allowPlay = true; styleChoice(playYes, playNo, true) }
        playNo.setOnClickListener { allowPlay = false; styleChoice(playYes, playNo, false) }
        namesYes.setOnClickListener { showNames = true; styleChoice(namesYes, namesNo, true) }
        namesNo.setOnClickListener { showNames = false; styleChoice(namesYes, namesNo, false) }

        // 识别时未区分说话人就没有名字可展示：锁定为隐藏并说明原因
        if (!hasDiarize) {
            showNames = false
            namesYes.isEnabled = false
            namesNo.isEnabled = false
            namesYes.alpha = 0.5f
            namesNo.alpha = 0.5f
            findViewById<TextView>(R.id.req_show_names_desc).setText(R.string.asr_req_show_names_disabled)
        }
        styleChoice(playYes, playNo, allowPlay)
        styleChoice(namesYes, namesNo, showNames)

        val ttlSpinner = findViewById<Spinner>(R.id.share_ttl_spinner)
        val opensSpinner = findViewById<Spinner>(R.id.share_opens_spinner)
        ttlSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            arrayOf("1小时", "24小时", "3天", "7天", "30天"))
        opensSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            arrayOf("1次", "5次", "10次", "不限"))
        // 默认值 = 上次分享时选择的参数（AppPrefs 全局记住，与文件夹分享共用）
        ttlSpinner.setSelection(ttlValues.indexOf(AppPrefs.getShareValidityHours(this))
            .let { if (it >= 0) it else 1 })
        opensSpinner.setSelection(opensValues.indexOf(AppPrefs.getShareMaxOpens(this))
            .let { if (it >= 0) it else 1 })

        findViewById<Button>(R.id.req_generate_btn).setOnClickListener {
            if (taskId.isNullOrEmpty()) {
                Toast.makeText(this, R.string.asr_no_task_id, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val hours = ttlValues[ttlSpinner.selectedItemPosition]
            val opens = opensValues[opensSpinner.selectedItemPosition]
            AppPrefs.setShareDefaults(this, hours, opens)
            generate(taskId, hours, opens, allowPlay, showNames)
        }
    }

    /** 选中项蓝底白字，未选项白底灰字，一眼可辨当前选择。 */
    private fun styleChoice(selected: Button, other: Button, selectedIsFirst: Boolean) {
        selected.setBackgroundResource(
            if (selectedIsFirst) R.drawable.bg_choice_selected else R.drawable.bg_choice_unselected)
        selected.setTextColor(if (selectedIsFirst) Color.WHITE else 0xFF666666.toInt())
        other.setBackgroundResource(
            if (selectedIsFirst) R.drawable.bg_choice_unselected else R.drawable.bg_choice_selected)
        other.setTextColor(if (selectedIsFirst) 0xFF666666.toInt() else Color.WHITE)
    }

    /** 识别设置回顾：识别已完成，本页选择的是分享页的展示要求。 */
    private fun buildRecap(hasDiarize: Boolean): String {
        val parts = mutableListOf<String>()
        if (hasDiarize) parts.add(getString(R.string.asr_opt_diarize))
        if (intent.getBooleanExtra("has_timestamps", true)) parts.add(getString(R.string.asr_req_recap_ts))
        if (intent.getBooleanExtra("has_mandarin", false)) parts.add(getString(R.string.asr_req_recap_mandarin))
        val body = if (parts.isEmpty()) getString(R.string.asr_req_recap_plain) else parts.joinToString(" · ")
        return getString(R.string.asr_req_recap_prefix, body)
    }

    private fun generate(taskId: String, hours: Int, opens: Int, allowPlay: Boolean, showNames: Boolean) {
        val btn = findViewById<Button>(R.id.req_generate_btn)
        btn.isEnabled = false
        Thread {
            try {
                val info = AsrClient.createShare(this, taskId, hours, opens, allowPlay, showNames)
                runOnUiThread {
                    btn.isEnabled = true
                    if (isFinishing) return@runOnUiThread
                    val opensText = if (info.maxOpens == 0) "不限打开次数" else "可打开${info.maxOpens}次"
                    val expireText = java.text.SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)
                        .format(java.util.Date(System.currentTimeMillis() + info.validityHours * 3600_000L))
                    val shareText = getString(R.string.asr_share_text, info.url, info.pwd) +
                        "\n有效至 $expireText（$opensText）"
                    AlertDialog.Builder(this)
                        .setTitle(R.string.asr_share_title)
                        .setMessage(getString(R.string.asr_share_msg, info.url, info.pwd,
                            humanValidity(info.validityHours), opensText))
                        .setNegativeButton(android.R.string.cancel, null)
                        .setNeutralButton(R.string.asr_btn_copy_link) { _, _ ->
                            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("share", shareText))
                            Toast.makeText(this, R.string.asr_share_copied, Toast.LENGTH_SHORT).show()
                        }
                        .setPositiveButton(R.string.asr_btn_share) { _, _ ->
                            val send = Intent(Intent.ACTION_SEND)
                                .setType("text/plain")
                                .putExtra(Intent.EXTRA_TEXT, shareText)
                            startActivity(Intent.createChooser(send, getString(R.string.asr_btn_share)))
                        }
                        .show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    btn.isEnabled = true
                    if (!isFinishing) {
                        Toast.makeText(this, getString(R.string.asr_failed_prefix) + (e.message ?: e.toString()), Toast.LENGTH_LONG).show()
                    }
                }
            }
        }.start()
    }

    private fun humanValidity(hours: Int): String = when {
        hours < 24 -> "${hours}小时"
        hours == 24 -> "24小时"
        else -> "${hours / 24}天"
    }
}
