package com.trae.simplebrowser

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.media.MediaPlayer
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.tabs.TabLayout
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

/** 录音转写结果页：按说话人分段展示，可切换原文/普通话译文，支持复制、分享与说话人标注。 */
class AsrResultActivity : AppCompatActivity() {

    private data class Segment(val speaker: Int, val begin: String, val end: String, val text: String,
                               val beginMs: Long, val endMs: Long)

    private data class SpeakerInfo(
        val name: String?,
        val gender: String,
        val hasEmbedding: Boolean,
        val matchIsUser: Boolean,
        val f0: Int
    )

    private var originalSegments: List<Segment> = emptyList()
    private var mandarinSegments: List<Segment>? = null
    private var showMandarin = false
    private var hasTimestamps = true
    private var hasDiarize = true
    private var taskId: String? = null
    private var resultPath: String? = null
    private var aiSummary = ""
    private lateinit var aiTodos: org.json.JSONArray
    private var aiDone = false
    private var speakerInfo: Map<Int, SpeakerInfo> = emptyMap()
    private val fallbackNames = LinkedHashMap<Int, String>()
    private val colorOrder = LinkedHashMap<Int, Int>()
    private var player: MediaPlayer? = null
    private var playingKey = -1L
    private var playingView: View? = null
    private val speakerColors = intArrayOf(0xFF1565C0.toInt(), 0xFFC62828.toInt(), 0xFF2E7D32.toInt(), 0xFF6A1B9A.toInt())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_asr_result)

        val path = intent.getStringExtra("result_path")
        val result = if (path != null) runCatching { JSONObject(File(path).readText()) }.getOrNull() else null
        if (result == null) {
            Toast.makeText(this, R.string.asr_result_missing, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        resultPath = path

        hasTimestamps = intent.getBooleanExtra("opt_timestamps", true)
        hasDiarize = intent.getBooleanExtra("opt_diarize", true)
        taskId = intent.getStringExtra("task_id")
        originalSegments = parseSegments(result.optJSONArray("segments"))
        mandarinSegments = result.optJSONArray("mandarin_segments")?.let { parseSegments(it) }
        showMandarin = mandarinSegments != null
        parseSpeakerNames(result.optJSONObject("speaker_names"))
        aiSummary = result.optString("summary")
        aiTodos = result.optJSONArray("todos") ?: org.json.JSONArray()
        aiDone = result.optBoolean("summary_done", aiSummary.isNotEmpty())

        val filename = intent.getStringExtra("display_name")
            ?: intent.getStringExtra("filename")
            ?: getString(R.string.asr_result_title)
        findViewById<TextView>(R.id.asr_title).text = filename
        val durationMs = result.optLong("duration_ms", 0L)
        findViewById<TextView>(R.id.asr_meta).text = buildMeta(durationMs)

        val toggle = findViewById<Button>(R.id.asr_toggle_lang)
        if (mandarinSegments != null) {
            toggle.visibility = View.VISIBLE
            toggle.setOnClickListener {
                showMandarin = !showMandarin
                render()
            }
        }
        findViewById<Button>(R.id.asr_btn_copy).setOnClickListener { copyOrShare(share = false) }
        findViewById<Button>(R.id.asr_btn_share).setOnClickListener { copyOrShare(share = true) }
        findViewById<Button>(R.id.asr_btn_share_link).setOnClickListener { shareLink() }

        val tabs = findViewById<TabLayout>(R.id.asr_tabs)
        tabs.addTab(tabs.newTab().setText(R.string.asr_tab_result))
        tabs.addTab(tabs.newTab().setText(R.string.asr_tab_ai))
        val pageResult = findViewById<View>(R.id.page_result)
        val pageAi = findViewById<View>(R.id.page_ai)
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                val ai = tab.position == 1
                pageResult.visibility = if (ai) View.GONE else View.VISIBLE
                pageAi.visibility = if (ai) View.VISIBLE else View.GONE
                if (ai) renderAiPage()
            }

            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {
                if (tab.position == 1) renderAiPage()
            }
        })

        render()
    }

    private fun parseSpeakerNames(obj: JSONObject?) {
        if (obj == null) {
            speakerInfo = emptyMap()
            return
        }
        val map = HashMap<Int, SpeakerInfo>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val id = k.toIntOrNull() ?: continue
            val o = obj.optJSONObject(k) ?: continue
            map[id] = SpeakerInfo(
                name = o.optString("name").takeIf { it.isNotEmpty() },
                gender = o.optString("gender"),
                hasEmbedding = o.has("embedding"),
                matchIsUser = o.optString("match") == "user",
                f0 = o.optInt("f0", 0)
            )
        }
        speakerInfo = map
    }

    /** 展示名：已标注用名字（附性别），未标注按出现顺序编号。 */
    private fun speakerDisplayLabel(id: Int): String {
        if (!hasDiarize) return ""
        val info = speakerInfo[id]
        if (info?.name != null) {
            return if (info.gender.isNotEmpty()) "${info.name}（${info.gender}）" else info.name
        }
        val fallback = fallbackNames.getOrPut(id) {
            getString(R.string.asr_speaker_label, fallbackNames.size + 1)
        }
        val gender = info?.gender.orEmpty()
        return if (gender.isNotEmpty()) "$fallback（$gender）" else fallback
    }

    /** 分享链接：进入「音视频识别要求」页集中选择展示要求与访问设置，再生成带提取码的链接。 */
    private fun shareLink() {
        val tid = taskId
        if (tid.isNullOrEmpty()) {
            Toast.makeText(this, R.string.asr_no_task_id, Toast.LENGTH_SHORT).show()
            return
        }
        startActivity(Intent(this, AsrShareOptionsActivity::class.java)
            .putExtra("task_id", tid)
            .putExtra("has_diarize", hasDiarize)
            .putExtra("has_timestamps", hasTimestamps)
            .putExtra("has_mandarin", mandarinSegments != null))
    }

    private fun parseSegments(arr: JSONArray): List<Segment> {
        val list = ArrayList<Segment>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            list.add(
                Segment(
                    speaker = o.optInt("speaker", 0),
                    begin = o.optString("begin"),
                    end = o.optString("end"),
                    text = o.optString("text"),
                    beginMs = o.optLong("begin_ms"),
                    endMs = o.optLong("end_ms")
                )
            )
        }
        return list
    }

    private fun buildMeta(durationMs: Long): String {
        val totalSec = durationMs / 1000
        val mm = totalSec / 60
        val ss = totalSec % 60
        val parts = mutableListOf(if (mm > 0) String.format(Locale.CHINA, "时长 %d分%02d秒", mm, ss) else "时长 ${totalSec}秒")
        if (hasDiarize) parts.add("区分说话人")
        if (hasTimestamps) parts.add("含起止时间")
        if (mandarinSegments != null) parts.add("方言已译普通话")
        return parts.joinToString(" · ")
    }

    private fun activeSegments(): List<Segment> =
        if (showMandarin) mandarinSegments ?: originalSegments else originalSegments

    private fun speakerColor(id: Int): Int {
        val index = colorOrder[id] ?: 0
        return speakerColors[index % speakerColors.size]
    }

    private fun render() {
        val container = findViewById<LinearLayout>(R.id.asr_container)
        container.removeAllViews()
        fallbackNames.clear()
        // 颜色按说话人首次出现顺序分配：同一说话人全程同色，跨设备/分享页规则一致
        colorOrder.clear()
        for (seg in activeSegments()) {
            if (seg.speaker !in colorOrder) colorOrder[seg.speaker] = colorOrder.size
        }
        val toggle = findViewById<Button>(R.id.asr_toggle_lang)
        toggle.setText(if (showMandarin) R.string.asr_btn_show_original else R.string.asr_btn_show_mandarin)
        val density = resources.displayMetrics.density
        for (seg in activeSegments()) {
            val block = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, (10 * density).toInt(), 0, (10 * density).toInt())
            }
            val labelParts = mutableListOf<String>()
            val speaker = speakerDisplayLabel(seg.speaker)
            if (speaker.isNotEmpty()) labelParts.add(speaker)
            if (hasTimestamps) labelParts.add("${seg.begin} - ${seg.end}")
            if (labelParts.isNotEmpty()) {
                block.addView(TextView(this).apply {
                    text = labelParts.joinToString("   ")
                    textSize = 12f
                    setTextColor(if (hasDiarize) speakerColor(seg.speaker) else 0xFF8A8A8A.toInt())
                    typeface = Typeface.DEFAULT_BOLD
                    if (hasDiarize) {
                        // 点击说话人标签即可标注名字（声纹库自动识别的"？"名也可在此确认）
                        background = null
                        setPadding(0, (6 * density).toInt(), (12 * density).toInt(), (6 * density).toInt())
                        setOnClickListener { showLabelDialog(seg.speaker) }
                    }
                })
            }
            block.addView(TextView(this).apply {
                text = seg.text
                textSize = 15f
                setTextColor(0xFF212121.toInt())
                setLineSpacing(2 * density, 1f)
                setTextIsSelectable(true)
                if (taskId != null && seg.endMs > seg.beginMs) {
                    // 与分享页一致的点段回听
                    setOnClickListener { toggleSegmentPlay(seg, this) }
                }
            })
            container.addView(block)
        }
        if (activeSegments().isEmpty()) {
            container.addView(TextView(this).apply {
                text = getString(R.string.asr_result_empty)
                textSize = 14f
                gravity = Gravity.CENTER
                setPadding(0, (40 * density).toInt(), 0, 0)
            })
        }
    }

    /** 标注说话人：输入名字（可从声纹库已有人名快捷选择），确认后并入声纹库。 */
    private fun showLabelDialog(speakerId: Int) {
        val info = speakerInfo[speakerId]
        val view = layoutInflater.inflate(R.layout.dialog_label_speaker, null)
        val nameInput = view.findViewById<EditText>(R.id.label_name_input)
        nameInput.setText(info?.name?.trimEnd('？') ?: "")
        val suggestionRow = view.findViewById<LinearLayout>(R.id.label_suggestions)
        val genderGroup = view.findViewById<RadioGroup>(R.id.label_gender_group)
        val genderHint = view.findViewById<TextView>(R.id.label_gender_hint)
        // 默认选中声纹自动判断的性别，弹窗里写明判断依据，方便有把握地纠正
        when (info?.gender) {
            "男" -> genderGroup.check(R.id.label_gender_male)
            "女" -> genderGroup.check(R.id.label_gender_female)
            else -> genderGroup.check(R.id.label_gender_auto)
        }
        val f0 = info?.f0 ?: 0
        genderHint.text = if (f0 > 0) {
            getString(R.string.asr_label_auto_gender, info?.gender?.ifEmpty { "不确定" } ?: "不确定", f0)
        } else {
            getString(R.string.asr_label_auto_gender_none)
        }
        var suggestionsLoaded = false
        nameInput.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable) {
                if (!suggestionsLoaded && s.isNotEmpty()) suggestionRow.removeAllViews()
            }

            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.asr_label_title)
            .setView(view)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.asr_label_confirm) { _, _ ->
                val name = nameInput.text.toString().trim()
                if (name.isNotEmpty()) {
                    val gender = when (genderGroup.checkedRadioButtonId) {
                        R.id.label_gender_male -> "男"
                        R.id.label_gender_female -> "女"
                        else -> ""
                    }
                    submitLabel(speakerId, name, gender)
                }
            }
            .show()
        Thread {
            val names = runCatching { AsrClient.listVoiceprints(this) }.getOrNull().orEmpty()
            runOnUiThread {
                if (isFinishing || dialog.isShowing.not() || names.isEmpty()) return@runOnUiThread
                suggestionsLoaded = true
                val density = resources.displayMetrics.density
                for (n in names.take(6)) {
                    val chip = Button(this).apply {
                        text = n
                        textSize = 12f
                        minWidth = 0
                        minimumWidth = 0
                        setPadding((12 * density).toInt(), 0, (12 * density).toInt(), 0)
                        isAllCaps = false
                        setOnClickListener { nameInput.setText(n) }
                    }
                    suggestionRow.addView(chip)
                }
            }
        }.start()
    }

    private fun submitLabel(speakerId: Int, name: String, gender: String) {
        val tid = taskId
        if (tid.isNullOrEmpty()) {
            Toast.makeText(this, R.string.asr_no_task_id, Toast.LENGTH_SHORT).show()
            return
        }
        Thread {
            try {
                val updated = AsrClient.labelSpeaker(this, tid, speakerId, name, gender)
                resultPath?.let { p ->
                    runCatching { File(p).writeText(updated.toString(), Charsets.UTF_8) }
                }
                runOnUiThread {
                    if (isFinishing) return@runOnUiThread
                    parseSpeakerNames(updated.optJSONObject("speaker_names"))
                    render()
                    Toast.makeText(this, R.string.asr_label_saved, Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (!isFinishing) {
                        Toast.makeText(this, getString(R.string.asr_failed_prefix) + (e.message ?: e.toString()), Toast.LENGTH_LONG).show()
                    }
                }
            }
        }.start()
    }

    /** 点击段落回听对应原音（服务端按毫秒切片返回 mp3），再次点击暂停/继续。 */
    private fun toggleSegmentPlay(seg: Segment, view: View) {
        val tid = taskId
        if (tid.isNullOrEmpty()) {
            Toast.makeText(this, R.string.asr_no_audio, Toast.LENGTH_SHORT).show()
            return
        }
        val key = seg.beginMs * 419585L + seg.endMs
        val p = player
        if (p != null && playingKey == key) {
            if (p.isPlaying) p.pause() else p.start()
            return
        }
        stopSegmentPlayback()
        playingKey = key
        playingView = view
        view.setBackgroundColor(0xFFE8F0FE.toInt())
        player = MediaPlayer().apply {
            setAudioAttributes(
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            setDataSource(AsrClient.taskAudioUrl(this@AsrResultActivity, tid, seg.beginMs, seg.endMs))
            setOnPreparedListener { it.start() }
            setOnCompletionListener { clearPlayHighlight() }
            setOnErrorListener { _, what, extra ->
                android.util.Log.e("AsrResult", "playback error what=$what extra=$extra")
                clearPlayHighlight()
                Toast.makeText(this@AsrResultActivity, R.string.asr_playback_failed, Toast.LENGTH_SHORT).show()
                true
            }
            prepareAsync()
        }
    }

    private fun clearPlayHighlight() {
        playingView?.setBackgroundColor(0x00000000)
        playingView = null
        playingKey = -1L
    }

    private fun stopSegmentPlayback() {
        player?.run {
            runCatching { stop() }
            release()
        }
        player = null
        clearPlayHighlight()
    }

    override fun onDestroy() {
        player?.release()
        player = null
        super.onDestroy()
    }

    /** AI 摘要与待办标签页：有内容展示内容，未生成提供一键生成。 */
    private fun renderAiPage() {
        val container = findViewById<LinearLayout>(R.id.asr_ai_container)
        container.removeAllViews()
        val density = resources.displayMetrics.density
        if (aiSummary.isNotEmpty() || (aiTodos != null && aiTodos.length() > 0)) {
            container.addView(Button(this).apply {
                text = getString(R.string.asr_copy_ai)
                setOnClickListener { copyAiText() }
            })
            if (aiSummary.isNotEmpty()) {
                container.addView(aiHeader("AI 摘要", density))
                container.addView(TextView(this).apply {
                    text = aiSummary
                    textSize = 14f
                    setTextColor(0xFF212121.toInt())
                    setLineSpacing(2 * density, 1f)
                    setTextIsSelectable(true)
                })
            }
            if (aiTodos != null && aiTodos.length() > 0) {
                container.addView(aiHeader("待办事项", density))
                for (i in 0 until aiTodos.length()) {
                    val t = aiTodos.optJSONObject(i) ?: continue
                    val row = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = android.view.Gravity.CENTER_VERTICAL
                        setPadding(0, (4 * density).toInt(), 0, (4 * density).toInt())
                    }
                    row.addView(CheckBox(this).apply {
                        text = t.optString("task")
                        textSize = 14f
                        setTextColor(0xFF212121.toInt())
                    })
                    val extra = listOf(t.optString("owner"), t.optString("due"))
                        .filter { it.isNotEmpty() && it != "未指定" }
                        .joinToString(" · ")
                    if (extra.isNotEmpty()) {
                        row.addView(TextView(this).apply {
                            text = extra
                            textSize = 11f
                            setTextColor(0xFF8A8A8A.toInt())
                            setPadding((6 * density).toInt(), 0, 0, 0)
                        })
                    }
                    container.addView(row)
                }
            }
        } else if (aiDone) {
            container.addView(TextView(this).apply {
                text = getString(R.string.asr_ai_none)
                textSize = 14f
                setTextColor(0xFF8A8A8A.toInt())
                setPadding(0, (10 * density).toInt(), 0, 0)
            })
        } else {
            container.addView(TextView(this).apply {
                text = getString(R.string.asr_ai_gen_hint)
                textSize = 13f
                setTextColor(0xFF8A8A8A.toInt())
                setPadding(0, (10 * density).toInt(), 0, (10 * density).toInt())
            })
            val genBtn = Button(this).apply {
                text = getString(R.string.asr_ai_gen_btn)
                isEnabled = taskId != null
            }
            container.addView(genBtn)
            if (taskId == null) return
            genBtn.setOnClickListener {
                genBtn.isEnabled = false
                val statusTag = TextView(this).apply {
                    text = getString(R.string.asr_ai_generating)
                    textSize = 12f
                    setTextColor(0xFF8A8A8A.toInt())
                    tag = "gen_status"
                    setPadding(0, (6 * density).toInt(), 0, 0)
                }
                container.addView(statusTag)
                Thread {
                    val tid = taskId ?: return@Thread
                    try {
                        val updated = AsrClient.generateSummary(this, tid)
                        resultPath?.let { path ->
                            runCatching {
                                val full = JSONObject(File(path).readText())
                                full.put("result", updated)
                                File(path).writeText(full.toString(), Charsets.UTF_8)
                            }
                        }
                        runOnUiThread {
                            if (isFinishing || isDestroyed) return@runOnUiThread
                            aiSummary = updated.optString("summary")
                            aiTodos = updated.optJSONArray("todos") ?: org.json.JSONArray()
                            aiDone = true
                            renderAiPage()
                            Toast.makeText(this, R.string.asr_ai_done, Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: Exception) {
                        runOnUiThread {
                            if (isFinishing || isDestroyed) return@runOnUiThread
                            genBtn.isEnabled = true
                            statusTag.setText(
                                getString(R.string.asr_failed_prefix) + (e.message ?: e.toString()))
                        }
                    }
                }.start()
            }
        }
    }

    private fun aiHeader(text: String, density: Float): TextView = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTextColor(0xFF1565C0.toInt())
        setTypeface(null, Typeface.BOLD)
        setPadding(0, (12 * density).toInt(), 0, (4 * density).toInt())
    }

    private fun copyAiText() {
        val sb = StringBuilder()
        sb.append("【AI 摘要】").append("\n").append(aiSummary)
        if (aiTodos != null && aiTodos.length() > 0) {
            sb.append("\n【待办事项】\n")
            for (i in 0 until aiTodos.length()) {
                val t = aiTodos.optJSONObject(i) ?: continue
                sb.append(i + 1).append(". ").append(t.optString("task"))
                val owner = t.optString("owner")
                val due = t.optString("due")
                val extra = listOf(owner, due).filter { it.isNotEmpty() && it != "未指定" }
                if (extra.isNotEmpty()) {
                    sb.append("（").append(extra.joinToString(" · ")).append("）")
                }
                sb.append("\n")
            }
        }
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("ai", sb.toString()))
        Toast.makeText(this, R.string.asr_copied, Toast.LENGTH_SHORT).show()
    }

    private fun buildPlainFullText(): String {
        val sb = StringBuilder()
        for (seg in activeSegments()) {
            val speaker = speakerDisplayLabel(seg.speaker)
            if (speaker.isNotEmpty()) sb.append('[').append(speaker).append(']')
            if (hasTimestamps) sb.append('[').append(seg.begin).append('-').append(seg.end).append(']')
            sb.append(seg.text).append('\n')
        }
        return sb.toString().trim()
    }

    private fun copyOrShare(share: Boolean) {
        val text = buildPlainFullText()
        if (share) {
            val send = Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, text)
            startActivity(Intent.createChooser(send, getString(R.string.asr_btn_share)))
        } else {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText(getString(R.string.asr_result_title), text))
            Toast.makeText(this, R.string.asr_copied, Toast.LENGTH_SHORT).show()
        }
    }
}
