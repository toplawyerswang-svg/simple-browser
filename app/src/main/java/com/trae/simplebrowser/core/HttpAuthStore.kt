package com.trae.simplebrowser.core

import android.content.Context
import android.util.Base64

/**
 * HTTP Basic 认证「记住密码」存储：按 域名+区域(host|realm) 保存账号密码，
 * 下次质询时预填登录框。Base64 仅避免明文裸存并非加密；存于应用私有目录，卸载即清。
 */
object HttpAuthStore {
    private const val PREFS_NAME = "http_auth_store"

    /** 取保存的凭据，无记录或数据损坏返回 null */
    fun get(context: Context, host: String?, realm: String?): Pair<String, String>? {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(prefsKey(host, realm), null) ?: return null
        val decoded = runCatching {
            String(Base64.decode(raw, Base64.NO_WRAP), Charsets.UTF_8)
        }.getOrNull() ?: return null
        val sep = decoded.indexOf('\n')
        if (sep < 0) return null
        return decoded.substring(0, sep) to decoded.substring(sep + 1)
    }

    fun put(context: Context, host: String?, realm: String?, username: String, password: String) {
        val encoded = Base64.encodeToString(
            "$username\n$password".toByteArray(Charsets.UTF_8),
            Base64.NO_WRAP
        )
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(prefsKey(host, realm), encoded)
            .apply()
    }

    fun clear(context: Context, host: String?, realm: String?) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(prefsKey(host, realm))
            .apply()
    }

    private fun prefsKey(host: String?, realm: String?): String =
        "${host.orEmpty()}|${realm.orEmpty()}"
}
