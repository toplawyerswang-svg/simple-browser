package com.trae.simplebrowser.core

/**
 * 媒体流探测判定（纯逻辑，可 JVM 单元测试）。
 *
 * 通用技术：视频站的流地址大多无扩展名（如 /playurl?vkey=...），仅凭 URL 无法识别，
 * 需对候选 URL 发 Range: bytes=0-1 探测请求，按响应的 Content-Type 与真实总大小判定。
 */
object MediaProbeLogic {

    /** 探测确认的媒体候选 */
    data class ProbeResult(
        val url: String,
        val contentType: String,
        val totalBytes: Long,
        val kind: VideoKind
    )

    /**
     * 响应分类：返回 null 表示不是可下载的媒体。
     * - m3u8：MIME 为 mpegurl（部分站给 audio/），大小无意义
     * - 直链视频：video 前缀 MIME、octet-stream 等 + 总大小 ≥ 1MB
     */
    fun classifyProbeResponse(contentType: String?, totalBytes: Long, statusCode: Int): VideoKind? {
        if (statusCode != 206 && statusCode != 200) return null
        val mime = contentType.orEmpty().substringBefore(';').trim().lowercase()
        if (mime.contains("mpegurl")) return VideoKind.M3U8
        if (mime.contains("dash+xml")) return VideoKind.OTHER
        val videoLike = mime.startsWith("video/") ||
            mime == "application/octet-stream" ||
            mime == "binary/octet-stream" ||
            mime == "content/unknown" ||
            mime.isBlank()
        if (!videoLike) return null
        return if (totalBytes >= MIN_DIRECT_VIDEO_BYTES) VideoKind.DIRECT else null
    }

    /**
     * 候选池粗筛：哪些 URL 值得发探测请求（探测代价 1 次轻量 HTTP，须先收窄范围）。
     * 命中视频扩展名或常见流媒体路径关键词；无后缀的播放接口（vkey/playurl 等）也在内。
     */
    fun looksPromisingForProbe(url: String): Boolean {
        val u = url.substringBefore('#').lowercase()
        if (VideoUrlSniffer.classify(u) != VideoKind.OTHER) return true
        if (u.contains(".mpd")) return true
        return PROMISING_KEYWORDS.any { u.contains(it) }
    }

    private val PROMISING_KEYWORDS = listOf(
        "m3u8", ".mp4", ".flv", ".ts?", ".ts&", "/ts/", "playlist", "master.",
        "playurl", "play_url", "getplay", "vkey", "stream", "video/", "media/",
        "/video", ".mpd", "seg", "chunk", "hls", "dash", "range", "mediatype=video"
    )

    /** 直链视频最小可信字节数（探测阶段） */
    const val MIN_DIRECT_VIDEO_BYTES = 1_000_000L
}
