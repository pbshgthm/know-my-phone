package com.knowyourphone.app.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.TypedValue
import android.view.View
import android.view.animation.LinearInterpolator
import com.knowyourphone.app.KypAccessibilityService
import com.knowyourphone.app.model.Bounds
import com.knowyourphone.app.model.HighlightTarget
import kotlin.math.min

class HighlightOverlayView(context: Context) : View(context) {
    private var targets: List<HighlightTarget> = emptyList()
    private var resolvedBounds: List<Pair<HighlightTarget, RectF>> = emptyList()

    // Fade-in alpha (0..255)
    private var fadeAlpha = 0
    private var fadeAnimator: ValueAnimator? = null

    // Pulse alpha for stroke (0.7..1.0 mapped to 178..255)
    private var pulseAlpha = 255
    private var pulseAnimator: ValueAnimator? = null

    private val strokeColor = Color.argb(216, 74, 144, 217) // #4A90D9 at 85%
    private val outerStrokeColor = Color.argb(120, 255, 255, 255) // white at ~47%

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(3f)
        color = strokeColor
    }

    private val outerStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(5f)
        color = outerStrokeColor
    }

    private val labelBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(230, 255, 255, 255) // white at 90%
        setShadowLayer(dp(3f), 0f, dp(1f), Color.argb(60, 0, 0, 0))
    }

    private val labelTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(230, 30, 30, 30)
        textSize = dp(13f)
        typeface = Typeface.DEFAULT_BOLD
    }

    fun setHighlights(highlights: List<HighlightTarget>) {
        targets = highlights
        resolveBounds()
        startFadeIn()
        startPulse()
        invalidate()
    }

    fun clear() {
        targets = emptyList()
        resolvedBounds = emptyList()
        fadeAnimator?.cancel()
        pulseAnimator?.cancel()
        fadeAlpha = 0
        pulseAlpha = 255
        invalidate()
    }

    private fun resolveBounds() {
        val accessibility = KypAccessibilityService.instance
        val resolved = mutableListOf<Pair<HighlightTarget, RectF>>()

        for (target in targets) {
            // If bounds are already provided, use them
            val bounds = target.bounds
            if (bounds != null) {
                resolved.add(target to boundsToRectF(bounds))
                continue
            }

            // Otherwise, look up bounds from accessibility tree by elementId
            if (accessibility != null) {
                val found = accessibility.findBoundsForId(target.elementId)
                if (found != null) {
                    resolved.add(target to boundsToRectF(found))
                }
                // If not found, skip silently (screen may have changed)
            }
        }

        resolvedBounds = resolved
    }

    private fun boundsToRectF(b: Bounds): RectF {
        return RectF(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat())
    }

    private fun startFadeIn() {
        fadeAnimator?.cancel()
        fadeAlpha = 0
        fadeAnimator = ValueAnimator.ofInt(0, 255).apply {
            duration = 200
            addUpdateListener {
                fadeAlpha = it.animatedValue as Int
                invalidate()
            }
            start()
        }
    }

    private fun startPulse() {
        pulseAnimator?.cancel()
        pulseAnimator = ValueAnimator.ofInt(178, 255).apply {
            duration = 750
            repeatMode = ValueAnimator.REVERSE
            repeatCount = 3 // 2 full cycles (4 half-cycles = ~3s total)
            interpolator = LinearInterpolator()
            addUpdateListener {
                pulseAlpha = it.animatedValue as Int
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (fadeAlpha == 0) return

        val total = resolvedBounds.size
        val pillY = 0f // pill overlap avoidance placeholder

        resolvedBounds.forEachIndexed { index, (target, rect) ->
            // Scale corner radius with element size
            val elementHeight = rect.height()
            val cornerRadius = min(dp(12f), elementHeight / 4f)

            // Apply fade and pulse alpha
            val combinedAlpha = (fadeAlpha * pulseAlpha) / 255

            // Outer white stroke for contrast on dark backgrounds
            outerStrokePaint.alpha = (fadeAlpha * 120) / 255
            canvas.drawRoundRect(rect, cornerRadius, cornerRadius, outerStrokePaint)

            // Main accent stroke
            strokePaint.alpha = combinedAlpha
            canvas.drawRoundRect(rect, cornerRadius, cornerRadius, strokePaint)

            // Label (only if present)
            if (target.label.isNotBlank()) {
                val prefix = if (total > 1) "${index + 1}. " else ""
                val labelText = prefix + target.label
                val textWidth = labelTextPaint.measureText(labelText)
                val padding = dp(8f)
                val labelHeight = dp(22f)

                // Default position below the element
                var labelX = rect.left
                var labelY = rect.bottom + dp(4f) + labelHeight

                // If off-screen bottom, place above
                if (labelY > height) {
                    labelY = rect.top - dp(4f)
                }

                // Clamp X to screen bounds
                if (labelX + textWidth + padding * 2f > width) {
                    labelX = width - textWidth - padding * 2f
                }
                if (labelX < 0f) labelX = 0f

                val bgRect = RectF(
                    labelX,
                    labelY - labelHeight,
                    labelX + textWidth + padding * 2f,
                    labelY
                )

                labelBgPaint.alpha = (fadeAlpha * 230) / 255
                canvas.drawRoundRect(bgRect, dp(12f), dp(12f), labelBgPaint)

                labelTextPaint.alpha = (fadeAlpha * 230) / 255
                val textY = labelY - (labelHeight / 2f) - (labelTextPaint.ascent() + labelTextPaint.descent()) / 2f
                canvas.drawText(labelText, labelX + padding, textY, labelTextPaint)
            }
        }
    }

    private fun dp(value: Float): Float {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics
        )
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        fadeAnimator?.cancel()
        pulseAnimator?.cancel()
    }
}
