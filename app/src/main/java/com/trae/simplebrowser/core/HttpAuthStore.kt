package com.trae.simplebrowser.core

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64

/**
 * HTTP Basic 认证「记住密码」存储：
 * - 键 = 源(origin: 协议+域名+端口) | 区域(realm)，同源下按 系统路径提示(appPath) 存多条凭据；
 *   同一服务器上的多个系统、不同子域名的站点、同账号名不同密码互不覆盖。
 * - 取消记住只清当前系统的记录；旧版「host|realm」单条记录在读侧兼容预填，重存后自动入新键。
 * Base64 仅避免明文裸存并非加密；存于应用私有目录，卸载即清。
 */
object HttpAuthStore {
    private const val PREFS_NAME = "http_auth_store"

    private val codec = HttpAuthEntryCodec(
        b64 = { Base64.encodeToString(it, Base64.NO_WRAP) },
        unb64 = { runCatching { Base64.decode(it, Base64.NO_WRAP) }.getOrNull() }
    )

    /** 取保存的凭据：新格式按源+区域+路径匹配；无新格式记录时回退旧版单条记录 */
    fun get(context: Context, originKey: String, realm: String?, appPath: String): Pair<String, String>? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val entries = codec.decode(prefs.getString(prefsKey(originKey, realm), null))
        if (entries.isNotEmpty()) {
            return HttpAuthEntries.pick(entries, appPath)?.let { it.username to it.password }
        }
        return getLegacy(prefs, legacyHostKey(originKey), realm)
    }

    fun put(context: Context, originKey: String, realm: String?, appPath: String, username: String, password: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val key = prefsKey(originKey, realm)
        val entries = HttpAuthEntries.upsert(
            codec.decode(prefs.getString(key, null)),
            HttpAuthEntries.Entry(appPath, username, password, System.currentTimeMillis())
        )
        prefs.edit().putString(key, codec.encode(entries)).apply()
    }

    /** 取消记住：清当前系统的记录；旧版记录一并清除 */
    fun clear(context: Context, originKey: String, realm: String?, appPath: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val key = prefsKey(originKey, realm)
        val left = HttpAuthEntries.removeAppPath(codec.decode(prefs.getString(key, null)), appPath)
        prefs.edit()
            .putString(key, codec.encode(left))
            .remove("${legacyHostKey(originKey)}|${realm.orEmpty()}")
            .apply()
    }

    private fun prefsKey(originKey: String, realm: String?): String = "$originKey|${realm.orEmpty()}"

    /** 从源标识还原旧版键用的 host（丢协议与端口，兼容旧数据） */
    private fun legacyHostKey(originKey: String): String =
        originKey.substringAfter("://", originKey).substringBefore(':')

    /** 旧版格式：一条 b64("账号\n密码")，无路径提示 */
    private fun getLegacy(prefs: SharedPreferences, host: String, realm: String?): Pair<String, String>? {
        val raw = prefs.getString("$host|${realm.orEmpty()}", null) ?: return null
        val decoded = runCatching {
            String(Base64.decode(raw, Base64.NO_WRAP), Charsets.UTF_8)
        }.getOrNull() ?: return null
        val sep = decoded.indexOf('\n')
        if (sep < 0) return null
        return decoded.substring(0, sep) to decoded.substring(sep + 1)
    }
}
