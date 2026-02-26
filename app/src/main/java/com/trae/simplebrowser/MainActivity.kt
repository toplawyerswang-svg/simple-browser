package com.trae.simplebrowser

import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.trae.simplebrowser.databinding.ActivityMainBinding
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.StorageController
import java.util.UUID

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private var geckoSession: GeckoSession? = null
    private var geckoRuntime: GeckoRuntime? = null
    private val logTag = "LiteBrowser"
    private var canGoBack = false
    private var currentTitle: String? = null

    // File prompt handling
    private var pendingFilePrompt: GeckoSession.PromptDelegate.FilePrompt? = null
    private var pendingPromptResult: GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? = null

    private val fileChooserLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val prompt = pendingFilePrompt
            val promptResult = pendingPromptResult
            pendingFilePrompt = null
            pendingPromptResult = null

            if (prompt == null || promptResult == null) return@registerForActivityResult

            if (result.resultCode == RESULT_OK) {
                val data = result.data
                var uris: Array<Uri>? = null
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

                if (uris != null && uris.isNotEmpty()) {
                    // Confirm with URIs
                    promptResult.complete(prompt.confirm(this, uris))
                } else {
                    promptResult.complete(prompt.dismiss())
                }
            } else {
                promptResult.complete(prompt.dismiss())
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupGeckoView()
        setupControls()

        val initialUrl = intent?.dataString
        if (!initialUrl.isNullOrBlank()) {
            loadUrl(initialUrl)
        } else if (savedInstanceState == null) {
            loadUrl("https://www.jiandaoyun.com/")
        }
    }

    override fun onResume() {
        super.onResume()
        // Update controls visibility based on settings
        val hideControls = AppPrefs.getHideControls(this)
        binding.bottomContainer.visibility = if (hideControls) View.GONE else View.VISIBLE
        
        // Update bookmark icon for current page
        // Wait, current URL might not be readily available if session hasn't loaded fully
        // But we can check session history or just let onLocationChange handle it.
        // However, onResume might happen when returning from settings, so check current URL.
        // GeckoSession doesn't expose current URL directly easily without navigation delegate tracking.
        // We'll rely on onLocationChange updates mostly, but maybe force update if possible.
    }

    private fun setupGeckoView() {
        // Initialize GeckoRuntime
        if (geckoRuntime == null) {
            geckoRuntime = GeckoRuntime.create(this)
        }

        geckoSession = GeckoSession()
        geckoSession?.open(geckoRuntime!!)
        
        // Connect session to view
        binding.webView.setSession(geckoSession!!)

        // Configure Settings
        geckoSession?.settings?.apply {
            useTrackingProtection = true
            allowJavascript = true
            // userAgentOverride = "Mozilla/5.0 (Linux; Android 10; Mobile) Gecko/123.0 Firefox/123.0" 
            // Let GeckoView use default UA unless specific issues arise
        }

        // Progress Delegate
        geckoSession?.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onProgressChange(session: GeckoSession, progress: Int) {
                if (progress == 100) {
                    binding.progressBar.visibility = View.GONE
                } else {
                    if (binding.progressBar.visibility != View.VISIBLE) {
                        binding.progressBar.visibility = View.VISIBLE
                    }
                    binding.progressBar.progress = progress
                }
            }
        }

        // Navigation Delegate (URL handling)
        geckoSession?.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onLoadRequest(session: GeckoSession, request: GeckoSession.NavigationDelegate.LoadRequest): GeckoResult<AllowOrDeny>? {
                val uri = request.uri
                val scheme = runCatching { Uri.parse(uri).scheme }.getOrNull()

                if (scheme == "http" || scheme == "https" || scheme == "about") {
                    return GeckoResult.fromValue(AllowOrDeny.ALLOW)
                }

                // Handle external intents (tel:, mailto:, intent:, etc.)
                return try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri))
                    startActivity(intent)
                    GeckoResult.fromValue(AllowOrDeny.DENY)
                } catch (e: Exception) {
                    Log.e(logTag, "Failed to handle URI: $uri", e)
                    GeckoResult.fromValue(AllowOrDeny.DENY)
                }
            }
            
            override fun onLocationChange(session: GeckoSession, url: String?, perms: MutableList<GeckoSession.PermissionDelegate.ContentPermission>, isPrivate: Boolean) {
                if (url != null) {
                    binding.urlEditText.setText(url)
                    updateBookmarkIcon(url)
                }
            }

            override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
                this@MainActivity.canGoBack = canGoBack
                // binding.backButton.isEnabled = canGoBack // Optional: disable button if can't go back
            }
        }

        // Content Delegate (Title handling)
        geckoSession?.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onTitleChange(session: GeckoSession, title: String?) {
                currentTitle = title
            }
        }

        // Prompt Delegate (File Chooser, Alerts)
        geckoSession?.promptDelegate = object : GeckoSession.PromptDelegate {
            override fun onFilePrompt(session: GeckoSession, prompt: GeckoSession.PromptDelegate.FilePrompt): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
                val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
                pendingFilePrompt = prompt
                pendingPromptResult = result

                val intent = Intent(Intent.ACTION_GET_CONTENT)
                intent.addCategory(Intent.CATEGORY_OPENABLE)
                intent.type = "*/*"
                
                val mimeTypes = prompt.mimeTypes
                if (mimeTypes != null && mimeTypes.isNotEmpty()) {
                    intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes)
                }
                
                if (prompt.type == GeckoSession.PromptDelegate.FilePrompt.Type.MULTIPLE) {
                    intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                }

                try {
                    fileChooserLauncher.launch(intent)
                } catch (e: Exception) {
                    Log.e(logTag, "Failed to launch file chooser", e)
                    result.complete(prompt.dismiss())
                }

                return result
            }
        }
    }

    private fun loadUrl(url: String) {
        var finalUrl = url.trim()
        if (!finalUrl.startsWith("http://") && !finalUrl.startsWith("https://") && !finalUrl.startsWith("about:")) {
            finalUrl = "https://$finalUrl"
        }
        geckoSession?.loadUri(finalUrl)
    }

    private fun loadFromInput() {
        val url = binding.urlEditText.text.toString()
        if (url.isNotBlank()) {
            loadUrl(url)
        }
    }

    private fun setupControls() {
        binding.backButton.setOnClickListener {
            geckoSession?.goBack()
        }
        binding.forwardButton.setOnClickListener {
            geckoSession?.goForward()
        }
        binding.refreshButton.setOnClickListener {
            geckoSession?.reload()
        }

        binding.goButton.setOnClickListener { loadFromInput() }
        binding.urlEditText.setOnEditorActionListener { _, actionId, event ->
            val isGo = actionId == EditorInfo.IME_ACTION_GO
            if (isGo) {
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
    
    private fun updateBookmarkIcon(url: String) {
        val isBookmarked = AppPrefs.isBookmarked(this, url)
        val iconRes = if (isBookmarked) android.R.drawable.btn_star_big_on else android.R.drawable.btn_star_big_off
        binding.bookmarkButton.setImageResource(iconRes)
    }

    private fun toggleBookmark() {
        val url = binding.urlEditText.text.toString()
        if (url.isBlank()) return
        
        // Use current page title if available, otherwise use URL as title
        val title = currentTitle ?: url
        
        if (AppPrefs.isBookmarked(this, url)) {
            AppPrefs.removeBookmark(this, url)
            Toast.makeText(this, "Removed from bookmarks", Toast.LENGTH_SHORT).show()
        } else {
            AppPrefs.addBookmark(this, title, url)
            Toast.makeText(this, "Added to bookmarks", Toast.LENGTH_SHORT).show()
        }
        updateBookmarkIcon(url)
    }

    private fun showPinShortcutNameDialog() {
        val url = binding.urlEditText.text.toString()
        if (url.isBlank()) return

        val input = android.widget.EditText(this)
        input.hint = "Shortcut Name"
        if (!currentTitle.isNullOrBlank()) {
            input.setText(currentTitle)
        }
        
        AlertDialog.Builder(this)
            .setTitle("Add to Home Screen")
            .setView(input)
            .setPositiveButton("Add") { _, _ ->
                val name = input.text.toString()
                if (name.isNotBlank()) {
                    addPinnedShortcut(name, url)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun addPinnedShortcut(name: String, url: String) {
        if (ShortcutManagerCompat.isRequestPinShortcutSupported(this)) {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            // Important: Set component to MainActivity to ensure it opens in this app
            intent.setClass(this, MainActivity::class.java)
            
            val pinShortcutInfo = ShortcutInfoCompat.Builder(this, UUID.randomUUID().toString())
                .setShortLabel(name)
                .setIcon(IconCompat.createWithResource(this, R.mipmap.ic_launcher))
                .setIntent(intent)
                .build()

            val callbackIntent = ShortcutManagerCompat.createShortcutResultIntent(this, pinShortcutInfo)
            val successCallback = PendingIntent.getBroadcast(
                this, 0, callbackIntent, PendingIntent.FLAG_IMMUTABLE
            )

            ShortcutManagerCompat.requestPinShortcut(this, pinShortcutInfo, successCallback.intentSender)
            Toast.makeText(this, "Shortcut requested", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Launcher does not support pinned shortcuts", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showMoreMenu() {
        val popup = PopupMenu(this, binding.moreButton)
        popup.menu.add("Bookmarks")
        popup.menu.add("Settings")
        popup.menu.add("Share")
        popup.menu.add("Clear Data")
        
        popup.setOnMenuItemClickListener { item ->
            when (item.title) {
                "Bookmarks" -> {
                    startActivity(Intent(this, BookmarksActivity::class.java))
                    true
                }
                "Settings" -> {
                    startActivity(Intent(this, SettingsActivity::class.java))
                    true
                }
                "Share" -> {
                    val url = binding.urlEditText.text.toString()
                    if (url.isNotBlank()) {
                        val shareIntent = Intent(Intent.ACTION_SEND)
                        shareIntent.type = "text/plain"
                        shareIntent.putExtra(Intent.EXTRA_TEXT, url)
                        startActivity(Intent.createChooser(shareIntent, "Share URL"))
                    }
                    true
                }
                "Clear Data" -> {
                    geckoRuntime?.storageController?.clearData(StorageController.ClearFlags.ALL)
                    Toast.makeText(this, "Data cleared", Toast.LENGTH_SHORT).show()
                    true
                }
                else -> false
            }
        }
        popup.show()
    }
    
    override fun onBackPressed() {
        if (canGoBack) {
            geckoSession?.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        geckoSession?.close()
        super.onDestroy()
    }
}
