package com.trae.simplebrowser

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.tabs.TabLayout
import org.json.JSONObject
import com.trae.simplebrowser.core.AdBlockEngine
import com.trae.simplebrowser.core.AsrTranscripts
import com.trae.simplebrowser.core.DownloadKind
import com.trae.simplebrowser.core.DownloadRecord
import com.trae.simplebrowser.core.DownloadStatus
import com.trae.simplebrowser.databinding.ActivitySettingsBinding
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 设置页：双标签页（下载管理 | 音频识别）。
 * 音频识别按用户令牌隔离，列表条目点击打开转写详情，「播放」按钮播放对应录音（语音消息式）。
 */
class SettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsBinding
    private val logTag = "Settings"

    private val downloadStore by lazy { DownloadStoreFile(this) }

    // ---------- 下载管理批量选择状态 ----------
    private var selectionMode = false
    private val selectedIds = LinkedHashSet<String>()

    private val downloadUpdatedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == DownloadEvents.ACTION_UPDATED) {
                renderDownloads()
            }
        }
    }

    // ---------- 音频识别管理状态 ----------
    private data class TaskItem(
        val id: String,
        val name: String,
        val status: String,
        val stage: String,
        val createdAt: Long,
        val durationMs: Long,
        val isVideo: Boolean,
        val shareCount: Int
    )

    private val items = ArrayList<TaskItem>()
    private lateinit var taskAdapter: BaseAdapter
    private lateinit var asrAuthPanel: LinearLayout
    private lateinit var asrContentPanel: LinearLayout
    private lateinit var asrTokenInput: EditText
    private lateinit var asrIdentityView: TextView
    private lateinit var asrListView: ListView
    private lateinit var asrEmptyView: TextView
    private var asrLoaded = false
    private var mediaPlayer: MediaPlayer? = null
    private var playingTaskId: String? = null

    // ---------- 资料夹：录音列表多选状态 ----------
    private var asrSelectMode = false
    private val asrSelected = LinkedHashSet<String>()   // 选中的 task_id

    // ---------- 资料夹：根目录模型 ----------
    // 根目录 = 未归入任何资料夹的条目。已归档条目从两个列表隐藏，只在资料夹里出现；
    // 服务端 move 语义保证一条记录最多归属一个夹。
    private var filedTaskRefs = setOf<String>()
    private var filedWxRefs = setOf<String>()
    private var folderCount = 0

    // ---------- 关键词搜索（本地实时过滤当前列表） ----------
    private var asrQuery = ""
    private var wxQuery = ""

    private fun asrFiltered(): List<TaskItem> =
        if (asrQuery.isBlank()) items
        else items.filter { it.name.contains(asrQuery, ignoreCase = true) }

    private fun wxFiltered(): List<WxShareItem> =
        if (wxQuery.isBlank()) wxShares
        else wxShares.filter {
            it.title.contains(wxQuery, ignoreCase = true) ||
                it.participantsDisplay.any { p -> p.contains(wxQuery, ignoreCase = true) }
        }

    /** 空态：无数据时给原因（全部归档/暂无记录）；有数据但搜索无匹配时提示换关键词。 */
    private fun renderAsrEmpty() {
        val visible = asrFiltered().isEmpty()
        binding.asrList.visibility = if (visible) View.GONE else View.VISIBLE
        binding.asrEmpty.visibility = if (visible) View.VISIBLE else View.GONE
        binding.asrEmpty.text = when {
            items.isEmpty() && filedTaskRefs.isNotEmpty() -> getString(R.string.asr_all_filed)
            items.isEmpty() -> getString(R.string.asr_manage_empty)
            else -> getString(R.string.search_no_match, asrQuery)
        }
    }

    private fun renderWxEmpty() {
        val visible = wxFiltered().isEmpty()
        binding.wxchatList.visibility = if (visible) View.GONE else View.VISIBLE
        binding.wxchatEmpty.visibility = if (visible) View.VISIBLE else View.GONE
        binding.wxchatEmpty.text = when {
            wxShares.isEmpty() && filedWxRefs.isNotEmpty() -> getString(R.string.wxchat_all_filed)
            wxShares.isEmpty() -> getString(R.string.wxchat_manage_empty)
            else -> getString(R.string.search_no_match, wxQuery)
        }
    }

    // ---------- 聊天记录分享管理状态 ----------
    private data class WxShareItem(
        val shareId: String,
        val pwd: String,
        val title: String,
        val messageCount: Int,
        val participantCount: Int,
        val participantsDisplay: List<String>,
        val attachmentCount: Int,
        val expiresAt: Long,
        val url: String,
        val ocrState: String,
        val ocrDone: Int,
        val ocrTotal: Int
    )

    private val wxShares = ArrayList<WxShareItem>()
    private lateinit var wxAdapter: BaseAdapter
    private lateinit var wxAuthHint: TextView
    private lateinit var wxContentPanel: LinearLayout
    private lateinit var wxIdentityView: TextView
    private lateinit var wxListView: ListView
    private lateinit var wxEmptyView: TextView

    // ---------- 资料夹：聊天记录列表多选状态 ----------
    private var wxSelectMode = false
    private val wxSelected = LinkedHashSet<String>()    // 选中的 share_id

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val current = AppPrefs.getHideControls(this)
        binding.hideControlsSwitch.isChecked = current
        binding.hideControlsSwitch.setOnCheckedChangeListener { _, isChecked ->
            AppPrefs.setHideControls(this, isChecked)
        }

        setupAdBlock()
        setupBrowsePrefs()
        setupTabs()
        setupAsrManage()
        setupWxChatManage()
        setupDownloadManage()

        val versionName = runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName
        }.getOrDefault("Unknown")
        binding.versionTextView.text = getString(R.string.label_version, versionName)

        renderDownloads()
    }

    override fun onResume() {
        super.onResume()
        refreshAdBlockStatus()
        // 从资料夹页返回时刷新可见页签：夹内移出/添加都会改变根目录内容
        when (binding.settingsTabs.selectedTabPosition) {
            2 -> if (AsrAuth.hasToken(this)) loadTasks()
            3 -> if (AsrAuth.hasToken(this)) loadWxShares()
        }
    }

    // ---------- 搜索/主页/浏览体验/隐私（F-15） ----------
    private fun setupBrowsePrefs() {
        binding.searchEngineRow.setOnClickListener { showSearchEngineDialog() }
        binding.homePageRow.setOnClickListener { showHomePageDialog() }
        binding.dataSaverSwitch.isChecked = AppPrefs.isDataSaver(this)
        binding.dataSaverSwitch.setOnCheckedChangeListener { _, isChecked ->
            AppPrefs.setDataSaver(this, isChecked)
        }
        binding.textZoomRow.setOnClickListener { showTextZoomDialog() }
        binding.clearBrowsingRow.setOnClickListener { showClearBrowsingDialog() }
        refreshBrowsePrefs()
    }

    private fun refreshBrowsePrefs() {
        val engine = com.trae.simplebrowser.core.SearchEngines.byId(AppPrefs.getSearchEngineId(this))
        binding.searchEngineStatusTextView.text =
            getString(R.string.setting_search_engine_fmt, engine.name)
        val home = AppPrefs.getHomePage(this) ?: getString(R.string.setting_home_page_default)
        binding.homePageStatusTextView.text = getString(R.string.setting_home_page_fmt, home)
        binding.textZoomStatusTextView.text =
            getString(R.string.setting_text_zoom_fmt, AppPrefs.getTextZoom(this))
    }

    private fun showSearchEngineDialog() {
        val engines = com.trae.simplebrowser.core.SearchEngines.ALL
        val names = engines.map { it.name }.toTypedArray()
        val checked = engines.indexOfFirst { it.id == AppPrefs.getSearchEngineId(this) }
        AlertDialog.Builder(this)
            .setTitle(R.string.setting_search_engine)
            .setSingleChoiceItems(names, checked) { dialog, which ->
                AppPrefs.setSearchEngineId(this, engines[which].id)
                dialog.dismiss()
                refreshBrowsePrefs()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showHomePageDialog() {
        val input = EditText(this).apply {
            hint = getString(R.string.setting_home_page_hint)
            isSingleLine = true
            setText(AppPrefs.getHomePage(this@SettingsActivity).orEmpty())
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.setting_home_page)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val url = input.text?.toString()?.trim().orEmpty()
                val normalized = when {
                    url.isEmpty() -> ""
                    url.startsWith("http://") || url.startsWith("https://") -> url
                    else -> "https://$url"
                }
                AppPrefs.setHomePage(this, normalized)
                refreshBrowsePrefs()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showTextZoomDialog() {
        val options = listOf(85, 100, 115, 130)
        val labels = options.map { "$it%" }.toTypedArray()
        val checked = options.indexOf(AppPrefs.getTextZoom(this)).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(R.string.setting_text_zoom)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                AppPrefs.setTextZoom(this, options[which])
                dialog.dismiss()
                refreshBrowsePrefs()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showClearBrowsingDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp2(20), dp2(8), dp2(20), dp2(4))
        }
        val cacheBox = CheckBox(this).apply { text = getString(R.string.setting_clear_cache); isChecked = true }
        val historyBox = CheckBox(this).apply { text = getString(R.string.setting_clear_history); isChecked = true }
        val cookieBox = CheckBox(this).apply { text = getString(R.string.setting_clear_cookies) }
        container.addView(cacheBox)
        container.addView(historyBox)
        container.addView(cookieBox)

        AlertDialog.Builder(this)
            .setTitle(R.string.setting_clear_browsing)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                if (!cacheBox.isChecked && !historyBox.isChecked && !cookieBox.isChecked) {
                    Toast.makeText(this, R.string.setting_clear_none, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (historyBox.isChecked) AppPrefs.clearHistory(this)
                if (cacheBox.isChecked) AppPrefs.markPendingClearCache(this)
                if (cookieBox.isChecked) clearAllCookies()
                if (!cacheBox.isChecked) {
                    Toast.makeText(this, R.string.setting_clear_done, Toast.LENGTH_SHORT).show()
                }
                // 缓存清理在回主窗口 onResume 执行，完成时有 Toast 提示
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun clearAllCookies() {
        // 清默认身份 + 全部命名身份的 Cookie（登出所有身份）
        runCatching { android.webkit.CookieManager.getInstance().removeAllCookies(null) }
        val count = AppPrefs.getIdentityCount(this)
        for (i in 1..count) {
            runCatching {
                com.trae.simplebrowser.core.ProfileManager
                    .cookieManager(com.trae.simplebrowser.core.ProfileManager.profileNameOf(i))
                    .removeAllCookies(null)
            }
        }
    }

    // ---------- 广告拦截设置（F-14） ----------
    private fun setupAdBlock() {
        AdBlockEngine.initAsync(applicationContext)
        binding.adBlockSwitch.isChecked = AdBlockEngine.isEnabled(this)
        binding.adBlockSwitch.setOnCheckedChangeListener { _, isChecked ->
            AdBlockEngine.setEnabled(this, isChecked)
            Toast.makeText(
                this,
                getString(
                    if (isChecked) R.string.setting_adblock_enabled_on
                    else R.string.setting_adblock_enabled_off
                ),
                Toast.LENGTH_SHORT
            ).show()
        }
        binding.adBlockAutoHideSwitch.isChecked = AdBlockEngine.isAutoHideEnabled(this)
        binding.adBlockAutoHideSwitch.setOnCheckedChangeListener { _, isChecked ->
            AdBlockEngine.setAutoHideEnabled(this, isChecked)
        }
        binding.adBlockWhitelistRow.setOnClickListener { showWhitelistDialog() }
        binding.adBlockUpdateRow.setOnClickListener { startRuleUpdate() }
        refreshAdBlockStatus()
    }

    private fun refreshAdBlockStatus() {
        val status = AdBlockEngine.ruleStatus(this)
        binding.adBlockWhitelistRow.text =
            getString(R.string.setting_adblock_whitelist, AdBlockEngine.getWhitelist(this).size)
        binding.adBlockRulesStatusTextView.text =
            if (status.lastUpdateAt > 0) {
                val time = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                    .format(Date(status.lastUpdateAt))
                getString(R.string.setting_adblock_rules_status_fmt, status.onlineCount, time)
            } else {
                getString(R.string.setting_adblock_rules_never) +
                    "；" + getString(R.string.setting_adblock_rules_builtin_fmt, status.builtinCount)
            }
        binding.adBlockTotalTextView.text =
            getString(R.string.setting_adblock_total_fmt, status.totalBlocked)
    }

    private fun startRuleUpdate() {
        if (AdBlockEngine.isUpdating()) {
            Toast.makeText(this, R.string.setting_adblock_update_doing, Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, R.string.setting_adblock_update_doing, Toast.LENGTH_SHORT).show()
        AdBlockEngine.updateOnlineRules(this) { result ->
            runOnUiThread {
                if (result.isSuccess) {
                    Toast.makeText(
                        this,
                        getString(R.string.setting_adblock_update_done_fmt, result.getOrDefault(0)),
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    Log.w(logTag, "ad rule update failed: ${result.exceptionOrNull()?.message}")
                    Toast.makeText(this, R.string.setting_adblock_update_failed, Toast.LENGTH_LONG).show()
                }
                refreshAdBlockStatus()
            }
        }
    }

    private fun showWhitelistDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp2(20), dp2(12), dp2(20), dp2(4))
        }
        val listWrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        container.addView(listWrap)

        fun renderList() {
            listWrap.removeAllViews()
            val hosts = AdBlockEngine.getWhitelist(this)
            if (hosts.isEmpty()) {
                listWrap.addView(TextView(this).apply {
                    text = getString(R.string.setting_adblock_whitelist_empty)
                    textSize = 14f
                    alpha = 0.7f
                    setPadding(0, dp2(8), 0, dp2(8))
                })
                return
            }
            hosts.forEach { host ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp2(8), 0, dp2(8))
                }
                row.addView(TextView(this).apply {
                    text = host
                    textSize = 15f
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                })
                row.addView(Button(this).apply {
                    text = getString(R.string.download_delete)
                    textSize = 12f
                    setOnClickListener {
                        AdBlockEngine.removeWhitelist(this@SettingsActivity, host)
                        renderList()
                    }
                })
                listWrap.addView(row)
            }
        }
        renderList()

        container.addView(Button(this).apply {
            text = getString(R.string.setting_adblock_whitelist_add)
            setOnClickListener {
                val input = EditText(this@SettingsActivity).apply {
                    hint = getString(R.string.setting_adblock_whitelist_hint)
                    isSingleLine = true
                }
                AlertDialog.Builder(this@SettingsActivity)
                    .setTitle(R.string.setting_adblock_whitelist_add)
                    .setView(input)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        val host = input.text?.toString()?.trim().orEmpty()
                        if (host.isNotBlank() && AdBlockEngine.addWhitelist(this@SettingsActivity, host)) {
                            renderList()
                        }
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        })

        AlertDialog.Builder(this)
            .setTitle(R.string.setting_adblock_whitelist_title)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ -> refreshAdBlockStatus() }
            .show()
    }

    private fun dp2(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun setupTabs() {
        binding.settingsTabs.addTab(
            binding.settingsTabs.newTab().setText(R.string.settings_tab_other))
        binding.settingsTabs.addTab(
            binding.settingsTabs.newTab().setText(R.string.downloads_section_title))
        binding.settingsTabs.addTab(
            binding.settingsTabs.newTab().setText(R.string.asr_manage_title))
        binding.settingsTabs.addTab(
            binding.settingsTabs.newTab().setText(R.string.wxchat_manage_title))
        binding.settingsTabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                binding.pageOther.visibility = if (tab.position == 0) View.VISIBLE else View.GONE
                binding.pageDownloads.visibility = if (tab.position == 1) View.VISIBLE else View.GONE
                binding.pageAsr.visibility = if (tab.position == 2) View.VISIBLE else View.GONE
                binding.pageWxchat.visibility = if (tab.position == 3) View.VISIBLE else View.GONE
                if (tab.position == 2) {
                    refreshAsrPanel()
                    if (!asrLoaded) loadTasks()
                }
                if (tab.position == 3) {
                    refreshWxChatPanel()
                    loadWxShares()
                }
            }

            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {
                if (tab.position == 1) renderDownloads()
                if (tab.position == 2) loadTasks()
                if (tab.position == 3) loadWxShares()
            }
        })
    }

    // ---------- 音频识别：令牌面板 / 列表 / 播放 ----------

    private fun setupAsrManage() {
        asrAuthPanel = binding.asrAuthPanel
        asrContentPanel = binding.asrContentPanel
        asrTokenInput = binding.asrTokenInput
        asrIdentityView = binding.asrIdentity
        asrListView = binding.asrList
        asrEmptyView = binding.asrEmpty

        taskAdapter = object : BaseAdapter() {
            override fun getCount(): Int = asrFiltered().size
            override fun getItem(position: Int): Any = asrFiltered()[position]
            override fun getItemId(position: Int): Long = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = convertView ?: LayoutInflater.from(this@SettingsActivity)
                    .inflate(R.layout.asr_task_item, parent, false)
                val item = asrFiltered()[position]
                v.findViewById<TextView>(R.id.item_name).text = item.name
                v.findViewById<TextView>(R.id.item_meta).text = metaText(item)
                val check = v.findViewById<CheckBox>(R.id.item_check)
                val play = v.findViewById<TextView>(R.id.item_play)
                val open = v.findViewById<TextView>(R.id.item_open)
                if (asrSelectMode) {
                    // 多选态：行首复选框，行内操作按钮让位（点行=勾选/取消）
                    check.visibility = View.VISIBLE
                    check.isChecked = item.id in asrSelected
                    play.visibility = View.GONE
                    open.visibility = View.GONE
                } else {
                    check.visibility = View.GONE
                    play.visibility = View.VISIBLE
                    open.visibility = View.VISIBLE
                    play.text = if (playingTaskId == item.id) "⏸" else "▶"
                    play.setOnClickListener { togglePlay(item) }
                    open.setOnClickListener { openTask(item) }
                }
                return v
            }
        }
        asrListView.adapter = taskAdapter
        asrListView.setOnItemClickListener { _, _, position, _ ->
            val item = asrFiltered()[position]
            if (asrSelectMode) toggleAsrSelection(item) else openTask(item)
        }
        asrListView.setOnItemLongClickListener { _, _, position, _ ->
            val item = asrFiltered()[position]
            if (asrSelectMode) {
                toggleAsrSelection(item)
                return@setOnItemLongClickListener true
            }
            AlertDialog.Builder(this)
                .setTitle(item.name)
                .setItems(arrayOf(
                    getString(R.string.asr_item_result),
                    getString(R.string.folder_add_action),
                    getString(R.string.folder_select_mode),
                    getString(R.string.asr_delete_ok))) { _, which ->
                    when (which) {
                        0 -> openTask(item)
                        1 -> showAddToFolderDialog("task", item.id, item.name)
                        2 -> enterAsrSelection()
                        3 -> confirmDelete(item)
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            true
        }
        binding.asrFolderEntry.setOnClickListener {
            startActivity(Intent(this, FolderActivity::class.java))
        }
        binding.asrSelDone.setOnClickListener { exitAsrSelection() }
        binding.asrSelMove.setOnClickListener {
            if (asrSelected.isEmpty()) {
                Toast.makeText(this, R.string.folder_select_empty, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val payload = items.filter { it.id in asrSelected }.map { "task" to it.id }
            showBatchAddDialog(payload)
        }
        binding.asrSelCopy.setOnClickListener { batchCopyTranscripts(share = false) }
        binding.asrSelShare.setOnClickListener { batchCopyTranscripts(share = true) }
        binding.asrSelShareLink.setOnClickListener { batchShareLinks() }
        binding.asrSearchInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                asrQuery = s?.toString().orEmpty()
                taskAdapter.notifyDataSetChanged()
                renderAsrEmpty()
            }
        })

        binding.asrTokenVerifyBtn.setOnClickListener {
            val token = asrTokenInput.text.toString().trim()
            if (token.length < 40) {
                Toast.makeText(this, R.string.asr_token_too_short, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            it.isEnabled = false
            verifyAndEnter(token) { it.isEnabled = true }
        }
        binding.asrRefresh.setOnClickListener { loadTasks() }
        binding.asrSwitch.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(R.string.asr_manage_switch)
                .setMessage(R.string.asr_switch_confirm)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    stopPlayback()
                    AppPrefs.clearAsrAuth(this)
                    items.clear()
                    taskAdapter.notifyDataSetChanged()
                    refreshAsrPanel()
                }
                .show()
        }
    }

    private fun refreshAsrPanel() {
        if (AsrAuth.hasToken(this)) {
            asrAuthPanel.visibility = View.GONE
            asrContentPanel.visibility = View.VISIBLE
            asrIdentityView.text = getString(R.string.asr_identity_label, AppPrefs.getAsrUser(this).ifEmpty { "?? " })
        } else {
            asrAuthPanel.visibility = View.VISIBLE
            asrContentPanel.visibility = View.GONE
        }
    }

    private fun verifyAndEnter(token: String, onFail: () -> Unit) {
        Thread {
            try {
                val user = AsrClient.verifyToken(this, token)
                AppPrefs.setAsrAuth(this, token, user)
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    onFail()
                    refreshAsrPanel()
                    loadTasks()
                    Toast.makeText(this, getString(R.string.asr_token_welcome, user), Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    onFail()
                    Toast.makeText(this, asrErrorText(e), Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    // ---------- 聊天记录分享：列表 / 复制链接 / 打开 / 删除 ----------

    private fun refreshWxChatPanel() {
        if (AsrAuth.hasToken(this)) {
            wxAuthHint.visibility = View.GONE
            wxContentPanel.visibility = View.VISIBLE
            wxIdentityView.text = getString(R.string.asr_identity_label,
                AppPrefs.getAsrUser(this).ifEmpty { "?? " })
        } else {
            wxAuthHint.visibility = View.VISIBLE
            wxContentPanel.visibility = View.GONE
        }
    }

    private fun setupWxChatManage() {
        wxAuthHint = binding.wxchatAuthHint
        wxContentPanel = binding.wxchatContentPanel
        wxIdentityView = binding.wxchatIdentity
        wxListView = binding.wxchatList
        wxEmptyView = binding.wxchatEmpty

        wxAdapter = object : BaseAdapter() {
            override fun getCount(): Int = wxFiltered().size
            override fun getItem(position: Int): Any = wxFiltered()[position]
            override fun getItemId(position: Int): Long = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = convertView ?: LayoutInflater.from(this@SettingsActivity)
                    .inflate(R.layout.wx_share_item, parent, false)
                val item = wxFiltered()[position]
                val check = v.findViewById<CheckBox>(R.id.item_check)
                check.visibility = if (wxSelectMode) View.VISIBLE else View.GONE
                check.isChecked = item.shareId in wxSelected
                val rowBtnVis = if (wxSelectMode) View.GONE else View.VISIBLE
                v.findViewById<TextView>(R.id.item_copy).visibility = rowBtnVis
                v.findViewById<TextView>(R.id.item_rename).visibility = rowBtnVis
                v.findViewById<TextView>(R.id.item_open).visibility = rowBtnVis
                v.findViewById<TextView>(R.id.item_name).text = item.title
                v.findViewById<TextView>(R.id.item_meta).text = wxMetaText(item)
                v.findViewById<TextView>(R.id.item_members).text =
                    getString(R.string.wxchat_item_members,
                        item.participantsDisplay.joinToString("、"))
                val ocrView = v.findViewById<TextView>(R.id.item_ocr)
                when (item.ocrState) {
                    "done" -> {
                        ocrView.text = getString(R.string.wxchat_ocr_done)
                        ocrView.setTextColor(0xFF388E3C.toInt())
                        ocrView.visibility = View.VISIBLE
                    }
                    "pending" -> {
                        ocrView.text = getString(R.string.wxchat_ocr_pending)
                        ocrView.setTextColor(0xFFE65100.toInt())
                        ocrView.visibility = View.VISIBLE
                    }
                    "partial" -> {
                        ocrView.text = getString(R.string.wxchat_ocr_partial,
                            item.ocrDone, item.ocrTotal)
                        ocrView.setTextColor(0xFFD32F2F.toInt())
                        ocrView.visibility = View.VISIBLE
                    }
                    "failed" -> {
                        ocrView.text = getString(R.string.wxchat_ocr_failed, item.ocrTotal)
                        ocrView.setTextColor(0xFFD32F2F.toInt())
                        ocrView.visibility = View.VISIBLE
                    }
                    else -> ocrView.visibility = View.GONE
                }
                v.findViewById<TextView>(R.id.item_copy).setOnClickListener { copyWxLink(item) }
                v.findViewById<TextView>(R.id.item_rename).setOnClickListener { promptRenameWxShare(item) }
                v.findViewById<TextView>(R.id.item_open).setOnClickListener { openWxShare(item) }
                return v
            }
        }
        wxListView.adapter = wxAdapter
        wxListView.setOnItemClickListener { _, _, position, _ ->
            val item = wxFiltered()[position]
            if (wxSelectMode) toggleWxSelection(item) else openWxShare(item)
        }
        wxListView.setOnItemLongClickListener { _, _, position, _ ->
            val item = wxFiltered()[position]
            if (wxSelectMode) {
                toggleWxSelection(item)
                return@setOnItemLongClickListener true
            }
            AlertDialog.Builder(this)
                .setTitle(item.title)
                .setItems(arrayOf(
                    getString(R.string.wxchat_item_open),
                    getString(R.string.wxchat_item_copy),
                    getString(R.string.folder_add_action),
                    getString(R.string.folder_select_mode),
                    getString(R.string.asr_delete_ok))) { _, which ->
                    when (which) {
                        0 -> openWxShare(item)
                        1 -> copyWxLink(item)
                        2 -> showAddToFolderDialog("wxchat", item.shareId, item.title)
                        3 -> enterWxSelection()
                        4 -> confirmDeleteWxShare(item)
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            true
        }
        binding.wxchatFolderEntry.setOnClickListener {
            startActivity(Intent(this, FolderActivity::class.java))
        }
        binding.wxchatSelDone.setOnClickListener { exitWxSelection() }
        binding.wxchatSelMove.setOnClickListener {
            if (wxSelected.isEmpty()) {
                Toast.makeText(this, R.string.folder_select_empty, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val payload = wxShares.filter { it.shareId in wxSelected }.map { "wxchat" to it.shareId }
            showBatchAddDialog(payload)
        }
        binding.wxchatSearchInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                wxQuery = s?.toString().orEmpty()
                wxAdapter.notifyDataSetChanged()
                renderWxEmpty()
            }
        })

        binding.wxchatRefresh.setOnClickListener { loadWxShares() }
        binding.wxchatNamesBtn.setOnClickListener { showNameMappingDialog() }
    }

    // ---------- 资料夹：两个列表的多选（同一套交互） ----------

    private fun enterAsrSelection() {
        asrSelectMode = true
        asrSelected.clear()
        renderAsrSelection()
        binding.asrSearchInput.visibility = View.GONE   // 多选时不与搜索混用
        taskAdapter.notifyDataSetChanged()
    }

    private fun exitAsrSelection() {
        asrSelectMode = false
        asrSelected.clear()
        if (!isFinishing && !isDestroyed) {
            binding.asrSelectionBar.visibility = View.GONE
            binding.asrSearchInput.visibility = View.VISIBLE
            taskAdapter.notifyDataSetChanged()
        }
    }

    private fun renderAsrSelection() {
        binding.asrSelectionBar.visibility = View.VISIBLE
        binding.asrSelCount.text = getString(R.string.folder_selected_count, asrSelected.size)
        // 四个批量操作共用一个可用态：没勾选时全部置灰
        for (btn in listOf(binding.asrSelMove, binding.asrSelCopy,
                binding.asrSelShare, binding.asrSelShareLink)) {
            btn.isEnabled = asrSelected.isNotEmpty()
            btn.alpha = if (asrSelected.isEmpty()) 0.5f else 1f
        }
    }

    private fun toggleAsrSelection(item: TaskItem) {
        if (!asrSelected.add(item.id)) asrSelected.remove(item.id)
        renderAsrSelection()
        taskAdapter.notifyDataSetChanged()
    }

    private fun enterWxSelection() {
        wxSelectMode = true
        wxSelected.clear()
        renderWxSelection()
        binding.wxchatSearchInput.visibility = View.GONE
        wxAdapter.notifyDataSetChanged()
    }

    private fun exitWxSelection() {
        wxSelectMode = false
        wxSelected.clear()
        if (!isFinishing && !isDestroyed) {
            binding.wxchatSelectionBar.visibility = View.GONE
            binding.wxchatSearchInput.visibility = View.VISIBLE
            wxAdapter.notifyDataSetChanged()
        }
    }

    private fun renderWxSelection() {
        binding.wxchatSelectionBar.visibility = View.VISIBLE
        binding.wxchatSelCount.text = getString(R.string.folder_selected_count, wxSelected.size)
        binding.wxchatSelMove.isEnabled = wxSelected.isNotEmpty()
        binding.wxchatSelMove.alpha = if (wxSelected.isEmpty()) 0.5f else 1f
    }

    private fun toggleWxSelection(item: WxShareItem) {
        if (!wxSelected.add(item.shareId)) wxSelected.remove(item.shareId)
        renderWxSelection()
        wxAdapter.notifyDataSetChanged()
    }

    /** 多选移入：列出已有文件夹 + 新建项，批量加入（复用单条版的对话框样式）。 */
    private fun showBatchAddDialog(payload: List<Pair<String, String>>) {
        FolderAddFlow.moveBatch(this, payload) {
            exitAsrSelection()
            exitWxSelection()
            // move 语义：归档后条目离开根目录，刷新两个列表（按参与 kinds 刷新）
            if (payload.any { it.first == "task" }) loadTasks()
            if (payload.any { it.first == "wxchat" }) loadWxShares()
        }
    }

    // ---------- 多选批量操作：复制全文 / 分享 / 分享链接（作用于勾选的已完成条目） ----------

    /** 勾选中已完成识别的条目（按列表顺序）；一个都没有时提示并返回空。 */
    private fun selectedDoneTasks(): List<TaskItem> {
        val done = items.filter { it.id in asrSelected && it.status == "SUCCEEDED" }
        if (done.isEmpty()) {
            Toast.makeText(this, R.string.asr_batch_none_done, Toast.LENGTH_SHORT).show()
        }
        return done
    }

    /** 批量复制全文（share=true 时改为系统分享同一份汇总文本）；单条拉取失败不阻断其余。 */
    private fun batchCopyTranscripts(share: Boolean) {
        val done = selectedDoneTasks()
        if (done.isEmpty()) return
        Toast.makeText(this, getString(R.string.asr_batch_copy_progress, done.size), Toast.LENGTH_SHORT).show()
        Thread {
            val ok = ArrayList<Pair<String, String>>(done.size)
            val failed = ArrayList<String>()
            for (t in done) {
                try {
                    val task = AsrClient.getTask(this, t.id)
                    ok.add(t.name to AsrTranscriptsJson.plainText(this, task.getJSONObject("result"),
                        task.optJSONObject("options")))
                } catch (e: Exception) {
                    Log.w(logTag, "batch transcript fetch failed: ${t.id}", e)
                    failed.add(t.name)
                }
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                val sb = StringBuilder(AsrTranscripts.joinDocuments(ok))
                if (failed.isNotEmpty()) {
                    if (sb.isNotEmpty()) sb.append("\n\n")
                    sb.append(getString(R.string.asr_batch_fetch_failed, failed.size, failed.joinToString("、")))
                }
                if (share) {
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_TEXT, sb.toString())
                    startActivity(Intent.createChooser(send, getString(R.string.asr_btn_share)))
                } else {
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText(getString(R.string.asr_result_title), sb.toString()))
                    Toast.makeText(this, getString(R.string.asr_batch_copied, ok.size), Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    /** 批量生成分享链接：逐条建分享（参数沿用上次分享的记忆值），汇总成链接清单供复制/分享。 */
    private fun batchShareLinks() {
        val done = selectedDoneTasks()
        if (done.isEmpty()) return
        AsrLinkBatch.generateAndShow(this, done.map { it.name to it.id })
    }

    // ---------- 资料夹：移入对话框（新建或选已有，录音与聊天记录共用一套文件夹）

    /** 长按记录 →「移入文件夹」：列出已有文件夹 + 新建项，选中即移入。 */
    private fun showAddToFolderDialog(kind: String, ref: String, title: String) {
        FolderAddFlow.moveSingle(this, kind, ref, title) {
            // move 语义：归档后条目离开根目录，刷新对应列表让它消失
            if (kind == "task") loadTasks() else loadWxShares()
        }
    }

    /** 昵称→真名管理：列出历史分享中出现过的微信昵称，逐个设置真名（参考音视频说话人标注）。 */
    private fun showNameMappingDialog() {
        Thread {
            try {
                val j = AsrClient.listWxNameMappings(this)
                val names = j.optJSONObject("names") ?: JSONObject()
                val known = j.optJSONArray("known") ?: org.json.JSONArray()
                val selfNick = names.optString("__self__", "")
                val items = ArrayList<Pair<String, String>>()
                for (i in 0 until known.length()) {
                    val n = known.optString(i)
                    if (n.isNotEmpty()) items.add(n to names.optString(n, ""))
                }
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    if (items.isEmpty()) {
                        Toast.makeText(this, R.string.wxchat_names_empty, Toast.LENGTH_SHORT).show()
                        return@runOnUiThread
                    }
                    val labels = items.map { (n, r) ->
                        val me = if (n == selfNick) getString(R.string.wxchat_name_me_suffix) else ""
                        when {
                            r.isNotEmpty() -> getString(R.string.wxchat_name_row, n, r) + me
                            else -> n + me
                        }
                    }
                    AlertDialog.Builder(this)
                        .setTitle(R.string.wxchat_names_title)
                        .setItems(labels.toTypedArray()) { _, which ->
                            promptRealname(items[which].first, items[which].second,
                                items[which].first == selfNick)
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) Toast.makeText(this, asrErrorText(e), Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun promptRealname(nickname: String, current: String, isSelf: Boolean) {
        val input = EditText(this).apply {
            hint = getString(R.string.wxchat_name_hint)
            setText(current)
            setSingleLine(true)
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.wxchat_name_edit_title, nickname))
            .setMessage(getString(R.string.wxchat_name_edit_msg))
            .setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(
                if (isSelf) R.string.wxchat_name_unmark_me else R.string.wxchat_name_mark_me
            ) { _, _ ->
                Thread {
                    try {
                        AsrClient.setWxNameMapping(this, nickname, current, markSelf = !isSelf)
                        runOnUiThread {
                            if (!isFinishing && !isDestroyed) {
                                Toast.makeText(this, if (isSelf) R.string.wxchat_name_unmarked
                                else R.string.wxchat_name_marked, Toast.LENGTH_SHORT).show()
                            }
                        }
                    } catch (e: Exception) {
                        runOnUiThread {
                            if (!isFinishing && !isDestroyed) Toast.makeText(this, asrErrorText(e), Toast.LENGTH_LONG).show()
                        }
                    }
                }.start()
            }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val real = input.text.toString().trim()
                Thread {
                    try {
                        AsrClient.setWxNameMapping(this, nickname, real)
                        runOnUiThread {
                            if (!isFinishing && !isDestroyed) {
                                Toast.makeText(this, R.string.wxchat_name_saved, Toast.LENGTH_SHORT).show()
                            }
                        }
                    } catch (e: Exception) {
                        runOnUiThread {
                            if (!isFinishing && !isDestroyed) Toast.makeText(this, asrErrorText(e), Toast.LENGTH_LONG).show()
                        }
                    }
                }.start()
            }
            .show()
    }

    private fun wxMetaText(item: WxShareItem): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)
        val remain = ((item.expiresAt - System.currentTimeMillis() / 1000) / 86400).coerceAtLeast(0)
        return getString(R.string.wxchat_item_meta, item.messageCount,
            item.attachmentCount, fmt.format(Date(item.expiresAt * 1000)), remain)
    }

    private fun loadWxShares() {
        if (!AsrAuth.hasToken(this)) {
            refreshWxChatPanel()
            return
        }
        Thread {
            try {
                // 先刷新归档索引：根目录只显示未归入资料夹的条目
                val filed = refreshFolderIndex().second
                val arr = AsrClient.listWxChatShares(this)
                val parsed = ArrayList<WxShareItem>(arr.length())
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val sid = o.optString("share_id")
                    if (sid in filed) continue  // 已归档：不出现在根目录
                    val disp = ArrayList<String>()
                    val pArr = o.optJSONArray("participants_display")
                    if (pArr != null) {
                        for (k in 0 until pArr.length()) {
                            val n = pArr.optString(k)
                            if (n.isNotEmpty()) disp.add(n)
                        }
                    }
                    if (disp.isEmpty()) disp.add(o.optString("title", "聊天记录"))
                    parsed.add(
                        WxShareItem(
                            shareId = sid,
                            pwd = o.optString("pwd"),
                            title = o.optString("title", "聊天记录"),
                            messageCount = o.optInt("message_count", 0),
                            participantCount = o.optInt("participant_count", 0),
                            participantsDisplay = disp,
                            attachmentCount = o.optInt("attachment_count", 0),
                            expiresAt = o.optLong("expires_at"),
                            url = o.optString("url"),
                            ocrState = o.optString("ocr_state", "none"),
                            ocrDone = o.optInt("ocr_done", 0),
                            ocrTotal = o.optInt("ocr_total", 0)
                        )
                    )
                }
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    refreshWxChatPanel()
                    wxShares.clear()
                    wxShares.addAll(parsed)
                    if (wxSelectMode) {
                        wxSelected.retainAll(parsed.map { it.shareId })
                        renderWxSelection()
                    }
                    wxAdapter.notifyDataSetChanged()
                    renderWxEmpty()
                }
            } catch (e: AsrAuthException) {
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        Toast.makeText(this, asrErrorText(e), Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) Toast.makeText(this, asrErrorText(e), Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun copyWxLink(item: WxShareItem) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("share",
            getString(R.string.wxchat_share_text, item.title, item.url)))
        Toast.makeText(this, R.string.asr_share_copied, Toast.LENGTH_SHORT).show()
    }

    /** 重命名记录：分享页标题与 Agent JSON 的 title 服务端同步生效。 */
    private fun promptRenameWxShare(item: WxShareItem) {
        val input = EditText(this).apply {
            setText(item.title)
            setSingleLine(true)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.wxchat_rename_title)
            .setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val t = input.text.toString().trim()
                if (t.isEmpty() || t == item.title) return@setPositiveButton
                Thread {
                    try {
                        AsrClient.renameWxChatShare(this, item.shareId, t)
                        runOnUiThread {
                            if (isFinishing || isDestroyed) return@runOnUiThread
                            Toast.makeText(this, R.string.wxchat_rename_saved,
                                Toast.LENGTH_SHORT).show()
                            loadWxShares()
                        }
                    } catch (e: Exception) {
                        runOnUiThread {
                            if (!isFinishing && !isDestroyed) {
                                Toast.makeText(this, asrErrorText(e), Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                }.start()
            }
            .show()
    }

    // ---------- 分享页内预览：BACK 关闭必回列表，不依赖任务栈 ----------

    private var wxPreviewWebView: WebView? = null

    /** 点击条目：在设置页内的全屏 WebView 打开分享页。
     *  之前用独立任务启动主页——任务栈行为在部分系统上不可靠，返回会丢列表页；
     *  页内预览的返回逻辑完全自己控制：BACK 关预览必回列表。 */
    private fun openWxShare(item: WxShareItem) {
        showWxPreview(item.url)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun showWxPreview(url: String) {
        val container = binding.wxPreviewContainer
        val wv = wxPreviewWebView ?: WebView(this).also { wv ->
            wv.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                useWideViewPort = true
                loadWithOverviewMode = true
            }
            wv.webViewClient = WebViewClient()
            // 附件下载交给系统下载器（分享页的「下载」链接）
            wv.setDownloadListener { dlUrl, _, _, _, _ ->
                runCatching {
                    val req = android.app.DownloadManager.Request(Uri.parse(dlUrl))
                        .setTitle(getString(R.string.wxchat_download_title))
                        .setNotificationVisibility(
                            android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    (getSystemService(Context.DOWNLOAD_SERVICE)
                        as android.app.DownloadManager).enqueue(req)
                    Toast.makeText(this, R.string.wxchat_download_started, Toast.LENGTH_SHORT).show()
                }.onFailure {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(dlUrl)))
                }
            }
            wxPreviewWebView = wv
            container.addView(wv, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
        wv.loadUrl(url)
        container.visibility = View.VISIBLE
    }

    private fun closeWxPreview() {
        binding.wxPreviewContainer.visibility = View.GONE
        // 销毁重建：防止页内跳转留下的 history 变成多余的返回次数
        wxPreviewWebView?.apply {
            (parent as? FrameLayout)?.removeView(this)
            destroy()
        }
        wxPreviewWebView = null
    }

    private fun wxPreviewVisible(): Boolean =
        binding.wxPreviewContainer.visibility == View.VISIBLE

    private fun confirmDeleteWxShare(item: WxShareItem) {
        AlertDialog.Builder(this)
            .setTitle(R.string.wxchat_delete_title)
            .setMessage(getString(R.string.wxchat_delete_confirm, item.title))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                Thread {
                    try {
                        AsrClient.deleteWxChatShare(this, item.shareId)
                        runOnUiThread {
                            if (isFinishing || isDestroyed) return@runOnUiThread
                            Toast.makeText(this, R.string.wxchat_deleted, Toast.LENGTH_SHORT).show()
                            loadWxShares()
                        }
                    } catch (e: Exception) {
                        runOnUiThread {
                            if (!isFinishing && !isDestroyed) {
                                Toast.makeText(this, asrErrorText(e), Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                }.start()
            }
            .show()
    }

    // ---------- 资料夹：根目录过滤 ----------

    /** 拉文件夹列表刷新归档索引，并更新两个页签入口行的计数文案。
     *  供列表加载线程直接调用；入口行文案经 runOnUiThread 回主线程更新。 */
    private fun refreshFolderIndex(): Pair<Set<String>, Set<String>> {
        val folders = AsrClient.listFolders(this)
        folderCount = folders.size
        filedTaskRefs = folders.flatMap { f -> f.items.filter { it.first == "task" } }
            .map { it.second }.toSet()
        filedWxRefs = folders.flatMap { f -> f.items.filter { it.first == "wxchat" } }
            .map { it.second }.toSet()
        val filed = filedTaskRefs.size + filedWxRefs.size
        val text = if (folderCount > 0)
            getString(R.string.folder_entry_count, folderCount, filed)
        else getString(R.string.folder_entry_desc)
        runOnUiThread {
            binding.asrFolderEntryText.text = text
            binding.wxchatFolderEntryText.text = text
        }
        return filedTaskRefs to filedWxRefs
    }

    private fun loadTasks() {
        Thread {
            try {
                // 先刷新归档索引：根目录只显示未归入资料夹的条目
                val filed = refreshFolderIndex().first
                val arr = AsrClient.listTasks(this)
                val parsed = ArrayList<TaskItem>(arr.length())
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val id = o.optString("task_id")
                    if (id in filed) continue  // 已归档：不出现在根目录
                    parsed.add(
                        TaskItem(
                            id = id,
                            name = o.optString("name", "未命名录音"),
                            status = o.optString("status"),
                            stage = o.optString("stage"),
                            createdAt = o.optLong("created_at"),
                            durationMs = o.optLong("duration_ms"),
                            isVideo = o.optBoolean("is_video"),
                            shareCount = o.optInt("share_count", 0)
                        )
                    )
                }
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    items.clear()
                    items.addAll(parsed)
                    if (asrSelectMode) {
                        // 刷新后清理已消失条目的勾选，避免计数虚高
                        asrSelected.retainAll(parsed.map { it.id })
                        renderAsrSelection()
                    }
                    taskAdapter.notifyDataSetChanged()
                    renderAsrEmpty()
                }
                asrLoaded = true
            } catch (e: AsrAuthException) {
                runOnUiThread { onAuthLost() }
            } catch (e: Exception) {
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) Toast.makeText(this, asrErrorText(e), Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    /** 点击条目：播放/暂停对应录音（语音消息式）。 */
    private fun togglePlay(item: TaskItem) {
        if (item.status != "SUCCEEDED") {
            Toast.makeText(this, R.string.asr_task_not_ready, Toast.LENGTH_SHORT).show()
            return
        }
        if (playingTaskId == item.id) {
            val p = mediaPlayer
            if (p != null) {
                if (p.isPlaying) p.pause() else p.start()
            }
            return
        }
        stopPlayback()
        playingTaskId = item.id
        taskAdapter.notifyDataSetChanged()
        mediaPlayer = MediaPlayer().apply {
            setAudioAttributes(
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            setDataSource(AsrClient.taskAudioUrl(this@SettingsActivity, item.id))
            setOnPreparedListener { it.start() }
            setOnCompletionListener {
                val finishedId = playingTaskId
                playingTaskId = null
                if (finishedId != null) taskAdapter.notifyDataSetChanged()
            }
            setOnErrorListener { _, what, extra ->
                Log.e(logTag, "playback error what=$what extra=$extra")
                stopPlayback()
                Toast.makeText(this@SettingsActivity, R.string.asr_playback_failed, Toast.LENGTH_SHORT).show()
                true
            }
            prepareAsync()
        }
    }

    private fun stopPlayback() {
        mediaPlayer?.run {
            runCatching { stop() }
            release()
        }
        mediaPlayer = null
        if (playingTaskId != null) {
            playingTaskId = null
            taskAdapter.notifyDataSetChanged()
        }
    }

    private fun openTask(item: TaskItem) {
        Thread {
            try {
                val task = AsrClient.getTask(this, item.id)
                val status = task.optString("status")
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    when (status) {
                        "SUCCEEDED" -> {
                            val resultFile = File(cacheDir, "asr_result_${item.id}.json")
                            resultFile.writeText(task.getJSONObject("result").toString(), Charsets.UTF_8)
                            val it = Intent(this, AsrResultActivity::class.java)
                                .putExtra("result_path", resultFile.absolutePath)
                                .putExtra("filename", item.name)
                                .putExtra("task_id", item.id)
                            val opts = task.optJSONObject("options")
                            if (opts != null) {
                                it.putExtra("opt_timestamps", opts.optBoolean("timestamps"))
                                    .putExtra("opt_diarize", opts.optBoolean("diarize"))
                                    .putExtra("opt_mandarin", opts.optBoolean("dialect2mandarin"))
                            }
                            startActivity(it)
                        }
                        "FAILED" -> Toast.makeText(
                            this,
                            getString(R.string.asr_failed_prefix) + task.optString("error", "识别失败"),
                            Toast.LENGTH_LONG
                        ).show()
                        else -> Toast.makeText(
                            this,
                            getString(R.string.asr_task_running, task.optString("stage", "处理中")),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            } catch (e: AsrAuthException) {
                runOnUiThread { onAuthLost() }
            } catch (e: Exception) {
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) Toast.makeText(this, asrErrorText(e), Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun confirmDelete(item: TaskItem) {
        AlertDialog.Builder(this)
            .setTitle(R.string.asr_delete_title)
            .setMessage(getString(R.string.asr_delete_confirm, item.name))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.asr_delete_ok) { _, _ ->
                Thread {
                    try {
                        AsrClient.deleteTask(this, item.id)
                        runOnUiThread {
                            if (isFinishing || isDestroyed) return@runOnUiThread
                            if (playingTaskId == item.id) stopPlayback()
                            items.removeAll { it.id == item.id }
                            taskAdapter.notifyDataSetChanged()
                            renderAsrEmpty()
                            Toast.makeText(this, R.string.asr_deleted, Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: AsrAuthException) {
                        runOnUiThread { onAuthLost() }
                    } catch (e: Exception) {
                        runOnUiThread {
                            if (!isFinishing && !isDestroyed) Toast.makeText(this, asrErrorText(e), Toast.LENGTH_LONG).show()
                        }
                    }
                }.start()
            }
            .show()
    }

    private fun onAuthLost() {
        if (isFinishing || isDestroyed) return
        stopPlayback()
        AppPrefs.clearAsrAuth(this)
        items.clear()
        taskAdapter.notifyDataSetChanged()
        refreshAsrPanel()
        Toast.makeText(this, R.string.asr_token_invalid, Toast.LENGTH_LONG).show()
    }

    private fun metaText(item: TaskItem): String {
        val parts = mutableListOf<String>()
        if (item.isVideo) parts.add("视频")
        when (item.status) {
            "SUCCEEDED" -> {
                val sec = item.durationMs / 1000
                if (sec > 0) parts.add("时长 %d分%02d秒".format(sec / 60, sec % 60))
                parts.add(formatDate(item.createdAt))
                if (item.shareCount > 0) parts.add("已分享${item.shareCount}条")
            }
            "FAILED" -> parts.add("识别失败 · " + formatDate(item.createdAt))
            else -> {
                val stage = item.stage.ifEmpty { "处理中" }
                parts.add("$stage · " + formatDate(item.createdAt))
            }
        }
        return parts.joinToString(" · ")
    }

    private fun asrErrorText(e: Exception): String =
        getString(R.string.asr_failed_prefix) + (e.message ?: e.toString())

    // ---------- 下载管理渲染 ----------

    private fun setupDownloadManage() {
        binding.btnDownloadManage.setOnClickListener { enterSelection(null) }
        binding.btnDownloadCancel.setOnClickListener { exitSelection() }
        binding.btnDownloadDelete.setOnClickListener { confirmDeleteSelected() }
        binding.cbDownloadSelectAll.setOnClickListener {
            // 点击时 CheckBox 已切换到新状态：勾上=全选，取消=清空
            val selectAll = binding.cbDownloadSelectAll.isChecked
            selectedIds.clear()
            if (selectAll) downloadStore.load().forEach { selectedIds.add(it.id) }
            renderDownloads()
        }
    }

    private fun renderDownloads() {
        val records = downloadStore.load()
        Log.d(logTag, "renderDownloads: ${records.size} records, selectionMode=$selectionMode")
        if (selectionMode) {
            binding.downloadSelectionBar.visibility = View.VISIBLE
            binding.btnDownloadManage.visibility = View.GONE
            binding.cbDownloadSelectAll.isChecked =
                records.isNotEmpty() && records.all { it.id in selectedIds }
            binding.btnDownloadDelete.text = getString(R.string.download_delete_fmt, selectedIds.size)
            binding.btnDownloadDelete.isEnabled = selectedIds.isNotEmpty()
            binding.btnDownloadDelete.alpha = if (selectedIds.isEmpty()) 0.5f else 1f
        } else {
            binding.downloadSelectionBar.visibility = View.GONE
            binding.btnDownloadManage.visibility =
                if (records.isEmpty()) View.GONE else View.VISIBLE
        }
        binding.downloadsContainer.removeAllViews()
        if (records.isEmpty()) {
            binding.downloadsContainer.addView(
                TextView(this).apply {
                    text = getString(R.string.downloads_empty)
                    setTextAppearance(android.R.style.TextAppearance_Small)
                    alpha = 0.7f
                    setPadding(0, dp(4), 0, dp(4))
                }
            )
            return
        }
        records.forEach { record ->
            binding.downloadsContainer.addView(buildDownloadRow(record))
        }
    }

    private fun buildDownloadRow(record: DownloadRecord): View {
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val title = TextView(this).apply {
            text = record.fileName.ifBlank { record.title.ifBlank { record.id } }
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextAppearance(android.R.style.TextAppearance_Medium)
        }
        content.addView(title)

        val statusText = TextView(this).apply {
            text = describeStatus(record)
            setTextAppearance(android.R.style.TextAppearance_Small)
            alpha = 0.8f
            setPadding(0, dp(2), 0, 0)
        }
        content.addView(statusText)

        if (record.status == DownloadStatus.RUNNING) {
            val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                isIndeterminate = false
                max = 100
                progress = record.progress
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(4) }
            }
            content.addView(bar)
        }

        if (selectionMode) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = ContextCompat.getDrawable(context, R.drawable.bg_glass_rounded)
                setPadding(dp(4), dp(4), dp(12), dp(4))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(8) }
            }
            row.addView(CheckBox(this).apply {
                isChecked = record.id in selectedIds
                isClickable = false
                isFocusable = false
            })
            content.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            row.addView(content)
            row.setOnClickListener { toggleSelect(record.id) }
            return row
        }

        content.background = ContextCompat.getDrawable(this, R.drawable.bg_glass_rounded)
        content.setPadding(dp(12), dp(10), dp(12), dp(10))
        content.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(8) }
        content.setOnClickListener { openDownload(record) }
        content.setOnLongClickListener { enterSelection(record.id); true }
        return content
    }

    // ---------- 下载管理：批量选择与删除 ----------

    private fun enterSelection(firstId: String?) {
        selectionMode = true
        selectedIds.clear()
        firstId?.let { selectedIds.add(it) }
        renderDownloads()
    }

    private fun exitSelection() {
        selectionMode = false
        selectedIds.clear()
        renderDownloads()
    }

    private fun toggleSelect(id: String) {
        if (!selectedIds.remove(id)) selectedIds.add(id)
        renderDownloads()
    }

    private fun confirmDeleteSelected() {
        if (selectedIds.isEmpty()) return
        val ids = selectedIds.toSet()
        val records = downloadStore.load().filter { it.id in ids }
        if (records.isEmpty()) {
            exitSelection()
            return
        }
        val hasFile = records.any {
            it.status == DownloadStatus.DONE &&
                (it.uri.startsWith("content://") || it.path.isNotBlank() || it.fileName.isNotBlank())
        }
        var fileBox: CheckBox? = null
        val builder = AlertDialog.Builder(this)
            .setTitle(R.string.download_delete_title)
            .setMessage(getString(R.string.download_delete_confirm_fmt, records.size))
        if (hasFile) {
            fileBox = CheckBox(this).apply {
                text = getString(R.string.download_delete_also_file)
                isChecked = true
                setPadding(dp(8), dp(12), dp(8), 0)
            }
            builder.setView(fileBox)
        }
        builder
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.download_delete) { _, _ ->
                deleteRecords(ids, fileBox?.isChecked ?: false)
            }
            .show()
    }

    private fun deleteRecords(ids: Set<String>, alsoDeleteFile: Boolean) {
        val records = downloadStore.load().filter { it.id in ids }
        Thread {
            var fileFail = 0
            records.forEach { record ->
                if (record.status == DownloadStatus.RUNNING) cancelRunningDownload(record)
                if (alsoDeleteFile && record.status == DownloadStatus.DONE &&
                    !deleteDownloadFile(record)
                ) {
                    fileFail++
                }
            }
            downloadStore.removeByIds(ids)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                exitSelection()
                val base = getString(R.string.download_deleted_fmt, records.size)
                val msg = if (fileFail > 0) {
                    "$base\n${getString(R.string.download_file_delete_partial_fail)}"
                } else {
                    base
                }
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            }
        }.start()
    }

    /** 取消进行中的下载：直链移除系统任务（连带半成品），m3u8 通知服务在下一分片前停止 */
    private fun cancelRunningDownload(record: DownloadRecord) {
        if (record.kind == DownloadKind.DIRECT) {
            val dmId = record.id.removePrefix("dm_").toLongOrNull() ?: return
            runCatching { getSystemService(DownloadManager::class.java)?.remove(dmId) }
                .onFailure { Log.w(logTag, "dm remove failed: ${it.message}") }
        } else {
            VideoDownloadService.requestCancel(record.id)
        }
    }

    /** 删除已完成下载对应的文件：先按记录/文件名找 MediaStore 条目删，找不到再按路径删 */
    private fun deleteDownloadFile(record: DownloadRecord): Boolean {
        val uri = runCatching { resolveRecordUri(record) }.getOrNull()
        if (uri != null) {
            val deleted = runCatching { contentResolver.delete(Uri.parse(uri), null, null) }
                .onFailure { Log.w(logTag, "media delete failed: ${it.message}") }
                .getOrDefault(0) > 0
            if (deleted) return true
        }
        candidateFiles(record).forEach { f ->
            if (f.exists() && runCatching { f.delete() }.getOrDefault(false)) return true
        }
        // 记录里既无 uri 也找不到文件，视为已删干净（可能早被手动清理）
        return uri == null && candidateFiles(record).none { it.exists() }
    }

    private fun candidateFiles(record: DownloadRecord): List<File> {
        val out = mutableListOf<File>()
        if (record.path.isNotBlank()) {
            val p = File(record.path)
            out += if (p.isAbsolute) p else File(Environment.getExternalStorageDirectory(), record.path)
        }
        if (record.fileName.isNotBlank()) {
            out += File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "轻舟浏览器/${record.fileName}"
            )
        }
        return out.distinctBy { it.absolutePath }
    }


    /** 直链下载实时进度（DownloadManager 查询；m3u8 用记录里的分片进度） */
    private fun queryDirectProgress(record: DownloadRecord): Int? {
        val dmId = record.id.removePrefix("dm_").toLongOrNull() ?: return null
        val dm = getSystemService(DownloadManager::class.java) ?: return null
        return runCatching {
            dm.query(DownloadManager.Query().setFilterById(dmId))?.use { c ->
                if (!c.moveToFirst()) return@runCatching null
                val soFarIdx = c.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                val totalIdx = c.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                if (soFarIdx < 0 || totalIdx < 0) return@runCatching null
                val soFar = c.getLong(soFarIdx)
                val total = c.getLong(totalIdx)
                if (total <= 0) null else ((soFar * 100) / total).toInt().coerceIn(0, 100)
            }
        }.getOrNull()
    }

    private fun describeStatus(record: DownloadRecord): String = when (record.status) {
        DownloadStatus.RUNNING -> {
            val pct = if (record.kind == com.trae.simplebrowser.core.DownloadKind.DIRECT) {
                queryDirectProgress(record) ?: record.progress
            } else {
                record.progress
            }
            "${record.detail.ifBlank { getString(R.string.download_status_downloading) }} $pct%"
        }

        DownloadStatus.DONE -> getString(
            R.string.download_status_done_fmt,
            formatTime(record.finishedAt)
        ) + if (record.path.isNotBlank()) "\n${record.path}" else ""

        DownloadStatus.FAILED -> getString(
            R.string.download_status_failed_fmt,
            record.detail.ifBlank { getString(R.string.video_download_error_network) }
        )
    }

    /** 点击记录：解析 content uri 直接打开；静态 uri 缺失时按文件名查 MediaStore */
    private fun openDownload(record: DownloadRecord) {
        if (record.status != DownloadStatus.DONE) {
            Toast.makeText(this, R.string.download_open_failed, Toast.LENGTH_LONG).show()
            return
        }
        val uri = resolveRecordUri(record)
        if (uri == null) {
            Toast.makeText(this, R.string.download_open_failed, Toast.LENGTH_LONG).show()
            return
        }
        runCatching {
            startActivity(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(Uri.parse(uri), "video/*")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            )
        }.onFailure {
            Log.w(logTag, "open download failed: ${it.message}")
            Toast.makeText(this, R.string.download_open_failed, Toast.LENGTH_LONG).show()
        }
    }

    /** 已下载文件的 content uri：优先记录值，否则按 DISPLAY_NAME 查 Downloads/Video 集合 */
    private fun resolveRecordUri(record: DownloadRecord): String? {
        if (record.uri.startsWith("content://")) return record.uri
        val name = record.fileName
        if (name.isBlank()) return null
        queryUriByName(MediaStore.Downloads.EXTERNAL_CONTENT_URI, name)?.let { return it }
        queryUriByName(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, name)?.let { return it }
        return null
    }

    private fun queryUriByName(collection: Uri, displayName: String): String? = runCatching {
        contentResolver.query(
            collection,
            arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.DISPLAY_NAME}=?",
            arrayOf(displayName),
            null
        )?.use { c ->
            if (c.moveToFirst()) {
                android.content.ContentUris.withAppendedId(collection, c.getLong(0)).toString()
            } else {
                null
            }
        }
    }.getOrNull()

    private fun formatTime(millis: Long): String {
        if (millis <= 0) return ""
        return SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(millis))
    }

    private fun formatDate(ts: Long): String {
        if (ts <= 0) return ""
        // 服务端 created_at 是秒级时间戳，小于 1e12 视为秒，避免被当毫秒格式化成 1970 年
        val ms = if (ts < 10_000_000_000L) ts * 1000 else ts
        return SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(ms))
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        stopPlayback()
        super.onDestroy()
    }

    // Two-finger swipe down gesture variables
    private var startY1 = 0f
    private var startY2 = 0f
    private var isTwoFingerGesture = false
    private val swipeThresholdPx by lazy { 100 * resources.displayMetrics.density }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            wxPreviewVisible() -> closeWxPreview()  // 一步回列表；预览内不做页内后退
            asrSelectMode -> exitAsrSelection()
            wxSelectMode -> exitWxSelection()
            selectionMode -> exitSelection()
            else -> super.onBackPressed()
        }
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        val action = ev.actionMasked
        when (action) {
            android.view.MotionEvent.ACTION_POINTER_DOWN -> {
                if (ev.pointerCount == 2) {
                    startY1 = ev.getY(0)
                    startY2 = ev.getY(1)
                    isTwoFingerGesture = true
                }
            }
            android.view.MotionEvent.ACTION_POINTER_UP -> {
                 if (ev.pointerCount <= 2) {
                     isTwoFingerGesture = false
                 }
            }
            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                isTwoFingerGesture = false
            }
            android.view.MotionEvent.ACTION_MOVE -> {
                if (isTwoFingerGesture && ev.pointerCount >= 2) {
                    val y1 = ev.getY(0)
                    val y2 = ev.getY(1)

                    val dy1 = y1 - startY1
                    val dy2 = y2 - startY2

                    // Check if both fingers moved down significantly (> 100dp)
                    if (dy1 > swipeThresholdPx && dy2 > swipeThresholdPx) {
                        isTwoFingerGesture = false // Reset
                        finish() // Close the settings activity
                    }
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }
}
