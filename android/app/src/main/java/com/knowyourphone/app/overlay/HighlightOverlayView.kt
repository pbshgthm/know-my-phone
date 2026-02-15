package com.knowyourphone.app.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import android.view.View
import android.view.animation.LinearInterpolator
import com.knowyourphone.app.KypAccessibilityService
import com.knowyourphone.app.model.Bounds
import com.knowyourphone.app.model.HighlightTarget
import kotlin.math.abs
import kotlin.math.min

class HighlightOverlayView(context: Context) : View(context) {
    private enum class LabelSide { LEFT, RIGHT, TOP, BOTTOM }
    private data class LabelPlacement(
        val rect: RectF,
        val side: LabelSide,
        val text: String,
        val anchorX: Float,
        val anchorY: Float
    )

    private var targets: List<HighlightTarget> = emptyList()
    private var resolvedBounds: List<Pair<HighlightTarget, RectF>> = emptyList()

    // Fade-in alpha (0..255)
    private var fadeAlpha = 0
    private var fadeAnimator: ValueAnimator? = null

    // Flow phase for animated gradient outline
    private var outlineShiftPhase = 0f
    private var outlineFlowAnimator: ValueAnimator? = null

    // Same outline palette as DotView (cyan -> blue -> purple).
    private val outlineStart = 0xFF22D3EE.toInt()
    private val outlineMid = 0xFF3B82F6.toInt()
    private val outlineEnd = 0xFF8B5CF6.toInt()

    private val spotlightScrimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(176, 7, 10, 20)
    }

    private val cutoutPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(8.5f)
        maskFilter = BlurMaskFilter(dp(7f), BlurMaskFilter.Blur.NORMAL)
    }

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2.25f)
    }

    private val leaderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.75f)
        color = Color.argb(220, 205, 233, 255)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val leaderDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(240, 220, 240, 255)
    }

    private val labelBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(232, 8, 14, 28)
    }

    private val labelOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        color = Color.argb(150, 72, 113, 176)
    }

    private val labelTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(245, 234, 244, 255)
        textSize = dp(12.5f)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    private val path = Path()

    init {
        // Needed for blur + clear cutouts in the spotlight scrim.
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    fun setHighlights(highlights: List<HighlightTarget>) {
        targets = highlights
        resolveBounds()
        startFadeIn()
        startOutlineFlow()
        invalidate()
    }

    fun hasHighlights(): Boolean = targets.isNotEmpty()

    fun clear() {
        targets = emptyList()
        resolvedBounds = emptyList()
        fadeAnimator?.cancel()
        outlineFlowAnimator?.cancel()
        fadeAlpha = 0
        outlineShiftPhase = 0f
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
            duration = 220
            addUpdateListener {
                fadeAlpha = it.animatedValue as Int
                invalidate()
            }
            start()
        }
    }

    private fun startOutlineFlow() {
        outlineFlowAnimator?.cancel()
        outlineFlowAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 2000
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                outlineShiftPhase = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (fadeAlpha == 0) return

        val paddedRects = resolvedBounds.map { (_, rect) ->
            val pad = dp(4f)
            RectF(rect.left - pad, rect.top - pad, rect.right + pad, rect.bottom + pad)
        }

        // Spotlight scrim with transparent cutouts.
        val scrimLayer = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
        spotlightScrimPaint.alpha = (fadeAlpha * 176) / 255
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), spotlightScrimPaint)
        paddedRects.forEach { hole ->
            val holeRadius = min(dp(14f), hole.height() / 3f)
            canvas.drawRoundRect(hole, holeRadius, holeRadius, cutoutPaint)
        }
        canvas.restoreToCount(scrimLayer)

        val occupiedLabels = mutableListOf<RectF>()
        val total = resolvedBounds.size

        resolvedBounds.forEachIndexed { index, (target, rect) ->
            val paddedRect = paddedRects[index]
            val cornerRadius = min(dp(14f), paddedRect.height() / 3f)

            val outlineShader = buildOutlineShader(paddedRect)
            glowPaint.shader = outlineShader
            glowPaint.alpha = (fadeAlpha * 120) / 255
            canvas.drawRoundRect(paddedRect, cornerRadius, cornerRadius, glowPaint)

            strokePaint.shader = outlineShader
            strokePaint.alpha = fadeAlpha
            canvas.drawRoundRect(paddedRect, cornerRadius, cornerRadius, strokePaint)

            if (target.label.isBlank()) {
                return@forEachIndexed
            }

            val prefix = if (total > 1) "${index + 1}. " else ""
            val placement = placeLabel(
                text = prefix + target.label,
                targetRect = paddedRect,
                occupiedRects = occupiedLabels
            )
            occupiedLabels.add(placement.rect)
            drawLeader(canvas, paddedRect, placement)
            drawLabel(canvas, placement)
        }
    }

    private fun drawLeader(canvas: Canvas, targetRect: RectF, placement: LabelPlacement) {
        var startX = 0f
        var startY = 0f
        var endX = 0f
        var endY = 0f
        when (placement.side) {
            LabelSide.RIGHT -> {
                startX = targetRect.right
                startY = safeClamp(placement.anchorY, targetRect.top + dp(4f), targetRect.bottom - dp(4f))
                endX = placement.rect.left
                endY = placement.rect.centerY()
            }
            LabelSide.LEFT -> {
                startX = targetRect.left
                startY = safeClamp(placement.anchorY, targetRect.top + dp(4f), targetRect.bottom - dp(4f))
                endX = placement.rect.right
                endY = placement.rect.centerY()
            }
            LabelSide.TOP -> {
                startX = safeClamp(placement.anchorX, targetRect.left + dp(4f), targetRect.right - dp(4f))
                startY = targetRect.top
                endX = placement.rect.centerX()
                endY = placement.rect.bottom
            }
            LabelSide.BOTTOM -> {
                startX = safeClamp(placement.anchorX, targetRect.left + dp(4f), targetRect.right - dp(4f))
                startY = targetRect.bottom
                endX = placement.rect.centerX()
                endY = placement.rect.top
            }
        }

        val elbowOffset = dp(14f)
        leaderPaint.alpha = (fadeAlpha * 220) / 255
        path.reset()
        path.moveTo(startX, startY)
        when (placement.side) {
            LabelSide.RIGHT, LabelSide.LEFT -> {
                val elbowX = if (placement.side == LabelSide.RIGHT) startX + elbowOffset else startX - elbowOffset
                path.lineTo(elbowX, startY)
                path.lineTo(elbowX, endY)
                path.lineTo(endX, endY)
            }
            LabelSide.TOP, LabelSide.BOTTOM -> {
                val elbowY = if (placement.side == LabelSide.BOTTOM) startY + elbowOffset else startY - elbowOffset
                path.lineTo(startX, elbowY)
                path.lineTo(endX, elbowY)
                path.lineTo(endX, endY)
            }
        }
        canvas.drawPath(path, leaderPaint)

        leaderDotPaint.alpha = (fadeAlpha * 235) / 255
        canvas.drawCircle(startX, startY, dp(2.4f), leaderDotPaint)
        canvas.drawCircle(endX, endY, dp(2f), leaderDotPaint)
    }

    private fun drawLabel(canvas: Canvas, placement: LabelPlacement) {
        val labelRadius = placement.rect.height() / 2f
        labelBgPaint.alpha = (fadeAlpha * 232) / 255
        canvas.drawRoundRect(placement.rect, labelRadius, labelRadius, labelBgPaint)

        labelOutlinePaint.alpha = (fadeAlpha * 180) / 255
        canvas.drawRoundRect(placement.rect, labelRadius, labelRadius, labelOutlinePaint)

        labelTextPaint.alpha = (fadeAlpha * 245) / 255
        val textY = placement.rect.centerY() - (labelTextPaint.ascent() + labelTextPaint.descent()) / 2f
        canvas.drawText(placement.text, placement.rect.left + dp(12f), textY, labelTextPaint)
    }

    private fun placeLabel(
        text: String,
        targetRect: RectF,
        occupiedRects: List<RectF>
    ): LabelPlacement {
        data class Candidate(
            val placement: LabelPlacement,
            val score: Float
        )

        val sideMargin = dp(12f)
        val gapFromTarget = dp(20f)
        val paddingH = dp(12f)
        val paddingV = dp(6f)
        val minLabelWidth = dp(88f)
        val labelHeight = labelTextPaint.textSize + paddingV * 2f
        val targetCenterX = targetRect.centerX()
        val targetCenterY = targetRect.centerY()
        val contentMinX = sideMargin
        val contentMaxX = width - sideMargin
        val contentMinY = sideMargin
        val contentMaxY = height - sideMargin
        val safeTargetRect = RectF(
            targetRect.left - dp(4f),
            targetRect.top - dp(4f),
            targetRect.right + dp(4f),
            targetRect.bottom + dp(4f)
        )

        fun preferredOrder(): List<LabelSide> {
            val horizontalPref = if (targetCenterX <= width * 0.5f) LabelSide.RIGHT else LabelSide.LEFT
            val horizontalAlt = if (horizontalPref == LabelSide.RIGHT) LabelSide.LEFT else LabelSide.RIGHT
            val verticalPref = if (targetCenterY <= height * 0.5f) LabelSide.BOTTOM else LabelSide.TOP
            val verticalAlt = if (verticalPref == LabelSide.BOTTOM) LabelSide.TOP else LabelSide.BOTTOM
            return listOf(horizontalPref, verticalPref, horizontalAlt, verticalAlt)
        }

        fun sideAvailableWidth(side: LabelSide): Float {
            return when (side) {
                LabelSide.RIGHT -> (contentMaxX - (targetRect.right + gapFromTarget)).coerceAtLeast(0f)
                LabelSide.LEFT -> ((targetRect.left - gapFromTarget) - contentMinX).coerceAtLeast(0f)
                LabelSide.TOP, LabelSide.BOTTOM -> (contentMaxX - contentMinX).coerceAtLeast(0f)
            }
        }

        fun sideWidthCap(side: LabelSide): Float {
            return when (side) {
                LabelSide.LEFT, LabelSide.RIGHT -> width * 0.48f
                LabelSide.TOP, LabelSide.BOTTOM -> width * 0.64f
            }
        }

        fun axisOffsets(step: Float): List<Float> {
            val offsets = mutableListOf(0f)
            for (i in 1..7) {
                offsets += i * step
                offsets += -i * step
            }
            return offsets
        }

        val candidates = mutableListOf<Candidate>()
        val orderedSides = preferredOrder()
        val offsetsForVerticalSides = axisOffsets(labelHeight + dp(8f))
        val offsetsForHorizontalSides = axisOffsets(dp(56f))

        for ((priority, side) in orderedSides.withIndex()) {
            val availableWidth = sideAvailableWidth(side)
            if (availableWidth <= 0f) continue

            val maxLabelWidth = min(sideWidthCap(side), availableWidth)
            val maxTextWidth = (maxLabelWidth - paddingH * 2f).coerceAtLeast(dp(18f))
            val clipped = TextUtils.ellipsize(text, labelTextPaint, maxTextWidth, TextUtils.TruncateAt.END).toString()
            val textWidth = labelTextPaint.measureText(clipped)
            val desiredWidth = textWidth + paddingH * 2f
            val labelWidth = desiredWidth.coerceAtLeast(minLabelWidth).coerceAtMost(maxLabelWidth)
            if (labelWidth <= dp(42f)) continue

            val baseRect = when (side) {
                LabelSide.RIGHT -> RectF(
                    targetRect.right + gapFromTarget,
                    targetCenterY - labelHeight / 2f,
                    targetRect.right + gapFromTarget + labelWidth,
                    targetCenterY + labelHeight / 2f
                )
                LabelSide.LEFT -> RectF(
                    targetRect.left - gapFromTarget - labelWidth,
                    targetCenterY - labelHeight / 2f,
                    targetRect.left - gapFromTarget,
                    targetCenterY + labelHeight / 2f
                )
                LabelSide.TOP -> RectF(
                    targetCenterX - labelWidth / 2f,
                    targetRect.top - gapFromTarget - labelHeight,
                    targetCenterX + labelWidth / 2f,
                    targetRect.top - gapFromTarget
                )
                LabelSide.BOTTOM -> RectF(
                    targetCenterX - labelWidth / 2f,
                    targetRect.bottom + gapFromTarget,
                    targetCenterX + labelWidth / 2f,
                    targetRect.bottom + gapFromTarget + labelHeight
                )
            }

            val offsets = if (side == LabelSide.LEFT || side == LabelSide.RIGHT) {
                offsetsForVerticalSides
            } else {
                offsetsForHorizontalSides
            }

            for (offset in offsets) {
                val rect = RectF(baseRect)
                if (side == LabelSide.LEFT || side == LabelSide.RIGHT) {
                    rect.offset(0f, offset)
                } else {
                    rect.offset(offset, 0f)
                }

                val clampedRect = RectF(
                    safeClamp(rect.left, contentMinX, contentMaxX - rect.width()),
                    safeClamp(rect.top, contentMinY, contentMaxY - rect.height()),
                    0f,
                    0f
                )
                clampedRect.right = clampedRect.left + rect.width()
                clampedRect.bottom = clampedRect.top + rect.height()

                val overlapsLabels = occupiedRects.count { RectF.intersects(it, clampedRect) }
                val intersectsTarget = RectF.intersects(clampedRect, safeTargetRect)

                val clampDistance = abs(clampedRect.left - rect.left) + abs(clampedRect.top - rect.top)
                val displacement = abs(offset)
                val availabilityPenalty = if (availableWidth < minLabelWidth) 180f else 0f
                val overlapPenalty = overlapsLabels * 260f
                val targetPenalty = if (intersectsTarget) 360f else 0f
                val orderPenalty = priority * 18f
                val clampPenalty = clampDistance * 0.6f
                val movementPenalty = displacement * 0.25f
                val score = availabilityPenalty + overlapPenalty + targetPenalty + orderPenalty + clampPenalty + movementPenalty

                val placement = LabelPlacement(
                    rect = clampedRect,
                    side = side,
                    text = clipped,
                    anchorX = safeClamp(targetCenterX, targetRect.left + dp(4f), targetRect.right - dp(4f)),
                    anchorY = safeClamp(targetCenterY, targetRect.top + dp(4f), targetRect.bottom - dp(4f))
                )
                candidates += Candidate(placement, score)
            }
        }

        val best = candidates.minByOrNull { it.score }?.placement
        if (best != null) return best

        // Fallback: center-right minimal label.
        val fallbackText = TextUtils.ellipsize(text, labelTextPaint, width * 0.45f, TextUtils.TruncateAt.END).toString()
        val fallbackWidth = labelTextPaint.measureText(fallbackText) + dp(24f)
        val fallbackRect = RectF(
            (targetRect.right + dp(20f)).coerceAtMost(width - fallbackWidth - dp(12f)),
            safeClamp(targetCenterY - labelHeight / 2f, dp(12f), height - labelHeight - dp(12f)),
            0f,
            0f
        )
        fallbackRect.right = fallbackRect.left + fallbackWidth
        fallbackRect.bottom = fallbackRect.top + labelHeight
        return LabelPlacement(
            rect = fallbackRect,
            side = LabelSide.RIGHT,
            text = fallbackText,
            anchorX = targetCenterX,
            anchorY = targetCenterY
        )
    }

    private fun buildOutlineShader(rect: RectF): Shader {
        val cycleWidth = rect.width() * 1.65f
        val shift = -cycleWidth * outlineShiftPhase
        return LinearGradient(
            rect.left + shift,
            rect.top,
            rect.left + shift + cycleWidth,
            rect.bottom,
            intArrayOf(outlineStart, outlineMid, outlineEnd, outlineStart),
            floatArrayOf(0f, 0.4f, 0.8f, 1f),
            Shader.TileMode.REPEAT
        )
    }

    private fun dp(value: Float): Float {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics
        )
    }

    private fun safeClamp(value: Float, minValue: Float, maxValue: Float): Float {
        return if (maxValue < minValue) {
            (minValue + maxValue) / 2f
        } else {
            value.coerceIn(minValue, maxValue)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        fadeAnimator?.cancel()
        outlineFlowAnimator?.cancel()
    }
}
