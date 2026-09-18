package com.trae.simplebrowser.core

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer

/**
 * 二维码解码核心（纯 JVM，可单元测试）。
 *
 * 只识别 QR_CODE；对相机帧与相册位图分别提供入口：
 * - [decodeNv21]：相机 ImageAnalysis 输出的 NV21（调用方负责按屏幕方向旋转）
 * - [decodeArgbPixels]：相册位图按 EXIF 摆正后的 ARGB 像素
 *
 * 两种二值化策略依次尝试：HybridBinarizer 对大面积图像更稳，
 * GlobalHistogramBinarizer 对对比度低的小图更稳，互为回退。
 */
object QrCodeDecoder {

    private fun newReader(): MultiFormatReader = MultiFormatReader().apply {
        setHints(
            mapOf(
                DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                DecodeHintType.TRY_HARDER to true,
                // 码内自带 ECI 声明时 ECI 优先；无 ECI 的国内常见生成器（UTF-8 裸字节）
                // 若按默认 ISO-8859-1 解会乱码，这里显式指定 UTF-8
                DecodeHintType.CHARACTER_SET to "UTF-8"
            )
        )
    }

    /** 对亮度源做解码，先 Hybrid 后 Global，均失败返回 null */
    fun decode(source: LuminanceSource): String? {
        val viaHybrid = runCatching {
            newReader().decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
        }.getOrNull()
        if (!viaHybrid.isNullOrBlank()) return viaHybrid

        val viaGlobal = runCatching {
            newReader().decodeWithState(BinaryBitmap(GlobalHistogramBinarizer(source))).text
        }.getOrNull()
        return viaGlobal?.takeIf { it.isNotBlank() }
    }

    /** 解码整帧 NV21 数据（luma 平面尺寸必须为 width*height） */
    fun decodeNv21(nv21: ByteArray, width: Int, height: Int): String? {
        if (width <= 0 || height <= 0) return null
        if (nv21.size < width * height) return null
        val source = PlanarYUVLuminanceSource(nv21, width, height, 0, 0, width, height, false)
        return decode(source)
    }

    /** 解码 ARGB 像素（相册位图路径） */
    fun decodeArgbPixels(pixels: IntArray, width: Int, height: Int): String? {
        if (width <= 0 || height <= 0) return null
        if (pixels.size < width * height) return null
        val source = RGBLuminanceSource(width, height, pixels)
        return decode(source)
    }

    /**
     * 判断扫码文本是否像一个可直接打开的网址。
     * - 带任意 scheme（http://、https://、weixin:// 等）→ 网址
     * - 无 scheme 但形如域名（example.com、sub.site.cn/path、host:8080/x）→ 网址
     * - 含空白、WIFI:、MECARD:、BEGIN:VCARD 等纯文本 → 非网址
     */
    fun isProbablyUrl(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty() || t.length > MAX_URL_LENGTH) return false
        if (t.any { it.isWhitespace() }) return false
        if (SCHEME_REGEX.matches(t)) return true
        return DOMAIN_REGEX.matches(t)
    }

    /** 无 scheme 的网址补 https:// 前缀；已带任意 scheme（含 weixin:// 等）的原样返回 */
    fun normalizeUrl(url: String): String {
        return if (url.lowercase().contains("://")) url else "https://$url"
    }

    private const val MAX_URL_LENGTH = 2048

    private val SCHEME_REGEX = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://.*")

    private val DOMAIN_REGEX =
        Regex("^[a-zA-Z0-9-]+(\\.[a-zA-Z0-9-]+)*\\.[a-zA-Z]{2,}(:\\d{1,5})?([/?#].*)?$")
}
