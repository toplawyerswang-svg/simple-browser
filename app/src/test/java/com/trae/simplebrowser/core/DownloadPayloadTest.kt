package com.trae.simplebrowser.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/** DownloadPayload：blob/data JS 桥下载的字节解析与命名 */
class DownloadPayloadTest {

    @Test
    fun decodeDataUrl_base64Pdf() {
        val bytes = byteArrayOf(0x25, 0x50, 0x44, 0x46, 0x2d) // "%PDF-"
        val b64 = Base64.getEncoder().encodeToString(bytes)
        val (mime, decoded) = DownloadPayload.decodeDataUrl("data:application/pdf;base64,$b64")!!
        assertEquals("application/pdf", mime)
        assertTrue(decoded.contentEquals(bytes))
    }

    @Test
    fun decodeDataUrl_plainUtf8() {
        val (_, decoded) = DownloadPayload.decodeDataUrl("data:text/plain,hello%20world")!!
        assertEquals("hello world", String(decoded, Charsets.UTF_8))
    }

    @Test
    fun decodeDataUrl_missingMime_defaultsOctetStream() {
        val b64 = Base64.getEncoder().encodeToString("x".toByteArray())
        val (mime, _) = DownloadPayload.decodeDataUrl("data:;base64,$b64")!!
        assertEquals("application/octet-stream", mime)
    }

    @Test
    fun decodeDataUrl_invalid_returnsNull() {
        assertNull(DownloadPayload.decodeDataUrl("blob:https://x/uuid"))
        assertNull(DownloadPayload.decodeDataUrl("data:nocomma"))
        assertNull(DownloadPayload.decodeDataUrl("data:image/png;base64,!!!not-base64!!!"))
    }

    @Test
    fun resolveName_suggestedWins() {
        val name = DownloadPayload.resolveName("附信源PDF.pdf", "application/pdf", "20260101_000000")
        assertEquals("附信源PDF.pdf", name)
    }

    @Test
    fun resolveName_noName_usesDownloadSeedPlusMimeExt() {
        val name = DownloadPayload.resolveName(null, "application/pdf", "20260101_000000")
        assertEquals("download_20260101_000000.pdf", name)
    }

    @Test
    fun resolveName_nameWithoutExtension_appendsMimeExt() {
        val name = DownloadPayload.resolveName("附信源PDF", "application/pdf", "seed")
        assertEquals("附信源PDF.pdf", name)
    }

    @Test
    fun resolveName_unknownMime_fallsBackBin() {
        val name = DownloadPayload.resolveName("", "application/x-unknown", "seed")
        assertEquals("download_seed.bin", name)
    }

    @Test
    fun resolveName_illegalCharsSanitized_andTruncated() {
        val longName = "a".repeat(200) + ":*?.pdf"
        val name = DownloadPayload.resolveName(longName, "application/pdf", "seed")
        assertTrue(name.length <= 130)
        assertTrue(!name.contains(':') && !name.contains('*') && !name.contains('?'))
    }
}
