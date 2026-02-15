package com.knowmyphone.app.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView
import com.knowmyphone.app.i18n.LanguageManager

class ErrorToastView(context: Context, message: String) : FrameLayout(context) {

    init {
        LanguageManager.init(context.applicationContext)
        val langCode = context
            .getSharedPreferences("kyp_prefs", Context.MODE_PRIVATE)
            .getString("language_code", "en") ?: "en"
        val messageSizeSp = if (LanguageManager.isIndicLanguage(langCode)) 12f else 13f

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
            setTextSize(TypedValue.COMPLEX_UNIT_SP, messageSizeSp)
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
