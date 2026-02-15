package com.knowyourphone.app.overlay

import android.content.Context
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import com.knowyourphone.app.overlay.IconButtonView.IconType

class ConfirmPillView(
    context: Context,
    private val onConfirm: () -> Unit,
    private val onDecline: () -> Unit
) : LinearLayout(context) {

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER
        setPadding(dp(8), dp(8), dp(8), dp(8))

        val confirm = IconButtonView(context, IconType.CAMERA).apply {
            setOnClickListener { onConfirm() }
        }
        val decline = IconButtonView(context, IconType.CLOSE).apply {
            setOnClickListener { onDecline() }
        }

        addView(confirm)
        addView(android.view.View(context).apply {
            layoutParams = LayoutParams(dp(12), 1)
        })
        addView(decline)
    }

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics
        ).toInt()
    }
}
