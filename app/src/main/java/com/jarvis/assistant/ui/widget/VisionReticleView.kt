package com.jarvis.assistant.ui.widget

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator

/**
 * Vision-page reticle: dashed scanning circle, rotating arcs and a crosshair,
 * matching the Scanner screen of the reference UI.
 */
class VisionReticleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val dashedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.3f)
        color = Color.argb(150, 70, 145, 235)
        pathEffect = DashPathEffect(floatArrayOf(dp(6f), dp(10f)), 0f)
    }
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2.4f)
        strokeCap = Paint.Cap.ROUND
        color = Color.argb(235, 58, 145, 248)
    }
    private val thinPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        color = Color.argb(120, 70, 145, 235)
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(240, 72, 158, 255)
        style = Paint.Style.FILL
    }
    private val rect = RectF()
    private var rotation = 0f
    private var animator: ValueAnimator? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        animator = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 16000L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                rotation = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val radius = minOf(width, height) / 2f * 0.92f
        if (radius <= 0f) return

        rect.set(cx - radius, cy - radius, cx + radius, cy + radius)
        canvas.drawArc(rect, rotation, 360f, false, dashedPaint)

        rect.set(cx - radius * 0.92f, cy - radius * 0.92f, cx + radius * 0.92f, cy + radius * 0.92f)
        canvas.drawArc(rect, rotation + 12f, 78f, false, arcPaint)
        canvas.drawArc(rect, rotation + 182f, 52f, false, arcPaint)

        rect.set(cx - radius * 0.62f, cy - radius * 0.62f, cx + radius * 0.62f, cy + radius * 0.62f)
        canvas.drawArc(rect, -rotation * 0.45f + 40f, 38f, false, thinPaint)

        val gap = radius * 0.5f
        canvas.drawLine(cx - gap, cy, cx - dp(5f), cy, thinPaint)
        canvas.drawLine(cx + dp(5f), cy, cx + gap, cy, thinPaint)
        canvas.drawLine(cx, cy - gap, cx, cy - dp(5f), thinPaint)
        canvas.drawLine(cx, cy + dp(5f), cx, cy + gap, thinPaint)
        canvas.drawCircle(cx, cy, dp(3f), dotPaint)
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
