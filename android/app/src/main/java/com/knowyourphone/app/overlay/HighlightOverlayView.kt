package com.knowyourphone.app.overlay

import android.content.Context
import android.graphics.*
import android.view.View
import com.knowyourphone.app.model.HighlightTarget

class HighlightOverlayView(context: Context) : View(context) {
    companion object {
        private const val STROKE_WIDTH = 6f
        private const val CORNER_RADIUS = 16f
        private const val BADGE_RADIUS = 18f
        private const val BADGE_TEXT_SIZE = 24f
        private const val LABEL_TEXT_SIZE = 28f
        private const val LABEL_PADDING = 8f
    }

    private var targets: List<HighlightTarget> = emptyList()

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = STROKE_WIDTH
        color = 0xFF2196F3.toInt() // blue
    }

    private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFF2196F3.toInt()
    }

    private val badgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = BADGE_TEXT_SIZE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }

    private val labelBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xE6000000.toInt() // semi-transparent black
    }

    private val labelTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = LABEL_TEXT_SIZE
        typeface = Typeface.DEFAULT_BOLD
    }

    fun setHighlights(highlights: List<HighlightTarget>) {
        targets = highlights
        invalidate()
    }

    fun clear() {
        targets = emptyList()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        targets.forEachIndexed { index, target ->
            val b = target.bounds
            val rect = RectF(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat())

            // Draw rounded rect stroke around the element
            canvas.drawRoundRect(rect, CORNER_RADIUS, CORNER_RADIUS, strokePaint)

            // Draw numbered badge at top-left corner
            val badgeNum = (index + 1).toString()
            val badgeCx = rect.left - 4f
            val badgeCy = rect.top - 4f
            canvas.drawCircle(badgeCx, badgeCy, BADGE_RADIUS, badgePaint)
            val textYOffset = -(badgeTextPaint.ascent() + badgeTextPaint.descent()) / 2f
            canvas.drawText(badgeNum, badgeCx, badgeCy + textYOffset, badgeTextPaint)

            // Draw label below the highlighted element
            if (target.label.isNotBlank()) {
                val labelText = "$badgeNum ${target.label}"
                val textWidth = labelTextPaint.measureText(labelText)
                val labelX = rect.left
                val labelY = rect.bottom + LABEL_TEXT_SIZE + LABEL_PADDING + 4f

                // Background
                val bgRect = RectF(
                    labelX - LABEL_PADDING,
                    labelY - LABEL_TEXT_SIZE - LABEL_PADDING,
                    labelX + textWidth + LABEL_PADDING,
                    labelY + LABEL_PADDING
                )
                canvas.drawRoundRect(bgRect, 8f, 8f, labelBgPaint)

                // Text
                canvas.drawText(labelText, labelX, labelY, labelTextPaint)
            }
        }
    }
}
