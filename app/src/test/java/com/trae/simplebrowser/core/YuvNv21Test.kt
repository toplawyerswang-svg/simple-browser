package com.trae.simplebrowser.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import java.nio.ByteBuffer

/**
 * NV21 转换与旋转基线测试：紧凑/带填充/半交错三种平面布局、四方向旋转、UV 平面一致性。
 */
class YuvNv21Test {

    private fun buffer(bytes: ByteArray): ByteBuffer = ByteBuffer.wrap(bytes)

    private fun yuvToNv21(
        y: ByteArray, u: ByteArray, v: ByteArray,
        width: Int, height: Int,
        yRowStride: Int = width, uvRowStride: Int = width / 2, uvPixelStride: Int = 1
    ): ByteArray = YuvNv21.yuv420888ToNv21(
        buffer(y), buffer(u), buffer(v),
        width, height, yRowStride, uvRowStride, uvPixelStride
    )

    // ---------- yuv420888ToNv21 ----------

    @Test
    fun nv21_layout_compactStrides() {
        val w = 4
        val h = 4
        val y = (0 until w * h).map { (it * 7).toByte() }.toByteArray()
        val u = byteArrayOf(10, 11, 12, 13)
        val v = byteArrayOf(20, 21, 22, 23)

        val nv21 = yuvToNv21(y, u, v, w, h)

        assertEquals(w * h * 3 / 2, nv21.size)
        // Y 平面原样前置
        for (i in 0 until w * h) assertEquals(y[i], nv21[i])
        // VU 交错（V 在前）
        assertEquals(20, nv21[16].toInt()); assertEquals(10, nv21[17].toInt())
        assertEquals(21, nv21[18].toInt()); assertEquals(11, nv21[19].toInt())
        assertEquals(22, nv21[20].toInt()); assertEquals(12, nv21[21].toInt())
        assertEquals(23, nv21[22].toInt()); assertEquals(13, nv21[23].toInt())
    }

    @Test
    fun nv21_layout_paddedYRowStride() {
        val w = 4
        val h = 4
        val stride = 8
        val y = ByteArray(stride * h) { (it * 3).toByte() }
        val u = byteArrayOf(1, 2, 3, 4)
        val v = byteArrayOf(5, 6, 7, 8)

        val nv21 = yuvToNv21(y, u, v, w, h, yRowStride = stride)

        // 每行只取前 w 个有效字节，跳过填充
        for (row in 0 until h) {
            for (col in 0 until w) {
                assertEquals(y[row * stride + col], nv21[row * w + col])
            }
        }
    }

    @Test
    fun nv21_layout_halfInterleavedUv() {
        // pixelStride=2：U 平面内存为 [u0 x x x x x x x u2 ...]，按绝对下标取样
        // uvRowStride=8 × 2 行 → 平面缓冲区需 16 字节
        val w = 4
        val h = 4
        val y = ByteArray(16) { 9 }
        val u = byteArrayOf(1, 0, 0, 0, 0, 0, 0, 0, 3, 0, 0, 0, 0, 0, 0, 0)
        val v = byteArrayOf(5, 0, 0, 0, 0, 0, 0, 0, 7, 0, 8, 0, 0, 0, 0, 0)

        val nv21 = yuvToNv21(y, u, v, w, h, uvRowStride = 8, uvPixelStride = 2)

        assertEquals(1, nv21[17].toInt())  // u0
        assertEquals(5, nv21[16].toInt())  // v0
        assertEquals(3, nv21[21].toInt())  // u2 @ row1 base(8)
        assertEquals(8, nv21[22].toInt())  // v3 @ row1 base(8) + col1*stride(2) = 10
    }

    @Test(expected = IllegalArgumentException::class)
    fun nv21_oddWidth_rejected() {
        val y = ByteArray(9)
        yuvToNv21(y, byteArrayOf(1), byteArrayOf(2), 3, 3)
    }

    // ---------- rotate ----------

    private fun frame4x4(): Nv21Frame {
        // Y 值 = y*10 + x，VU 对按对索引填充
        val w = 4
        val h = 4
        val data = ByteArray(w * h * 3 / 2)
        for (y in 0 until h) for (x in 0 until w) data[y * w + x] = (y * 10 + x).toByte()
        val uvBase = w * h
        var p = 0
        for (i in 0 until 4) {
            data[uvBase + p] = (100 + i).toByte()  // V
            data[uvBase + p + 1] = (60 + i).toByte() // U（≤127，避免 Byte 溢出）
            p += 2
        }
        return Nv21Frame(data, w, h)
    }

    @Test
    fun rotate_zero_returnsSameInstance() {
        val f = frame4x4()
        val r = YuvNv21.rotate(f, 0)
        assertSame(f, r)
    }

    @Test
    fun rotate_90_clockwise_yPlane() {
        val r = YuvNv21.rotate(frame4x4(), 90)
        assertEquals(4, r.width)
        assertEquals(4, r.height)
        // 顺时针 90°：左上 → 右上；右上 → 右下；左下 → 左上
        assertEquals(0, r.data[3].toInt())    // src(0,0)=0 → dest(3,0)
        assertEquals(3, r.data[15].toInt())   // src(3,0)=3 → dest(3,3)
        assertEquals(30, r.data[0].toInt())   // src(0,3)=30 → dest(0,0)
        assertEquals(33, r.data[12].toInt())  // src(3,3)=33 → dest(0,3)
    }

    @Test
    fun rotate_180_yPlane() {
        val r = YuvNv21.rotate(frame4x4(), 180)
        assertEquals(4, r.width)
        assertEquals(4, r.height)
        assertEquals(0, r.data[15].toInt())
        assertEquals(33, r.data[0].toInt())
        // dest(x=2,y=1) ← src(1,2)=21
        assertEquals(21, r.data[6].toInt())
    }

    @Test
    fun rotate_270_clockwise_yPlane() {
        val r = YuvNv21.rotate(frame4x4(), 270)
        assertEquals(4, r.width)
        assertEquals(4, r.height)
        // 逆时针 90°：左上 → 左下；左下 → 右下
        assertEquals(0, r.data[12].toInt())   // src(0,0)=0 → dest(0,3)
        assertEquals(30, r.data[15].toInt())  // src(0,3)=30 → dest(3,3)
        assertEquals(3, r.data[0].toInt())    // src(3,0)=3 → dest(0,0)
    }

    @Test
    fun rotate_90_uvPairsStayTogether() {
        val r = YuvNv21.rotate(frame4x4(), 90)
        val uvBase = 16
        // 2x2 的 VU 网格顺时针旋转：对 0（左上）→ 对 1（右上）
        assertEquals(100, r.data[uvBase + 2].toInt())   // V0 → dest 对 1 的 V 位
        assertEquals(60, r.data[uvBase + 3].toInt())    // U0
        // 对 3（右下）→ 对 2（左下）
        assertEquals(103, r.data[uvBase + 4].toInt())
        assertEquals(63, r.data[uvBase + 5].toInt())
    }

    @Test
    fun rotate_invalidDegrees_returnsSameInstance() {
        val f = frame4x4()
        // 45° 非法原样返回；-90° 等价 270° 顺时针，属有效旋转，会生成新帧
        assertSame(f, YuvNv21.rotate(f, 45))
        assertSame(f, YuvNv21.rotate(f, -45))
        assertEquals(3, YuvNv21.rotate(f, -90).data[0].toInt()) // 等价 270°：src(3,0)=3 → dest(0,0)
    }

    @Test
    fun rotate_fourTimes_returnsOriginal() {
        val original = frame4x4()
        var f = YuvNv21.rotate(original, 90)
        f = YuvNv21.rotate(f, 90)
        f = YuvNv21.rotate(f, 90)
        f = YuvNv21.rotate(f, 90)
        for (i in original.data.indices) {
            assertEquals(original.data[i], f.data[i])
        }
    }
}
