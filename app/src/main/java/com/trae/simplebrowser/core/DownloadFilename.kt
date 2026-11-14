package com.trae.simplebrowser.core

import java.net.URLDecoder

/**
 * 普通文件下载（WebView DownloadListener）的文件名推断，纯逻辑可 JVM 单测。
 * 优先级：Content-Disposition filename > URL 路径末段 > mimetype 推断的默认名。
 */
object DownloadFilename {

    /** 常用 mimetype → 扩展名（下载命名兜底用；不走 android.webkit.MimeTypeMap 以保持 JVM 可测） */
    private val MIME_EXTENSIONS = mapOf(
        "application/pdf" to "pdf",
        "application/zip" to "zip",
        "application/x-rar-compressed" to "rar",
        "application/gzip" to "gz",
        "application/x-7z-compressed" to "7z",
        "application/msword" to "doc",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document" to "docx",
        "application/vnd.ms-excel" to "xls",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" to "xlsx",
        "application/vnd.ms-powerpoint" to "ppt",
        "application/vnd.openxmlformats-officedocument.presentationml.presentation" to "pptx",
        "application/vnd.android.package-archive" to "apk",
        "application/octet-stream" to "bin",
        "text/plain" to "txt",
        "text/html" to "html",
        "text/csv" to "csv",
        "image/jpeg" to "jpg",
        "image/png" to "png",
        "image/gif" to "gif",
        "image/webp" to "webp",
        "audio/mpeg" to "mp3",
        "audio/mp4" to "m4a",
        "audio/wav" to "wav",
        "video/mp4" to "mp4",
        "video/x-msvideo" to "avi",
        "video/quicktime" to "mov"
    )

    /** 从下载来源推断落地文件名；结果保证非空且含扩展名（推不出扩展名时用 .bin） */
    fun resolve(url: String, contentDisposition: String?, mimetype: String?): String {
        val fromDisposition = fromContentDisposition(contentDisposition.orEmpty())
        val name = sanitize(fromDisposition)
            ?: sanitize(fromUrlPath(url))
            ?: defaultName(mimetype)
        return ensureExtension(name, url, mimetype)
    }

    /** 解析 Content-Disposition 的 filename / filename*（RFC 5987） */
    fun fromContentDisposition(cd: String): String? {
        if (cd.isBlank()) return null
        // filename*=UTF-8''xxx 优先（编码更可靠）
        Regex("filename\\*\\s*=\\s*(?:UTF-8|utf-8)''([^;]+)").find(cd)?.let {
            return runCatching { URLDecoder.decode(it.groupValues[1].trim(), "UTF-8") }
                .getOrNull()?.takeIf { s -> s.isNotBlank() }
        }
        Regex("filename\\s*=\\s*\"([^\"]+)\"").find(cd)?.let {
            return it.groupValues[1].trim().takeIf { s -> s.isNotBlank() }
        }
        Regex("filename\\s*=\\s*([^;]+)").find(cd)?.let {
            return it.groupValues[1].trim().takeIf { s -> s.isNotBlank() }
        }
        return null
    }

    /** URL 路径末段（去 query；percent-decode） */
    fun fromUrlPath(url: String): String? {
        val path = url.substringBefore("?").substringBefore("#")
        val last = path.substringAfterLast('/').trim()
        if (last.isBlank()) return null
        return runCatching { URLDecoder.decode(last, "UTF-8") }.getOrNull()
            ?: last
    }

    /** mimetype 推断的兜底名 */
    fun defaultName(mimetype: String?): String {
        val ext = extOfMime(mimetype) ?: "bin"
        return "download_${System.currentTimeMillis()}.$ext"
    }

    /** 确保带扩展名：没有就从 url 末段 / mimetype 推，再不行用 .bin */
    private fun ensureExtension(name: String, url: String, mimetype: String?): String {
        if (name.contains('.')) return name
        val urlExt = fromUrlPath(url)?.substringAfterLast('.', "")
        val ext = urlExt?.takeIf {
            it.isNotBlank() && it.length <= 5 && it.all { c -> c.isLetterOrDigit() }
        } ?: extOfMime(mimetype)
        return "$name.${ext ?: "bin"}"
    }

    private fun extOfMime(mimetype: String?): String? =
        mimetype?.substringBefore(';')?.trim()?.lowercase()?.let { MIME_EXTENSIONS[it] }

    /** mime → 扩展名（供 DownloadPayload 等 JS 桥下载共用）；未知返回 null */
    fun extOf(mimetype: String?): String? = extOfMime(mimetype)

    /** 去非法字符；空返回 null */
    private fun sanitize(name: String?): String? {
        val cleaned = name?.replace(Regex("[\\\\/:*?\"<>|\\r\\n]"), "_")?.trim()
        return cleaned?.takeIf { it.isNotBlank() && it != "." && it != ".." }
    }
}
