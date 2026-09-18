package com.trae.simplebrowser

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator

/**
 * 扫码取景框：四周半透明暗化遮罩 + 居中方形透窗 + 青绿角标 + 上下往复扫描线。
 * 仅负责视觉，识别用的是整帧画面，与透窗位置无耦合。
 */
class ScanOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val maskPaint = Paint().apply { color = MASK_COLOR }
    private val bracketPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = ACCENT_COLOR
    }
    private val laserPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = ACCENT_COLOR
    }

    private val windowRect = RectF()

    private var laserAnimator: ValueAnimator? = null

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val density = resources.displayMetrics.density
        val side = (minOf(w, h) * WINDOW_RATIO).toInt().coerceAtLeast((220 * density).toInt())
        val left = (w - side) / 2f
        val top = (h - side) / 2f
        windowRect.set(left, top, left + side, top + side)
        bracketPaint.strokeWidth = 4 * density
        laserPaint.strokeWidth = 2.5f * density
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val r = windowRect
        if (r.isEmpty) return

        // 四块暗化遮罩围出透窗
        canvas.drawRect(0f, 0f, width.toFloat(), r.top, maskPaint)
        canvas.drawRect(0f, r.top, r.left, r.bottom, maskPaint)
        canvas.drawRect(r.right, r.top, width.toFloat(), r.bottom, maskPaint)
        canvas.drawRect(0f, r.bottom, width.toFloat(), height.toFloat(), maskPaint)

        drawBrackets(canvas)
        drawLaser(canvas)
    }

    private fun drawBrackets(canvas: Canvas) {
        val r = windowRect
        val len = BRACKET_LENGTH_RATIO * r.width()
        val half = bracketPaint.strokeWidth / 2f
        val inset = half

        // 左上
        canvas.drawLine(r.left + inset, r.top + inset + len, r.left + inset, r.top + inset, bracketPaint)
        canvas.drawLine(r.left + inset, r.top + inset, r.left + inset + len, r.top + inset, bracketPaint)
        // 右上
        canvas.drawLine(r.right - inset - len, r.top + inset, r.right - inset, r.top + inset, bracketPaint)
        canvas.drawLine(r.right - inset, r.top + inset, r.right - inset, r.top + inset + len, bracketPaint)
        // 左下
        canvas.drawLine(r.left + inset, r.bottom - inset - len, r.left + inset, r.bottom - inset, bracketPaint)
        canvas.drawLine(r.left + inset, r.bottom - inset, r.left + inset + len, r.bottom - inset, bracketPaint)
        // 右下
        canvas.drawLine(r.right - inset - len, r.bottom - inset, r.right - inset, r.bottom - inset, bracketPaint)
        canvas.drawLine(r.right - inset, r.bottom - inset - len, r.right - inset, r.bottom - inset, bracketPaint)
    }

    private fun drawLaser(canvas: Canvas) {
        val r = windowRect
        val y = laserY ?: return
        if (y < r.top || y > r.bottom) return
        val margin = LASER_MARGIN_RATIO * r.width()
        canvas.drawLine(r.left + margin, y, r.right - margin, y, laserPaint)
    }

    private var laserY: Float? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val r = windowRect
        laserAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = LASER_PERIOD_MS
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = LinearInterpolator()
            addUpdateListener { anim ->
                if (!r.isEmpty) {
                    laserY = r.top + anim.animatedValue as Float * r.height()
                    postInvalidateOnAnimation()
                }
            }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        laserAnimator?.cancel()
        laserAnimator = null
        super.onDetachedFromWindow()
    }

    private companion object {
        val MASK_COLOR: Int = 0x96000000.toInt()
        val ACCENT_COLOR: Int = 0xFF26A69A.toInt()
        const val WINDOW_RATIO = 0.68f
        const val BRACKET_LENGTH_RATIO = 0.16f
        const val LASER_MARGIN_RATIO = 0.06f
        const val LASER_PERIOD_MS = 1500L
    }
}
