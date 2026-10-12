package com.trae.simplebrowser

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.trae.simplebrowser.core.AsrTranscripts
import org.json.JSONObject
import java.io.File
import java.util.Locale

/** 批量转写结果页：一次分享多个音视频全部转完后统一呈现本批文件，
 *  底栏四个按钮对整批执行（复制全文=拼篇、分享、分享链接=逐条清单、移入资料夹=整批归夹）；
 *  点任一条目进单篇详情页。 */
class AsrBatchResultActivity : AppCompatActivity() {

    private data class Entry(
        val path: String,
        val name: String,
        val taskId: String,
        val optionsJson: String,
        var meta: String = ""
    )

    private val entries = ArrayList<Entry>()
    private lateinit var adapter: BaseAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_asr_batch_result)

        val paths = intent.getStringArrayListExtra("result_paths").orEmpty()
        val names = intent.getStringArrayListExtra("display_names").orEmpty()
        val taskIds = intent.getStringArrayListExtra("task_ids").orEmpty()
        val options = intent.getStringArrayListExtra("options_json").orEmpty()
        val failed = intent.getIntExtra("failed_count", 0)
        for (i in paths.indices) {
            entries.add(Entry(
                path = paths[i],
                name = names.getOrNull(i) ?: "录音${i + 1}",
                taskId = taskIds.getOrNull(i).orEmpty(),
                optionsJson = options.getOrNull(i).orEmpty()
            ))
        }
        if (entries.isEmpty()) {
            Toast.makeText(this, R.string.asr_result_missing, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        findViewById<TextView>(R.id.batch_title).text =
            getString(R.string.asr_batch_page_title, entries.size)
        findViewById<TextView>(R.id.batch_meta).apply {
            text = if (failed > 0) getString(R.string.asr_batch_page_failed_note, failed) else ""
            visibility = if (failed > 0) View.VISIBLE else View.GONE
        }

        adapter = object : BaseAdapter() {
            override fun getCount(): Int = entries.size
            override fun getItem(position: Int): Any = entries[position]
            override fun getItemId(position: Int): Long = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = convertView ?: layoutInflater.inflate(R.layout.asr_batch_item, parent, false)
                val e = entries[position]
                view.findViewById<TextView>(R.id.batch_item_name).text = e.name
                view.findViewById<TextView>(R.id.batch_item_meta).text = e.meta
                return view
            }
        }
        val list = findViewById<ListView>(R.id.asr_batch_list)
        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ -> openDetail(entries[position]) }

        findViewById<Button>(R.id.asr_btn_copy).setOnClickListener { copyOrShare(share = false) }
        findViewById<Button>(R.id.asr_btn_move_folder).setOnClickListener { moveToFolder() }
        findViewById<Button>(R.id.asr_btn_share).setOnClickListener { copyOrShare(share = true) }
        findViewById<Button>(R.id.asr_btn_share_link).setOnClickListener {
            AsrLinkBatch.generateAndShow(this, entries.map { it.name to it.taskId })
        }

        Thread {
            for (e in entries) e.meta = buildMeta(e)
            runOnUiThread {
                if (!isFinishing && !isDestroyed) adapter.notifyDataSetChanged()
            }
        }.start()
    }

    /** 单篇进详情页（与分享流程 openResult 同参数口径）。 */
    private fun openDetail(e: Entry) {
        val it = Intent(this, AsrResultActivity::class.java)
            .putExtra("result_path", e.path)
            .putExtra("filename", e.name)
            .putExtra("task_id", e.taskId)
        val opts = e.optionsJson.takeIf { it.isNotEmpty() }?.let {
            runCatching { JSONObject(it) }.getOrNull()
        }
        if (opts != null) {
            it.putExtra("opt_timestamps", opts.optBoolean("timestamps"))
                .putExtra("opt_diarize", opts.optBoolean("diarize"))
                .putExtra("opt_mandarin", opts.optBoolean("dialect2mandarin"))
        }
        startActivity(it)
    }

    /** 整批移入资料夹：与列表多选移入同一套归夹流程（选已有夹/新建）。 */
    private fun moveToFolder() {
        FolderAddFlow.moveBatch(this, entries.map { "task" to it.taskId })
    }

    /** 整批复制/分享：本地结果文件直接读，不走网络；单篇读取失败不阻断其余。 */
    private fun copyOrShare(share: Boolean) {
        Toast.makeText(this, getString(R.string.asr_batch_copy_progress, entries.size),
            Toast.LENGTH_SHORT).show()
        Thread {
            val ok = ArrayList<Pair<String, String>>(entries.size)
            val failedNames = ArrayList<String>()
            for (e in entries) {
                try {
                    val result = JSONObject(File(e.path).readText())
                    val opts = e.optionsJson.takeIf { it.isNotEmpty() }
                        ?.let { runCatching { JSONObject(it) }.getOrNull() }
                    ok.add(e.name to AsrTranscriptsJson.plainText(this, result, opts))
                } catch (ex: Exception) {
                    failedNames.add(e.name)
                }
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                val sb = StringBuilder(AsrTranscripts.joinDocuments(ok))
                if (failedNames.isNotEmpty()) {
                    if (sb.isNotEmpty()) sb.append("\n\n")
                    sb.append(getString(R.string.asr_batch_fetch_failed, failedNames.size,
                        failedNames.joinToString("、")))
                }
                if (share) {
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_TEXT, sb.toString())
                    startActivity(Intent.createChooser(send, getString(R.string.asr_btn_share)))
                } else {
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText(getString(R.string.asr_result_title),
                        sb.toString()))
                    Toast.makeText(this, getString(R.string.asr_batch_copied, ok.size),
                        Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    /** 条目副标题：时长与识别选项摘要（与详情页口径一致）。 */
    private fun buildMeta(e: Entry): String {
        val result = runCatching { JSONObject(File(e.path).readText()) }.getOrNull()
            ?: return getString(R.string.asr_batch_item_done)
        val opts = e.optionsJson.takeIf { it.isNotEmpty() }
            ?.let { runCatching { JSONObject(it) }.getOrNull() }
        val parts = mutableListOf<String>()
        val totalSec = result.optLong("duration_ms", 0L) / 1000
        if (totalSec > 0) {
            parts.add(String.format(Locale.CHINA, "时长 %d分%02d秒", totalSec / 60, totalSec % 60))
        }
        val diarize = opts?.optBoolean("diarize") ?: true
        val timestamps = opts?.optBoolean("timestamps") ?: true
        if (diarize) parts.add("区分说话人")
        if (timestamps) parts.add("含起止时间")
        if (result.optJSONArray("mandarin_segments") != null) parts.add("方言已译普通话")
        return if (parts.isEmpty()) getString(R.string.asr_batch_item_done) else parts.joinToString(" · ")
    }
}
