package com.trae.simplebrowser.core

import java.nio.ByteBuffer

/** 一帧 NV21 数据（Y 平面 + 交错 VU 平面）及其尺寸 */
data class Nv21Frame(val data: ByteArray, val width: Int, val height: Int)

/**
 * YUV_420_888 → NV21 转换与旋转（纯字节数组运算，可 JVM 单元测试）。
 *
 * CameraX ImageAnalysis 输出 YUV_420_888，各行 stride 可能大于有效宽度（对齐填充），
 * U/V 平面可能是半交错（pixelStride=2），这里统一转成紧凑 NV21；
 * 旋转在 NV21 域完成（顺时针），避免依赖解码库的旋转能力。
 */
object YuvNv21 {

    /** 从 ImageProxy 三个平面提取 NV21。调用方需传各平面的 buffer 与 stride 元数据 */
    fun yuv420888ToNv21(
        yBuffer: ByteBuffer,
        uBuffer: ByteBuffer,
        vBuffer: ByteBuffer,
        width: Int,
        height: Int,
        yRowStride: Int,
        uvRowStride: Int,
        uvPixelStride: Int
    ): ByteArray {
        require(width > 0 && height > 0 && width % 2 == 0 && height % 2 == 0) {
            "invalid yuv size ${width}x$height"
        }
        val nv21 = ByteArray(width * height * 3 / 2)
        var pos = 0
        if (yRowStride == width) {
            yBuffer.position(0)
            yBuffer.get(nv21, 0, width * height)
            pos = width * height
        } else {
            for (row in 0 until height) {
                yBuffer.position(row * yRowStride)
                yBuffer.get(nv21, pos, width)
                pos += width
            }
        }
        val chromaWidth = width / 2
        val chromaHeight = height / 2
        for (row in 0 until chromaHeight) {
            val base = row * uvRowStride
            for (col in 0 until chromaWidth) {
                val idx = base + col * uvPixelStride
                nv21[pos++] = vBuffer.get(idx)
                nv21[pos++] = uBuffer.get(idx)
            }
        }
        return nv21
    }

    /** 顺时针旋转 NV21 帧；degrees 仅支持 0/90/180/270，其他角度原样返回 */
    fun rotate(frame: Nv21Frame, degrees: Int): Nv21Frame {
        val d = ((degrees % 360) + 360) % 360
        if (d == 0 || d !in intArrayOf(90, 180, 270)) return frame

        val w = frame.width
        val h = frame.height
        val outW = if (d == 180) w else h
        val outH = if (d == 180) h else w
        val out = ByteArray(frame.data.size)

        rotateYPlane(frame.data, 0, w, h, out, 0, d)

        // NV21 的 VU 交错平面：每个"像素"占 2 字节（V 在前），按像素网格做同角度旋转
        val uvW = w / 2
        val uvH = h / 2
        val destUvW = if (d == 180) uvW else uvH
        val srcUvOff = w * h
        val destUvOff = outW * outH
        var srcIdx = srcUvOff
        for (vy in 0 until uvH) {
            for (vx in 0 until uvW) {
                val v = frame.data[srcIdx]
                val u = frame.data[srcIdx + 1]
                val pair = rotatedIndex(vx, vy, uvW, uvH, d)
                val destIdx = destUvOff + (pair[1] * destUvW + pair[0]) * 2
                out[destIdx] = v
                out[destIdx + 1] = u
                srcIdx += 2
            }
        }
        return Nv21Frame(out, outW, outH)
    }

    private fun rotateYPlane(
        src: ByteArray, srcOff: Int, w: Int, h: Int,
        dest: ByteArray, destOff: Int, degrees: Int
    ) {
        val destW = if (degrees == 180) w else h
        var idx = srcOff
        for (y in 0 until h) {
            for (x in 0 until w) {
                val pair = rotatedIndex(x, y, w, h, degrees)
                dest[destOff + pair[1] * destW + pair[0]] = src[idx++]
            }
        }
    }

    /** 源坐标 (x,y) 在顺时针旋转 degrees 后的目标坐标，返回 [x', y'] */
    private fun rotatedIndex(x: Int, y: Int, w: Int, h: Int, degrees: Int): IntArray {
        return when (degrees) {
            90 -> intArrayOf(h - 1 - y, x)
            180 -> intArrayOf(w - 1 - x, h - 1 - y)
            270 -> intArrayOf(y, w - 1 - x)
            else -> intArrayOf(x, y)
        }
    }
}
