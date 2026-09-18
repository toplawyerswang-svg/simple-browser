package com.trae.simplebrowser.core

import com.google.zxing.BarcodeFormat
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 扫码解码核心基线测试：编码→解码回环、非法输入、网址判定、URL 归一化。
 */
class QrCodeDecoderTest {

    private fun encodeToMatrix(text: String, size: Int = 200): BitMatrix =
        QRCodeWriter().encode(
            text, BarcodeFormat.QR_CODE, size, size,
            mapOf(
                com.google.zxing.EncodeHintType.MARGIN to 2,
                // 与真实中文二维码生成器一致：UTF-8 字符集
                com.google.zxing.EncodeHintType.CHARACTER_SET to "UTF-8"
            )
        )

    private fun matrixToSource(matrix: BitMatrix): RGBLuminanceSource {
        val w = matrix.width
        val h = matrix.height
        val pixels = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                pixels[y * w + x] = if (matrix.get(x, y)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
            }
        }
        return RGBLuminanceSource(w, h, pixels)
    }

    // ---------- 编码→解码回环（正常输入） ----------

    @Test
    fun decodeRoundtrip_url() {
        val text = "https://www.example.com/page?q=scan"
        val decoded = QrCodeDecoder.decode(matrixToSource(encodeToMatrix(text)))
        assertEquals(text, decoded)
    }

    @Test
    fun decodeRoundtrip_chineseText() {
        val text = "轻舟浏览器-扫码测试"
        val decoded = QrCodeDecoder.decode(matrixToSource(encodeToMatrix(text)))
        assertEquals(text, decoded)
    }

    @Test
    fun decodeRoundtrip_wifiConfig() {
        val text = "WIFI:T:WPA;S:MyHome;P:pass1234;;"
        val decoded = QrCodeDecoder.decode(matrixToSource(encodeToMatrix(text)))
        assertEquals(text, decoded)
    }

    @Test
    fun decodeRoundtrip_schemelessUrl() {
        val text = "example.com/path"
        val decoded = QrCodeDecoder.decode(matrixToSource(encodeToMatrix(text)))
        assertEquals(text, decoded)
    }

    // ---------- 异常/边界输入 ----------

    @Test
    fun decodeBlankImage_returnsNull() {
        val pixels = IntArray(200 * 200) { 0xFFFFFFFF.toInt() }
        assertNull(QrCodeDecoder.decodeArgbPixels(pixels, 200, 200))
    }

    @Test
    fun decodeNv21_invalidSize_returnsNull() {
        val nv21 = ByteArray(64)
        assertNull(QrCodeDecoder.decodeNv21(nv21, 0, 0))
        assertNull(QrCodeDecoder.decodeNv21(nv21, -1, 8))
        assertNull(QrCodeDecoder.decodeNv21(nv21, 100, 100)) // 数据不足
    }

    @Test
    fun decodeArgbPixels_pixelCountMismatch_returnsNull() {
        assertNull(QrCodeDecoder.decodeArgbPixels(IntArray(10), 20, 20))
    }

    // ---------- isProbablyUrl ----------

    @Test
    fun isProbablyUrl_normal() {
        assertTrue(QrCodeDecoder.isProbablyUrl("https://www.example.com"))
        assertTrue(QrCodeDecoder.isProbablyUrl("http://example.com/path?q=1"))
        assertTrue(QrCodeDecoder.isProbablyUrl("HTTPS://EXAMPLE.COM"))
    }

    @Test
    fun isProbablyUrl_schemelessDomain() {
        assertTrue(QrCodeDecoder.isProbablyUrl("www.example.com"))
        assertTrue(QrCodeDecoder.isProbablyUrl("example.com"))
        assertTrue(QrCodeDecoder.isProbablyUrl("sub.domain.co.uk/path/to"))
        assertTrue(QrCodeDecoder.isProbablyUrl("example.com:8080/x"))
        assertTrue(QrCodeDecoder.isProbablyUrl("t.cn/abc"))
    }

    @Test
    fun isProbablyUrl_otherSchemes() {
        assertTrue(QrCodeDecoder.isProbablyUrl("weixin://dl/business/"))
        assertTrue(QrCodeDecoder.isProbablyUrl("baiduboxapp://utils?params"))
    }

    @Test
    fun isProbablyUrl_plainText() {
        assertFalse(QrCodeDecoder.isProbablyUrl("just some text"))
        assertFalse(QrCodeDecoder.isProbablyUrl("hello world 123"))
        assertFalse(QrCodeDecoder.isProbablyUrl(""))
        assertFalse(QrCodeDecoder.isProbablyUrl("   "))
    }

    @Test
    fun isProbablyUrl_nonWebPayloads() {
        assertFalse(QrCodeDecoder.isProbablyUrl("WIFI:T:WPA;S:MyWifi;P:pass;;"))
        assertFalse(QrCodeDecoder.isProbablyUrl("BEGIN:VCARD"))
        assertFalse(QrCodeDecoder.isProbablyUrl("MECARD:N:name;TEL:123;"))
        assertFalse(QrCodeDecoder.isProbablyUrl("tel:10086"))
    }

    @Test
    fun isProbablyUrl_boundary() {
        // 纯 IP 无 scheme 不当网址打开（边界约定，走文本弹窗）
        assertFalse(QrCodeDecoder.isProbablyUrl("192.168.1.1"))
        // 超长文本拒绝
        assertFalse(QrCodeDecoder.isProbablyUrl("a".repeat(2049)))
        // 长 but 合法域名
        assertTrue(QrCodeDecoder.isProbablyUrl("a".repeat(95) + ".com"))
    }

    // ---------- normalizeUrl ----------

    @Test
    fun normalizeUrl_addsHttpsWhenMissing() {
        assertEquals("https://example.com", QrCodeDecoder.normalizeUrl("example.com"))
        assertEquals("https://www.example.com/a?b=1", QrCodeDecoder.normalizeUrl("www.example.com/a?b=1"))
    }

    @Test
    fun normalizeUrl_keepsExistingScheme() {
        assertEquals("http://a.com", QrCodeDecoder.normalizeUrl("http://a.com"))
        // 已带 scheme 的原样返回（含大小写，不做改写）
        assertEquals("HTTPS://a.com", QrCodeDecoder.normalizeUrl("HTTPS://a.com"))
        assertEquals("weixin://dl/business/", QrCodeDecoder.normalizeUrl("weixin://dl/business/"))
    }
}
