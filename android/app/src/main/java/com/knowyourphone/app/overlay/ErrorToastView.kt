package com.knowyourphone.app.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView

class ErrorToastView(context: Context, message: String) : FrameLayout(context) {

    init {
        val paddingH = dp(20)
        val paddingV = dp(10)

        val bg = GradientDrawable().apply {
            setColor(0xF00E0E18.toInt())
            cornerRadius = dp(24).toFloat()
            setStroke(dp(1), 0x663A3A50)
        }
        background = bg

        val textView = TextView(context).apply {
            text = message
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            setPadding(paddingH, paddingV, paddingH, paddingV)
            letterSpacing = 0.01f
        }

        addView(textView)
    }

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics
        ).toInt()
    }
}
