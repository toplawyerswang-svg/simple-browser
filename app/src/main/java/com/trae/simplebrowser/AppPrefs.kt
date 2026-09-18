package com.trae.simplebrowser

import android.content.Context
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
}
