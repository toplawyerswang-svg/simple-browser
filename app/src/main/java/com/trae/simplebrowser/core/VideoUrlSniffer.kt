package com.trae.simplebrowser.core

import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 视频地址类型 */
enum class VideoKind { DIRECT, M3U8, BLOB, OTHER }

/** 一次可下载的视频候选（url + 类型） */
data class DownloadCandidate(val url: String, val kind: VideoKind)

/** m3u8 主播放列表中的一条清晰度变体 */
data class HlsVariant(val bandwidth: Long, val url: String)

/** EXT-X-KEY 描述（AES-128 时含密钥地址与可选 IV） */
data class HlsKey(val method: String, val uri: String?, val ivHex: String?)

/** 一个视频分片 */
data class HlsSegment(val url: String, val durationSec: Double, val key: HlsKey?)

/** 解析后的 m3u8 媒体播放列表 */
data class HlsMediaPlaylist(
    val segments: List<HlsSegment>,
    val totalDurationSec: Double,
    val mediaSequence: Long,
    val hasEndList: Boolean,
    val hasByteRange: Boolean,
    val initSegmentUrl: String?,
    /** 存在 AES-128 以外的加密（如 SAMPLE-AES），无法下载 */
    val encryptionUnsupported: Boolean
)

/**
 * 视频地址识别与 m3u8 解析（纯字符串运算，可 JVM 单元测试）。
 *
 * 供 MainActivity（地址探测）与 VideoDownloadService（播放列表解析）共用。
 */
object VideoUrlSniffer {

    private val DIRECT_EXTENSIONS =
        setOf("mp4", "webm", "flv", "mkv", "avi", "mov", "m4v", "3gp", "ts", "ogv")

    fun classify(url: String): VideoKind {
        val u = url.trim()
        if (u.isEmpty()) return VideoKind.OTHER
        if (u.startsWith("blob:", ignoreCase = true) ||
            u.startsWith("mediasource:", ignoreCase = true)
        ) return VideoKind.BLOB
        val path = u.substringBefore('#').substringBefore('?').lowercase()
        return when {
            path.endsWith(".m3u8") -> VideoKind.M3U8
            DIRECT_EXTENSIONS.any { path.endsWith(".$it") } -> VideoKind.DIRECT
            else -> VideoKind.OTHER
        }
    }

    /** 网络嗅探钩子用：URL 是否像可直接下载的媒体文件 */
    fun looksLikeMediaUrl(url: String): Boolean =
        classify(url) == VideoKind.M3U8 || classify(url) == VideoKind.DIRECT

    /** 直链下载的扩展名，未知时按 mp4 命名 */
    fun guessExtensionFor(url: String): String {
        val path = url.substringBefore('#').substringBefore('?').lowercase()
        return DIRECT_EXTENSIONS.firstOrNull { path.endsWith(".$it") } ?: "mp4"
    }

    /**
     * 下载候选决策：
     * 页面 video 元素地址（m3u8 > 直链扩展名）> 嗅探直链 > 嗅探 m3u8 > video 元素无扩展名 http 地址（兜底当直链）。
     * blob: 地址交给嗅探结果兜底；都拿不到返回 null。
     */
    fun pickDownloadCandidate(elementUrl: String?, sniffedRecentFirst: List<String>): DownloadCandidate? {
        val element = elementUrl.orEmpty().trim()
        when (classify(element)) {
            VideoKind.M3U8 -> return DownloadCandidate(element, VideoKind.M3U8)
            VideoKind.DIRECT -> return DownloadCandidate(element, VideoKind.DIRECT)
            else -> Unit
        }
        sniffedRecentFirst.firstOrNull { classify(it) == VideoKind.DIRECT }?.let {
            return DownloadCandidate(it, VideoKind.DIRECT)
        }
        sniffedRecentFirst.firstOrNull { classify(it) == VideoKind.M3U8 }?.let {
            return DownloadCandidate(it, VideoKind.M3U8)
        }
        if (element.startsWith("http", ignoreCase = true)) {
            // 无扩展名的 http 视频地址（<video src> 直播放），当直链试下载
            return DownloadCandidate(element, VideoKind.DIRECT)
        }
        return null
    }

    /** 主播放列表（多清晰度）判定 */
    fun isMasterPlaylist(content: String): Boolean = content.contains("#EXT-X-STREAM-INF")

    /** 解析主播放列表，按带宽从高到低排序；URI 解析为绝对地址 */
    fun parseMaster(content: String, baseUrl: String): List<HlsVariant> {
        val variants = mutableListOf<HlsVariant>()
        var pendingBandwidth = -1L
        for (raw in content.lines()) {
            val line = raw.trim()
            if (line.startsWith("#EXT-X-STREAM-INF")) {
                val attrs = parseAttrs(line.substringAfter(':', ""))
                pendingBandwidth = attrs["BANDWIDTH"]?.toLongOrNull() ?: 0L
            } else if (line.isNotEmpty() && !line.startsWith("#") && pendingBandwidth >= 0) {
                variants.add(HlsVariant(pendingBandwidth, resolveUrl(baseUrl, line)))
                pendingBandwidth = -1L
            }
        }
        return variants.sortedByDescending { it.bandwidth }
    }

    /** 解析媒体播放列表（分片序列） */
    fun parseMedia(content: String, baseUrl: String): HlsMediaPlaylist {
        var mediaSequence = 0L
        var hasEndList = false
        var hasByteRange = false
        var initSegmentUrl: String? = null
        var encryptionUnsupported = false
        var currentKey: HlsKey? = null
        var pendingDuration: Double? = null
        val segments = mutableListOf<HlsSegment>()

        for (raw in content.lines()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("#")) {
                when {
                    line.startsWith("#EXT-X-MEDIA-SEQUENCE:") ->
                        mediaSequence = line.substringAfter(':').trim().toLongOrNull() ?: 0L

                    line.startsWith("#EXT-X-ENDLIST") -> hasEndList = true

                    line.startsWith("#EXT-X-BYTERANGE:") -> hasByteRange = true

                    line.startsWith("#EXT-X-MAP:") -> {
                        val attrs = parseAttrs(line.substringAfter(':', ""))
                        attrs["URI"]?.takeIf { it.isNotBlank() }?.let {
                            initSegmentUrl = resolveUrl(baseUrl, it)
                        }
                    }

                    line.startsWith("#EXT-X-KEY:") -> {
                        val attrs = parseAttrs(line.substringAfter(':', ""))
                        val method = attrs["METHOD"].orEmpty().uppercase()
                        currentKey = when (method) {
                            "NONE" -> null
                            "AES-128" -> HlsKey(
                                "AES-128",
                                attrs["URI"]?.takeIf { it.isNotBlank() }?.let { resolveUrl(baseUrl, it) },
                                attrs["IV"]
                            )

                            else -> {
                                encryptionUnsupported = true
                                HlsKey(method, attrs["URI"], attrs["IV"])
                            }
                        }
                    }

                    line.startsWith("#EXTINF:") -> pendingDuration =
                        (line.substringAfter(':').substringBefore(',').trim().toDoubleOrNull() ?: 0.0)
                            .coerceAtLeast(0.0)
                }
            } else {
                segments.add(HlsSegment(resolveUrl(baseUrl, line), pendingDuration ?: 0.0, currentKey))
                pendingDuration = null
            }
        }
        return HlsMediaPlaylist(
            segments = segments,
            totalDurationSec = segments.sumOf { it.durationSec },
            mediaSequence = mediaSequence,
            hasEndList = hasEndList,
            hasByteRange = hasByteRange,
            initSegmentUrl = initSegmentUrl,
            encryptionUnsupported = encryptionUnsupported
        )
    }

    /** 相对地址 → 绝对地址（按 RFC 3986，支持 /abs、//host、完整 URL） */
    fun resolveUrl(baseUrl: String, ref: String): String {
        if (ref.isBlank()) return ref
        return runCatching { URI(baseUrl).resolve(ref.trim()).toString() }.getOrElse { ref }
    }

    /** 文件名清洗：去掉非法字符、折叠空白、限长；空标题用 fallback */
    fun sanitizeFileName(title: String, fallback: String = "video", maxLength: Int = 60): String {
        val cleaned = title.replace(Regex("[\\\\/:*?\"<>|\\r\\n\\t]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        return cleaned.ifBlank { fallback }.take(maxLength)
    }

    /** 下载文件名的时间戳后缀 */
    fun fileTimestamp(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    /**
     * 下载产物有效性：小于阈值的"视频"实为防盗链错误页/广告片段，不能算成功。
     * 网络层拿到的字节数/时长小于阈值时应判 FAILED 而不是 DONE。
     */
    fun isSuspiciouslySmall(totalBytes: Long): Boolean =
        totalBytes in 0 until MIN_VALID_VIDEO_BYTES

    fun isSuspiciouslyShort(durationSec: Double): Boolean =
        durationSec >= 0 && durationSec < MIN_VALID_DURATION_SEC

    /** 合并产物有效字节数阈值（100KB） */
    const val MIN_VALID_VIDEO_BYTES = 100_000L

    /** 播放列表有效总时长阈值（秒） */
    const val MIN_VALID_DURATION_SEC = 3.0

    private val ATTR_REGEX = Regex("""([A-Za-z0-9_-]+)=("[^"]*"|[^,]*)""")

    private fun parseAttrs(attrText: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        ATTR_REGEX.findAll(attrText).forEach { m ->
            map[m.groupValues[1]] = m.groupValues[2].removeSurrounding("\"")
        }
        return map
    }
}
