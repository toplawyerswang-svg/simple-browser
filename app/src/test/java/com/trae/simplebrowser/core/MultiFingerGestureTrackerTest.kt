package com.trae.simplebrowser.core

import com.trae.simplebrowser.core.MultiFingerGestureTracker.Gesture
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * MultiFingerGestureTracker 基线测试：
 * 覆盖双指上滑刷新、双指下滑切换隐藏、双指右滑进设置、捏合缩放防误触、屏幕四边系统手势冲突排除、
 * 方向优先级与三指手势不触发（三指下滑交还系统截图）。
 */
class MultiFingerGestureTrackerTest {

    private companion object {
        const val THRESHOLD = 100f
        const val EDGE = 50f
        const val SCREEN_WIDTH = 1000f
        const val SCREEN_HEIGHT = 1000f
    }

    private lateinit var tracker: MultiFingerGestureTracker

    @Before
    fun setUp() {
        tracker = MultiFingerGestureTracker(THRESHOLD, EDGE)
    }

    private fun twoFingerDown(
        x1: Float = 100f, y1: Float = 500f,
        x2: Float = 300f, y2: Float = 520f
    ) {
        tracker.onPointerDown(2, floatArrayOf(x1, x2), floatArrayOf(y1, y2), SCREEN_WIDTH, SCREEN_HEIGHT)
    }

    private fun threeFingerDown(
        x1: Float = 100f, y1: Float = 500f,
        x2: Float = 300f, y2: Float = 520f,
        x3: Float = 200f, y3: Float = 540f
    ) {
        tracker.onPointerDown(3, floatArrayOf(x1, x2, x3), floatArrayOf(y1, y2, y3), SCREEN_WIDTH, SCREEN_HEIGHT)
    }

    private fun twoFingerMove(y1: Float, y2: Float, x1: Float = 100f, x2: Float = 300f): Gesture {
        return tracker.onMove(floatArrayOf(x1, x2), floatArrayOf(y1, y2))
    }

    private fun threeFingerMove(
        y1: Float, y2: Float, y3: Float,
        x1: Float = 100f, x2: Float = 300f, x3: Float = 200f
    ): Gesture {
        return tracker.onMove(floatArrayOf(x1, x2, x3), floatArrayOf(y1, y2, y3))
    }

    @Test
    fun bothFingersSwipeUp_triggersRefresh() {
        twoFingerDown()
        assertEquals(Gesture.TWO_FINGER_SWIPE_UP, twoFingerMove(y1 = 350f, y2 = 360f))
    }

    @Test
    fun bothFingersSwipeDown_triggersHideToggle() {
        twoFingerDown()
        assertEquals(Gesture.TWO_FINGER_SWIPE_DOWN, twoFingerMove(y1 = 650f, y2 = 660f))
    }

    @Test
    fun bothFingersSwipeRight_triggersSettings() {
        twoFingerDown()
        assertEquals(Gesture.TWO_FINGER_SWIPE_RIGHT, twoFingerMove(y1 = 505f, y2 = 525f, x1 = 220f, x2 = 420f))
    }

    @Test
    fun diagonalRight_withHorizontalDominant_triggersSettings() {
        twoFingerDown()
        assertEquals(Gesture.TWO_FINGER_SWIPE_RIGHT, twoFingerMove(y1 = 560f, y2 = 580f, x1 = 250f, x2 = 450f))
    }

    @Test
    fun diagonalUpRight_withVerticalDominant_triggersUpNotRight() {
        twoFingerDown()
        assertEquals(Gesture.TWO_FINGER_SWIPE_UP, twoFingerMove(y1 = 340f, y2 = 360f, x1 = 220f, x2 = 420f))
    }

    @Test
    fun diagonalDownRight_withVerticalDominant_triggersDownNotRight() {
        twoFingerDown()
        assertEquals(Gesture.TWO_FINGER_SWIPE_DOWN, twoFingerMove(y1 = 680f, y2 = 700f, x1 = 240f, x2 = 440f))
    }

    @Test
    fun fingersSwipeOppositeDirections_triggersNothing() {
        twoFingerDown()
        assertEquals(Gesture.NONE, twoFingerMove(y1 = 350f, y2 = 700f))
    }

    @Test
    fun movementBelowThreshold_triggersNothing() {
        twoFingerDown()
        assertEquals(Gesture.NONE, twoFingerMove(y1 = 420f, y2 = 430f))
    }

    @Test
    fun rightSwipeBelowThreshold_triggersNothing() {
        twoFingerDown()
        assertEquals(Gesture.NONE, twoFingerMove(y1 = 500f, y2 = 520f, x1 = 150f, x2 = 350f))
    }

    @Test
    fun fingersSwipeOppositeHorizontal_triggersNothing() {
        twoFingerDown()
        assertEquals(Gesture.NONE, twoFingerMove(y1 = 505f, y2 = 525f, x1 = 300f, x2 = 100f))
    }

    @Test
    fun pinchZoom_horizontalDominant_triggersNothing() {
        twoFingerDown()
        assertEquals(Gesture.NONE, tracker.onMove(floatArrayOf(-30f, 430f), floatArrayOf(380f, 640f)))
    }

    @Test
    fun diagonalUp_withVerticalDominant_triggersRefresh() {
        twoFingerDown()
        assertEquals(Gesture.TWO_FINGER_SWIPE_UP, twoFingerMove(y1 = 340f, y2 = 400f, x1 = 60f, x2 = 340f))
    }

    @Test
    fun fingerStartingAtTopEdge_triggersNothing() {
        twoFingerDown(y1 = 30f, y2 = 500f)
        assertEquals(Gesture.NONE, twoFingerMove(y1 = 350f, y2 = 350f))
    }

    @Test
    fun fingerStartingAtBottomEdge_triggersNothing() {
        twoFingerDown(y1 = 500f, y2 = 960f)
        assertEquals(Gesture.NONE, twoFingerMove(y1 = 350f, y2 = 810f))
    }

    @Test
    fun fingerStartingAtLeftEdge_triggersNothing() {
        twoFingerDown(x1 = 30f)
        assertEquals(Gesture.NONE, twoFingerMove(y1 = 505f, y2 = 525f, x1 = 220f, x2 = 420f))
    }

    @Test
    fun fingerStartingAtRightEdge_triggersNothing() {
        twoFingerDown(x2 = 960f)
        assertEquals(Gesture.NONE, twoFingerMove(y1 = 505f, y2 = 525f, x1 = 220f, x2 = 960f))
    }

    @Test
    fun gestureTriggersOnlyOnce() {
        twoFingerDown()
        assertEquals(Gesture.TWO_FINGER_SWIPE_UP, twoFingerMove(y1 = 350f, y2 = 360f))
        assertEquals(Gesture.NONE, twoFingerMove(y1 = 300f, y2 = 310f))
    }

    @Test
    fun reset_reEnablesTracking() {
        twoFingerDown()
        assertEquals(Gesture.TWO_FINGER_SWIPE_UP, twoFingerMove(y1 = 350f, y2 = 360f))
        tracker.reset()
        twoFingerDown()
        assertEquals(Gesture.TWO_FINGER_SWIPE_UP, twoFingerMove(y1 = 350f, y2 = 360f))
    }

    @Test
    fun moveWithoutPointerDown_triggersNothing() {
        assertEquals(Gesture.NONE, twoFingerMove(y1 = 350f, y2 = 360f))
    }

    @Test
    fun threeFingersSwipeDown_triggersNothing_systemGesture() {
        threeFingerDown()
        assertEquals(Gesture.NONE, threeFingerMove(y1 = 650f, y2 = 660f, y3 = 680f))
    }

    @Test
    fun threeFingersSwipeRight_triggersNothing() {
        threeFingerDown()
        assertEquals(
            Gesture.NONE,
            threeFingerMove(y1 = 505f, y2 = 525f, y3 = 545f, x1 = 220f, x2 = 420f, x3 = 320f)
        )
    }

    @Test
    fun twoFingerPartialMove_thenThirdFingerDown_abortsAppGesture() {
        twoFingerDown()
        assertEquals(Gesture.NONE, twoFingerMove(y1 = 560f, y2 = 570f))
        threeFingerDown()
        assertEquals(3, tracker.trackedFingerCount)
        assertEquals(Gesture.NONE, threeFingerMove(y1 = 650f, y2 = 660f, y3 = 680f))
    }

    @Test
    fun thirdFingerStartingAtEdge_abortsGesture() {
        threeFingerDown(y3 = 980f)
        assertEquals(Gesture.NONE, threeFingerMove(y1 = 650f, y2 = 660f, y3 = 1000f))
    }

    @Test
    fun threeFingerGesture_onlyTwoFingersMove_triggersNothing() {
        threeFingerDown()
        assertEquals(Gesture.NONE, threeFingerMove(y1 = 650f, y2 = 660f, y3 = 545f))
    }

    @Test
    fun singlePointerDown_doesNotArmTracking() {
        tracker.onPointerDown(1, floatArrayOf(100f), floatArrayOf(500f), SCREEN_WIDTH, SCREEN_HEIGHT)
        assertEquals(Gesture.NONE, twoFingerMove(y1 = 350f, y2 = 360f))
        assertEquals(0, tracker.trackedFingerCount)
    }
}
