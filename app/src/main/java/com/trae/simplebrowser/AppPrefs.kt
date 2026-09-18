package com.trae.simplebrowser

import android.content.Context
import com.trae.simplebrowser.core.HistoryEntry
import com.trae.simplebrowser.core.HistoryStore
import com.trae.simplebrowser.core.SearchEngines
import org.json.JSONArray
import org.json.JSONObject

data class Bookmark(
    val title: String,
    val url: String
)

object AppPrefs {
    private const val PREFS_NAME = "lite_browser_prefs"
    private const val KEY_HIDE_CONTROLS = "hide_controls"
    private const val KEY_BOOKMARKS_JSON = "bookmarks_json"
    private const val KEY_ASR_TOKEN = "asr_token"
    private const val KEY_ASR_USER = "asr_user"
    private const val KEY_IDENTITY_COUNT = "identity_count"
    private const val KEY_HISTORY_JSON = "history_json"
    private const val KEY_SEARCH_ENGINE = "search_engine_id"
    private const val KEY_HOME_PAGE = "home_page"
    private const val KEY_DATA_SAVER = "data_saver"
    private const val KEY_TEXT_ZOOM = "text_zoom"
    private const val KEY_PENDING_CLEAR_CACHE = "pending_clear_cache"

    /** 已创建的身份数量（含身份1）；默认 1，即升级用户保持原单身份行为 */
    fun getIdentityCount(context: Context): Int {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_IDENTITY_COUNT, 1)
            .coerceAtLeast(1)
    }

    fun setIdentityCount(context: Context, count: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_IDENTITY_COUNT, count.coerceAtLeast(1))
            .apply()
    }

    fun getHideControls(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_HIDE_CONTROLS, false)
    }

    fun setHideControls(context: Context, hide: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_HIDE_CONTROLS, hide)
            .apply()
    }

    fun getAsrToken(context: Context): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_ASR_TOKEN, "")
            ?: ""
    }

    fun getAsrUser(context: Context): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_ASR_USER, "")
            ?: ""
    }

    fun setAsrAuth(context: Context, token: String, user: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ASR_TOKEN, token)
            .putString(KEY_ASR_USER, user)
            .apply()
    }

    fun clearAsrAuth(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_ASR_TOKEN)
            .remove(KEY_ASR_USER)
            .apply()
    }

    fun getBookmarks(context: Context): List<Bookmark> {
        val json = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_BOOKMARKS_JSON, "[]")
            ?: "[]"

        val array = runCatching { JSONArray(json) }.getOrElse { JSONArray() }
        val result = ArrayList<Bookmark>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val title = obj.optString("title", "")
            val url = obj.optString("url", "")
            if (url.isNotBlank()) {
                result.add(Bookmark(title, url))
            }
        }
        return result
    }

    fun isBookmarked(context: Context, url: String): Boolean {
        return getBookmarks(context).any { it.url == url }
    }

    fun addBookmark(context: Context, title: String, url: String) {
        val current = getBookmarks(context).toMutableList()
        if (current.any { it.url == url }) return
        current.add(0, Bookmark(title, url))
        saveBookmarks(context, current)
    }

    fun removeBookmark(context: Context, url: String) {
        val current = getBookmarks(context).filterNot { it.url == url }
        saveBookmarks(context, current)
    }

    private fun saveBookmarks(context: Context, bookmarks: List<Bookmark>) {
        val array = JSONArray()
        for (b in bookmarks) {
            val obj = JSONObject()
            obj.put("title", b.title)
            obj.put("url", b.url)
            array.put(obj)
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_BOOKMARKS_JSON, array.toString())
            .apply()
    }

    // ---------- 浏览历史（F-15） ----------
    fun getHistory(context: Context): List<HistoryEntry> {
        val json = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_HISTORY_JSON, "[]")
            ?: "[]"
        val array = runCatching { JSONArray(json) }.getOrElse { return emptyList() }
        val result = ArrayList<HistoryEntry>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val url = obj.optString("url", "")
            if (url.isNotBlank()) {
                result.add(HistoryEntry(obj.optString("title", url), url, obj.optLong("ts", 0L)))
            }
        }
        return result
    }

    fun addHistory(context: Context, title: String, url: String) {
        if (!url.startsWith("http")) return
        val updated = HistoryStore.add(getHistory(context), title, url, System.currentTimeMillis())
        saveHistory(context, updated)
    }

    fun removeHistory(context: Context, url: String) {
        saveHistory(context, HistoryStore.remove(getHistory(context), url))
    }

    fun clearHistory(context: Context) {
        saveHistory(context, emptyList())
    }

    private fun saveHistory(context: Context, entries: List<HistoryEntry>) {
        val array = JSONArray()
        for (e in entries) {
            val obj = JSONObject()
            obj.put("title", e.title)
            obj.put("url", e.url)
            obj.put("ts", e.ts)
            array.put(obj)
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_HISTORY_JSON, array.toString())
            .apply()
    }

    // ---------- 搜索引擎 / 主页 / 浏览体验（F-15） ----------
    fun getSearchEngineId(context: Context): String =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_SEARCH_ENGINE, SearchEngines.DEFAULT_ID)
            ?: SearchEngines.DEFAULT_ID

    fun setSearchEngineId(context: Context, id: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_SEARCH_ENGINE, id).apply()
    }

    /** 主页为空时返回 null，调用方用默认主页 */
    fun getHomePage(context: Context): String? =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_HOME_PAGE, null)?.takeIf { it.isNotBlank() }

    fun setHomePage(context: Context, url: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_HOME_PAGE, url.trim()).apply()
    }

    fun isDataSaver(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_DATA_SAVER, false)

    fun setDataSaver(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_DATA_SAVER, value).apply()
    }

    /** 网页文字缩放百分比，范围 50-200；非法值回落 100 */
    fun getTextZoom(context: Context): Int =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_TEXT_ZOOM, 100)
            .coerceIn(50, 200)

    fun setTextZoom(context: Context, percent: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putInt(KEY_TEXT_ZOOM, percent.coerceIn(50, 200)).apply()
    }

    /** 设置页发起的"清缓存"请求，由 MainActivity.onResume 执行（缓存清理需要 WebView 实例） */
    fun consumePendingClearCache(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val pending = prefs.getBoolean(KEY_PENDING_CLEAR_CACHE, false)
        if (pending) prefs.edit().putBoolean(KEY_PENDING_CLEAR_CACHE, false).apply()
        return pending
    }

    fun markPendingClearCache(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_PENDING_CLEAR_CACHE, true).apply()
    }
}
