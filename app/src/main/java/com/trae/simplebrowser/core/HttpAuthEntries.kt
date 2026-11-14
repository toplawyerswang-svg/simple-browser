package com.trae.simplebrowser.core

/**
 * HTTP Basic 认证「记住密码」的纯逻辑层（无 Android 依赖，可 JVM 单测）：
 * 一条凭据 = 系统路径提示(appPath) + 账号 + 密码，同一 源(origin)+区域(realm) 下可存多条，
 * 使同一服务器上的多个系统、同一账号名在不同网站各自独立记忆，互不覆盖。
 */
object HttpAuthEntries {

    /** 一条记住的凭据：appPath 为登录时页面路径的首段（"/" 表示挂根/未知），lastUsed 毫秒时间戳用于多账号时偏好最近使用 */
    data class Entry(val appPath: String, val username: String, val password: String, val lastUsed: Long)

    /**
     * 源标识：scheme://host[:port]，默认端口省略；scheme 未知时用 unknown 前缀，
     * 保证与旧版「host|realm」键永不重合。不同子域名/端口/协议各自成键。
     */
    fun originKey(scheme: String?, host: String?, port: Int): String {
        val h = (host ?: "").lowercase().trim()
        val defaultPort = (scheme == "http" && port == 80) || (scheme == "https" && port == 443)
        val portPart = if (port > 0 && !defaultPort) ":$port" else ""
        val s = scheme?.lowercase()?.trim().takeUnless { it.isNullOrEmpty() } ?: "unknown"
        return "$s://$h$portPart"
    }

    /** 系统路径提示：取页面路径首段；根路径/空/异常一律归 "/" */
    fun appPathOf(path: String?): String {
        val p = path ?: return "/"
        if (!p.startsWith("/")) return "/"
        val second = p.indexOf('/', 1)
        return if (second < 0) p else p.substring(0, second)
    }

    /**
     * 预填选择：精确段匹配/祖先前缀匹配取最长 appPath，并列取最近使用；
     * 无匹配但该源只有一条记录时回退它（单系统源不受路径提示影响）。
     */
    fun pick(entries: List<Entry>, pageAppPath: String): Entry? {
        fun matches(e: Entry): Boolean =
            e.appPath == pageAppPath || e.appPath == "/" ||
                (pageAppPath.startsWith(e.appPath) && pageAppPath.startsWith("${e.appPath}/"))
        val best = entries.filter(::matches).maxByOrNull { it.appPath.length * 1_000_000_000L + it.lastUsed.coerceAtLeast(0) }
        if (best != null) return best
        return entries.singleOrNull()
    }

    /** 保存：同 路径+账号 视为改密/重记，覆盖并刷新时间；否则追加 */
    fun upsert(entries: List<Entry>, entry: Entry): List<Entry> {
        val idx = entries.indexOfFirst { it.appPath == entry.appPath && it.username == entry.username }
        if (idx < 0) return entries + entry
        return entries.toMutableList().also { it[idx] = entry }
    }

    /** 取消记住：清掉该系统路径下的全部账号 */
    fun removeAppPath(entries: List<Entry>, appPath: String): List<Entry> =
        entries.filterNot { it.appPath == appPath }
}

/**
 * 序列化协议（与设备侧 HttpAuthStore 解耦，便于 JVM 单测）：
 * 每条一行 `b64(appPath)|b64(username)|b64(password)|lastUsed`，坏行跳过。
 * Base64 由调用方注入，避免纯逻辑层触碰 Android API（minSdk 24 无 java.util.Base64）。
 */
class HttpAuthEntryCodec(private val b64: (ByteArray) -> String, private val unb64: (String) -> ByteArray?) {

    fun encode(entries: List<HttpAuthEntries.Entry>): String =
        entries.joinToString("\n") { e ->
            listOf(e.appPath, e.username, e.password)
                .joinToString("|") { f -> b64(f.toByteArray(Charsets.UTF_8)) } + "|" + e.lastUsed
        }

    fun decode(raw: String?): List<HttpAuthEntries.Entry> =
        raw.orEmpty().split('\n').mapNotNull { line ->
            val parts = line.split('|')
            if (parts.size != 4) return@mapNotNull null
            val fields = parts.take(3).map { p ->
                runCatching { String(unb64(p) ?: return@mapNotNull null, Charsets.UTF_8) }.getOrNull() ?: return@mapNotNull null
            }
            val ts = parts[3].toLongOrNull() ?: return@mapNotNull null
            HttpAuthEntries.Entry(fields[0], fields[1], fields[2], ts)
        }
}
