package com.knowyourphone.app.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView

class ErrorToastView(context: Context, message: String) : FrameLayout(context) {

    init {
        val paddingH = dp(16)
        val paddingV = dp(8)

        val bg = GradientDrawable().apply {
            setColor(0xDD333333.toInt())
            cornerRadius = dp(20).toFloat()
        }
        background = bg

        val textView = TextView(context).apply {
            text = message
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER
            setPadding(paddingH, paddingV, paddingH, paddingV)
        }

        addView(textView)
    }

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics
        ).toInt()
    }
}
