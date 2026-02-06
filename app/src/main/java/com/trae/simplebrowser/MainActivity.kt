package com.trae.simplebrowser

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.ValueCallback
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebViewDatabase
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.getSystemService
import androidx.core.graphics.drawable.IconCompat
import com.trae.simplebrowser.databinding.ActivityMainBinding
import java.net.URLEncoder
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.net.http.SslError
import java.net.HttpURLConnection
import java.net.URL
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
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import android.provider.MediaStore

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private var currentTitle: String = ""
    private val logTag = "LiteBrowser"
    private var lastMixedContentFixAtMs: Long = 0
    private var currentFavicon: Bitmap? = null
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var pendingCapturedUri: Uri? = null

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

        setupWebView(binding.webView)
        setupControls()
        ensureAppShortcuts()

        val initialUrl = intent?.dataString
        if (!initialUrl.isNullOrBlank()) {
            loadUrl(initialUrl)
        } else if (savedInstanceState == null) {
            loadUrl("https://www.jiandaoyun.com/")
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putParcelable("pending_captured_uri", pendingCapturedUri)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val url = intent.dataString
        if (!url.isNullOrBlank()) {
            loadUrl(url)
        }
    }

    // Two-finger swipe down gesture variables
    private var startY1 = 0f
    private var startY2 = 0f
    private var isTwoFingerGesture = false
    private val swipeThresholdPx by lazy { 100 * resources.displayMetrics.density }

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
                        startActivity(Intent(this, SettingsActivity::class.java))
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
    }

    override fun onBackPressed() {
        if (binding.webView.canGoBack()) {
            binding.webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    private fun setupControls() {
        binding.backButton.setOnClickListener {
            if (binding.webView.canGoBack()) binding.webView.goBack()
        }
        binding.forwardButton.setOnClickListener {
            if (binding.webView.canGoForward()) binding.webView.goForward()
        }
        binding.refreshButton.setOnClickListener { binding.webView.reload() }

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
        binding.addToHomeButton.setOnClickListener { showPinShortcutNameDialog() }
        binding.moreButton.setOnClickListener { showMoreMenu() }
    }

    private fun setupWebView(webView: WebView) {
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

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        WebViewDatabase.getInstance(this)

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
                return runCatching { fetchAsWebResourceResponse(httpsUrl, request.requestHeaders) }
                    .getOrNull()
            }

            override fun onPageFinished(view: WebView, url: String) {
                binding.urlEditText.setText(url)
                updateBookmarkIcon()
                runMixedContentFixThrottled()
            }

            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                runMixedContentFixThrottled()
            }

            override fun onLoadResource(view: WebView, url: String) {
                runMixedContentFixThrottled()
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
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onReceivedTitle(view: WebView, title: String?) {
                currentTitle = title.orEmpty()
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

    private fun runMixedContentFixThrottled() {
        val now = SystemClock.uptimeMillis()
        if (now - lastMixedContentFixAtMs < 1500) return
        lastMixedContentFixAtMs = now
        upgradeKnownInsecureImages()
    }

    private fun fetchAsWebResourceResponse(
        httpsUrl: String,
        requestHeaders: Map<String, String>
    ): WebResourceResponse? {
        val connection = (URL(httpsUrl).openConnection() as? HttpURLConnection) ?: return null
        connection.instanceFollowRedirects = true
        connection.connectTimeout = 15000
        connection.readTimeout = 20000

        val userAgent = binding.webView.settings.userAgentString
        if (userAgent.isNotBlank()) connection.setRequestProperty("User-Agent", userAgent)

        requestHeaders["Accept"]?.let { connection.setRequestProperty("Accept", it) }
        requestHeaders["Accept-Language"]?.let { connection.setRequestProperty("Accept-Language", it) }
        requestHeaders["Referer"]?.let { connection.setRequestProperty("Referer", it) }

        val cookie = CookieManager.getInstance().getCookie(httpsUrl)
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

    private fun upgradeKnownInsecureImages() {
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
        runCatching { binding.webView.evaluateJavascript(js, null) }
            .onFailure { runCatching { binding.webView.loadUrl("javascript:" + js.replace('\n', ' ')) } }
    }

    private fun loadFromInput() {
        val input = binding.urlEditText.text?.toString()?.trim().orEmpty()
        if (input.isBlank()) return

        val url = normalizeToUrlOrSearch(input)
        loadUrl(url)
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
        binding.webView.loadUrl(trimmed)
        binding.urlEditText.setText(trimmed)
        updateBookmarkIcon()
        binding.webView.postDelayed({ runMixedContentFixThrottled() }, 1500)
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
        val encoded = URLEncoder.encode(input, Charsets.UTF_8.name())
        return "https://www.google.com/search?q=$encoded"
    }

    private fun applyControlsVisibility() {
        val hide = AppPrefs.getHideControls(this)
        binding.bottomContainer.visibility = if (hide) View.GONE else View.VISIBLE
    }

    private fun toggleBookmark() {
        val url = binding.webView.url.orEmpty()
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
        val url = binding.webView.url.orEmpty()
        val bookmarked = url.isNotBlank() && AppPrefs.isBookmarked(this, url)
        
        binding.bookmarkButton.imageTintList = null
        binding.bookmarkButton.setImageResource(
            if (bookmarked) R.drawable.ic_star_filled_yellow else R.drawable.ic_star_outline_white
        )
    }

    private fun showMoreMenu() {
        val menu = PopupMenu(this, binding.moreButton)
        menu.menuInflater.inflate(R.menu.menu_more, menu.menu)
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.menu_add_to_home -> {
                    showPinShortcutNameDialog()
                    true
                }
                R.id.menu_open_bookmarks -> {
                    startActivity(Intent(this, BookmarksActivity::class.java))
                    true
                }
                R.id.menu_settings -> {
                    startActivity(Intent(this, SettingsActivity::class.java))
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
                else -> false
            }
        }
        menu.show()
    }

    private fun copyCurrentUrl() {
        val url = binding.webView.url.orEmpty()
        if (url.isBlank()) return
        val clipboard = getSystemService<ClipboardManager>() ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText("url", url))
        Toast.makeText(this, getString(R.string.toast_copied), Toast.LENGTH_SHORT).show()
    }

    private fun shareCurrentUrl() {
        val url = binding.webView.url.orEmpty()
        if (url.isBlank()) return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, url)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.menu_share)))
    }

    private fun showPinShortcutNameDialog() {
        val url = binding.webView.url.orEmpty()
        if (url.isBlank()) return

        val defaultLabel = currentTitle.ifBlank { Uri.parse(url).host ?: url }.trim().ifBlank { url }

        val input = EditText(this).apply {
            hint = getString(R.string.hint_shortcut_name)
            setText(defaultLabel)
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

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_edit_shortcut_name))
            .setView(container)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val label = input.text?.toString()?.trim().orEmpty().ifBlank { defaultLabel }
                val selectedIcon = iconOptions.getOrNull(selectedIndex)?.icon
                    ?: IconCompat.createWithResource(this, R.mipmap.ic_launcher)
                requestPinShortcutForCurrentPage(label, selectedIcon)
            }
            .show()
    }

    private fun requestPinShortcutForCurrentPage(label: String, icon: IconCompat) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            Toast.makeText(this, getString(R.string.toast_shortcut_requested), Toast.LENGTH_SHORT).show()
            return
        }

        val shortcutManager = getSystemService<ShortcutManager>() ?: return
        if (!shortcutManager.isRequestPinShortcutSupported) return

        val url = binding.webView.url.orEmpty()
        if (url.isBlank()) return

        val shortLabel = label.trim().ifBlank { url }.take(20)

        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url), this, MainActivity::class.java)
        val shortcutId = "pin_${url.hashCode()}_${SystemClock.uptimeMillis()}"
        val shortcut = ShortcutInfo.Builder(this, shortcutId)
            .setShortLabel(shortLabel)
            .setLongLabel(label.trim().ifBlank { url })
            .setIntent(intent)
            .setIcon(icon.toIcon(this))
            .build()

        shortcutManager.requestPinShortcut(shortcut, null)
        Toast.makeText(this, getString(R.string.toast_shortcut_requested), Toast.LENGTH_SHORT).show()
    }

    private fun ensureAppShortcuts() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1) return

        runCatching {
            val shortcutManager = getSystemService<ShortcutManager>() ?: return
            val intent = Intent(this, SettingsActivity::class.java).apply {
                action = Intent.ACTION_VIEW
            }

            val shortcut = ShortcutInfo.Builder(this, "settings")
                .setShortLabel(getString(R.string.settings_title))
                .setLongLabel(getString(R.string.settings_title))
                .setIntent(intent)
                .setIcon(IconCompat.createWithResource(this, R.mipmap.ic_launcher).toIcon(this))
                .build()

            shortcutManager.dynamicShortcuts = listOf(shortcut)
            Log.i(logTag, "Dynamic shortcuts set: ${shortcutManager.dynamicShortcuts.size}")
        }.onFailure { e ->
            Log.e(logTag, "ensureAppShortcuts failed", e)
        }
    }

    private enum class PinIconKind {
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
        val base = takeFirstCodePoints(trimmed, 2).ifBlank { "WB" }
        val shouldUppercase = base.all { it.isLetterOrDigit() }
        return if (shouldUppercase) base.uppercase() else base
    }

    private fun takeFirstCodePoints(text: String, count: Int): String {
        if (text.isBlank()) return ""
        val cpCount = text.codePointCount(0, text.length)
        val take = count.coerceAtMost(cpCount)
        val end = text.offsetByCodePoints(0, take)
        return text.substring(0, end)
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
        val radius = sizePx / 2f
        canvas.drawCircle(radius, radius, radius, bgPaint)

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textSize = sizePx * if (text.codePointCount(0, text.length) >= 2) 0.44f else 0.52f
        }
        val fm = textPaint.fontMetrics
        val y = radius - (fm.ascent + fm.descent) / 2f
        canvas.drawText(text, radius, y, textPaint)

        return bitmap
    }

    private fun createGlyphIconBitmap(glyph: String, backgroundColor: Int, sizePx: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = backgroundColor }
        val radius = sizePx / 2f
        canvas.drawCircle(radius, radius, radius, bgPaint)

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textSize = sizePx * 0.54f
        }
        val fm = textPaint.fontMetrics
        val y = radius - (fm.ascent + fm.descent) / 2f
        canvas.drawText(glyph, radius, y, textPaint)

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
            shape = GradientDrawable.OVAL
            setColor(Color.TRANSPARENT)
            setStroke(strokeWidth, strokeColor)
        }
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt().coerceAtLeast(1)
    }
}
