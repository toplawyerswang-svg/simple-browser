package com.trae.simplebrowser.core

import android.util.Base64

/**
 * blob:/data: 下载（JS 内存文件）的解析与命名，纯逻辑可 JVM 单测。
 * 页面下载按钮普遍走 URL.createObjectURL(blob)，这类下载不经过网络层，
 * 需 WebView JS 桥把字节取到 Java 侧落盘。
 */
object DownloadPayload {

    /** 解析 data:[:][;base64,] URL → (mime, bytes)；非法返回 null */
    fun decodeDataUrl(dataUrl: String): Pair<String, ByteArray>? {
        if (!dataUrl.startsWith("data:")) return null
        val comma = dataUrl.indexOf(',')
        if (comma <= 5) return null
        val header = dataUrl.substring(5, comma)   // 如 image/png 或 image/png;base64
        val payload = dataUrl.substring(comma + 1)
        val mime = header.substringBefore(';').ifBlank { "application/octet-stream" }
        val isBase64 = header.substringAfter(';', "").trim() == "base64"
        val bytes = if (isBase64) {
            // java.util.Base64 需 API26+；JVM 单测用它，低版本设备回落 android.util.Base64
            b64Decode(payload) ?: return null
        } else {
            runCatching {
                java.net.URLDecoder.decode(payload, "UTF-8").toByteArray(Charsets.UTF_8)
            }.getOrNull() ?: return null
        }
        return mime to bytes
    }

    private fun b64Decode(s: String): ByteArray? = runCatching {
        java.util.Base64.getDecoder().decode(s)
    }.getOrNull() ?: runCatching {
        @Suppress("DEPRECATION")
        Base64.decode(s, Base64.DEFAULT)
    }.getOrNull()

    /**
     * JS 桥下载的落地文件名：页面建议名优先，无则按 mime 生成；
     * 保证非空、带扩展名、无非法字符。
     */
    fun resolveName(suggested: String?, mime: String, fallbackSeed: String): String {
        val cleaned = sanitize(suggested)
        val name = cleaned ?: "download_${fallbackSeed}"
        if (name.contains('.')) return name
        val ext = DownloadFilename.extOf(mime) ?: "bin"
        return "$name.$ext"
    }

    /** 去非法字符；空/纯点返回 null */
    private fun sanitize(name: String?): String? {
        val cleaned = name
            ?.replace(Regex("[\\\\/:*?\"<>|\\r\\n\\t]"), "_")
            ?.trim()
            ?.take(120)
        return cleaned?.takeIf { it.isNotBlank() && it != "." && it != ".." }
    }
}
