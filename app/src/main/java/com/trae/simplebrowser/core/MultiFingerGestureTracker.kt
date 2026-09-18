package com.trae.simplebrowser.core

import kotlin.math.abs

/**
 * 多指滑动方向判定器（纯逻辑，无 Android 依赖，便于单元测试）。
 *
 * 判定规则：
 * 1. 任一参与手指的起点位于屏幕四边边缘排除区内时手势作废（上下避开系统通知栏/导航条手势，
 *    左右避开全面屏返回手势）；
 * 2. 参与判定的手指必须同向移动且位移均超过阈值；
 * 3. 主方向位移必须大于各自侧向位移（排除捏合缩放 pinch 误触）；
 * 4. 有效手势：双指上滑（刷新）、双指下滑（切换隐藏）、双指右滑（打开设置）；
 * 5. 三指及以上的手势一律不触发（三指下滑与系统截图手势冲突，交还给系统处理）；
 * 6. 一次手势只触发一次结果，触发后自动锁定；
 * 7. 第 3 指落下时会以当前全部手指重新记录基准点（手指数量变为 3 后不再触发任何应用手势）。
 */
class MultiFingerGestureTracker(
    private val swipeThresholdPx: Float,
    private val edgeExclusionPx: Float,
    private val maxTrackedFingers: Int = 3
) {
    enum class Gesture { NONE, TWO_FINGER_SWIPE_UP, TWO_FINGER_SWIPE_DOWN, TWO_FINGER_SWIPE_RIGHT }

    private var fingerCount = 0
    private val startX = FloatArray(maxTrackedFingers)
    private val startY = FloatArray(maxTrackedFingers)
    private var active = false

    val trackedFingerCount: Int get() = fingerCount

    fun onPointerDown(
        count: Int,
        xs: FloatArray,
        ys: FloatArray,
        screenWidth: Float,
        screenHeight: Float
    ) {
        val n = count.coerceAtMost(maxTrackedFingers)
        if (n < 2) {
            fingerCount = 0
            active = false
            return
        }
        fingerCount = n
        for (i in 0 until n) {
            startX[i] = xs[i]
            startY[i] = ys[i]
        }
        active = (0 until n).all { isInUsableArea(xs[it], ys[it], screenWidth, screenHeight) }
    }

    fun onMove(xs: FloatArray, ys: FloatArray): Gesture {
        if (!active) return Gesture.NONE
        val n = fingerCount
        if (xs.size < n || ys.size < n) return Gesture.NONE

        var allDown = true
        var allUp = true
        var allRight = true
        for (i in 0 until n) {
            val dy = ys[i] - startY[i]
            val dx = xs[i] - startX[i]
            if (!(dy > swipeThresholdPx && abs(dx) < dy)) allDown = false
            if (!(dy < -swipeThresholdPx && abs(dx) < -dy)) allUp = false
            if (!(dx > swipeThresholdPx && abs(dy) < dx)) allRight = false
        }

        if (allDown && n == 2) {
            active = false
            return Gesture.TWO_FINGER_SWIPE_DOWN
        }
        if (allUp && n == 2) {
            active = false
            return Gesture.TWO_FINGER_SWIPE_UP
        }
        if (allRight && n == 2) {
            active = false
            return Gesture.TWO_FINGER_SWIPE_RIGHT
        }
        return Gesture.NONE
    }

    fun reset() {
        active = false
    }

    private fun isInUsableArea(x: Float, y: Float, screenWidth: Float, screenHeight: Float): Boolean {
        return x > edgeExclusionPx && x < screenWidth - edgeExclusionPx &&
            y > edgeExclusionPx && y < screenHeight - edgeExclusionPx
    }
}
