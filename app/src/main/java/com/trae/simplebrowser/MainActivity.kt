package com.trae.simplebrowser

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.Manifest
import android.app.DownloadManager
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.SystemClock
import android.text.Editable
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextWatcher
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.webkit.ConsoleMessage
import android.webkit.HttpAuthHandler
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.ValueCallback
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebViewDatabase
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.CheckBox
import android.widget.EditText
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.getSystemService
import androidx.core.graphics.drawable.IconCompat
import com.trae.simplebrowser.core.AdBlockEngine
import com.trae.simplebrowser.core.DownloadKind
import com.trae.simplebrowser.core.DownloadRecord
import com.trae.simplebrowser.core.DownloadStatus
import com.trae.simplebrowser.core.HistoryStore
import com.trae.simplebrowser.core.HttpAuthStore
import com.trae.simplebrowser.core.IconCropMath
import com.trae.simplebrowser.core.MediaProbeLogic
import com.trae.simplebrowser.core.MultiFingerGestureTracker
import com.trae.simplebrowser.core.ProfileManager
import com.trae.simplebrowser.core.SearchEngines
import com.trae.simplebrowser.core.SuggestEngine
import com.trae.simplebrowser.core.TabManager
import com.trae.simplebrowser.core.VideoKind
import com.trae.simplebrowser.core.VideoUrlSniffer
import com.trae.simplebrowser.databinding.ActivityMainBinding
import java.io.ByteArrayInputStream
import java.net.URLEncoder
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.PermissionRequest
import android.net.http.SslError
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListPopupWindow
import android.widget.TextView
import androidx.core.content.ContextCompat
import android.provider.MediaStore
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private var currentTitle: String = ""
    private val logTag = "LiteBrowser"
    private val collectVideoUrlJs = """(function(){var vs=document.querySelectorAll('video');var best='';var bestArea=0;for(var i=0;i<vs.length;i++){var v=vs[i];var s=v.currentSrc||v.src||'';if(!s){var q=v.querySelector('source');if(q)s=q.src||''}if(!s)continue;var area=(v.videoWidth||v.clientWidth||0)*(v.videoHeight||v.clientHeight||0);if(!v.paused&&!v.ended){return s}if(area>=bestArea){bestArea=area;best=s}}return best})()"""
    private var lastMixedContentFixAtMs: Long = 0
    private var lastPopupBlockedToastAtMs: Long = 0
    /** Basic 认证登录框是否正在展示：并发 401（主文档+子资源同时质询）时只弹一个，其余直接 cancel */
    private var httpAuthDialogShowing = false
    private var currentFavicon: Bitmap? = null
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var pendingCapturedUri: Uri? = null
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var webViewVisibilityBeforeFullscreen: Int = View.VISIBLE
    private var bottomContainerVisibilityBeforeFullscreen: Int = View.VISIBLE

    // ---------- 多标签（F-08） ----------
    private val tabManager = TabManager()
    private val tabWebViews = mutableMapOf<Long, WebView>()
    private var webViewLayoutParams: ViewGroup.LayoutParams? = null

    // ---------- 桌面版 UA（F-15，按标签记忆） ----------
    private val tabDesktopMode = mutableMapOf<Long, Boolean>()

    // ---------- 地址栏联想（F-15） ----------
    private var suggestPopup: ListPopupWindow? = null
    private var suggestItems: List<SuggestEngineResult> = emptyList()
    private var suppressSuggestOnce = false

    /** 联想下拉条目（title + url 双行展示用） */
    private data class SuggestEngineResult(val title: String, val url: String)

    // ---------- 网页内查找（F-15） ----------
    private var findBar: LinearLayout? = null
    private var findInput: EditText? = null
    private var findCountView: TextView? = null

    // ---------- 多账号身份（Profile 隔离） ----------
    /** 已创建的身份数量（含身份1）；仅在设备支持多身份时可能大于 1 */
    private var identityCount = 1

    /** 本会话已出结论的「站点|身份」组合（当前身份已有 cookie、或已弹过提示），不再重复探测/打扰 */
    private val loginHintSettledKeys = mutableSetOf<String>()

    /** 当前标签的 WebView；标签集合永不为空（最后一个标签关闭时立即补开新标签） */
    private val currentWebView: WebView
        get() = tabWebViews[tabManager.currentId]
            ?: error("no WebView for tab ${tabManager.currentId}")

    private fun attachTabWebView(tabId: Long) {
        val target = tabWebViews[tabId] ?: return
        val old = tabWebViews[tabManager.currentId]
        if (old != null && old !== target) {
            old.onPause()
            binding.root.removeView(old)
        }
        if (target.parent == null) {
            binding.root.addView(target, 0, ensureWebViewLayoutParams())
        }
        target.onResume()
        tabManager.select(tabId)
        setAddressBarText(target.url.orEmpty())
        updateBookmarkIcon()
        updateIdentityBadge()
        applyAdBadge()
        hideFindBar()
        Log.d(logTag, "Tab attach: $tabId, url=${target.url.orEmpty().take(60)}")
    }

    private fun ensureWebViewLayoutParams(): ViewGroup.LayoutParams {
        webViewLayoutParams?.let { return it }
        return binding.webView.layoutParams.also { webViewLayoutParams = it }
    }

    /** 新建一个标签（含 WebView）；已达上限时 Toast 提示。identity：目标身份序号（1 起始） */
    private fun openNewTab(url: String = homeUrl(), identity: Int = 1): Long {
        if (!tabManager.canCreate()) {
            Toast.makeText(
                this,
                getString(R.string.toast_tab_limit, tabManager.size),
                Toast.LENGTH_SHORT
            ).show()
            return tabManager.currentId ?: -1L
        }
        val profileName = ProfileManager.profileNameOf(identity)
        if (identity > 1 && !ProfileManager.isSupported()) {
            Toast.makeText(this, R.string.identity_unsupported, Toast.LENGTH_LONG).show()
            return tabManager.currentId ?: -1L
        }
        if (identity > identityCount) {
            // 快捷方式/弹窗直接以更高身份进入：自动登记身份数量，徽标与身份清单才能生效
            identityCount = identity
            AppPrefs.setIdentityCount(this, identity)
        }
        val layoutParams = ensureWebViewLayoutParams()
        val oldView = currentWebViewSafe()
        val webView = WebView(this)
        if (!ProfileManager.applyTo(webView, profileName)) {
            // 绑定失败绝不能按该身份继续，否则会与其他账号串登录态
            Log.e(logTag, "profile bind failed: $profileName, fallback to default identity")
            Toast.makeText(this, R.string.identity_unsupported, Toast.LENGTH_LONG).show()
            return tabManager.currentId ?: -1L
        }
        setupWebView(webView, profileName)
        val tab = tabManager.create(url = url, profile = profileName)
        tabWebViews[tab.id] = webView
        if (tabDesktopMode[tab.id] == true) {
            webView.settings.userAgentString = DESKTOP_USER_AGENT
        }

        oldView?.let {
            it.onPause()
            binding.root.removeView(it)
        }
        binding.root.addView(webView, 0, layoutParams)
        webView.loadUrl(url)
        setAddressBarText(url)
        updateBookmarkIcon()
        updateIdentityBadge()
        updateTabBadge()
        Log.i(logTag, "Tab created: ${tab.id} url=$url identity=$identity (total=${tabManager.size})")
        return tab.id
    }

    private fun currentWebViewSafe(): WebView? = tabWebViews[tabManager.currentId]

    /** 关闭标签；关到最后一个时自动补开主页标签 */
    private fun closeTab(tabId: Long) {
        val view = tabWebViews.remove(tabId) ?: return
        (view.parent as? ViewGroup)?.removeView(view)
        view.removeAllViews()
        view.destroy()
        val nextId = tabManager.close(tabId)
        if (nextId == null) {
            Log.i(logTag, "Tab closed: $tabId -> no tabs left, open home tab")
            openNewTab()
        } else {
            attachTabWebView(nextId)
        }
        updateTabBadge()
        Log.i(logTag, "Tab closed: $tabId (total=${tabManager.size})")
    }

    private fun updateTabBadge() {
        binding.tabCountBadge.text = tabManager.size.toString()
    }

    // ---------- 多账号身份（Profile 隔离） ----------
    // ---------- 多账号身份（Profile 隔离） ----------

    /** 从 intent 读取目标身份序号；设备不支持多身份时按身份1（普通打开）处理 */
    private fun resolveIdentityExtra(intent: Intent?): Int {
        val identity = intent?.getIntExtra(EXTRA_IDENTITY, 1) ?: 1
        if (identity <= 1) return 1
        return if (ProfileManager.isSupported()) identity.coerceAtMost(ProfileManager.MAX_IDENTITY_INDEX) else 1
    }

    /** 当前标签所属身份序号 */
    private fun currentTabIdentity(): Int =
        tabManager.current?.let { ProfileManager.indexOfProfileName(it.profile) } ?: 1

    /** 当前标签所属身份的 Profile 名 */
    private fun currentProfileName(): String =
        tabManager.current?.profile ?: ProfileManager.DEFAULT_PROFILE

    private fun identityColor(index: Int): Int = ContextCompat.getColor(
        this,
        when (index) {
            2 -> R.color.identity_2
            3 -> R.color.identity_3
            4 -> R.color.identity_4
            else -> R.color.identity_1
        }
    )

    /** 地址栏左侧身份徽标：仅当存在多身份且当前标签不是身份1 时显示 */
    private fun updateIdentityBadge() {
        val identity = currentTabIdentity()
        if (identity <= 1 || identityCount <= 1) {
            binding.identityBadge.visibility = View.GONE
            return
        }
        binding.identityBadge.text = identity.toString()
        binding.identityBadge.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(identityColor(identity))
        }
        binding.identityBadge.contentDescription = getString(R.string.identity_badge_desc, identity)
        binding.identityBadge.visibility = View.VISIBLE
    }

    /** 身份选择弹窗：点选身份即以其打开当前网页；可新建身份 */
    private fun showIdentityDialog() {
        if (!ProfileManager.isSupported()) {
            Toast.makeText(this, R.string.identity_unsupported, Toast.LENGTH_LONG).show()
            return
        }
        val currentUrl = currentWebView.url.orEmpty().takeIf { it.isNotBlank() } ?: homeUrl()
        val currentIdentity = currentTabIdentity()

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(20), dpToPx(12), dpToPx(20), dpToPx(4))
        }
        container.addView(TextView(this).apply {
            text = getString(R.string.identity_dialog_hint)
            textSize = 13f
            setPadding(0, 0, 0, dpToPx(8))
        })

        fun identityRow(index: Int, label: String, onClick: () -> Unit): View {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dpToPx(4), dpToPx(10), dpToPx(4), dpToPx(10))
                setBackgroundResource(android.R.color.transparent)
            }
            val dot = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(dpToPx(14), dpToPx(14)).apply {
                    marginEnd = dpToPx(10)
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(identityColor(index))
                }
            }
            val text = TextView(this).apply {
                this.text = label
                textSize = 16f
            }
            row.addView(dot)
            row.addView(text)
            row.setOnClickListener { onClick() }
            return row
        }

        for (i in 1..identityCount) {
            val label = if (i == currentIdentity) {
                getString(R.string.identity_row_current, i)
            } else {
                getString(R.string.identity_row, i)
            }
            container.addView(
                identityRow(i, label) {
                    dialogRef?.dismiss()
                    if (i == currentIdentity) {
                        Toast.makeText(this, getString(R.string.identity_row_current, i), Toast.LENGTH_SHORT).show()
                    } else {
                        openNewTab(currentUrl, i)
                        Toast.makeText(this, getString(R.string.identity_switched, i, currentIdentity), Toast.LENGTH_SHORT).show()
                    }
                }
            )
        }

        if (identityCount < ProfileManager.MAX_IDENTITY_INDEX) {
            val next = identityCount + 1
            container.addView(
                identityRow(next, getString(R.string.identity_new_row, next)) {
                    dialogRef?.dismiss()
                    identityCount = next
                    AppPrefs.setIdentityCount(this, next)
                    openNewTab(currentUrl, next)
                    Toast.makeText(this, getString(R.string.identity_created, next), Toast.LENGTH_SHORT).show()
                }
            )
        } else {
            container.addView(TextView(this).apply {
                text = getString(R.string.identity_max_reached, ProfileManager.MAX_IDENTITY_INDEX)
                textSize = 12f
                alpha = 0.7f
                setPadding(dpToPx(4), dpToPx(6), 0, dpToPx(6))
            })
        }

        dialogRef = AlertDialog.Builder(this)
            .setTitle(getString(R.string.identity_dialog_title))
            .setView(container)
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun tabIdOf(view: WebView): Long? =
        tabWebViews.entries.firstOrNull { it.value === view }?.key

    // ---------- 跨身份登录态提示 ----------
    // 场景：同一网站在身份1 登录过，之后从身份2/3 的标签再打开时表现为"登录丢了"。
    // 此时提示用户该站登录态还在另一个身份里，可一键切回；只提示不自动切换，
    // 因为多身份的意义就是同站多账号共存，自动跳身份会破坏这个语义。

    /**
     * 当前标签身份在某站没有任何 cookie、而另一身份存有登录样式 cookie 时弹提示。
     * 「站点|身份」只在结论确定时登记：当前身份已有 cookie（无需提示）或已弹过提示；
     * 结论未定（两边都没数据）不登记，下次导航再探，避免首跳探测过早错过后续登录态变化。
     * 注意：命名 Profile 的 CookieManager.getCookie 在非主线程上恒返回 null（真机实测、无异常），
     * 因此探测必须留在主线程，不能挪去后台线程池。
     */
    private fun maybeShowCrossIdentityLoginHint(view: WebView, url: String) {
        if (!ProfileManager.isSupported() || !url.startsWith("http")) return
        if (view !== currentWebViewSafe()) return
        val tabId = tabIdOf(view) ?: return
        val tab = tabManager.tabs.firstOrNull { it.id == tabId } ?: return
        val host = runCatching { Uri.parse(url).host }.getOrNull()?.takeIf { it.isNotBlank() } ?: return
        val key = "$host|${tab.profile}"
        if (key in loginHintSettledKeys) return
        if (host in AppPrefs.getLoginHintDisabledHosts(this)) return

        val currentCookies = runCatching { ProfileManager.cookieManager(tab.profile).getCookie(url) }.getOrNull()
        if (!currentCookies.isNullOrEmpty()) {
            loginHintSettledKeys.add(key) // 本身份在该站有数据，会话内免打扰
            return
        }
        val identityCount = AppPrefs.getIdentityCount(this)
        val found = (1..identityCount).firstOrNull { i ->
            val name = ProfileManager.profileNameOf(i)
            if (name == tab.profile) return@firstOrNull false
            val cookies = runCatching { ProfileManager.cookieManager(name).getCookie(url) }.getOrNull()
            !cookies.isNullOrEmpty() && looksLikeLoginCookies(cookies)
        } ?: return
        loginHintSettledKeys.add(key)
        showCrossIdentityLoginHintDialog(tabId, url, found, host)
    }

    /** 登录样式 cookie 启发式：access_token / sessionid / auth / passport 一类名字；统计类（_ga、MUID 等）不命中 */
    private fun looksLikeLoginCookies(cookieHeader: String): Boolean =
        Regex("token|sess|auth|login|passport|jwt|ticket|\\bsid", RegexOption.IGNORE_CASE)
            .containsMatchIn(cookieHeader)

    private fun showCrossIdentityLoginHintDialog(tabId: Long, url: String, identity: Int, host: String) {
        val currentIdentity = ProfileManager.indexOfProfileName(
            tabManager.tabs.firstOrNull { it.id == tabId }?.profile ?: ProfileManager.DEFAULT_PROFILE
        )
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.identity_login_hint_title))
            .setMessage(getString(R.string.identity_login_hint_msg, identity, currentIdentity))
            .setPositiveButton(getString(R.string.identity_login_hint_switch, identity)) { _, _ ->
                val newTabId = openNewTab(url, identity)
                if (newTabId != -1L) closeTab(tabId)
            }
            .setNeutralButton(R.string.identity_login_hint_never) { _, _ ->
                AppPrefs.addLoginHintDisabledHost(this, host)
            }
            .setNegativeButton(R.string.identity_login_hint_stay, null)
            .show()
    }

    private val fileChooserLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val callback = filePathCallback
            filePathCallback = null
            
            var uris: Array<Uri>? = null
            
            if (result.resultCode == RESULT_OK) {
                // 1. Try to parse from Intent data
                val data = result.data
                if (data != null) {
                    val targetUris = mutableListOf<Uri>()
                    data.data?.let { targetUris.add(it) }
                    data.clipData?.let { clipData ->
                        for (i in 0 until clipData.itemCount) {
                            targetUris.add(clipData.getItemAt(i).uri)
                        }
                    }
                    if (targetUris.isNotEmpty()) {
                        uris = targetUris.toTypedArray()
                    }
                }
                
                // 2. Fallback to standard parser
                if (uris == null) {
                    uris = WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
                }

                // 3. Check for camera capture (if no other URIs found)
                if ((uris == null || uris.isEmpty()) && pendingCapturedUri != null) {
                    uris = arrayOf(pendingCapturedUri!!)
                }
            }

            if (uris != null && uris.isNotEmpty()) {
                // Clean up pending capture if we are using selected files instead
                if (pendingCapturedUri != null && !uris.contains(pendingCapturedUri)) {
                     runCatching { contentResolver.delete(pendingCapturedUri!!, null, null) }
                }
                
                takePersistableReadPermissionIfPossible(result.data, uris)
                callback?.onReceiveValue(uris)
            } else {
                callback?.onReceiveValue(null)
                pendingCapturedUri?.let { runCatching { contentResolver.delete(it, null, null) } }
            }
            pendingCapturedUri = null
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) {
            @Suppress("DEPRECATION")
            pendingCapturedUri = savedInstanceState.getParcelable("pending_captured_uri")
        }
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 广告拦截引擎：异步加载内置+在线规则表
        AdBlockEngine.initAsync(applicationContext)

        // 12368 短信备份：启动时补传一次离线期间攒下的队列
        SmsBackupManager.flushPendingAsync(applicationContext)

        identityCount = AppPrefs.getIdentityCount(this)

        // 冷启动若从"身份快捷方式"进入（extra_identity>1 且设备支持），
        // 首个标签直接按该身份构造，不使用布局中的默认 WebView
        val launchIdentity = resolveIdentityExtra(intent)
        val firstTabUsesIdentity = launchIdentity > 1
        if (firstTabUsesIdentity) {
            binding.root.removeView(binding.webView)
            openNewTab(
                intent?.dataString?.takeIf { it.isNotBlank() } ?: homeUrl(),
                launchIdentity
            )
        } else {
            // 第一个标签复用布局中的 WebView（F-08）
            setupWebView(binding.webView)
            val firstTab = tabManager.create(url = homeUrl())
            tabWebViews[firstTab.id] = binding.webView
        }
        setupControls()
        updateTabBadge()
        updateIdentityBadge()

        val initialUrl = intent?.dataString
        if (!firstTabUsesIdentity) {
            if (!initialUrl.isNullOrBlank()) {
                loadUrl(initialUrl)
            } else if (savedInstanceState == null) {
                loadUrl(homeUrl())
            }
        }

        if (savedInstanceState == null) {
            handleIncomingShare(intent)
        }
        registerDownloadCompleteReceiver()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putParcelable("pending_captured_uri", pendingCapturedUri)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // 快捷方式/外链进入：确保已有实例能拿到最新 intent（缺陷修复：桌面快捷方式打开仍是旧页面）
        setIntent(intent)
        val identity = resolveIdentityExtra(intent)
        val url = intent.dataString
        if (identity > 1) {
            // 身份快捷方式：新开该身份标签并加载目标网址
            openNewTab(url?.takeIf { it.isNotBlank() } ?: homeUrl(), identity)
            Toast.makeText(this, getString(R.string.identity_opened, identity), Toast.LENGTH_SHORT).show()
        } else if (intent.hasExtra(EXTRA_IDENTITY) && currentTabIdentity() != 1) {
            // 身份1 快捷方式：当前标签不是身份1 时新开身份1标签，
            // 避免"身份1的快捷方式却在身份2/3标签里打开、登录态对不上"
            openNewTab(url?.takeIf { it.isNotBlank() } ?: homeUrl(), 1)
        } else if (!url.isNullOrBlank()) {
            Log.i(logTag, "onNewIntent -> loadUrl: $url")
            loadUrl(url)
        }
        handleIncomingShare(intent)
    }

    /** 接收外部分享（SEND / SEND_MULTIPLE）：微信聊天记录 zip 优先转存；
     *  其余逐项识别音视频进入转写流程，无匹配类型仅提示已收到 */
    private fun handleIncomingShare(intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) return
        val mime = intent.type.orEmpty()
        Log.i(logTag, "收到分享: action=$action mime=$mime hasStream=${intent.hasExtra(Intent.EXTRA_STREAM)}")
        @Suppress("DEPRECATION")
        val streams: List<Uri> = when (action) {
            Intent.ACTION_SEND -> listOfNotNull(intent.getParcelableExtra(Intent.EXTRA_STREAM))
            else -> intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                ?: listOfNotNull(intent.getParcelableExtra(Intent.EXTRA_STREAM))
        }
        // 微信合并转发分享固定为「聊天记录_*.zip」，初判文件名，服务端解压再终判
        val wxItems = streams.filter { WxChatShareFlow.isWxChatZip(this, it) }
            .map { AsrShareFlow.MediaItem(it, "application/zip") }
        if (wxItems.isNotEmpty()) {
            WxChatShareFlow.offer(this, wxItems)
            if (wxItems.size == streams.size) return
        }
        // 逐项核实音视频类型：微信多文件分享常把 intent 类型标成通配，不能只看 intent.mime
        val mediaItems = streams.filter { it !in wxItems.map { w -> w.uri } }.mapNotNull { uri ->
            AsrShareFlow.resolveMediaMime(this, uri, mime)?.let { AsrShareFlow.MediaItem(uri, it) }
        }
        if (mediaItems.isNotEmpty()) {
            AsrShareFlow.offerAudioProcessing(this, mediaItems)
            return
        }
        if (wxItems.isEmpty()) {
            Toast.makeText(this, R.string.share_received_toast, Toast.LENGTH_SHORT).show()
        }
    }

    private val gestureTracker by lazy {
        MultiFingerGestureTracker(
            swipeThresholdPx = 100 * resources.displayMetrics.density,
            edgeExclusionPx = 48 * resources.displayMetrics.density
        )
    }
    private var lastTwoFingerRefreshAtMs: Long = 0
    private var lastHideToggleAtMs: Long = 0

    /** 网络候选池：记录全部 http(s) 请求地址（url → 最近活跃时刻），点下载时再粗筛+探测。
     *  视频站流地址多无扩展名，仅凭 URL 后缀识别会漏（v1.5.x 优酷失败的教训）。 */
    private val sniffPool = LinkedHashMap<String, Long>()
    private var pendingHlsDownload: HlsDownloadParams? = null

    /** 探测线程池：并发对候选 URL 发轻量 Range 请求 */
    private val probeExecutor by lazy { java.util.concurrent.Executors.newFixedThreadPool(6) }

    private data class HlsDownloadParams(
        val url: String,
        val title: String,
        val referer: String,
        val cookie: String?,
        val ua: String
    )

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            Log.i(logTag, "VideoDownload: notification permission granted=$granted")
            val params = pendingHlsDownload
            pendingHlsDownload = null
            params?.let { startVideoDownloadService(it) }
        }

    // ---------- 快捷方式自定义图标（F-13） ----------
    private var pendingCustomIcon: Bitmap? = null
    private var pendingIconCaptureUri: Uri? = null
    private var pendingDialogText: String? = null

    private val galleryIconLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) loadCustomIcon(uri, deleteAfter = false)
        }

    private val cameraIconLauncher =
        registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
            val uri = pendingIconCaptureUri
            pendingIconCaptureUri = null
            if (ok && uri != null) {
                loadCustomIcon(uri, deleteAfter = true)
            } else {
                uri?.let { runCatching { contentResolver.delete(it, null, null) } }
            }
        }

    private val cameraPermissionForIconLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                launchIconCapture()
            } else {
                Log.w(logTag, "camera permission denied for icon capture")
                Toast.makeText(this, R.string.toast_camera_needed, Toast.LENGTH_SHORT).show()
            }
        }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        val action = ev.actionMasked
        when (action) {
            android.view.MotionEvent.ACTION_POINTER_DOWN -> {
                if (ev.pointerCount >= 2) {
                    val tracked = minOf(ev.pointerCount, 3)
                    val xs = FloatArray(tracked) { ev.getX(it) }
                    val ys = FloatArray(tracked) { ev.getY(it) }
                    gestureTracker.onPointerDown(
                        ev.pointerCount, xs, ys,
                        resources.displayMetrics.widthPixels.toFloat(),
                        resources.displayMetrics.heightPixels.toFloat()
                    )
                    Log.d(logTag, "MultiFingerGesture start count=${ev.pointerCount} tracked=$tracked")
                } else {
                    gestureTracker.reset()
                    Log.d(logTag, "MultiFingerGesture aborted: pointerCount=${ev.pointerCount}")
                }
            }
            android.view.MotionEvent.ACTION_POINTER_UP -> {
                gestureTracker.reset()
                Log.d(logTag, "MultiFingerGesture end: finger lifted, remaining=${ev.pointerCount}")
            }
            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                gestureTracker.reset()
            }
            android.view.MotionEvent.ACTION_MOVE -> {
                if (ev.pointerCount >= 2) {
                    val tracked = minOf(ev.pointerCount, 3)
                    val xs = FloatArray(tracked) { ev.getX(it) }
                    val ys = FloatArray(tracked) { ev.getY(it) }
                    when (gestureTracker.onMove(xs, ys)) {
                        MultiFingerGestureTracker.Gesture.TWO_FINGER_SWIPE_UP -> {
                            val now = SystemClock.uptimeMillis()
                            if (now - lastTwoFingerRefreshAtMs > 800) {
                                lastTwoFingerRefreshAtMs = now
                                Log.i(logTag, "MultiFingerGesture TWO_FINGER_SWIPE_UP -> reload page")
                                currentWebView.reload()
                                Toast.makeText(
                                    this,
                                    getString(R.string.toast_page_refreshed),
                                    Toast.LENGTH_SHORT
                                ).show()
                            } else {
                                Log.d(logTag, "MultiFingerGesture TWO_FINGER_SWIPE_UP ignored: refresh cooldown")
                            }
                        }
                        MultiFingerGestureTracker.Gesture.TWO_FINGER_SWIPE_DOWN -> toggleControlsVisibility()
                        MultiFingerGestureTracker.Gesture.TWO_FINGER_SWIPE_RIGHT -> {
                            Log.i(logTag, "MultiFingerGesture TWO_FINGER_SWIPE_RIGHT -> open settings")
                            startActivity(Intent(this, SettingsActivity::class.java))
                        }
                        MultiFingerGestureTracker.Gesture.NONE -> Unit
                    }
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onResume() {
        super.onResume()
        applyControlsVisibility()
        updateBookmarkIcon()
        // 转写批次在服务里跑：页面回前台时补展示后台完成的结果/失败汇总
        AsrShareFlow.activityResumed = true
        AsrShareFlow.drainPending(this)
        WxChatShareFlow.drainPending(this)
        // 仅恢复当前标签的渲染与计时器；后台标签保持暂停
        currentWebView.onResume()
        currentWebView.resumeTimers()
        // 设置页改动的浏览体验项对所有标签立即生效（无图模式/字体缩放）
        val dataSaver = AppPrefs.isDataSaver(this)
        val textZoom = AppPrefs.getTextZoom(this)
        tabWebViews.values.forEach {
            it.settings.blockNetworkImage = dataSaver
            it.settings.textZoom = textZoom
        }
        if (AppPrefs.consumePendingClearCache(this) && tabWebViews.isNotEmpty()) {
            runCatching { tabWebViews.values.first().clearCache(true) }
            Toast.makeText(this, R.string.toast_cache_cleared, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onPause() {
        AsrShareFlow.activityResumed = false
        // 全局暂停所有标签的计时器与布局
        tabWebViews.values.forEach { it.onPause() }
        currentWebViewSafe()?.pauseTimers()
        super.onPause()
    }

    override fun onDestroy() {
        tabWebViews.values.forEach { wv ->
            (wv.parent as? ViewGroup)?.removeView(wv)
            wv.removeAllViews()
            wv.destroy()
        }
        tabWebViews.clear()
        AdBlockEngine.persistTotalBlocked(this)
        probeExecutor.shutdownNow()
        unregisterDownloadCompleteReceiver()
        super.onDestroy()
    }

    override fun onBackPressed() {
        // 如果处于全屏模式，先退出全屏
        if (customView != null) {
            customViewCallback?.onCustomViewHidden()

            // 手动清理视图（以防回调未触发或有延迟）
            binding.videoContainer.removeView(customView)
            binding.videoContainer.visibility = View.GONE

            customView = null
            customViewCallback = null
            binding.btnVideoDownload.visibility = View.GONE
            // 恢复进入全屏模式前的可见性状态
            currentWebView.visibility = webViewVisibilityBeforeFullscreen
            binding.bottomContainer.visibility = bottomContainerVisibilityBeforeFullscreen
        } else if (currentWebView.canGoBack()) {
            currentWebView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    private fun setupControls() {
        binding.backButton.setOnClickListener {
            if (currentWebView.canGoBack()) currentWebView.goBack()
        }
        binding.forwardButton.setOnClickListener {
            if (currentWebView.canGoForward()) currentWebView.goForward()
        }
        binding.refreshButton.setOnClickListener { currentWebView.reload() }

        binding.tabButton.setOnClickListener { showTabSwitcher() }
        binding.tabCountBadge.setOnClickListener { showTabSwitcher() }

        binding.goButton.setOnClickListener { loadFromInput() }
        binding.urlEditText.setOnEditorActionListener { _, actionId, event ->
            val isGo = actionId == EditorInfo.IME_ACTION_GO
            val isEnter = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
            if (isGo || isEnter) {
                loadFromInput()
                true
            } else {
                false
            }
        }

        binding.bookmarkButton.setOnClickListener { toggleBookmark() }
        binding.scanButton.setOnClickListener {
            Log.i(logTag, "ScanButton clicked -> open ScanActivity")
            startActivity(Intent(this, ScanActivity::class.java))
        }
        binding.btnVideoDownload.setOnClickListener { onVideoDownloadClicked() }
        binding.addToHomeButton.setOnClickListener { showPinShortcutNameDialog() }
        binding.moreButton.setOnClickListener { showMoreMenu() }
        setupUrlSuggest()
    }

    /** 标签切换器：列表（切换/关闭）+ 新建 */
    private fun showTabSwitcher() {
        Log.i(logTag, "showTabSwitcher: ${tabManager.size} tabs")
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(8), dpToPx(4), dpToPx(8), dpToPx(4))
        }
        if (tabManager.tabs.isEmpty()) {
            container.addView(TextView(this).apply { text = getString(R.string.tab_empty) })
        }
        tabManager.tabs.forEach { tab ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dpToPx(8), dpToPx(6), dpToPx(8), dpToPx(6))
                if (tab.id == tabManager.currentId) {
                    background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_glass_rounded)
                }
            }
            val titleView = TextView(this).apply {
                text = tab.title.ifBlank { getString(R.string.tab_default_title) }
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val identityIndex = ProfileManager.indexOfProfileName(tab.profile)
            if (identityIndex > 1 && identityCount > 1) {
                val dot = View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(dpToPx(10), dpToPx(10)).apply {
                        marginEnd = dpToPx(8)
                    }
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(identityColor(identityIndex))
                    }
                }
                row.addView(dot)
            }
            val closeBtn = ImageButton(this).apply {
                setBackgroundResource(android.R.color.transparent)
                setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
                layoutParams = LinearLayout.LayoutParams(dpToPx(36), dpToPx(36))
            }
            closeBtn.setOnClickListener {
                Log.i(logTag, "tab close clicked: ${tab.id}")
                closeTab(tab.id)
                dialogRef?.dismiss()
                showTabSwitcher()
            }
            row.addView(titleView)
            row.addView(closeBtn)
            row.setOnClickListener {
                attachTabWebView(tab.id)
                dialogRef?.dismiss()
            }
            container.addView(row)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.tab_switcher_title))
            .setView(container)
            .setPositiveButton(getString(R.string.tab_new)) { _, _ -> openNewTab() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        dialogRef = dialog
    }

    private var dialogRef: AlertDialog? = null

    private fun setupWebView(webView: WebView, profileName: String = ProfileManager.DEFAULT_PROFILE) {
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.loadsImagesAutomatically = true
        settings.blockNetworkImage = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        settings.allowFileAccess = true
        settings.allowContentAccess = true
        // 多窗口必须开启，否则 window.open / target=_blank 会退化为当前窗口跳转，
        // 网页广告得以直接"劫持"整个页面；开启后统一走 onCreateWindow 拦截分流
        settings.javaScriptCanOpenWindowsAutomatically = true
        settings.setSupportMultipleWindows(true)
        settings.mediaPlaybackRequiresUserGesture = false
        settings.databaseEnabled = true

        // 无图模式（省流量）与网页字体缩放（设置页可调，onResume 对全部标签刷新）
        settings.blockNetworkImage = AppPrefs.isDataSaver(this)
        settings.textZoom = AppPrefs.getTextZoom(this)

        // 设置用户代理为较新的Chrome版本，以支持更多播放器（桌面版切换见 tabDesktopMode）
        val originalUA = settings.userAgentString
        settings.userAgentString = MOBILE_USER_AGENT

        // Cookie 按身份取：非默认身份使用其 Profile 独立的 CookieManager
        val profileCookies = ProfileManager.cookieManager(profileName)
        CookieManager.getInstance().setAcceptCookie(true)
        profileCookies.setAcceptCookie(true)
        profileCookies.setAcceptThirdPartyCookies(webView, true)
        WebViewDatabase.getInstance(this)

        // 网页内查找：匹配计数回调（查找栏 UI 见 showFindBar()）
        webView.setFindListener { activeMatchOrdinal, numberOfMatches, _ ->
            findCountView?.text = getString(R.string.find_count_fmt, activeMatchOrdinal + 1, numberOfMatches)
        }

        webView.webViewClient = object : WebViewClient() {
            private fun handleNonHttpUri(uri: Uri): Boolean {
                return handleExternalOrSpecialUri(uri)
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url ?: return false
                return handleNonHttpUri(uri)
            }

            @Suppress("OVERRIDE_DEPRECATION")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return false
                return handleNonHttpUri(uri)
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val url = request.url ?: return null
                val urlStr = url.toString()
                recordMediaUrl(urlStr)

                // 广告拦截：主文档放行（避免整页白屏），仅拦子资源；命中广告域名表直接掐断
                if (urlStr.startsWith("http") && !request.isForMainFrame) {
                    // pageHost 传 null，引擎按 view 自查在案的主文档 host（供白名单判断）
                    val blocked = AdBlockEngine.shouldBlock(this@MainActivity, view, null, url.host)
                    if (blocked) {
                        val count = AdBlockEngine.recordBlocked(view, urlStr)
                        Log.i(logTag, "AdBlock: #$count ${url.host}${url.encodedPath.orEmpty().take(40)}")
                        binding.root.post { updateAdBadge() }
                        return emptyBlockedResponse()
                    }
                }

                if (url.scheme != "http") return null

                val host = url.host.orEmpty()
                val path = url.encodedPath.orEmpty()
                val looksLikeImage =
                    path.endsWith(".png", ignoreCase = true) ||
                        path.endsWith(".jpg", ignoreCase = true) ||
                        path.endsWith(".jpeg", ignoreCase = true) ||
                        path.endsWith(".gif", ignoreCase = true) ||
                        path.endsWith(".webp", ignoreCase = true) ||
                        path.endsWith(".svg", ignoreCase = true)

                if (!looksLikeImage && !host.endsWith("qpic.cn")) return null

                Log.i(logTag, "Intercept http resource: $url")
                val httpsUrl = url.buildUpon().scheme("https").build().toString()
                return runCatching { fetchAsWebResourceResponse(view, httpsUrl, request.requestHeaders) }
                    .getOrNull()
            }

            override fun onPageFinished(view: WebView, url: String) {
                val tabId = tabIdOf(view)
                tabId?.let { tabManager.update(it, url = url) }
                if (view === currentWebViewSafe()) {
                    setAddressBarText(url)
                    updateBookmarkIcon()
                }
                runMixedContentFixThrottled(view)
                injectAdAutoHideJs(view, url)
                // 浏览历史：仅记录 http(s) 主文档（file://、intent: 等不记）
                if (url.startsWith("http")) {
                    AppPrefs.addHistory(this@MainActivity, currentTitle, url)
                }
            }

            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                AdBlockEngine.resetPageBlocked(view)
                AdBlockEngine.setPageHost(view, runCatching { Uri.parse(url).host }.getOrNull())
                if (view === currentWebViewSafe()) {
                    setAddressBarText(url)
                    updateBookmarkIcon()
                    updateAdBadge()
                }
                maybeShowCrossIdentityLoginHint(view, url)
                runMixedContentFixThrottled(view)
            }

            override fun onLoadResource(view: WebView, url: String) {
                recordMediaUrl(url)
                runMixedContentFixThrottled(view)
            }

            override fun onReceivedHttpError(
                view: WebView,
                request: WebResourceRequest,
                errorResponse: WebResourceResponse
            ) {
                val url = request.url?.toString().orEmpty()
                Log.w(logTag, "HTTP ${errorResponse.statusCode} for $url")
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                val url = request.url?.toString().orEmpty()
                Log.w(logTag, "ERR ${error.errorCode} ${error.description} for $url")
            }

            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                Log.e(logTag, "SSL error: ${error.primaryError} url=${error.url}")
                handler.cancel()
            }

            override fun onReceivedHttpAuthRequest(
                view: WebView,
                handler: HttpAuthHandler,
                host: String?,
                realm: String?
            ) {
                showHttpAuthPrompt(handler, host, realm)
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            /**
             * 新窗口统一拦截分流：
             * - JS 自动弹窗（isUserGesture=false）：广告拦截开启时拒绝并提示——这是"打开网页跳出广告"的主要来源；
             * - 用户手势弹窗（target=_blank 正常链接）：取链接地址新标签打开，原页面保留。
             * 广告拦截总开关只影响自动弹窗；target=_blank 属于基础浏览能力，始终可用。
             */
            override fun onCreateWindow(
                view: WebView,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: android.os.Message?
            ): Boolean {
                Log.i(logTag, "onCreateWindow: gesture=$isUserGesture dialog=$isDialog")
                if (!isUserGesture && AdBlockEngine.isEnabled(this@MainActivity)) {
                    val now = SystemClock.uptimeMillis()
                    if (now - lastPopupBlockedToastAtMs > 2500) {
                        lastPopupBlockedToastAtMs = now
                        Toast.makeText(this@MainActivity, R.string.toast_popup_blocked, Toast.LENGTH_SHORT).show()
                    }
                    return false
                }
                // 优先用 hitTestResult 直接拿点击链接地址（target=_blank 场景最常见）
                val hitUrl = view.hitTestResult?.extra
                if (!hitUrl.isNullOrBlank() &&
                    (hitUrl.startsWith("http://") || hitUrl.startsWith("https://"))
                ) {
                    openNewTab(hitUrl)
                    return false
                }
                // 兜底：window.open 场景用临时 WebView 接收并捕获首个真实地址
                val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                val probe = WebView(view.context)
                var captured = false
                probe.webViewClient = object : WebViewClient() {
                    private fun capture(url: String?) {
                        if (captured) return
                        val u = url ?: return
                        if (!u.startsWith("http://") && !u.startsWith("https://")) return
                        captured = true
                        runOnUiThread {
                            if (!isFinishing) openNewTab(u)
                            probe.destroy()
                        }
                    }

                    override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean {
                        capture(request.url?.toString())
                        return true
                    }

                    @Suppress("OVERRIDE_DEPRECATION")
                    override fun shouldOverrideUrlLoading(v: WebView, url: String): Boolean {
                        capture(url)
                        return true
                    }

                    override fun onPageStarted(v: WebView, url: String, favicon: android.graphics.Bitmap?) {
                        capture(url)
                    }
                }
                transport.webView = probe
                // 必须 sendToTarget，引擎才会把弹窗导航装进 probe；缺了它 probe 永远空载
                resultMsg.sendToTarget()
                probe.postDelayed({
                    if (!captured) {
                        captured = true
                        probe.destroy()
                    }
                }, 15_000)
                return true
            }

            override fun onProgressChanged(view: WebView, newProgress: Int) {
                if (view !== currentWebViewSafe()) return
                if (newProgress == 100) {
                    binding.progressBar.visibility = View.GONE
                } else {
                    if (binding.progressBar.visibility != View.VISIBLE) {
                        binding.progressBar.visibility = View.VISIBLE
                    }
                    binding.progressBar.progress = newProgress
                }
                super.onProgressChanged(view, newProgress)
            }

            override fun onReceivedTitle(view: WebView, title: String?) {
                val t = title.orEmpty()
                tabIdOf(view)?.let { tabManager.update(it, title = t) }
                if (view === currentWebViewSafe()) {
                    currentTitle = t
                }
            }

            override fun onReceivedIcon(view: WebView, icon: Bitmap?) {
                if (icon != null) currentFavicon = icon
                super.onReceivedIcon(view, icon)
            }

            override fun onShowFileChooser(
                webView: WebView,
                filePathCallback: ValueCallback<Array<Uri>>,
                fileChooserParams: FileChooserParams
            ): Boolean {
                this@MainActivity.filePathCallback?.onReceiveValue(null)
                this@MainActivity.filePathCallback = filePathCallback
                val intent = runCatching { createBestFileChooserIntent(fileChooserParams) }.getOrNull()
                if (intent == null) {
                    this@MainActivity.filePathCallback = null
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.toast_file_chooser_failed),
                        Toast.LENGTH_SHORT
                    ).show()
                    return true
                }

                launchFileChooserIntent(intent)
                return true
            }

            override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                Log.i(logTag, "console: ${consoleMessage.message()} @${consoleMessage.sourceId()}:${consoleMessage.lineNumber()}")
                return super.onConsoleMessage(consoleMessage)
            }
            
            // 处理视频全屏显示
            override fun onShowCustomView(view: View, callback: WebChromeClient.CustomViewCallback) {
                if (customView != null) {
                    callback.onCustomViewHidden()
                    return
                }
                
                customView = view
                customViewCallback = callback

                // 保存进入全屏模式前的可见性状态
                webViewVisibilityBeforeFullscreen = currentWebView.visibility
                bottomContainerVisibilityBeforeFullscreen = binding.bottomContainer.visibility

                // 将自定义视图添加到视频容器中
                binding.videoContainer.addView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                binding.videoContainer.visibility = View.VISIBLE

                // 隐藏浏览器UI
                currentWebView.visibility = View.GONE
                binding.bottomContainer.visibility = View.GONE

                // 全屏视频窗口内显示浮动下载按钮（F-07），并置于视频视图之上
                binding.btnVideoDownload.visibility = View.VISIBLE
                binding.btnVideoDownload.bringToFront()
            }
            
            override fun onHideCustomView() {
                customView ?: return
                
                // 移除自定义视图
                binding.videoContainer.removeView(customView)
                binding.videoContainer.visibility = View.GONE
                customView = null
                customViewCallback?.onCustomViewHidden()
                customViewCallback = null

                binding.btnVideoDownload.visibility = View.GONE

                // 恢复进入全屏模式前的可见性状态
                currentWebView.visibility = webViewVisibilityBeforeFullscreen
                binding.bottomContainer.visibility = bottomContainerVisibilityBeforeFullscreen
            }
            
            override fun getVideoLoadingProgressView(): View? {
                // 返回一个加载指示器视图
                val progressView = View(this@MainActivity)
                progressView.layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                progressView.setBackgroundColor(Color.BLACK)
                return progressView
            }
            
            // 处理带有方向的全屏请求
            override fun onShowCustomView(view: View, requestedOrientation: Int, callback: WebChromeClient.CustomViewCallback) {
                onShowCustomView(view, callback)
            }
            
            // 提供默认的视频海报图像
            override fun getDefaultVideoPoster(): Bitmap? {
                return BitmapFactory.decodeResource(resources, android.R.drawable.ic_media_play)
            }
            
            // 处理权限请求
            override fun onPermissionRequest(request: PermissionRequest) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    // 授予所有请求的权限
                    request.grant(request.resources)
                }
            }
            
            // 处理权限请求取消
            override fun onPermissionRequestCanceled(request: PermissionRequest) {
                // 权限请求被取消，无需处理
            }
        }
    }

    private fun createBestFileChooserIntent(params: WebChromeClient.FileChooserParams): Intent {
        val acceptTypes = params.acceptTypes.orEmpty()
            .asSequence()
            .flatMap { it.split(',').asSequence() }
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .toList()

        val pickerMimeTypes = acceptTypes
            .filter { it != "*/*" }
            .ifEmpty { emptyList() }

        // Use ACTION_GET_CONTENT instead of ACTION_OPEN_DOCUMENT for better compatibility with image pickers
        val pickerIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, params.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE)
            if (pickerMimeTypes.isEmpty()) {
                type = "*/*"
            } else if (pickerMimeTypes.size == 1) {
                type = pickerMimeTypes[0]
            } else {
                type = "*/*"
                putExtra(Intent.EXTRA_MIME_TYPES, pickerMimeTypes.toTypedArray())
            }
            // ACTION_GET_CONTENT grants temporary read permission by default
            // We can add FLAG_GRANT_READ_URI_PERMISSION explicitly too
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        val initialIntents = mutableListOf<Intent>()
        if (params.isCaptureEnabled && Build.VERSION.SDK_INT >= 29) {
            val wantsImage = pickerMimeTypes.isEmpty() || pickerMimeTypes.any { it.startsWith("image/") || it == "image/*" }
            val wantsVideo = pickerMimeTypes.isEmpty() || pickerMimeTypes.any { it.startsWith("video/") || it == "video/*" }

            if (wantsImage) {
                createImageCaptureIntent()?.let { initialIntents.add(it) }
            }
            if (wantsVideo) {
                createVideoCaptureIntent()?.let { initialIntents.add(it) }
            }
        }

        return Intent(Intent.ACTION_CHOOSER).apply {
            putExtra(Intent.EXTRA_INTENT, pickerIntent)
            putExtra(
                Intent.EXTRA_TITLE,
                params.title?.toString().orEmpty().ifBlank { getString(R.string.label_choose_file) }
            )
            if (initialIntents.isNotEmpty()) {
                putExtra(Intent.EXTRA_INITIAL_INTENTS, initialIntents.toTypedArray())
            }
        }
    }

    private fun createImageCaptureIntent(): Intent? {
        val uri = createPendingMediaUri(
            collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            mimeType = "image/jpeg",
            fileExtension = "jpg",
            prefix = "litebrowser_img_"
        ) ?: return null

        pendingCapturedUri = uri
        return Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, uri)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newRawUri("output", uri)
        }
    }

    private fun createVideoCaptureIntent(): Intent? {
        val uri = createPendingMediaUri(
            collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            mimeType = "video/mp4",
            fileExtension = "mp4",
            prefix = "litebrowser_vid_"
        ) ?: return null

        pendingCapturedUri = uri
        return Intent(MediaStore.ACTION_VIDEO_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, uri)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newRawUri("output", uri)
        }
    }

    private fun createPendingMediaUri(
        collection: Uri,
        mimeType: String,
        fileExtension: String,
        prefix: String
    ): Uri? {
        val displayName = prefix + System.currentTimeMillis() + "." + fileExtension
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
        }
        return runCatching { contentResolver.insert(collection, values) }.getOrNull()
    }

    private fun takePersistableReadPermissionIfPossible(data: Intent?, uris: Array<Uri>?) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) return
        if (data == null) return
        if (uris.isNullOrEmpty()) return
        
        // Some pickers don't set the action in the result intent, so we skip the action check.
        // if (data.action != Intent.ACTION_OPEN_DOCUMENT) return

        val flags = data.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION
        if (flags == 0) return

        uris.forEach { uri ->
            runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
    }

    private fun launchFileChooserIntent(intent: Intent) {
        runCatching {
            fileChooserLauncher.launch(intent)
        }.onFailure {
            filePathCallback?.onReceiveValue(null)
            filePathCallback = null
            pendingCapturedUri?.let { runCatching { contentResolver.delete(it, null, null) } }
            pendingCapturedUri = null
            Toast.makeText(this, getString(R.string.toast_file_chooser_failed), Toast.LENGTH_SHORT).show()
        }
    }

    private fun runMixedContentFixThrottled(view: WebView) {
        val now = SystemClock.uptimeMillis()
        if (now - lastMixedContentFixAtMs < 1500) return
        lastMixedContentFixAtMs = now
        upgradeKnownInsecureImages(view)
    }

    private fun fetchAsWebResourceResponse(
        view: WebView,
        httpsUrl: String,
        requestHeaders: Map<String, String>
    ): WebResourceResponse? {
        val connection = (URL(httpsUrl).openConnection() as? HttpURLConnection) ?: return null
        connection.instanceFollowRedirects = true
        connection.connectTimeout = 15000
        connection.readTimeout = 20000

        val userAgent = view.settings.userAgentString
        if (userAgent.isNotBlank()) connection.setRequestProperty("User-Agent", userAgent)

        requestHeaders["Accept"]?.let { connection.setRequestProperty("Accept", it) }
        requestHeaders["Accept-Language"]?.let { connection.setRequestProperty("Accept-Language", it) }
        requestHeaders["Referer"]?.let { connection.setRequestProperty("Referer", it) }

        val cookie = ProfileManager.cookieManager(currentProfileName()).getCookie(httpsUrl)
        if (!cookie.isNullOrBlank()) connection.setRequestProperty("Cookie", cookie)

        val responseCode = runCatching { connection.responseCode }.getOrElse { return null }
        if (responseCode >= 400) return null

        val contentType = connection.contentType.orEmpty()
        val mimeType = contentType.substringBefore(';').trim().ifBlank { null } ?: return null
        val encoding = contentType.substringAfter("charset=", "").trim().ifBlank { "utf-8" }
        val inputStream = runCatching { connection.inputStream }.getOrElse { return null }

        val response = WebResourceResponse(mimeType, encoding, inputStream)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            response.setStatusCodeAndReasonPhrase(responseCode, connection.responseMessage ?: "OK")
            val responseHeaders = mutableMapOf<String, String>()
            connection.headerFields.orEmpty().forEach { (key, values) ->
                if (!key.isNullOrBlank() && !values.isNullOrEmpty()) {
                    responseHeaders[key] = values.joinToString(",")
                }
            }
            if (responseHeaders.isNotEmpty()) response.responseHeaders = responseHeaders
        }
        Log.i(logTag, "Upgraded resource to HTTPS: $httpsUrl")
        return response
    }

    private fun upgradeKnownInsecureImages(view: WebView) {
        Log.i(logTag, "Inject mixed-content fixer")
        val js = """
            (function () {
              try { console.log('litebrowser_fix_mixed_content_ready'); } catch (e) {}
              function fixUrl(u) {
                if (!u || typeof u !== 'string') return u;
                if (u.indexOf('http://wework.qpic.cn/') === 0) return 'https://' + u.substring(7);
                if (u.indexOf('http://') === 0 && u.indexOf('.qpic.cn/') > 0) return 'https://' + u.substring(7);
                return u;
              }
              function fixImg(img) {
                try {
                  var src = img.getAttribute('src');
                  var next = fixUrl(src);
                  if (next !== src) img.setAttribute('src', next);
                } catch (e) {}
              }
              function fixStyle(el) {
                try {
                  var cs = window.getComputedStyle(el);
                  var bg = cs && cs.backgroundImage;
                  if (bg && bg.indexOf('http://') >= 0) {
                    var nextBg = bg.replace(/http:\/\/wework\.qpic\.cn\//g, 'https://wework.qpic.cn/')
                                   .replace(/http:\/\/([^\"')]+\.qpic\.cn\/)/g, 'https://$1');
                    if (nextBg !== bg) el.style.backgroundImage = nextBg;
                  }
                  var ls = cs && cs.listStyleImage;
                  if (ls && ls.indexOf('http://') >= 0) {
                    var nextLs = ls.replace(/http:\/\/wework\.qpic\.cn\//g, 'https://wework.qpic.cn/')
                                   .replace(/http:\/\/([^\"')]+\.qpic\.cn\/)/g, 'https://$1');
                    if (nextLs !== ls) el.style.listStyleImage = nextLs;
                  }
                } catch (e) {}
              }
              function walkAndFix(root) {
                try {
                  if (!root) return;
                  if (root.nodeType === 1) {
                    if (root.tagName === 'IMG') fixImg(root);
                    fixStyle(root);
                    if (root.querySelectorAll) {
                      root.querySelectorAll('img').forEach(fixImg);
                      root.querySelectorAll('*').forEach(fixStyle);
                    }
                  }
                } catch (e) {}
              }
              walkAndFix(document.documentElement);
              new MutationObserver(function (muts) {
                muts.forEach(function (m) {
                  m.addedNodes && m.addedNodes.forEach(function (n) {
                    if (!n) return;
                    walkAndFix(n);
                  });
                });
              }).observe(document.documentElement, { childList: true, subtree: true });
            })();
        """.trimIndent()
        runCatching { view.evaluateJavascript(js, null) }
            .onFailure { runCatching { view.loadUrl("javascript:" + js.replace('\n', ' ')) } }
    }

    private fun loadFromInput() {
        val input = binding.urlEditText.text?.toString()?.trim().orEmpty()
        if (input.isBlank()) return

        val url = normalizeToUrlOrSearch(input)
        loadUrl(url)
    }

    // ---------- 广告拦截（F-14） ----------

    /** 广告拦截关/白名单站点跳过时返回 null；命中返回空响应体 */
    private fun emptyBlockedResponse(): WebResourceResponse =
        WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))

    /** 保守自动隐藏：广告关/白名单站点不注入 */
    private fun injectAdAutoHideJs(view: WebView, url: String) {
        if (!AdBlockEngine.isEnabled(this) || !AdBlockEngine.isAutoHideEnabled(this)) return
        val pageHost = runCatching { Uri.parse(url).host }.getOrNull()
        if (AdBlockEngine.isPageWhitelisted(this, pageHost)) return
        runCatching { view.evaluateJavascript(AdBlockEngine.AUTO_HIDE_JS, null) }
    }

    /** 地址栏拦截徽标：本页拦截数 > 0 时显示，点击看详情/快捷放行 */
    private fun updateAdBadge() {
        applyAdBadge()
    }

    private fun applyAdBadge() {
        val view = currentWebViewSafe() ?: run {
            Log.d(logTag, "AdBadge: no webview, gone")
            binding.adBlockBadge.visibility = View.GONE
            return
        }
        val count = AdBlockEngine.pageBlockedCount(view)
        Log.d(logTag, "AdBadge: apply count=$count")
        if (count <= 0) {
            binding.adBlockBadge.visibility = View.GONE
            return
        }
        binding.adBlockBadge.text = getString(R.string.adblock_badge_fmt, count)
        if (binding.adBlockBadge.background == null) {
            binding.adBlockBadge.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpToPx(10).toFloat()
                setColor(Color.parseColor("#1E8E5A"))
            }
            binding.adBlockBadge.setOnClickListener { showBlockDetailDialog() }
        }
        binding.adBlockBadge.visibility = View.VISIBLE
    }

    /** 拦截详情：本页被拦域名列表 + 快捷放行当前站点 */
    private fun showBlockDetailDialog() {
        val view = currentWebViewSafe() ?: return
        val pageUrl = view.url.orEmpty()
        val pageHost = runCatching { Uri.parse(pageUrl).host }.getOrNull().orEmpty()
        val hosts = AdBlockEngine.recentBlockedHosts(view)
        val count = AdBlockEngine.pageBlockedCount(view)

        val message = buildString {
            append(getString(R.string.adblock_detail_count_fmt, count))
            if (hosts.isNotEmpty()) {
                append("\n")
                hosts.take(10).forEach { append("\n· ").append(it) }
                if (hosts.size > 10) append("\n…")
            }
        }
        val builder = AlertDialog.Builder(this)
            .setTitle(getString(R.string.adblock_detail_title))
            .setMessage(message)
        if (pageHost.isNotBlank()) {
            builder.setPositiveButton(getString(R.string.adblock_allow_this, pageHost)) { _, _ ->
                if (AdBlockEngine.addWhitelist(this, pageHost)) {
                    Toast.makeText(this, getString(R.string.adblock_whitelist_added_fmt, pageHost), Toast.LENGTH_SHORT).show()
                    view.reload()
                }
            }
        }
        builder.setNegativeButton(android.R.string.cancel, null).show()
    }

    /** 菜单"清理浮层广告"：激进移除大块遮罩与悬浮广告层（刷新即可恢复） */
    private fun stripOverlayAds() {
        currentWebView.evaluateJavascript(AdBlockEngine.STRIP_OVERLAY_JS) { value ->
            val n = decodeJsString(value)?.toIntOrNull() ?: 0
            if (n > 0) {
                Toast.makeText(this, getString(R.string.toast_strip_removed_fmt, n), Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, R.string.toast_strip_none, Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ---------- 浏览体验增强（F-15） ----------

    /** 桌面版网站：按标签记忆 UA 并刷新当前页 */
    private fun toggleDesktopUa() {
        val view = currentWebViewSafe() ?: return
        val tabId = tabManager.currentId ?: return
        val desktop = !(tabDesktopMode[tabId] ?: false)
        tabDesktopMode[tabId] = desktop
        view.settings.userAgentString = if (desktop) DESKTOP_USER_AGENT else MOBILE_USER_AGENT
        view.reload()
        Toast.makeText(
            this,
            getString(if (desktop) R.string.toast_desktop_ua_on else R.string.toast_desktop_ua_off),
            Toast.LENGTH_SHORT
        ).show()
    }

    /**
     * HTTP Basic 认证登录弹窗（WebView 默认静默取消 401，必须由应用实现）：
     * - 有「记住的密码」则预填账号密码，点「登录」即可；
     * - 勾选记住则按 域名+区域 保存，取消勾选清除已存密码；
     * - 登录成功后引擎自带会话级凭据缓存，同站子资源/XHR 不再质询；
     * - 并发 401 只弹一个窗，其余直接 cancel，避免弹窗叠加。
     */
    private fun showHttpAuthPrompt(handler: HttpAuthHandler, host: String?, realm: String?) {
        if (isFinishing || httpAuthDialogShowing) {
            handler.cancel()
            return
        }
        httpAuthDialogShowing = true
        Log.i(logTag, "HTTP auth prompt: host=$host realm=$realm")
        val saved = HttpAuthStore.get(this, host, realm)
        val content = layoutInflater.inflate(R.layout.dialog_http_auth, null)
        val userInput = content.findViewById<EditText>(R.id.http_auth_username)
        val passInput = content.findViewById<EditText>(R.id.http_auth_password)
        val rememberBox = content.findViewById<CheckBox>(R.id.http_auth_remember)
        saved?.let { (user, pass) ->
            userInput.setText(user)
            passInput.setText(pass)
        }
        rememberBox.isChecked = saved != null
        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.http_auth_dialog_message, host.orEmpty()))
            .setView(content)
            .setPositiveButton(R.string.http_auth_login, null)
            .setNegativeButton(android.R.string.cancel) { _, _ -> handler.cancel() }
            .setOnCancelListener { handler.cancel() } // 返回键 / 点窗外关闭
            .show()
        dialog.setOnDismissListener { httpAuthDialogShowing = false }
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val user = userInput.text.toString()
            val pass = passInput.text.toString()
            if (rememberBox.isChecked) {
                HttpAuthStore.put(this, host, realm, user, pass)
            } else {
                HttpAuthStore.clear(this, host, realm)
            }
            dialog.dismiss()
            handler.proceed(user, pass)
        }
        // 密码框按回车/完成键直接登录
        passInput.setOnEditorActionListener { _, actionId, event ->
            val isDone = actionId == EditorInfo.IME_ACTION_DONE ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER)
            if (isDone) {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            }
            isDone
        }
    }

    /** 网页内查找栏：输入实时查找，▲▼ 跳转匹配项 */
    private fun showFindBar() {
        if (findBar != null) {
            findInput?.setSelection(findInput?.text?.length ?: 0)
            findInput?.requestFocus()
            return
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#EE223344"))
            setPadding(dpToPx(10), dpToPx(6), dpToPx(10), dpToPx(6))
        }
        val input = EditText(this).apply {
            hint = getString(R.string.find_hint)
            isSingleLine = true
            textSize = 14f
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#88FFFFFF"))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val count = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.WHITE)
            setPadding(dpToPx(8), 0, dpToPx(8), 0)
        }
        fun arrowButton(glyph: String, onClick: () -> Unit): TextView = TextView(this).apply {
            text = glyph
            textSize = 16f
            setTextColor(Color.WHITE)
            setPadding(dpToPx(10), dpToPx(4), dpToPx(10), dpToPx(4))
            setOnClickListener { onClick() }
        }
        bar.addView(input)
        bar.addView(count)
        bar.addView(arrowButton("▲") { currentWebViewSafe()?.findNext(false) })
        bar.addView(arrowButton("▼") { currentWebViewSafe()?.findNext(true) })
        bar.addView(arrowButton("✕") { hideFindBar() })

        val params = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            topToTop = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
            startToStart = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
            endToEnd = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
        }
        binding.root.addView(bar, 0, params)
        findBar = bar
        findInput = input
        findCountView = count

        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                currentWebViewSafe()?.findAllAsync(s?.toString().orEmpty())
            }
        })
        input.requestFocus()
    }

    private fun hideFindBar() {
        currentWebViewSafe()?.findAllAsync("")
        findBar?.let { binding.root.removeView(it) }
        findBar = null
        findInput = null
        findCountView = null
    }

    /** 当前页面生成二维码（设备间传网址） */
    private fun showPageQrCode() {
        val url = currentWebView.url.orEmpty()
        if (url.isBlank()) return
        val size = dpToPx(280)
        val bitmap = runCatching { generateQrBitmap(url, size) }.getOrNull()
        if (bitmap == null) {
            Toast.makeText(this, R.string.toast_qr_failed, Toast.LENGTH_SHORT).show()
            return
        }
        val iv = ImageView(this).apply {
            setImageBitmap(bitmap)
            setPadding(dpToPx(20), dpToPx(8), dpToPx(20), dpToPx(8))
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.qr_page_title))
            .setView(iv)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun generateQrBitmap(content: String, sizePx: Int): Bitmap {
        val hints = mapOf(
            EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.MARGIN to 1
        )
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
        val bmp = Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
        for (x in 0 until matrix.width) {
            for (y in 0 until matrix.height) {
                bmp.setPixel(x, y, if (matrix.get(x, y)) Color.BLACK else Color.WHITE)
            }
        }
        return bmp
    }

    // ---------- 地址栏联想（历史 + 书签，F-15） ----------

    private fun setupUrlSuggest() {
        val popup = ListPopupWindow(this).apply {
            anchorView = binding.urlEditText
            inputMethodMode = ListPopupWindow.INPUT_METHOD_NEEDED
        }
        val adapter = android.widget.ArrayAdapter<String>(
            this, android.R.layout.simple_list_item_1
        )
        popup.setAdapter(adapter)
        popup.setOnItemClickListener { _, _, position, _ ->
            val item = suggestItems.getOrNull(position)
            popup.dismiss()
            if (item != null) {
                suppressSuggestOnce = true
                binding.urlEditText.setText(item.url)
                binding.urlEditText.setSelection(item.url.length)
                hideKeyboard()
                loadUrl(item.url)
            }
        }
        suggestPopup = popup

        binding.urlEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (suppressSuggestOnce) {
                    suppressSuggestOnce = false
                    return
                }
                val q = s?.toString()?.trim().orEmpty()
                if (q.length < 2 || q.contains(" ") && q.startsWith("http")) {
                    popup.dismiss()
                    return
                }
                val suggestions = SuggestEngine.suggest(
                    AppPrefs.getHistory(this@MainActivity),
                    AppPrefs.getBookmarks(this@MainActivity),
                    q
                )
                if (suggestions.isEmpty() || binding.urlEditText.windowToken == null) {
                    popup.dismiss()
                    return
                }
                suggestItems = suggestions.map { SuggestEngineResult(it.title, it.url) }
                adapter.clear()
                adapter.addAll(suggestItems.map { item ->
                    val host = runCatching { Uri.parse(item.url).host }.getOrNull() ?: item.url
                    "${item.title}  ·  $host"
                })
                popup.show()
            }
        })
    }

    private fun hideKeyboard() {
        val imm = getSystemService(android.view.inputmethod.InputMethodManager::class.java)
        imm?.hideSoftInputFromWindow(binding.urlEditText.windowToken, 0)
    }

    /** 程序化设置地址栏文本：抑制联想弹窗（联想只响应用户输入） */
    private fun setAddressBarText(text: String) {
        suppressSuggestOnce = true
        binding.urlEditText.setText(text)
    }

    private fun loadUrl(url: String) {
        val trimmed = url.trim()
        if (trimmed.isBlank()) return

        val uri = runCatching { Uri.parse(trimmed) }.getOrNull()
        val scheme = uri?.scheme.orEmpty().lowercase()
        if (scheme.isNotBlank() && scheme != "http" && scheme != "https") {
            if (uri != null && handleExternalOrSpecialUri(uri)) return
        }

        Log.i(logTag, "loadUrl: $trimmed")
        currentWebView.loadUrl(trimmed)
        setAddressBarText(trimmed)
        updateBookmarkIcon()
        val target = currentWebViewSafe()
        binding.webView.postDelayed({ target?.let { runMixedContentFixThrottled(it) } }, 1500)
    }

    private fun handleExternalOrSpecialUri(uri: Uri): Boolean {
        val scheme = uri.scheme.orEmpty().lowercase()
        if (scheme == "http" || scheme == "https") return false

        if (scheme == "baiduboxapp") {
            val target = uri.getQueryParameter("url").orEmpty()
            if (target.startsWith("http://") || target.startsWith("https://")) {
                loadUrl(target)
                return true
            }
        }

        if (scheme == "intent") {
            val handled = runCatching {
                val intent = Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME)
                val fallback = intent.getStringExtra("browser_fallback_url").orEmpty()
                if (fallback.startsWith("http://") || fallback.startsWith("https://")) {
                    loadUrl(fallback)
                    true
                } else {
                    startActivity(intent)
                    true
                }
            }.getOrDefault(false)
            if (handled) return true
        }

        val handledBySystem = runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
            true
        }.getOrDefault(false)
        if (!handledBySystem) {
            Toast.makeText(this, getString(R.string.toast_unsupported_link), Toast.LENGTH_SHORT).show()
        }
        return true
    }

    private fun normalizeToUrlOrSearch(input: String): String {
        val looksLikeUrl = input.contains("://") || input.contains(".")
        if (looksLikeUrl) {
            val hasScheme = input.startsWith("http://") || input.startsWith("https://")
            return if (hasScheme) input else "https://$input"
        }
        return SearchEngines.byId(AppPrefs.getSearchEngineId(this)).buildQueryUrl(input)
    }

    /** 主页：设置页可自定义，未配置时用默认主页 */
    private fun homeUrl(): String =
        AppPrefs.getHomePage(this) ?: DEFAULT_HOME_URL

    private fun applyControlsVisibility() {
        val hide = AppPrefs.getHideControls(this)
        binding.bottomContainer.visibility = if (hide) View.GONE else View.VISIBLE
    }

    private fun toggleControlsVisibility() {
        val now = SystemClock.uptimeMillis()
        if (now - lastHideToggleAtMs < 800) {
            Log.d(logTag, "MultiFingerGesture TWO_FINGER_SWIPE_DOWN ignored: toggle cooldown")
            return
        }
        lastHideToggleAtMs = now
        val newHide = !AppPrefs.getHideControls(this)
        AppPrefs.setHideControls(this, newHide)
        applyControlsVisibility()
        Log.i(logTag, "MultiFingerGesture TWO_FINGER_SWIPE_DOWN -> toggle hideControls=$newHide")
        Toast.makeText(
            this,
            getString(if (newHide) R.string.toast_controls_hidden else R.string.toast_controls_shown),
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun toggleBookmark() {
        val url = currentWebView.url.orEmpty()
        if (url.isBlank()) return

        binding.bookmarkButton.animate()
            .scaleX(1.2f)
            .scaleY(1.2f)
            .setDuration(100)
            .withEndAction {
                val already = AppPrefs.isBookmarked(this, url)
                if (already) {
                    AppPrefs.removeBookmark(this, url)
                    Toast.makeText(this, getString(R.string.toast_bookmark_removed), Toast.LENGTH_SHORT).show()
                } else {
                    val title = currentTitle.ifBlank { url }
                    AppPrefs.addBookmark(this, title, url)
                    Toast.makeText(this, getString(R.string.toast_bookmarked), Toast.LENGTH_SHORT).show()
                }
                updateBookmarkIcon()
                
                binding.bookmarkButton.animate()
                    .scaleX(1.0f)
                    .scaleY(1.0f)
                    .setDuration(100)
                    .start()
            }
            .start()
    }

    private fun updateBookmarkIcon() {
        val url = currentWebView.url.orEmpty()
        val bookmarked = url.isNotBlank() && AppPrefs.isBookmarked(this, url)
        
        binding.bookmarkButton.imageTintList = null
        binding.bookmarkButton.setImageResource(
            if (bookmarked) R.drawable.ic_star_filled_yellow else R.drawable.ic_star_outline_white
        )
    }

    private fun showMoreMenu() {
        val menu = PopupMenu(this, binding.moreButton)
        menu.menuInflater.inflate(R.menu.menu_more, menu.menu)
        // 桌面版菜单项带状态标题
        val desktopOn = tabDesktopMode[tabManager.currentId] ?: false
        menu.menu.findItem(R.id.menu_desktop_ua)?.setTitle(
            if (desktopOn) R.string.menu_mobile_ua else R.string.menu_desktop_ua
        )
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.menu_new_tab -> {
                    openNewTab()
                    true
                }
                R.id.menu_add_to_home -> {
                    showPinShortcutNameDialog()
                    true
                }
                R.id.menu_identity -> {
                    showIdentityDialog()
                    true
                }
                R.id.menu_strip_overlay -> {
                    stripOverlayAds()
                    true
                }
                R.id.menu_history -> {
                    startActivity(Intent(this, HistoryActivity::class.java))
                    true
                }
                R.id.menu_find_in_page -> {
                    showFindBar()
                    true
                }
                R.id.menu_desktop_ua -> {
                    toggleDesktopUa()
                    true
                }
                R.id.menu_qr_page -> {
                    showPageQrCode()
                    true
                }
                R.id.menu_open_bookmarks -> {
                    startActivity(Intent(this, BookmarksActivity::class.java))
                    true
                }
                R.id.menu_copy_link -> {
                    copyCurrentUrl()
                    true
                }
                R.id.menu_share -> {
                    shareCurrentUrl()
                    true
                }
                R.id.menu_settings -> {
                    startActivity(Intent(this, SettingsActivity::class.java))
                    true
                }
                else -> false
            }
        }
        menu.show()
    }

    private fun copyCurrentUrl() {
        val url = currentWebView.url.orEmpty()
        if (url.isBlank()) return
        val clipboard = getSystemService<ClipboardManager>() ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText("url", url))
        Toast.makeText(this, getString(R.string.toast_copied), Toast.LENGTH_SHORT).show()
    }

    private fun shareCurrentUrl() {
        val url = currentWebView.url.orEmpty()
        if (url.isBlank()) return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, url)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.menu_share)))
    }

    // ---------- 视频下载（F-07） ----------

    /** 候选池记录：全部 http(s) 请求入池，LRU 上限 120 条 */
    private fun recordMediaUrl(url: String?) {
        if (url.isNullOrBlank()) return
        if (!url.startsWith("http", ignoreCase = true)) return
        synchronized(sniffPool) {
            sniffPool.remove(url)
            sniffPool[url] = SystemClock.uptimeMillis()
            while (sniffPool.size > SNIFF_POOL_LIMIT) {
                sniffPool.remove(sniffPool.keys.first())
            }
        }
    }

    private fun onVideoDownloadClicked() {
        Log.i(logTag, "VideoDownload: floating button clicked")
        val referer = currentWebView.url.orEmpty()
        val cookie = runCatching { ProfileManager.cookieManager(currentProfileName()).getCookie(referer) }.getOrNull()
        val ua = currentWebView.settings.userAgentString.orEmpty()
        binding.btnVideoDownload.isEnabled = false
        currentWebView.evaluateJavascript(collectVideoUrlJs) { value ->
            val elementUrl = decodeJsString(value)
            val candidates = buildProbeCandidates(elementUrl)
            Log.i(logTag, "VideoDownload: probing ${candidates.size} candidates (element=${elementUrl?.take(60)})")
            probeExecutor.execute {
                val results = candidates.map { url -> probeUrl(url, referer, cookie, ua) }
                    .filterNotNull()
                    .distinctBy { it.url }
                    .sortedWith(
                        compareByDescending<MediaProbeLogic.ProbeResult> { it.kind == VideoKind.M3U8 }
                            .thenByDescending { it.totalBytes }
                    )
                runOnUiThread {
                    binding.btnVideoDownload.isEnabled = true
                    onProbeFinished(results, referer, cookie, ua)
                }
            }
        }
    }

    /** 候选构建：video 元素地址优先 + 候选池粗筛（活跃窗口内的疑似流），不足补最近请求 */
    private fun buildProbeCandidates(elementUrl: String?): List<String> {
        val urls = LinkedHashSet<String>()
        elementUrl?.trim()?.takeIf { it.startsWith("http", ignoreCase = true) }?.let { urls.add(it) }
        val snapshot = synchronized(sniffPool) { sniffPool.toList() }
        val now = SystemClock.uptimeMillis()
        val active = snapshot.asReversed().filter { now - it.second <= SNIFF_ACTIVE_WINDOW_MS }
        active.forEach { (u, _) ->
            if (urls.size < MAX_PROBE_CANDIDATES && MediaProbeLogic.looksPromisingForProbe(u)) {
                urls.add(u)
            }
        }
        if (urls.size < MAX_PROBE_CANDIDATES) {
            active.forEach { (u, _) ->
                if (urls.size < MAX_PROBE_CANDIDATES && !looksLikeStaticAsset(u)) {
                    urls.add(u)
                }
            }
        }
        return urls.toList()
    }

    private fun looksLikeStaticAsset(url: String): Boolean {
        val path = url.substringBefore('#').substringBefore('?').lowercase()
        return STATIC_ASSET_EXTENSIONS.any { path.endsWith(".$it") }
    }

    /** 轻量探测：Range 0-1 请求，按 Content-Type 与总大小判定是否为可下载媒体 */
    private fun probeUrl(url: String, referer: String, cookie: String?, ua: String): MediaProbeLogic.ProbeResult? {
        return runCatching {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = true
            conn.connectTimeout = PROBE_TIMEOUT_MS
            conn.readTimeout = PROBE_TIMEOUT_MS
            conn.setRequestProperty("Range", "bytes=0-1")
            ua.takeIf { it.isNotBlank() }?.let { conn.setRequestProperty("User-Agent", it) }
            referer.takeIf { it.isNotBlank() }?.let { conn.setRequestProperty("Referer", it) }
            cookie?.takeIf { it.isNotBlank() }?.let { conn.setRequestProperty("Cookie", it) }
            try {
                val code = conn.responseCode
                val mime = conn.contentType
                val total = when (code) {
                    206 -> parseContentRangeTotal(conn.getHeaderField("Content-Range"))
                    else -> conn.contentLengthLong.takeIf { it > 0 } ?: -1L
                }
                runCatching { conn.inputStream.close() }
                val kind = MediaProbeLogic.classifyProbeResponse(mime, total, code)
                if (kind == null || kind == VideoKind.OTHER || kind == VideoKind.BLOB) {
                    null
                } else {
                    Log.i(logTag, "VideoDownload: probe OK $kind size=$total ${url.take(70)}")
                    MediaProbeLogic.ProbeResult(url, mime.orEmpty(), total, kind)
                }
            } finally {
                conn.disconnect()
            }
        }.onFailure { Log.d(logTag, "probe failed: ${it.message}") }.getOrNull()
    }

    /** "bytes 0-1/123456" → 123456；无则 -1 */
    private fun parseContentRangeTotal(header: String?): Long {
        if (header.isNullOrBlank()) return -1L
        val total = header.substringAfter('/', "").trim().toLongOrNull()
        return if (total != null && total > 0) total else -1L
    }

    private fun onProbeFinished(
        results: List<MediaProbeLogic.ProbeResult>,
        referer: String,
        cookie: String?,
        ua: String
    ) {
        if (isFinishing) return
        if (results.isEmpty()) {
            Toast.makeText(this, getString(R.string.video_download_none_detected), Toast.LENGTH_SHORT).show()
            return
        }
        if (results.size == 1) {
            dispatchConfirmedDownload(results[0], referer, cookie, ua)
            return
        }
        val labels = results.mapIndexed { i, r -> formatProbeLabel(i, r) }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.video_pick_title))
            .setItems(labels) { _, which ->
                dispatchConfirmedDownload(results[which], referer, cookie, ua)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun formatProbeLabel(index: Int, r: MediaProbeLogic.ProbeResult): String {
        val kindText = getString(if (r.kind == VideoKind.M3U8) R.string.video_kind_hls else R.string.video_kind_direct)
        val sizeText = if (r.totalBytes > 0) formatBytes(r.totalBytes) else "—"
        val path = runCatching { Uri.parse(r.url).encodedPath.orEmpty() }.getOrDefault(r.url)
        return "${index + 1}. $kindText · $sizeText · ${path.takeLast(46)}"
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1_000_000_000 -> String.format(Locale.US, "%.2fGB", bytes / 1e9)
        bytes >= 1_000_000 -> String.format(Locale.US, "%.1fMB", bytes / 1e6)
        else -> "${bytes / 1000}KB"
    }

    private fun dispatchConfirmedDownload(
        r: MediaProbeLogic.ProbeResult,
        referer: String,
        cookie: String?,
        ua: String
    ) {
        when (r.kind) {
            VideoKind.M3U8 -> beginHlsDownload(r.url, referer, cookie, ua)
            VideoKind.DIRECT -> downloadDirectVideo(r.url, referer, cookie, ua)
            else -> Toast.makeText(this, getString(R.string.video_download_unsupported), Toast.LENGTH_SHORT).show()
        }
    }

    /** evaluateJavascript 回调值（JSON 字符串或 null）→ 原始字符串 */
    private fun decodeJsString(value: String?): String? {
        if (value.isNullOrBlank() || value == "null") return null
        return value.trim()
            .removeSurrounding("\"")
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
            .takeIf { it.isNotBlank() }
    }

    private fun beginHlsDownload(url: String, referer: String, cookie: String?, ua: String) {
        val title = currentTitle.ifBlank { Uri.parse(url).host ?: "video" }
        val params = HlsDownloadParams(url, title, referer, cookie, ua)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingHlsDownload = params
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            startVideoDownloadService(params)
        }
    }

    private fun startVideoDownloadService(params: HlsDownloadParams) {
        val intent = Intent(this, VideoDownloadService::class.java).apply {
            putExtra(VideoDownloadService.EXTRA_URL, params.url)
            putExtra(VideoDownloadService.EXTRA_TITLE, params.title)
            putExtra(VideoDownloadService.EXTRA_REFERER, params.referer)
            putExtra(VideoDownloadService.EXTRA_COOKIE, params.cookie)
            putExtra(VideoDownloadService.EXTRA_UA, params.ua)
        }
        runCatching { ContextCompat.startForegroundService(this, intent) }
            .onSuccess {
                Log.i(logTag, "VideoDownload: hls service started")
                Toast.makeText(this, getString(R.string.video_download_started), Toast.LENGTH_SHORT).show()
            }
            .onFailure {
                Log.e(logTag, "VideoDownload: start service failed", it)
                Toast.makeText(this, getString(R.string.video_download_start_failed), Toast.LENGTH_SHORT).show()
            }
    }

    /** 直链视频：系统 DownloadManager 下载到 Download/轻舟浏览器/，同时登记到设置页下载管理 */
    private fun downloadDirectVideo(url: String, referer: String, cookie: String?, ua: String) {
        val dm = getSystemService<DownloadManager>()
        if (dm == null) {
            Toast.makeText(this, getString(R.string.video_download_start_failed), Toast.LENGTH_SHORT).show()
            return
        }
        val baseName = currentTitle.ifBlank { Uri.parse(url).host ?: "video" }
        val fileName = VideoUrlSniffer.sanitizeFileName(baseName) +
            "_" + VideoUrlSniffer.fileTimestamp() + "." + VideoUrlSniffer.guessExtensionFor(url)
        val request = DownloadManager.Request(Uri.parse(url)).apply {
            setTitle(fileName)
            setDescription(getString(R.string.app_name))
            setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "轻舟浏览器/$fileName")
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setAllowedOverMetered(true)
            setAllowedOverRoaming(true)
            if (referer.isNotBlank()) addRequestHeader("Referer", referer)
            cookie?.takeIf { it.isNotBlank() }?.let { addRequestHeader("Cookie", it) }
            if (ua.isNotBlank()) addRequestHeader("User-Agent", ua)
        }
        runCatching { dm.enqueue(request) }
            .onSuccess { id ->
                Log.i(logTag, "VideoDownload: direct enqueued id=$id file=$fileName")
                downloadStore.upsert(
                    DownloadRecord(
                        id = "dm_$id",
                        kind = DownloadKind.DIRECT,
                        title = baseName,
                        fileName = fileName,
                        path = "Download/轻舟浏览器/$fileName",
                        uri = "",
                        status = DownloadStatus.RUNNING,
                        progress = 0,
                        detail = getString(R.string.download_status_downloading),
                        createdAt = System.currentTimeMillis(),
                        finishedAt = 0L
                    )
                )
                Toast.makeText(this, getString(R.string.video_download_started), Toast.LENGTH_SHORT).show()
            }
            .onFailure {
                Log.e(logTag, "VideoDownload: direct enqueue failed", it)
                Toast.makeText(this, getString(R.string.video_download_start_failed), Toast.LENGTH_SHORT).show()
            }
    }

    /** DownloadManager 完成回调 → 更新下载记录（设置页展示） */
    private val downloadCompleteReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
            if (id == -1L) return
            val recordId = "dm_$id"
            val dm = getSystemService<DownloadManager>() ?: return
            val cursor = dm.query(DownloadManager.Query().setFilterById(id))
            cursor?.use { c ->
                if (!c.moveToFirst()) return
                val statusIdx = c.getColumnIndex(DownloadManager.COLUMN_STATUS)
                val reasonIdx = c.getColumnIndex(DownloadManager.COLUMN_REASON)
                val localUriIdx = c.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
                val sizeIdx = c.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                val status = if (statusIdx >= 0) c.getInt(statusIdx) else -1
                val reason = if (reasonIdx >= 0) c.getInt(reasonIdx) else 0
                val localUri = if (localUriIdx >= 0) c.getString(localUriIdx) else null
                val totalBytes = if (sizeIdx >= 0) c.getLong(sizeIdx) else -1L
                Log.i(logTag, "VideoDownload: dm $id finished status=$status size=$totalBytes uri=$localUri")
                if (status == DownloadManager.STATUS_SUCCESSFUL &&
                    VideoUrlSniffer.isSuspiciouslySmall(totalBytes)
                ) {
                    // 下载成功但内容过小：多半是防盗链错误页/广告，按失败处理（修复"瞬间完成"）
                    val detail = getString(R.string.video_download_error_too_small)
                    downloadStore.updateById(recordId) { rec ->
                        rec.copy(
                            status = DownloadStatus.FAILED,
                            detail = detail,
                            finishedAt = System.currentTimeMillis()
                        )
                    }
                    Log.w(logTag, "VideoDownload: dm $id suspiciously small ($totalBytes bytes)")
                    return
                }
                if (status == DownloadManager.STATUS_SUCCESSFUL) {
                    downloadStore.updateById(recordId) { rec ->
                        rec.copy(
                            status = DownloadStatus.DONE,
                            progress = 100,
                            uri = resolveDownloadedUri(rec, localUri),
                            detail = getString(R.string.video_download_saved_path_fmt, rec.path),
                            finishedAt = System.currentTimeMillis()
                        )
                    }
                } else {
                    downloadStore.updateById(recordId) { rec ->
                        rec.copy(
                            status = DownloadStatus.FAILED,
                            detail = getString(R.string.video_download_error_network),
                            finishedAt = System.currentTimeMillis()
                        )
                    }
                    Log.w(logTag, "VideoDownload: dm $id failed reason=$reason")
                }
            }
        }
    }

    /** DownloadManager 的 file:// 在 targetSdk 29+ 不可直接打开；尝试换 MediaStore content uri */
    private fun resolveDownloadedUri(rec: DownloadRecord, localUri: String?): String {
        if (!localUri.isNullOrBlank() && localUri.startsWith("content://")) return localUri
        if (rec.fileName.isNotBlank() && Build.VERSION.SDK_INT >= 29) {
            runCatching {
                contentResolver.query(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.MediaColumns._ID),
                    "${MediaStore.MediaColumns.DISPLAY_NAME}=?",
                    arrayOf(rec.fileName),
                    null
                )?.use { c ->
                    if (c.moveToFirst()) {
                        val rowId = c.getLong(0)
                        return "content://${MediaStore.Downloads.EXTERNAL_CONTENT_URI.authority}/downloads/$rowId"
                    }
                }
            }
        }
        return ""
    }

    private fun registerDownloadCompleteReceiver() {
        runCatching {
            ContextCompat.registerReceiver(
                this,
                downloadCompleteReceiver,
                IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                ContextCompat.RECEIVER_EXPORTED
            )
        }.onFailure { Log.w(logTag, "register download receiver failed: ${it.message}") }
    }

    private fun unregisterDownloadCompleteReceiver() {
        runCatching { unregisterReceiver(downloadCompleteReceiver) }
    }

    private val downloadStore by lazy { DownloadStoreFile(this) }

    private fun showPinShortcutNameDialog(restoreText: String? = null) {
        val url = currentWebView.url.orEmpty()
        if (url.isBlank()) return

        val defaultLabel = currentTitle.ifBlank { Uri.parse(url).host ?: url }.trim().ifBlank { url }

        val input = EditText(this).apply {
            hint = getString(R.string.hint_shortcut_name)
            setText(restoreText ?: defaultLabel)
            setSelection(text.length)
            isSingleLine = true
        }

        val iconOptions = buildPinIconOptions(defaultLabel).toMutableList()
        var selectedIndex = 0

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(20), dpToPx(12), dpToPx(20), dpToPx(4))
            addView(
                input,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }

        container.addView(
            TextView(this).apply {
                text = getString(R.string.label_choose_shortcut_icon)
                setPadding(0, dpToPx(12), 0, dpToPx(8))
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        // 自定义图标入口：相册 / 拍照（F-13）
        fun iconSourceButton(textRes: Int, iconRes: Int, onClick: () -> Unit): TextView {
            return TextView(this).apply {
                text = getString(textRes)
                setCompoundDrawablesWithIntrinsicBounds(iconRes, 0, 0, 0)
                compoundDrawablePadding = dpToPx(6)
                background = ContextCompat.getDrawable(context, R.drawable.bg_glass_rounded)
                setPadding(dpToPx(12), dpToPx(8), dpToPx(12), dpToPx(8))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { rightMargin = dpToPx(10) }
                setOnClickListener { onClick() }
            }
        }
        val customRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        customRow.addView(
            iconSourceButton(R.string.icon_custom_gallery, android.R.drawable.ic_menu_gallery) {
                pendingDialogText = input.text?.toString()
                galleryIconLauncher.launch("image/*")
            }
        )
        customRow.addView(
            iconSourceButton(R.string.icon_custom_camera, android.R.drawable.ic_menu_camera) {
                pendingDialogText = input.text?.toString()
                val granted = ContextCompat.checkSelfPermission(
                    this@MainActivity, android.Manifest.permission.CAMERA
                ) == PackageManager.PERMISSION_GRANTED
                if (granted) {
                    launchIconCapture()
                } else {
                    cameraPermissionForIconLauncher.launch(android.Manifest.permission.CAMERA)
                }
            }
        )
        container.addView(
            customRow,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dpToPx(4) }
        )

        val monogramRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.START
        }
        val monogramScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(
                monogramRow,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
        container.addView(
            monogramScroll,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        val otherRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.START
        }
        val otherScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(
                otherRow,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
        container.addView(
            otherScroll,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dpToPx(10)
            }
        )

        val iconViewsByIndex = mutableMapOf<Int, ImageView>()
        val monogramIndices = mutableListOf<Int>()
        fun applySelection() {
            iconViewsByIndex.forEach { (index, view) ->
                view.background = buildIconOptionBackground(index == selectedIndex)
            }
        }

        iconOptions.forEachIndexed { index, option ->
            val size = dpToPx(56)
            val margin = dpToPx(10)
            val imageView = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setImageBitmap(option.previewBitmap)
                contentDescription = option.label
                setPadding(dpToPx(2), dpToPx(2), dpToPx(2), dpToPx(2))
                layoutParams = LinearLayout.LayoutParams(size, size).apply {
                    rightMargin = margin
                }
                setOnClickListener {
                    selectedIndex = index
                    applySelection()
                }
            }
            iconViewsByIndex[index] = imageView
            val targetRow = if (option.kind == PinIconKind.MONOGRAM) {
                monogramIndices.add(index)
                monogramRow
            } else {
                otherRow
            }
            targetRow.addView(imageView)
        }
        applySelection()

        var pendingMonogramUpdateToken = 0
        fun refreshMonogramIcons(newLabel: String) {
            val token = ++pendingMonogramUpdateToken
            input.postDelayed({
                if (token != pendingMonogramUpdateToken) return@postDelayed
                val text = buildMonogramText(newLabel)
                val iconSizePx = dpToPx(96)
                monogramIndices.forEach { index ->
                    val option = iconOptions.getOrNull(index) ?: return@forEach
                    val bg = option.backgroundColor ?: return@forEach
                    val bitmap = createMonogramIconBitmap(text, bg, iconSizePx)
                    option.previewBitmap = bitmap
                    option.icon = IconCompat.createWithBitmap(bitmap)
                    iconViewsByIndex[index]?.setImageBitmap(bitmap)
                }
            }, 250)
        }

        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                refreshMonogramIcons(s?.toString().orEmpty())
            }
        })

        dialogRef = AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_edit_shortcut_name))
            .setView(container)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val label = input.text?.toString()?.trim().orEmpty().ifBlank { defaultLabel }
                val selectedIcon = iconOptions.getOrNull(selectedIndex)?.icon
                    ?: IconCompat.createWithResource(this, R.mipmap.ic_launcher)
                requestPinShortcutForCurrentPage(label, selectedIcon)
                pendingCustomIcon = null
            }
            .show()
        Log.d(logTag, "pin dialog shown (customIcon=${pendingCustomIcon != null})")
    }

    /** 相册/拍照选图 → 解码摆正居中方裁剪 → 重建图标选择弹窗（自定义选项自动选中） */
    private fun loadCustomIcon(uri: Uri, deleteAfter: Boolean) {
        Log.i(logTag, "custom icon load: $uri deleteAfter=$deleteAfter")
        Thread {
            val square = runCatching { decodeSquareBitmap(uri) }
                .onFailure { Log.e(logTag, "custom icon decode failed", it) }
                .getOrNull()
            if (deleteAfter) {
                runCatching { contentResolver.delete(uri, null, null) }
            }
            runOnUiThread {
                if (square == null) {
                    Toast.makeText(this, R.string.toast_icon_load_failed, Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
            pendingCustomIcon = square
            val restore = pendingDialogText
            pendingDialogText = null
            dialogRef?.dismiss()
            showPinShortcutNameDialog(restoreText = restore)
            }
        }.start()
    }

    /** 启动系统拍照，输出到 MediaStore 临时记录（用完即删，不留相册） */
    private fun launchIconCapture() {
        val uri = createPendingMediaUri(
            collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            mimeType = "image/jpeg",
            fileExtension = "jpg",
            prefix = "lightboat_icon_"
        )
        if (uri == null) {
            Toast.makeText(this, R.string.toast_icon_load_failed, Toast.LENGTH_SHORT).show()
            return
        }
        pendingIconCaptureUri = uri
        runCatching { cameraIconLauncher.launch(uri) }
            .onFailure {
                Log.e(logTag, "icon capture launch failed", it)
                pendingIconCaptureUri = null
                runCatching { contentResolver.delete(uri, null, null) }
                Toast.makeText(this, R.string.toast_icon_load_failed, Toast.LENGTH_SHORT).show()
            }
    }

    /** 解码 uri → EXIF 摆正 → 居中方裁剪 → 缩放到 targetPx */
    private fun decodeSquareBitmap(uri: Uri, targetPx: Int = 512): Bitmap? {
        val resolver = contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val decodeOpts = BitmapFactory.Options().apply {
            inSampleSize = IconCropMath.sampleSizeFor(bounds.outWidth, bounds.outHeight, targetPx)
        }
        val bitmap = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, decodeOpts)
        } ?: return null

        val orientation = resolver.openInputStream(uri)?.use {
            androidx.exifinterface.media.ExifInterface(it).getAttributeInt(
                androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION,
                androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL
            )
        } ?: androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL
        val matrix = android.graphics.Matrix()
        when (orientation) {
            androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            else -> Unit
        }
        val upright = if (matrix.isIdentity) bitmap
        else Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)

        val (left, top, side) = IconCropMath.centerCropRect(upright.width, upright.height)
        val cropped = Bitmap.createBitmap(upright, left, top, side, side)
        return if (cropped.width != targetPx) {
            Bitmap.createScaledBitmap(cropped, targetPx, targetPx, true)
        } else cropped
    }

    private fun requestPinShortcutForCurrentPage(label: String, icon: IconCompat) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            Toast.makeText(this, getString(R.string.toast_shortcut_requested), Toast.LENGTH_SHORT).show()
            return
        }

        val shortcutManager = getSystemService<ShortcutManager>() ?: return
        if (!shortcutManager.isRequestPinShortcutSupported) return

        val url = currentWebView.url.orEmpty()
        if (url.isBlank()) return

        val identity = currentTabIdentity()
        val shortLabel = label.trim().ifBlank { url }.take(20)

        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url), this, MainActivity::class.java)
        // 快捷方式记住身份：打开时以对应 Profile 恢复该账号登录态。
        // 身份1 也要显式写入：点击时靠"是否带该 extra"与普通外链区分，保证回到身份1。
        intent.putExtra(EXTRA_IDENTITY, identity)
        val shortcutId = "pin_${url.hashCode()}_${SystemClock.uptimeMillis()}"
        val shortcut = ShortcutInfo.Builder(this, shortcutId)
            .setShortLabel(shortLabel)
            .setLongLabel(label.trim().ifBlank { url })
            .setIntent(intent)
            .setIcon(icon.toIcon(this))
            .build()

        shortcutManager.requestPinShortcut(shortcut, null)
        if (identity > 1) {
            Toast.makeText(this, getString(R.string.toast_shortcut_identity, identity), Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, getString(R.string.toast_shortcut_requested), Toast.LENGTH_SHORT).show()
        }
    }

    private enum class PinIconKind {
        CUSTOM,
        MONOGRAM,
        SITE,
        DEFAULT,
        GLYPH
    }

    private data class PinIconOption(
        val label: String,
        var icon: IconCompat,
        var previewBitmap: Bitmap,
        val kind: PinIconKind,
        val backgroundColor: Int? = null
    )

    private fun buildPinIconOptions(label: String): List<PinIconOption> {
        val options = mutableListOf<PinIconOption>()

        // 自定义图标（相册/拍照，F-13）放最前，配合 selectedIndex=0 自动选中
        pendingCustomIcon?.let { bmp ->
            options.add(
                PinIconOption(
                    getString(R.string.icon_custom),
                    IconCompat.createWithBitmap(bmp),
                    bmp,
                    PinIconKind.CUSTOM
                )
            )
        }

        val iconSizePx = dpToPx(96)

        val monogram = buildMonogramText(label)
        val swatches = buildColorSwatches(140)
        swatches.forEach { color ->
            val bitmap = createMonogramIconBitmap(monogram, color, iconSizePx)
            options.add(
                PinIconOption(
                    getString(R.string.shortcut_icon_letter),
                    IconCompat.createWithBitmap(bitmap),
                    bitmap,
                    PinIconKind.MONOGRAM,
                    color
                )
            )
        }

        val favicon = currentFavicon?.let { Bitmap.createScaledBitmap(it, iconSizePx, iconSizePx, true) }
        if (favicon != null) {
            options.add(
                PinIconOption(
                    getString(R.string.shortcut_icon_site),
                    IconCompat.createWithBitmap(favicon),
                    favicon,
                    PinIconKind.SITE
                )
            )
        }

        val appDrawable = ContextCompat.getDrawable(this, R.mipmap.ic_launcher)
        val appBitmap = appDrawable?.let { drawableToBitmap(it, iconSizePx, iconSizePx) }
        if (appBitmap != null) {
            options.add(
                PinIconOption(
                    getString(R.string.shortcut_icon_default),
                    IconCompat.createWithResource(this, R.mipmap.ic_launcher),
                    appBitmap,
                    PinIconKind.DEFAULT
                )
            )
        }

        val glyphs = buildGlyphLibrary()
        val glyphCount = 120.coerceAtMost(glyphs.size)
        repeat(glyphCount) { i ->
            val glyph = glyphs[i]
            val color = swatches[i % swatches.size]
            val bitmap = createGlyphIconBitmap(glyph, color, iconSizePx)
            options.add(
                PinIconOption(
                    getString(R.string.shortcut_icon_glyph),
                    IconCompat.createWithBitmap(bitmap),
                    bitmap,
                    PinIconKind.GLYPH,
                    color
                )
            )
        }

        if (options.isEmpty()) {
            val fallback = BitmapFactory.decodeResource(resources, R.mipmap.ic_launcher)
            val scaled = Bitmap.createScaledBitmap(fallback, iconSizePx, iconSizePx, true)
            options.add(
                PinIconOption(
                    getString(R.string.shortcut_icon_default),
                    IconCompat.createWithBitmap(scaled),
                    scaled,
                    PinIconKind.DEFAULT
                )
            )
        }

        return options
    }

    private fun buildMonogramText(label: String): String {
        val trimmed = label.trim().replace("\\s+".toRegex(), "")
        val base = trimmed.ifBlank { "WB" }
        val shouldUppercase = base.all { it.isLetterOrDigit() }
        return if (shouldUppercase) base.uppercase() else base
    }

    private fun buildColorSwatches(count: Int): List<Int> {
        val list = ArrayList<Int>(count)
        val hsv = floatArrayOf(0f, 0.72f, 0.92f)
        repeat(count) { i ->
            hsv[0] = (i * 360f) / count
            val saturation = when (i % 4) {
                0 -> 0.62f
                1 -> 0.70f
                2 -> 0.78f
                else -> 0.86f
            }
            val value = when (i % 3) {
                0 -> 0.88f
                1 -> 0.92f
                else -> 0.96f
            }
            hsv[1] = saturation
            hsv[2] = value
            list.add(Color.HSVToColor(hsv))
        }
        return list
    }

    private fun buildGlyphLibrary(): List<String> {
        return listOf(
            "★", "☆", "✦", "✧", "✪", "✫", "✬", "✭", "✮", "✯",
            "❤", "❥", "❣", "✿", "❀", "❁", "❃", "❇", "❈", "❉",
            "☀", "☼", "☁", "☂", "☃", "☄", "☾", "☽", "⚡", "⛅",
            "♠", "♥", "♦", "♣", "♤", "♡", "♢", "♧", "♛", "♚",
            "♫", "♪", "♩", "♬", "♭", "♯", "𝄞", "🎵", "🎶", "🎼",
            "⚙", "⛭", "⛓", "✈", "⚓", "✉", "⌛", "⏳", "⏰", "⏱",
            "☕", "🍵", "🍎", "🍊", "🍋", "🍉", "🍓", "🍒", "🍞", "🍰",
            "🚀", "🚗", "🚕", "🚲", "🚌", "🚇", "✴", "✳", "❖", "⬟",
            "▲", "△", "▼", "▽", "◆", "◇", "■", "□", "●", "○",
            "⬤", "◉", "◎", "◌", "◍", "◐", "◑", "◒", "◓", "◔",
            "✓", "✔", "✕", "✖", "✗", "✘", "➕", "➖", "➗", "✚",
            "∞", "∑", "∏", "∫", "≈", "≠", "≤", "≥", "∎", "∴",
            "⚑", "⚐", "⛳", "🏁", "⚔", "🛡", "🧩", "🧠", "📌", "📎",
            "📍", "📝", "📚", "📁", "📂", "🔍", "🔑", "🔒", "🔓", "🧰",
            "💡", "🔦", "🧲", "🧱", "🧭", "🧪", "🧫", "🧯", "🧴", "🧵"
        )
    }

    private fun createMonogramIconBitmap(text: String, backgroundColor: Int, sizePx: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = backgroundColor }
        drawIconTile(canvas, bgPaint, sizePx)

        val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        // 字号自适应 + 自动换行：任意字数都能完整放进图标
        val textSize = resolveMonogramTextSize(text, sizePx, textPaint)
        textPaint.textSize = textSize
        val contentWidth = (sizePx * 0.84f).toInt().coerceAtLeast(1)
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, textPaint, contentWidth)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY)
            .setIncludePad(false)
            .build()
        canvas.save()
        canvas.translate((sizePx - layout.width) / 2f, (sizePx - layout.height) / 2f)
        layout.draw(canvas)
        canvas.restore()

        return bitmap
    }

    /** 同一段文字在 140 个色块间重复求解字号纯属浪费，按 text+size 缓存结果 */
    private var monogramTextSizeCacheKey: String? = null
    private var monogramTextSizeCacheValue = 0f

    private fun resolveMonogramTextSize(text: String, sizePx: Int, textPaint: TextPaint): Float {
        val key = "$text\u0000$sizePx"
        monogramTextSizeCacheKey?.let { if (it == key) return monogramTextSizeCacheValue }

        val contentWidth = (sizePx * 0.84f).toInt().coerceAtLeast(1)
        val maxContentHeight = sizePx * 0.82f
        val singleChar = text.codePointCount(0, text.length) <= 1
        var textSize = sizePx * if (singleChar) 0.52f else 0.44f
        while (textSize > 1f) {
            textPaint.textSize = textSize
            val layout = StaticLayout.Builder.obtain(text, 0, text.length, textPaint, contentWidth)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY)
                .setIncludePad(false)
                .build()
            if (layout.height <= maxContentHeight) break
            textSize *= 0.88f
        }
        monogramTextSizeCacheKey = key
        monogramTextSizeCacheValue = textSize
        return textSize
    }

    /** 图标底板：方形、四角带弧度 */
    private fun drawIconTile(canvas: Canvas, paint: Paint, sizePx: Int) {
        val corner = sizePx * 0.22f
        canvas.drawRoundRect(
            0f, 0f, sizePx.toFloat(), sizePx.toFloat(),
            corner, corner, paint
        )
    }

    private fun createGlyphIconBitmap(glyph: String, backgroundColor: Int, sizePx: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = backgroundColor }
        drawIconTile(canvas, bgPaint, sizePx)

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textSize = sizePx * 0.54f
        }
        val fm = textPaint.fontMetrics
        val y = sizePx / 2f - (fm.ascent + fm.descent) / 2f
        canvas.drawText(glyph, sizePx / 2f, y, textPaint)

        return bitmap
    }

    private fun drawableToBitmap(drawable: Drawable, width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, width, height)
        drawable.draw(canvas)
        return bitmap
    }

    private fun buildIconOptionBackground(isSelected: Boolean): Drawable {
        val strokeWidth = dpToPx(2)
        val strokeColor = if (isSelected) Color.parseColor("#FF007AFF") else Color.TRANSPARENT
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dpToPx(12).toFloat() // 与图标位图 22% 圆角视觉一致（56dp 预览）
            setColor(Color.TRANSPARENT)
            setStroke(strokeWidth, strokeColor)
        }
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt().coerceAtLeast(1)
    }

    private companion object {
        const val DEFAULT_HOME_URL = "https://www.jiandaoyun.com/"
        const val MOBILE_USER_AGENT = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.6099.144 Mobile Safari/537.36"
        const val DESKTOP_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        /** intent extra：目标身份序号（int，1 起始）。桌面"身份快捷方式"携带 */
        const val EXTRA_IDENTITY = "extra_identity"
        const val SNIFF_POOL_LIMIT = 120
        /** 只探测最近 N 秒活跃过的请求（页面停留久了池会脏） */
        const val SNIFF_ACTIVE_WINDOW_MS = 90_000L
        /** 最多探测的候选数量 */
        const val MAX_PROBE_CANDIDATES = 12
        const val PROBE_TIMEOUT_MS = 4000

        /** 补位探测时排除的静态资源后缀 */
        val STATIC_ASSET_EXTENSIONS = setOf(
            "jpg", "jpeg", "png", "gif", "webp", "svg", "ico", "css", "js",
            "woff", "woff2", "ttf", "eot", "html", "htm", "json"
        )
    }
}
