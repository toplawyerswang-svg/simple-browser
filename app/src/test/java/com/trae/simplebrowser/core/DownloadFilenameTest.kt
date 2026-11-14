package com.trae.simplebrowser.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** DownloadFilename：WebView 下载文件名推断（Content-Disposition > URL 末段 > mimetype 默认名） */
class DownloadFilenameTest {

    @Test
    fun contentDisposition_quotedFilename_wins() {
        val name = DownloadFilename.resolve(
            "https://example.com/files/abc123",
            "attachment; filename=\"年度报告.pdf\"",
            "application/pdf"
        )
        assertEquals("年度报告.pdf", name)
    }

    @Test
    fun contentDisposition_rfc5987_utf8_winsOverQuoted() {
        val name = DownloadFilename.resolve(
            "https://example.com/files/abc123",
            "attachment; filename=\"fallback.txt\"; filename*=UTF-8''%E6%8A%A5%E5%91%8A.docx",
            "application/octet-stream"
        )
        assertEquals("报告.docx", name)
    }

    @Test
    fun contentDisposition_unquoted_taken() {
        val name = DownloadFilename.resolve(
            "https://example.com/x",
            "attachment; filename=data.csv",
            null
        )
        assertEquals("data.csv", name)
    }

    @Test
    fun blankDisposition_fallsBackToUrlPath_decoded() {
        val name = DownloadFilename.resolve(
            "https://example.com/files/%E6%B3%95%E5%BE%8B%E6%84%8F%E8%A7%81.pdf?token=abc",
            null,
            null
        )
        assertEquals("法律意见.pdf", name)
    }

    @Test
    fun dispositionWithoutFilename_fallsBackToUrlPath() {
        val name = DownloadFilename.resolve(
            "https://example.com/files/report.pdf",
            "attachment",
            null
        )
        assertEquals("report.pdf", name)
    }

    @Test
    fun noExtensionAnywhere_urlNamePlusMimeExt() {
        val name = DownloadFilename.resolve(
            "https://example.com/getfile?id=9",
            "attachment",
            "application/pdf"
        )
        assertEquals("getfile.pdf", name)
    }

    @Test
    fun nothingUsable_defaultDownloadName() {
        val name = DownloadFilename.resolve("https://example.com/", "", null)
        assertTrue(name.matches(Regex("download_\\d+\\.bin")))
    }

    @Test
    fun urlOnly_downloadName_usesMimeExtension() {
        val name = DownloadFilename.resolve("https://example.com/", "", "image/png")
        assertTrue(name.matches(Regex("download_\\d+\\.png")))
    }

    @Test
    fun illegalChars_sanitized() {
        val name = DownloadFilename.resolve(
            "https://example.com/x",
            "attachment; filename=\"weird:name*?.zip\"",
            null
        )
        // : * ? 三个非法字符各替换为一个下划线
        assertEquals("weird_name__.zip", name)
    }

    @Test
    fun fromContentDisposition_blankOrNull() {
        assertNull(DownloadFilename.fromContentDisposition(""))
        assertNull(DownloadFilename.fromContentDisposition("attachment"))
    }

    @Test
    fun fromUrlPath_stripsQueryAndFragment() {
        assertEquals("a.zip", DownloadFilename.fromUrlPath("https://e.com/d/a.zip#frag"))
        assertEquals("b.bin", DownloadFilename.fromUrlPath("https://e.com/b.bin"))
        assertNull(DownloadFilename.fromUrlPath("https://e.com/"))
    }
}
