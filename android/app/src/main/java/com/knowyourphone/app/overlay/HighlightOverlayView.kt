package com.knowyourphone.app.overlay

import android.content.Context
import android.graphics.*
import android.util.TypedValue
import android.view.View
import com.knowyourphone.app.model.HighlightTarget

class HighlightOverlayView(context: Context) : View(context) {
    private var targets: List<HighlightTarget> = emptyList()

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        color = Color.argb(180, 0, 188, 212) // soft teal, ~70% opacity
    }

    private val labelBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(180, 255, 255, 255) // white, ~70% opacity
    }

    private val labelTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(230, 30, 30, 30)
        textSize = dp(13f)
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

        val total = targets.size

        targets.forEachIndexed { index, target ->
            val b = target.bounds
            val rect = RectF(
                b.left.toFloat(),
                b.top.toFloat(),
                b.right.toFloat(),
                b.bottom.toFloat()
            )

            // Rounded ring
            canvas.drawRoundRect(rect, dp(12f), dp(12f), strokePaint)

            // Label (only if present)
            if (target.label.isNotBlank()) {
                val prefix = if (total > 1) "${index + 1}. " else ""
                val labelText = prefix + target.label
                val textWidth = labelTextPaint.measureText(labelText)
                val padding = dp(6f)
                val labelHeight = dp(20f)

                // Default position below the element
                var labelX = rect.left
                var labelY = rect.bottom + padding + labelHeight

                // If off-screen, place above
                if (labelY + labelHeight > height) {
                    labelY = rect.top - padding
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
                canvas.drawRoundRect(bgRect, dp(8f), dp(8f), labelBgPaint)

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
}
