package com.knowyourphone.app.overlay

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SweepGradient
import android.util.TypedValue
import android.view.View
import android.view.animation.LinearInterpolator

class ScreenshotFlashOverlayView(context: Context) : View(context) {
    private var intensity = 0f
    private var strokeWidth = dp(132f)
    private var animator: ValueAnimator? = null

    private val edgeInset = dp(8f)
    private val cornerRadius = dp(28f)
    private val sweepStops = floatArrayOf(0f, 0.14f, 0.38f, 0.64f, 0.86f, 1f)
    private val sweepColors = intArrayOf(
        Color.argb(0, 34, 211, 238),
        Color.argb(255, 34, 211, 238),  // outline start (cyan)
        Color.argb(255, 59, 130, 246),  // outline mid (blue)
        Color.argb(255, 139, 92, 246),  // outline end (purple)
        Color.argb(255, 34, 211, 238),
        Color.argb(0, 34, 211, 238)
    )

    private val shaderMatrix = Matrix()
    private var sweepGradient: SweepGradient? = null

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    fun flash() {
        animator?.cancel()
        visibility = VISIBLE

        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 400L
            interpolator = LinearInterpolator()
            addUpdateListener { va ->
                val t = va.animatedFraction
                // 100ms in, 100ms hold, 200ms out.
                intensity = when {
                    t < 0.25f -> (t / 0.25f)
                    t < 0.50f -> 1f
                    else -> {
                        val p = ((t - 0.50f) / 0.50f).coerceIn(0f, 1f)
                        (1f - p).coerceIn(0f, 1f)
                    }
                }
                // Roughly 3x thicker than prior edge stroke values.
                strokeWidth = lerp(dp(152f), dp(78f), t)
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    intensity = 0f
                    visibility = GONE
                    invalidate()
                }

                override fun onAnimationCancel(animation: Animator) {
                    intensity = 0f
                    visibility = GONE
                    invalidate()
                }
            })
            start()
        }
    }

    fun isFlashing(): Boolean = intensity > 0f

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) {
            sweepGradient = null
            return
        }
        sweepGradient = SweepGradient(
            w / 2f,
            h / 2f,
            sweepColors,
            sweepStops
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (intensity <= 0f || width <= 0 || height <= 0) return

        // BlurMaskFilter only renders correctly in software mode.
        setLayerType(LAYER_TYPE_SOFTWARE, null)

        val rect = RectF(
            edgeInset,
            edgeInset,
            width - edgeInset,
            height - edgeInset
        )
        if (rect.width() <= 0f || rect.height() <= 0f) return

        val gradient = sweepGradient ?: SweepGradient(
            rect.centerX(),
            rect.centerY(),
            sweepColors,
            sweepStops
        ).also { sweepGradient = it }

        shaderMatrix.reset()
        shaderMatrix.postRotate(14f + 20f * (1f - intensity), rect.centerX(), rect.centerY())
        gradient.setLocalMatrix(shaderMatrix)

        glowPaint.shader = gradient
        glowPaint.strokeWidth = strokeWidth
        glowPaint.alpha = (intensity * 245).toInt().coerceIn(0, 255)
        glowPaint.maskFilter = BlurMaskFilter(strokeWidth * 0.6f, BlurMaskFilter.Blur.NORMAL)
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, glowPaint)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator?.cancel()
    }

    private fun lerp(start: Float, end: Float, fraction: Float): Float {
        return start + (end - start) * fraction.coerceIn(0f, 1f)
    }

    private fun dp(value: Float): Float {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value,
            resources.displayMetrics
        )
    }
}
