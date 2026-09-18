package com.trae.simplebrowser.core

/**
 * 图标居中方裁剪计算（纯函数，可 JVM 单元测试）。
 * 返回 intArrayOf(left, top, side)：在 srcW×srcH 内取最大居中正方形。
 */
object IconCropMath {

    fun centerCropRect(srcWidth: Int, srcHeight: Int): IntArray {
        require(srcWidth > 0 && srcHeight > 0) { "bad size ${srcWidth}x$srcHeight" }
        val side = minOf(srcWidth, srcHeight)
        val left = (srcWidth - side) / 2
        val top = (srcHeight - side) / 2
        return intArrayOf(left, top, side)
    }

    /** 降采样系数：解码后最长边落在 [targetPx, targetPx*2) 区间的最小 2 的幂 */
    fun sampleSizeFor(srcWidth: Int, srcHeight: Int, targetPx: Int): Int {
        require(srcWidth > 0 && srcHeight > 0 && targetPx > 0)
        var sample = 1
        while (maxOf(srcWidth, srcHeight) / (sample * 2) >= targetPx) {
            sample *= 2
        }
        return sample
    }
}
