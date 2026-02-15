package com.knowyourphone.app.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

class ConfirmPillView(
    context: Context,
    private val reason: String,
    private val onConfirm: () -> Unit,
    private val onDecline: () -> Unit
) : LinearLayout(context) {

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER
        setBackgroundColor(0xEE333333.toInt())
        setPadding(dp(16), dp(12), dp(16), dp(12))

        // Reason text
        addView(TextView(context).apply {
            text = "Screenshot needed"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        })

        if (reason.isNotBlank()) {
            addView(TextView(context).apply {
                text = reason
                setTextColor(0xFFCCCCCC.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                gravity = Gravity.CENTER
                setPadding(0, dp(4), 0, dp(8))
            })
        }

        // Buttons row
        val buttonsRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
        }

        // Confirm button
        buttonsRow.addView(TextView(context).apply {
            text = "  Confirm  "
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.DEFAULT_BOLD
            setBackgroundColor(0xFF4CAF50.toInt())
            setPadding(dp(16), dp(8), dp(16), dp(8))
            setOnClickListener { onConfirm() }
        })

        // Spacer
        buttonsRow.addView(android.view.View(context).apply {
            layoutParams = LayoutParams(dp(12), 1)
        })

        // Decline button
        buttonsRow.addView(TextView(context).apply {
            text = "  Not now  "
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setBackgroundColor(0xFF666666.toInt())
            setPadding(dp(16), dp(8), dp(16), dp(8))
            setOnClickListener { onDecline() }
        })

        addView(buttonsRow)
    }

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics
        ).toInt()
    }
}
