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

    // Pulse alpha for stroke (0.75..1.0 mapped to 191..255)
    private var pulseAlpha = 255
    private var pulseAnimator: ValueAnimator? = null

    // Modern blue palette
    private val accentColor = Color.rgb(96, 165, 250) // #60A5FA
    private val glowColor = Color.argb(50, 96, 165, 250)

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(8f)
        color = glowColor
        maskFilter = BlurMaskFilter(dp(6f), BlurMaskFilter.Blur.NORMAL)
    }

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2.5f)
        color = accentColor
    }

    // Dark glass-style label background matching pill design
    private val labelBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(230, 14, 14, 24) // #0E0E18 at 90%
    }

    private val labelOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        color = Color.argb(100, 58, 58, 80) // #3A3A50 at ~40%
    }

    private val labelTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(240, 212, 212, 220) // #D4D4DC
        textSize = dp(12f)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    fun setHighlights(highlights: List<HighlightTarget>) {
        targets = highlights
        resolveBounds()
        startFadeIn()
        startPulse()
        invalidate()
    }

    fun hasHighlights(): Boolean = targets.isNotEmpty()

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

        val viewLocation = IntArray(2)
        getLocationOnScreen(viewLocation)
        val offsetX = viewLocation[0].toFloat()
        val offsetY = viewLocation[1].toFloat()

        for (target in targets) {
            val bounds = target.bounds
            if (bounds != null) {
                resolved.add(target to boundsToRectF(bounds, offsetX, offsetY))
                continue
            }

            if (accessibility != null) {
                val found = accessibility.findBoundsForId(target.elementId)
                if (found != null) {
                    resolved.add(target to boundsToRectF(found, offsetX, offsetY))
                }
            }
        }

        resolvedBounds = resolved
    }

    private fun boundsToRectF(b: Bounds, offsetX: Float = 0f, offsetY: Float = 0f): RectF {
        return RectF(
            b.left.toFloat() - offsetX,
            b.top.toFloat() - offsetY,
            b.right.toFloat() - offsetX,
            b.bottom.toFloat() - offsetY
        )
    }

    private fun startFadeIn() {
        fadeAnimator?.cancel()
        fadeAlpha = 0
        fadeAnimator = ValueAnimator.ofInt(0, 255).apply {
            duration = 250
            addUpdateListener {
                fadeAlpha = it.animatedValue as Int
                invalidate()
            }
            start()
        }
    }

    private fun startPulse() {
        pulseAnimator?.cancel()
        pulseAnimator = ValueAnimator.ofInt(191, 255).apply {
            duration = 900
            repeatMode = ValueAnimator.REVERSE
            repeatCount = 3
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

        setLayerType(LAYER_TYPE_SOFTWARE, null) // Needed for BlurMaskFilter

        val total = resolvedBounds.size

        resolvedBounds.forEachIndexed { index, (target, rect) ->
            // Add slight padding around the element
            val pad = dp(3f)
            val paddedRect = RectF(rect.left - pad, rect.top - pad, rect.right + pad, rect.bottom + pad)

            val elementHeight = paddedRect.height()
            val cornerRadius = min(dp(12f), elementHeight / 3f)

            val combinedAlpha = (fadeAlpha * pulseAlpha) / 255

            // Soft outer glow
            glowPaint.alpha = (fadeAlpha * 50) / 255
            canvas.drawRoundRect(paddedRect, cornerRadius, cornerRadius, glowPaint)

            // Main accent stroke
            strokePaint.alpha = combinedAlpha
            canvas.drawRoundRect(paddedRect, cornerRadius, cornerRadius, strokePaint)

            // Label (only if present)
            if (target.label.isNotBlank()) {
                val prefix = if (total > 1) "${index + 1}. " else ""
                val labelText = prefix + target.label
                val textWidth = labelTextPaint.measureText(labelText)
                val paddingH = dp(10f)
                val paddingV = dp(6f)
                val labelHeight = labelTextPaint.textSize + paddingV * 2f

                // Position: centered below the element
                val labelTotalWidth = textWidth + paddingH * 2f
                var labelX = paddedRect.centerX() - labelTotalWidth / 2f
                var labelY = paddedRect.bottom + dp(6f) + labelHeight

                // If off-screen bottom, place above
                if (labelY > height) {
                    labelY = paddedRect.top - dp(6f)
                }

                // Clamp X to screen bounds with margin
                val margin = dp(4f)
                if (labelX + labelTotalWidth > width - margin) {
                    labelX = width - labelTotalWidth - margin
                }
                if (labelX < margin) labelX = margin

                val bgRect = RectF(
                    labelX,
                    labelY - labelHeight,
                    labelX + labelTotalWidth,
                    labelY
                )

                val labelRadius = labelHeight / 2f

                // Dark glass background
                labelBgPaint.alpha = (fadeAlpha * 230) / 255
                canvas.drawRoundRect(bgRect, labelRadius, labelRadius, labelBgPaint)

                // Subtle outline
                labelOutlinePaint.alpha = (fadeAlpha * 100) / 255
                canvas.drawRoundRect(bgRect, labelRadius, labelRadius, labelOutlinePaint)

                // Label text
                labelTextPaint.alpha = (fadeAlpha * 240) / 255
                val textY = labelY - labelHeight / 2f - (labelTextPaint.ascent() + labelTextPaint.descent()) / 2f
                canvas.drawText(labelText, labelX + paddingH, textY, labelTextPaint)
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
