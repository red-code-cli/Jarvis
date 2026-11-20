package com.jarvis.assistant.ui.widget

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.cos
import kotlin.math.sin

/**
 * Decorative HUD dial drawn behind/above the live particle orb. It recreates the
 * reference UI's frosted glass disc, rotating arcs, tick ring and cross markers.
 */
class OrbRingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val discPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.2f)
        color = Color.argb(150, 255, 255, 255)
    }
    private val dashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.4f)
        color = Color.argb(160, 61, 143, 246)
        pathEffect = DashPathEffect(floatArrayOf(dp(5f), dp(9f)), 0f)
    }
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2.2f)
        strokeCap = Paint.Cap.ROUND
        color = Color.argb(220, 68, 157, 255)
    }
    private val softArcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.2f)
        strokeCap = Paint.Cap.ROUND
        color = Color.argb(110, 255, 255, 255)
    }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.1f)
        color = Color.argb(135, 55, 109, 190)
    }
    private val crossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.1f)
        color = Color.argb(155, 255, 255, 255)
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(230, 68, 157, 255)
    }

    private val rect = RectF()
    private var rotation = 0f
    private var animator: ValueAnimator? = null
    private var lastFrameAt = 0L
    private var windowVisible = true

    init {
        setWillNotDraw(false)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startAnimator()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        windowVisible = visibility == VISIBLE
        if (windowVisible && animator == null) startAnimator()
    }

    private fun startAnimator() {
        if (animator != null) return
        animator = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 28000L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                // 30 fps is visually identical for a slow dial and halves GPU work.
                val now = SystemClock.uptimeMillis()
                if (now - lastFrameAt < 33L) return@addUpdateListener
                lastFrameAt = now
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
        val radius = minOf(width, height) / 2f
        if (radius <= 0f) return

        // Frosted glass core of the reference dial.
        discPaint.shader = RadialGradient(
            cx, cy * 0.92f, radius * 0.56f,
            intArrayOf(
                Color.argb(118, 255, 255, 255),
                Color.argb(92, 226, 236, 250),
                Color.argb(34, 147, 174, 208)
            ),
            floatArrayOf(0f, 0.62f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, radius * 0.56f, discPaint)

        // Outer specular rings.
        canvas.drawCircle(cx, cy, radius * 0.92f, ringPaint)
        canvas.drawCircle(cx, cy, radius * 0.80f, ringPaint)
        rect.set(cx - radius * 0.72f, cy - radius * 0.72f, cx + radius * 0.72f, cy + radius * 0.72f)
        canvas.drawArc(rect, rotation, 360f, false, dashPaint)

        // Rotating bright arc segments.
        rect.set(cx - radius * 0.72f, cy - radius * 0.72f, cx + radius * 0.72f, cy + radius * 0.72f)
        canvas.drawArc(rect, rotation + 18f, 92f, false, arcPaint)
        canvas.drawArc(rect, rotation + 196f, 64f, false, arcPaint)
        rect.set(cx - radius * 0.88f, cy - radius * 0.88f, cx + radius * 0.88f, cy + radius * 0.88f)
        canvas.drawArc(rect, -rotation * 0.55f + 128f, 48f, false, softArcPaint)

        // Tick ring.
        val tickOuter = radius * 0.93f
        val tickInner = radius * 0.89f
        for (i in 0 until 72) {
            val angle = Math.toRadians(i * 5.0)
            val length = if (i % 6 == 0) tickOuter - tickInner + dp(2.5f) else tickOuter - tickInner
            val startX = cx + cos(angle).toFloat() * (tickOuter - length)
            val startY = cy + sin(angle).toFloat() * (tickOuter - length)
            val endX = cx + cos(angle).toFloat() * tickOuter
            val endY = cy + sin(angle).toFloat() * tickOuter
            canvas.drawLine(startX, startY, endX, endY, tickPaint)
        }

        // Cross markers.
        val crossR = radius * 0.80f
        for (i in 0 until 4) {
            val angle = Math.toRadians(45.0 + i * 90.0)
            val x = cx + cos(angle).toFloat() * crossR
            val y = cy + sin(angle).toFloat() * crossR
            val s = dp(4.5f)
            canvas.drawLine(x - s, y, x + s, y, crossPaint)
            canvas.drawLine(x, y - s, x, y + s, crossPaint)
        }

        canvas.drawCircle(cx, cy, dp(2.4f), dotPaint)
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
