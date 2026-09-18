package com.trae.simplebrowser.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 媒体流探测判定基线测试（修复无扩展名流地址识别） */
class MediaProbeLogicTest {

    // ---------- classifyProbeResponse ----------

    @Test
    fun m3u8_byMime_anySize() {
        assertEquals(
            VideoKind.M3U8,
            MediaProbeLogic.classifyProbeResponse("application/vnd.apple.mpegurl", -1L, 200)
        )
        assertEquals(
            VideoKind.M3U8,
            MediaProbeLogic.classifyProbeResponse("application/x-mpegurl; charset=utf-8", 800L, 206)
        )
        // 播放列表常见被误标 audio/
        assertEquals(
            VideoKind.M3U8,
            MediaProbeLogic.classifyProbeResponse("audio/mpegurl", 500L, 200)
        )
    }

    @Test
    fun direct_byMimeWithLargeSize() {
        assertEquals(
            VideoKind.DIRECT,
            MediaProbeLogic.classifyProbeResponse("video/mp4", 80_000_000L, 206)
        )
        assertEquals(
            VideoKind.DIRECT,
            MediaProbeLogic.classifyProbeResponse("video/mp2t", 12_000_000L, 200)
        )
        // octet-stream + 足够大（无后缀 CDN 流的常见形态）
        assertEquals(
            VideoKind.DIRECT,
            MediaProbeLogic.classifyProbeResponse("application/octet-stream", 30_000_000L, 206)
        )
        // 空 MIME（部分 CDN 不给）但 Content-Range 总长很大
        assertEquals(
            VideoKind.DIRECT,
            MediaProbeLogic.classifyProbeResponse(null, 50_000_000L, 206)
        )
    }

    @Test
    fun reject_smallContentAndNonMedia() {
        // 防盗链 HTML 页：octet-stream 但只有几 KB → 拒绝
        assertNull(MediaProbeLogic.classifyProbeResponse("application/octet-stream", 3_000L, 200))
        // 明确的 HTML/图片 → 拒绝
        assertNull(MediaProbeLogic.classifyProbeResponse("text/html", 80_000_000L, 200))
        assertNull(MediaProbeLogic.classifyProbeResponse("image/jpeg", 80_000_000L, 200))
        assertNull(MediaProbeLogic.classifyProbeResponse("application/json", 80_000_000L, 200))
        // 错误状态码 → 拒绝
        assertNull(MediaProbeLogic.classifyProbeResponse("video/mp4", 80_000_000L, 403))
        assertNull(MediaProbeLogic.classifyProbeResponse("video/mp4", 80_000_000L, 302))
    }

    @Test
    fun dashMarkedUnsupported() {
        assertEquals(
            VideoKind.OTHER,
            MediaProbeLogic.classifyProbeResponse("application/dash+xml", 1000L, 200)
        )
    }

    // ---------- looksPromisingForProbe ----------

    @Test
    fun promising_urls() {
        // 标准扩展名
        assertTrue(MediaProbeLogic.looksPromisingForProbe("https://a.com/v/index.m3u8?sign=x"))
        assertTrue(MediaProbeLogic.looksPromisingForProbe("https://cdn.a.com/v.mp4"))
        // 国内站常见无后缀播放接口
        assertTrue(MediaProbeLogic.looksPromisingForProbe("https://v.youku.com/playurl?vkey=abc"))
        assertTrue(MediaProbeLogic.looksPromisingForProbe("https://a.com/getplayinfo?id=1"))
        assertTrue(MediaProbeLogic.looksPromisingForProbe("https://a.com/hls/stream01/seg-3.ts"))
        assertTrue(MediaProbeLogic.looksPromisingForProbe("https://a.com/manifest.mpd"))
    }

    @Test
    fun notPromising_urls() {
        assertFalse(MediaProbeLogic.looksPromisingForProbe("https://a.com/page.html"))
        assertFalse(MediaProbeLogic.looksPromisingForProbe("https://a.com/static/app.js"))
        assertFalse(MediaProbeLogic.looksPromisingForProbe("https://a.com/api/user/info"))
        assertFalse(MediaProbeLogic.looksPromisingForProbe(""))
    }

    @Test
    fun constants_sane() {
        assertTrue(MediaProbeLogic.MIN_DIRECT_VIDEO_BYTES >= 1_000_000L)
    }
}
