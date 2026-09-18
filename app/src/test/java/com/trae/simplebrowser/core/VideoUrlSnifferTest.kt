package com.trae.simplebrowser.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 视频地址识别与 m3u8 解析基线测试。
 */
class VideoUrlSnifferTest {

    // ---------- classify ----------

    @Test
    fun classify_directExtensions() {
        assertEquals(VideoKind.DIRECT, VideoUrlSniffer.classify("https://a.com/v.mp4"))
        assertEquals(VideoKind.DIRECT, VideoUrlSniffer.classify("https://a.com/v.WEBM"))
        assertEquals(VideoKind.DIRECT, VideoUrlSniffer.classify("http://a.com/v.flv?token=x"))
        assertEquals(VideoKind.DIRECT, VideoUrlSniffer.classify("https://a.com/p/v.mkv#frag"))
        assertEquals(VideoKind.DIRECT, VideoUrlSniffer.classify("https://a.com/v.ts"))
    }

    @Test
    fun classify_m3u8() {
        assertEquals(VideoKind.M3U8, VideoUrlSniffer.classify("https://a.com/live/index.m3u8"))
        assertEquals(VideoKind.M3U8, VideoUrlSniffer.classify("https://a.com/live/pl.M3U8?sign=1"))
    }

    @Test
    fun classify_blobAndOther() {
        assertEquals(VideoKind.BLOB, VideoUrlSniffer.classify("blob:https://a.com/abc-123"))
        assertEquals(VideoKind.BLOB, VideoUrlSniffer.classify("mediasource:xyz"))
        assertEquals(VideoKind.OTHER, VideoUrlSniffer.classify("https://a.com/page.html"))
        assertEquals(VideoKind.OTHER, VideoUrlSniffer.classify("https://a.com/video/init"))
        assertEquals(VideoKind.OTHER, VideoUrlSniffer.classify(""))
    }

    @Test
    fun looksLikeMediaUrl_and_guessExtension() {
        assertTrue(VideoUrlSniffer.looksLikeMediaUrl("https://a.com/v.mp4"))
        assertTrue(VideoUrlSniffer.looksLikeMediaUrl("https://a.com/i.m3u8"))
        assertFalse(VideoUrlSniffer.looksLikeMediaUrl("https://a.com/i.js"))
        assertEquals("webm", VideoUrlSniffer.guessExtensionFor("https://a.com/v.webm?q=1"))
        assertEquals("mp4", VideoUrlSniffer.guessExtensionFor("https://a.com/noext"))
    }

    // ---------- resolveUrl ----------

    @Test
    fun resolveUrl_relativeForms() {
        val base = "https://v.ex.com/live/master.m3u8"
        assertEquals("https://v.ex.com/live/seg1.ts", VideoUrlSniffer.resolveUrl(base, "seg1.ts"))
        assertEquals("https://v.ex.com/720/index.m3u8", VideoUrlSniffer.resolveUrl(base, "/720/index.m3u8"))
        assertEquals("https://cdn.other.com/s.ts", VideoUrlSniffer.resolveUrl(base, "//cdn.other.com/s.ts"))
        assertEquals("https://x.y/z.ts", VideoUrlSniffer.resolveUrl(base, "https://x.y/z.ts"))
    }

    // ---------- 主播放列表 ----------

    @Test
    fun masterPlaylist_sortedByBandwidthAndResolved() {
        val content = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360
            360/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=2800000,RESOLUTION=1280x720
            720/index.m3u8
        """.trimIndent()
        assertTrue(VideoUrlSniffer.isMasterPlaylist(content))
        val variants = VideoUrlSniffer.parseMaster(content, "https://v.ex.com/live/master.m3u8")
        assertEquals(2, variants.size)
        assertEquals(2800000L, variants[0].bandwidth)
        assertEquals("https://v.ex.com/live/720/index.m3u8", variants[0].url)
        assertEquals(800000L, variants[1].bandwidth)
    }

    @Test
    fun mediaPlaylist_isNotMaster() {
        val media = "#EXTM3U\n#EXTINF:9.0,\nseg.ts\n#EXT-X-ENDLIST"
        assertFalse(VideoUrlSniffer.isMasterPlaylist(media))
    }

    // ---------- 媒体播放列表 ----------

    @Test
    fun mediaPlaylist_basicSegments() {
        val content = """
            #EXTM3U
            #EXT-X-TARGETDURATION:10
            #EXT-X-MEDIA-SEQUENCE:5
            #EXTINF:9.009,
            seg_5.ts
            #EXTINF:8.5
            seg_6.ts
            #EXT-X-ENDLIST
        """.trimIndent()
        val pl = VideoUrlSniffer.parseMedia(content, "https://v.ex.com/live/media.m3u8")
        assertEquals(2, pl.segments.size)
        assertEquals(5L, pl.mediaSequence)
        assertTrue(pl.hasEndList)
        assertFalse(pl.hasByteRange)
        assertNull(pl.initSegmentUrl)
        assertFalse(pl.encryptionUnsupported)
        assertEquals("https://v.ex.com/live/seg_5.ts", pl.segments[0].url)
        assertEquals(9.009, pl.segments[0].durationSec, 0.0001)
        assertEquals(8.5, pl.segments[1].durationSec, 0.0001)
        assertEquals(17.509, pl.totalDurationSec, 0.001)
    }

    @Test
    fun mediaPlaylist_noEndList_isLive() {
        val content = "#EXTM3U\n#EXTINF:4,\nseg.ts"
        val pl = VideoUrlSniffer.parseMedia(content, "https://a.com/x.m3u8")
        assertFalse(pl.hasEndList)
    }

    @Test
    fun mediaPlaylist_aesKeyWithIvAndUriResolution() {
        val content = """
            #EXTM3U
            #EXT-X-KEY:METHOD=AES-128,URI="enc.key",IV=0x000102030405060708090a0b0c0d0e0f
            #EXTINF:5,
            s1.ts
            #EXT-X-ENDLIST
        """.trimIndent()
        val pl = VideoUrlSniffer.parseMedia(content, "https://v.ex.com/live/x.m3u8")
        val key = pl.segments[0].key
        assertNotNull(key)
        assertEquals("AES-128", key!!.method)
        assertEquals("https://v.ex.com/live/enc.key", key.uri)
        assertEquals("0x000102030405060708090a0b0c0d0e0f", key.ivHex)
        assertFalse(pl.encryptionUnsupported)
    }

    @Test
    fun mediaPlaylist_keyNoneResetsAndSampleAesUnsupported() {
        val reset = """
            #EXTM3U
            #EXT-X-KEY:METHOD=AES-128,URI="a.key"
            #EXTINF:1,
            s1.ts
            #EXT-X-KEY:METHOD=NONE
            #EXTINF:2,
            s2.ts
        """.trimIndent()
        val pl1 = VideoUrlSniffer.parseMedia(reset, "https://a.com/x.m3u8")
        assertNotNull(pl1.segments[0].key)
        assertNull(pl1.segments[1].key)

        val sampleAes = "#EXTM3U\n#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"k\"\n#EXTINF:1,\ns.ts"
        val pl2 = VideoUrlSniffer.parseMedia(sampleAes, "https://a.com/x.m3u8")
        assertTrue(pl2.encryptionUnsupported)
    }

    @Test
    fun mediaPlaylist_byterangeAndMap() {
        val content = """
            #EXTM3U
            #EXT-X-MAP:URI="init.mp4"
            #EXTINF:4,
            part.ts
            #EXT-X-BYTERANGE:1000@0
        """.trimIndent()
        val pl = VideoUrlSniffer.parseMedia(content, "https://a.com/v/x.m3u8")
        assertTrue(pl.hasByteRange)
        assertEquals("https://a.com/v/init.mp4", pl.initSegmentUrl)
    }

    // ---------- 下载候选决策 ----------

    @Test
    fun pickCandidate_elementM3u8Wins() {
        val c = VideoUrlSniffer.pickDownloadCandidate(
            "https://a.com/x.m3u8",
            listOf("https://a.com/v.mp4")
        )
        assertEquals(VideoKind.M3U8, c!!.kind)
    }

    @Test
    fun pickCandidate_elementDirectWins() {
        val c = VideoUrlSniffer.pickDownloadCandidate(
            "https://a.com/v.mp4",
            listOf("https://a.com/x.m3u8")
        )
        assertEquals(VideoKind.DIRECT, c!!.kind)
    }

    @Test
    fun pickCandidate_blobFallsBackToSniffed() {
        val c1 = VideoUrlSniffer.pickDownloadCandidate("blob:https://a.com/1", listOf("https://a.com/x.m3u8"))
        assertEquals(VideoKind.M3U8, c1!!.kind)

        val c2 = VideoUrlSniffer.pickDownloadCandidate(
            "blob:https://a.com/1",
            listOf("https://a.com/x.m3u8", "https://cdn.a.com/v.mp4")
        )
        assertEquals(VideoKind.DIRECT, c2!!.kind)
    }

    @Test
    fun pickCandidate_extensionlessElementIsLastResort() {
        // 嗅探到 m3u8 时优先嗅探结果
        val c1 = VideoUrlSniffer.pickDownloadCandidate(
            "https://a.com/video/stream",
            listOf("https://a.com/x.m3u8")
        )
        assertEquals(VideoKind.M3U8, c1!!.kind)
        // 什么都没嗅探到时，无扩展名 http 地址当直链兜底
        val c2 = VideoUrlSniffer.pickDownloadCandidate("https://a.com/video/stream", emptyList())
        assertEquals(VideoKind.DIRECT, c2!!.kind)
    }

    @Test
    fun pickCandidate_noneAvailable() {
        assertNull(VideoUrlSniffer.pickDownloadCandidate(null, emptyList()))
        assertNull(VideoUrlSniffer.pickDownloadCandidate("blob:https://a.com/1", emptyList()))
        assertNull(VideoUrlSniffer.pickDownloadCandidate("", emptyList()))
    }

    // ---------- 文件名清洗 ----------

    @Test
    fun sanitizeFileName_replacesIllegalCharsWithSpace() {
        // 非法字符替换为空格并折叠，保留英文标题的可读性
        assertEquals("视频 第1集 下 载", VideoUrlSniffer.sanitizeFileName("视频/第1集:下?载*\"< >"))
        assertEquals("My Video Part 1", VideoUrlSniffer.sanitizeFileName("My Video: Part 1?"))
        assertEquals("video", VideoUrlSniffer.sanitizeFileName("/:*?\"<>|"))
        assertEquals("video", VideoUrlSniffer.sanitizeFileName("   "))
        assertEquals("video", VideoUrlSniffer.sanitizeFileName(""))
        assertEquals(10, VideoUrlSniffer.sanitizeFileName("a".repeat(100), maxLength = 10).length)
    }

    // ---------- 下载产物有效性（修复"瞬间完成"误报） ----------

    @Test
    fun suspiciouslySmall_detection() {
        assertTrue(VideoUrlSniffer.isSuspiciouslySmall(0L))
        assertTrue(VideoUrlSniffer.isSuspiciouslySmall(50_000L))
        assertFalse(VideoUrlSniffer.isSuspiciouslySmall(VideoUrlSniffer.MIN_VALID_VIDEO_BYTES))
        assertFalse(VideoUrlSniffer.isSuspiciouslySmall(80_000_000L))
    }

    @Test
    fun suspiciouslyShort_detection() {
        assertTrue(VideoUrlSniffer.isSuspiciouslyShort(0.0))
        assertTrue(VideoUrlSniffer.isSuspiciouslyShort(2.9))
        assertFalse(VideoUrlSniffer.isSuspiciouslyShort(3.0))
        assertFalse(VideoUrlSniffer.isSuspiciouslyShort(1200.0))
        // -1 等负值表示未知时长，不判短
        assertFalse(VideoUrlSniffer.isSuspiciouslyShort(-1.0))
    }
}
