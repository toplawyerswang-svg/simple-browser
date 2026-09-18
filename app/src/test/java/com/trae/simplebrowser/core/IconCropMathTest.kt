package com.trae.simplebrowser.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** 快捷方式自定义图标：居中方裁剪与降采样计算基线测试 */
class IconCropMathTest {

    @Test
    fun landscape_centerCrop() {
        // 1000x500 → 中心 500x500，left=250
        assertArrayEquals(intArrayOf(250, 0, 500), IconCropMath.centerCropRect(1000, 500))
    }

    @Test
    fun portrait_centerCrop() {
        // 500x1000 → 中心 500x500，top=250
        assertArrayEquals(intArrayOf(0, 250, 500), IconCropMath.centerCropRect(500, 1000))
    }

    @Test
    fun square_noOffset() {
        assertArrayEquals(intArrayOf(0, 0, 500), IconCropMath.centerCropRect(500, 500))
    }

    @Test
    fun extremeAspect_sideEqualsShortEdge() {
        assertArrayEquals(intArrayOf(0, 4, 1), IconCropMath.centerCropRect(1, 10))
        assertArrayEquals(intArrayOf(4, 0, 1), IconCropMath.centerCropRect(10, 1))
        assertArrayEquals(intArrayOf(1, 0, 2), IconCropMath.centerCropRect(5, 2))
    }

    @Test
    fun oddDimensions_roundDown() {
        // 1001x501 → side=501, left=(1001-501)/2=250
        assertArrayEquals(intArrayOf(250, 0, 501), IconCropMath.centerCropRect(1001, 501))
    }

    @Test
    fun invalidSize_rejected() {
        assertThrows(IllegalArgumentException::class.java) { IconCropMath.centerCropRect(0, 10) }
        assertThrows(IllegalArgumentException::class.java) { IconCropMath.centerCropRect(10, -1) }
    }

    @Test
    fun sampleSize_boundsDecodeDownscale() {
        // 4000x3000 目标 512：/2=2000≥512 → /4=1000≥512 → /8=500<512 停 → 4（解码后 1000x750 ∈ [512,1024)）
        assertEquals(4, IconCropMath.sampleSizeFor(4000, 3000, 512))
        // 4096x3072：/8=512≥512 → /16=256<512 → 8
        assertEquals(8, IconCropMath.sampleSizeFor(4096, 3072, 512))
        // 已在目标区间：不降采样
        assertEquals(1, IconCropMath.sampleSizeFor(800, 600, 512))
        assertEquals(1, IconCropMath.sampleSizeFor(512, 512, 512))
        // 小于目标也不放大
        assertEquals(1, IconCropMath.sampleSizeFor(300, 200, 512))
    }
}
