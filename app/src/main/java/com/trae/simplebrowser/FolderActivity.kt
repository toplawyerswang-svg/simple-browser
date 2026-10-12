package com.trae.simplebrowser

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.BaseAdapter
import android.widget.ListView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.trae.simplebrowser.databinding.ActivityFolderBinding
import java.io.File
import java.util.Locale

/**
 * 资料夹管理页：录音转写与微信聊天记录的跨类分组，支持聚合分享（目录+逐条查看）。
 * 列表态（全部文件夹）↔ 详情态（成员管理）在同一 Activity 内切换，BACK 逐级返回；
 * 数据实时拉服务端，本地不落盘（与设置页两个列表同一数据源）。
 */
class FolderActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFolderBinding
    private var folders: List<AsrClient.FolderInfo> = emptyList()
    private var detail: AsrClient.FolderDetail? = null
    private val adapter = FolderAdapter()

    // 列表态关键词搜索（按文件夹名过滤，本地实时）
    private var folderQuery = ""

    private fun folderFiltered(): List<AsrClient.FolderInfo> =
        if (folderQuery.isBlank()) folders
        else folders.filter { it.name.contains(folderQuery, ignoreCase = true) }

    private var previewWebView: WebView? = null

    private val ttlValues = intArrayOf(1, 24, 72, 168, 720)
    private val opensValues = intArrayOf(1, 5, 10, 0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFolderBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.btnBack.setOnClickListener {
            if (detail != null) exitDetail() else finish()
        }
        binding.btnNew.setOnClickListener {
            if (detail == null) promptCreateFolder() else showAddItemsPicker()
        }
        binding.btnShare.setOnClickListener { promptShareOptions() }
        binding.btnMore.setOnClickListener { showDetailMenu() }
        binding.folderList.adapter = adapter
        binding.folderList.setOnItemClickListener { _, _, pos, _ ->
            if (detail == null) {
                folderFiltered().getOrNull(pos)?.let(::openFolder)
            } else {
                detail?.entries?.getOrNull(pos)?.let(::openEntry)
            }
        }
        binding.folderList.setOnItemLongClickListener { _, _, pos, _ ->
            if (detail == null) {
                folderFiltered().getOrNull(pos)?.let(::showFolderMenu)
            } else {
                detail?.entries?.getOrNull(pos)?.let(::confirmRemoveEntry)
            }
            true
        }
        binding.folderSearchInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                folderQuery = s?.toString().orEmpty()
                if (detail == null) {
                    adapter.notifyDataSetChanged()
                    renderFolderEmpty()
                }
            }
        })
        loadList()
    }

    override fun onBackPressed() {
        if (previewVisible()) {
            closePreview()
            return
        }
        if (detail != null) {
            exitDetail()
            return
        }
        super.onBackPressed()
    }

    // ---------- 列表态 / 详情态切换与数据加载

    private fun loadList() {
        applyChrome()
        Thread {
            try {
                val list = AsrClient.listFolders(this)
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    folders = list
                    adapter.notifyDataSetChanged()
                    renderFolderEmpty()
                }
            } catch (e: Exception) {
                uiError(e)
            }
        }.start()
    }

    /** 列表态空态：无文件夹给引导文案；有数据但搜索无匹配给换词提示。 */
    private fun renderFolderEmpty() {
        val visible = folderFiltered().isEmpty()
        binding.folderList.visibility = if (visible) View.GONE else View.VISIBLE
        binding.folderEmptyHint.visibility = if (visible) View.VISIBLE else View.GONE
        binding.folderEmptyHint.text = when {
            folders.isEmpty() -> getString(R.string.folder_list_empty)
            else -> getString(R.string.search_no_match, folderQuery)
        }
    }

    private fun loadDetail() {
        applyChrome()
        val d = detail ?: return
        Thread {
            try {
                val fresh = AsrClient.folderDetail(this, d.folderId)
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    detail = fresh
                    binding.tvTitle.text = fresh.name
                    adapter.notifyDataSetChanged()
                    val empty = fresh.entries.isEmpty()
                    binding.folderList.visibility = if (empty) View.GONE else View.VISIBLE
                    binding.folderEmptyHint.visibility = if (empty) View.VISIBLE else View.GONE
                    binding.folderEmptyHint.text = getString(R.string.folder_detail_empty)
                }
            } catch (e: Exception) {
                uiError(e)
            }
        }.start()
    }

    private fun applyChrome() {
        val d = detail
        if (d == null) {
            binding.tvTitle.text = getString(R.string.folder_title)
            binding.btnNew.text = getString(R.string.folder_new)
            binding.btnNew.visibility = View.VISIBLE
            binding.btnShare.visibility = View.GONE
            binding.btnMore.visibility = View.GONE
            binding.folderSearchInput.visibility = View.VISIBLE
            binding.folderEmptyHint.text = getString(R.string.folder_list_empty)
        } else {
            binding.tvTitle.text = d.name
            // 详情态：在夹内向里添加外面的录音/聊天记录（多选）
            binding.btnNew.text = getString(R.string.folder_add_items)
            binding.btnNew.visibility = View.VISIBLE
            binding.btnShare.visibility = View.VISIBLE
            binding.btnMore.visibility = View.VISIBLE
            binding.folderSearchInput.visibility = View.GONE
        }
    }

    private fun openFolder(f: AsrClient.FolderInfo) {
        detail = AsrClient.FolderDetail(f.folderId, f.name, emptyList())
        loadDetail()
    }

    private fun exitDetail() {
        detail = null
        loadList()
    }

    private inner class FolderAdapter : BaseAdapter() {
        override fun getCount(): Int =
            if (detail == null) folderFiltered().size else (detail?.entries?.size ?: 0)

        override fun getItem(position: Int): Any =
            if (detail == null) folderFiltered()[position] else detail!!.entries[position]

        override fun getItemId(position: Int): Long = position.toLong()

        override fun getViewTypeCount(): Int = 2

        override fun getItemViewType(position: Int): Int = if (detail == null) 0 else 1

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            if (detail == null) {
                val v = convertView ?: LayoutInflater.from(this@FolderActivity)
                    .inflate(R.layout.folder_list_item, parent, false)
                val f = folderFiltered()[position]
                v.findViewById<TextView>(R.id.item_name).text = f.name
                v.findViewById<TextView>(R.id.item_meta).text =
                    getString(R.string.folder_item_count, f.itemCount)
                return v
            }
            val v = convertView ?: LayoutInflater.from(this@FolderActivity)
                .inflate(R.layout.folder_entry_item, parent, false)
            val e = detail!!.entries[position]
            v.findViewById<TextView>(R.id.item_icon).text =
                if (e.kind == "task") "🎙" else "💬"
            v.findViewById<TextView>(R.id.item_name).text = e.title
            val meta = v.findViewById<TextView>(R.id.item_meta)
            if (e.alive) {
                meta.text = e.meta
                meta.setTextColor(0xFF8A8A8A.toInt())
            } else {
                meta.text = e.error ?: getString(R.string.folder_item_dead)
                meta.setTextColor(0xFFD32F2F.toInt())
            }
            return v
        }
    }

    // ---------- 文件夹内添加：挑外面的录音/聊天记录（多选） ----------

    /** 挑选行：header 非空为分组标题行；否则为可勾选条目。 */
    private data class PickRow(
        val header: String?,
        val kind: String,
        val ref: String,
        val name: String,
        val meta: String
    )

    private fun pickKey(r: PickRow) = "${r.kind}:${r.ref}"

    /** 打开挑选面板：列出未归入任何资料夹的转写完成录音 + 有效聊天记录（根目录条目），
     *  勾选后批量加入；已归档条目不出现——一条记录只归属一个文件夹。 */
    private fun showAddItemsPicker() {
        val d = detail ?: return
        Thread {
            try {
                val folders = AsrClient.listFolders(this)
                val filed = folders.flatMap { it.items }.toSet()
                val tasks = AsrClient.listTasks(this)
                val shares = AsrClient.listWxChatShares(this)
                val asrRows = ArrayList<PickRow>()
                for (i in 0 until tasks.length()) {
                    val o = tasks.optJSONObject(i) ?: continue
                    val id = o.optString("task_id")
                    if (id.isEmpty() || o.optString("status") != "SUCCEEDED") continue
                    if (("task" to id) in filed) continue  // 已归档（含本夹）：不可再挑
                    val sec = o.optLong("duration_ms") / 1000
                    val meta = buildString {
                        append(if (o.optBoolean("is_video")) getString(R.string.folder_pick_video)
                               else getString(R.string.folder_pick_audio))
                        if (sec > 0) append(" · %d分%02d秒".format(sec / 60, sec % 60))
                    }
                    asrRows.add(PickRow(null, "task", id,
                        o.optString("name", "未命名录音"), meta))
                }
                val wxRows = ArrayList<PickRow>()
                for (i in 0 until shares.length()) {
                    val o = shares.optJSONObject(i) ?: continue
                    val sid = o.optString("share_id")
                    if (sid.isEmpty() || ("wxchat" to sid) in filed) continue
                    wxRows.add(PickRow(null, "wxchat", sid,
                        o.optString("title", "聊天记录"),
                        getString(R.string.folder_pick_msg_count, o.optInt("message_count", 0))))
                }
                val rows = ArrayList<PickRow>()
                if (asrRows.isNotEmpty()) {
                    rows.add(PickRow(getString(R.string.folder_pick_section_asr), "", "", "", ""))
                    rows.addAll(asrRows)
                }
                if (wxRows.isNotEmpty()) {
                    rows.add(PickRow(getString(R.string.folder_pick_section_wx), "", "", "", ""))
                    rows.addAll(wxRows)
                }
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    if (rows.isEmpty()) {
                        toast(R.string.folder_pick_empty)
                        return@runOnUiThread
                    }
                    showPickDialog(d, rows)
                }
            } catch (e: Exception) {
                uiError(e)
            }
        }.start()
    }

    private fun showPickDialog(d: AsrClient.FolderDetail, rows: List<PickRow>) {
        val checked = LinkedHashSet<String>()
        val listView = ListView(this)
        listView.divider = null
        val adapter = object : BaseAdapter() {
            override fun getCount(): Int = rows.size
            override fun getItem(position: Int): Any = rows[position]
            override fun getItemId(position: Int): Long = position.toLong()
            override fun getViewTypeCount(): Int = 2
            override fun getItemViewType(position: Int): Int =
                if (rows[position].header != null) 0 else 1
            override fun isEnabled(position: Int): Boolean = rows[position].header == null

            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val row = rows[position]
                if (row.header != null) {
                    val tv = convertView as? TextView ?: TextView(this@FolderActivity).apply {
                        setPadding(dp(20), dp(12), dp(20), dp(4))
                        setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
                        setTextColor(0xFF8A8A8A.toInt())
                    }
                    tv.text = row.header
                    return tv
                }
                val v = convertView ?: LayoutInflater.from(this@FolderActivity)
                    .inflate(R.layout.folder_pick_item, parent, false)
                v.findViewById<TextView>(R.id.pick_icon).text =
                    if (row.kind == "task") "🎙" else "💬"
                v.findViewById<TextView>(R.id.pick_name).text = row.name
                v.findViewById<TextView>(R.id.pick_meta).text = row.meta
                v.findViewById<CheckBox>(R.id.pick_check).isChecked = pickKey(row) in checked
                return v
            }
        }
        listView.adapter = adapter
        listView.setOnItemClickListener { _, _, position, _ ->
            val row = rows[position]
            if (row.header != null) return@setOnItemClickListener
            val k = pickKey(row)
            if (!checked.add(k)) checked.remove(k)
            adapter.notifyDataSetChanged()
        }
        val container = FrameLayout(this)
        container.addView(listView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, dp(380)))
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.folder_add_picker_title, d.name))
            .setView(container)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                if (checked.isEmpty()) {
                    toast(R.string.folder_select_empty)
                    return@setPositiveButton
                }
                val payload = rows.filter { it.header == null && pickKey(it) in checked }
                    .map { it.kind to it.ref }
                addPickedItems(d.folderId, payload)
            }
            .show()
    }

    private fun addPickedItems(folderId: String, payload: List<Pair<String, String>>) {
        Thread {
            try {
                val (added, skipped, failed) = AsrClient.folderAddItems(this, folderId, payload)
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    var msg = getString(R.string.folder_pick_added, added)
                    if (skipped > 0) msg += getString(R.string.folder_batch_skipped, skipped)
                    if (failed > 0) msg += getString(R.string.folder_batch_failed, failed)
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                    if (detail?.folderId == folderId) loadDetail() else loadList()
                }
            } catch (e: Exception) {
                uiError(e)
            }
        }.start()
    }

    // ---------- 成员打开 / 移出

    private fun openEntry(e: AsrClient.FolderEntry) {
        if (!e.alive) {
            Toast.makeText(this, e.error ?: getString(R.string.folder_item_dead),
                Toast.LENGTH_SHORT).show()
            return
        }
        if (e.kind == "task") openTask(e.ref, e.title) else showPreview(e.url)
    }

    /** 录音条目：拉取任务结果写入缓存后进结果页（与设置页 openTask 同路径）。 */
    private fun openTask(taskId: String, name: String) {
        Thread {
            try {
                val task = AsrClient.getTask(this, taskId)
                val status = task.optString("status")
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    when (status) {
                        "SUCCEEDED" -> {
                            val resultFile = File(cacheDir, "asr_result_$taskId.json")
                            resultFile.writeText(
                                task.getJSONObject("result").toString(), Charsets.UTF_8)
                            val it = Intent(this, AsrResultActivity::class.java)
                                .putExtra("result_path", resultFile.absolutePath)
                                .putExtra("filename", name)
                                .putExtra("task_id", taskId)
                            val opts = task.optJSONObject("options")
                            if (opts != null) {
                                it.putExtra("opt_timestamps", opts.optBoolean("timestamps"))
                                    .putExtra("opt_diarize", opts.optBoolean("diarize"))
                                    .putExtra("opt_mandarin", opts.optBoolean("dialect2mandarin"))
                            }
                            startActivity(it)
                        }
                        "FAILED" -> Toast.makeText(this,
                            getString(R.string.asr_failed_prefix) +
                                task.optString("error", "识别失败"), Toast.LENGTH_LONG).show()
                        else -> Toast.makeText(this,
                            getString(R.string.asr_task_running,
                                task.optString("stage", "处理中")), Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                uiError(e)
            }
        }.start()
    }

    private fun confirmRemoveEntry(e: AsrClient.FolderEntry) {
        val fid = detail?.folderId ?: return
        AlertDialog.Builder(this)
            .setTitle(R.string.folder_remove_title)
            .setMessage(getString(R.string.folder_remove_confirm, e.title))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                Thread {
                    try {
                        AsrClient.folderRemoveItem(this, fid, e.kind, e.ref)
                        runOnUiThread {
                            if (isFinishing || isDestroyed) return@runOnUiThread
                            toast(R.string.folder_removed)
                            loadDetail()
                        }
                    } catch (err: Exception) {
                        uiError(err)
                    }
                }.start()
            }
            .show()
    }

    // ---------- 新建 / 重命名 / 删除文件夹

    private fun promptCreateFolder() {
        promptFolderName(getString(R.string.folder_create_title), null) { name ->
            Thread {
                try {
                    AsrClient.createFolder(this, name)
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        toast(R.string.folder_created)
                        loadList()
                    }
                } catch (e: Exception) {
                    uiError(e)
                }
            }.start()
        }
    }

    private fun promptRenameFolder(f: AsrClient.FolderInfo) {
        promptFolderName(getString(R.string.folder_rename_title), f.name) { name ->
            Thread {
                try {
                    AsrClient.renameFolder(this, f.folderId, name)
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        toast(R.string.folder_renamed)
                        if (detail?.folderId == f.folderId) {
                            detail = detail?.copy(name = name)
                        }
                        loadList()
                    }
                } catch (e: Exception) {
                    uiError(e)
                }
            }.start()
        }
    }

    private fun promptFolderName(title: String, initial: String?, onOk: (String) -> Unit) {
        val input = EditText(this).apply {
            hint = getString(R.string.folder_name_hint)
            setSingleLine(true)
            initial?.let { setText(it) }
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) onOk(name)
            }
            .show()
    }

    private fun confirmDeleteFolder(f: AsrClient.FolderInfo) {
        AlertDialog.Builder(this)
            .setTitle(R.string.folder_delete_title)
            .setMessage(getString(R.string.folder_delete_confirm, f.name))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.asr_delete_ok) { _, _ ->
                Thread {
                    try {
                        AsrClient.deleteFolder(this, f.folderId)
                        runOnUiThread {
                            if (isFinishing || isDestroyed) return@runOnUiThread
                            toast(R.string.folder_deleted)
                            if (detail?.folderId == f.folderId) exitDetail() else loadList()
                        }
                    } catch (e: Exception) {
                        uiError(e)
                    }
                }.start()
            }
            .show()
    }

    private fun showFolderMenu(f: AsrClient.FolderInfo) {
        AlertDialog.Builder(this)
            .setTitle(f.name)
            .setItems(arrayOf(
                getString(R.string.folder_open),
                getString(R.string.wxchat_item_rename),
                getString(R.string.asr_delete_ok))) { _, which ->
                when (which) {
                    0 -> openFolder(f)
                    1 -> promptRenameFolder(f)
                    2 -> confirmDeleteFolder(f)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showDetailMenu() {
        val d = detail ?: return
        AlertDialog.Builder(this)
            .setTitle(d.name)
            .setItems(arrayOf(
                getString(R.string.wxchat_item_rename),
                getString(R.string.folder_delete_title))) { _, which ->
                when (which) {
                    0 -> promptRenameFolder(AsrClient.FolderInfo(
                        d.folderId, d.name, d.entries.size, d.entries.count { it.alive }, 0L))
                    1 -> confirmDeleteFolder(AsrClient.FolderInfo(
                        d.folderId, d.name, d.entries.size, d.entries.count { it.alive }, 0L))
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ---------- 文件夹聚合分享

    private fun promptShareOptions() {
        val d = detail ?: return
        if (d.entries.none { it.alive }) {
            toast(R.string.folder_empty_share)
            return
        }
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(20), px(10), px(20), 0)
        }
        container.addView(TextView(this).apply {
            text = getString(R.string.folder_share_options_msg)
            textSize = 13f
            setTextColor(0xFF666666.toInt())
        })
        container.addView(TextView(this).apply {
            text = getString(R.string.folder_share_ttl)
            textSize = 13f
            setPadding(0, px(12), 0, px(4))
        })
        val ttl = Spinner(this)
        ttl.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            arrayOf("1小时", "24小时", "3天", "7天", "30天"))
        // 默认值 = 上次分享时选择的参数（与录音分享要求页共用同一份记忆）
        ttl.setSelection(ttlValues.indexOf(AppPrefs.getShareValidityHours(this))
            .let { if (it >= 0) it else 1 })
        container.addView(ttl)
        container.addView(TextView(this).apply {
            text = getString(R.string.folder_share_opens)
            textSize = 13f
            setPadding(0, px(12), 0, px(4))
        })
        val opens = Spinner(this)
        opens.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            arrayOf("1次", "5次", "10次", "不限"))
        opens.setSelection(opensValues.indexOf(AppPrefs.getShareMaxOpens(this))
            .let { if (it >= 0) it else 1 })
        container.addView(opens)
        AlertDialog.Builder(this)
            .setTitle(R.string.folder_share_title)
            .setView(container)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.asr_share_create) { _, _ ->
                val hours = ttlValues[ttl.selectedItemPosition]
                val maxOpens = opensValues[opens.selectedItemPosition]
                AppPrefs.setShareDefaults(this, hours, maxOpens)
                generateShare(d.folderId, d.name, hours, maxOpens)
            }
            .show()
    }

    private fun generateShare(folderId: String, name: String, hours: Int, opens: Int) {
        Thread {
            try {
                val info = AsrClient.createFolderShare(this, folderId, hours, opens)
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    val opensText = if (info.maxOpens == 0) "不限打开次数"
                    else "可打开${info.maxOpens}次"
                    val expireText = java.text.SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)
                        .format(java.util.Date(System.currentTimeMillis() +
                            info.validityHours * 3600_000L))
                    val shareText = getString(R.string.folder_share_text, name, info.url, info.pwd) +
                        "\n有效至 $expireText（$opensText）"
                    AlertDialog.Builder(this)
                        .setTitle(R.string.folder_share_done_title)
                        .setMessage(getString(R.string.folder_share_msg, info.url, info.pwd))
                        .setNegativeButton(android.R.string.cancel, null)
                        .setNeutralButton(R.string.asr_btn_copy_link) { _, _ ->
                            val cm = getSystemService(Context.CLIPBOARD_SERVICE)
                                as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("share", shareText))
                            toast(R.string.asr_share_copied)
                        }
                        .setPositiveButton(R.string.asr_btn_share) { _, _ ->
                            val send = Intent(Intent.ACTION_SEND)
                                .setType("text/plain")
                                .putExtra(Intent.EXTRA_TEXT, shareText)
                            startActivity(Intent.createChooser(
                                send, getString(R.string.asr_btn_share)))
                        }
                        .show()
                }
            } catch (e: Exception) {
                uiError(e)
            }
        }.start()
    }

    // ---------- 条目页内预览（WebView overlay，BACK 关闭）

    @SuppressLint("SetJavaScriptEnabled")
    private fun showPreview(url: String) {
        val container = binding.previewContainer
        val wv = previewWebView ?: WebView(this).also { wv ->
            wv.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                useWideViewPort = true
                loadWithOverviewMode = true
            }
            wv.webViewClient = WebViewClient()
            wv.setDownloadListener { dlUrl, _, _, _, _ ->
                runCatching {
                    val req = android.app.DownloadManager.Request(android.net.Uri.parse(dlUrl))
                        .setTitle(getString(R.string.wxchat_download_title))
                        .setNotificationVisibility(
                            android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    (getSystemService(Context.DOWNLOAD_SERVICE)
                        as android.app.DownloadManager).enqueue(req)
                    toast(R.string.wxchat_download_started)
                }.onFailure {
                    startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(dlUrl)))
                }
            }
            previewWebView = wv
            container.addView(wv, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
        wv.loadUrl(url)
        container.visibility = View.VISIBLE
    }

    private fun closePreview() {
        binding.previewContainer.visibility = View.GONE
        previewWebView?.apply {
            (parent as? FrameLayout)?.removeView(this)
            destroy()
        }
        previewWebView = null
    }

    private fun previewVisible(): Boolean =
        binding.previewContainer.visibility == View.VISIBLE

    // ---------- 小工具

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun toast(resId: Int) {
        Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()
    }

    private fun errText(e: Exception): String =
        if (e is AsrAuthException) getString(R.string.asr_token_invalid)
        else (e.message ?: e.toString())

    private fun uiError(e: Exception) {
        runOnUiThread {
            if (!isFinishing && !isDestroyed) {
                Toast.makeText(this, errText(e), Toast.LENGTH_LONG).show()
            }
        }
    }
}
