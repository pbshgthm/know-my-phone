package com.knowmyphone.app.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.view.View.MeasureSpec
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

class PillContextMenuOverlayView(
    context: Context,
    showOpenAppLabel: String,
    closeAppLabel: String,
    private val onShowOpenApp: () -> Unit,
    private val onCloseApp: () -> Unit,
    private val onDismiss: () -> Unit
) : FrameLayout(context) {

    companion object {
        const val MENU_ITEM_HEIGHT_DP = 44f
        const val MENU_VERTICAL_PADDING_DP = 8f
        const val MENU_DIVIDER_DP = 1f
    }

    private val card = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(14f)
            setColor(0xF21B2338.toInt())
            setStroke(dp(1f).toInt(), 0x554B7AF7.toInt())
        }
        elevation = dp(10f)
        clipToOutline = false
        isClickable = true
        isFocusable = false
        setPadding(0, dp(MENU_VERTICAL_PADDING_DP).toInt(), 0, dp(MENU_VERTICAL_PADDING_DP).toInt())
    }

    init {
        layoutParams = LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        setBackgroundColor(Color.TRANSPARENT)
        isClickable = true
        isFocusable = false

        addView(
            card,
            LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        card.addView(createMenuItem(showOpenAppLabel, onShowOpenApp))
        card.addView(createDivider())
        card.addView(createMenuItem(closeAppLabel, onCloseApp))

        setOnClickListener { onDismiss() }
        card.setOnClickListener { /* consume outside-dismiss click */ }
    }

    fun placeMenu(menuLeft: Int, menuTop: Int) {
        val params = card.layoutParams as LayoutParams
        params.gravity = Gravity.TOP or Gravity.START
        params.leftMargin = menuLeft
        params.topMargin = menuTop
        card.layoutParams = params
    }

    fun measureMenu(maxWidthPx: Int): Pair<Int, Int> {
        val widthSpec = MeasureSpec.makeMeasureSpec(maxWidthPx, MeasureSpec.AT_MOST)
        val heightSpec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        card.measure(widthSpec, heightSpec)
        return card.measuredWidth to card.measuredHeight
    }

    private fun createMenuItem(label: String, onClick: () -> Unit): TextView {
        return TextView(context).apply {
            text = label
            setTextColor(0xFFF3F8FF.toInt())
            textSize = 13.5f
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            setSingleLine(true)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            textAlignment = TEXT_ALIGNMENT_VIEW_START
            setPadding(dp(14f).toInt(), 0, dp(14f).toInt(), 0)
            setOnClickListener {
                onClick()
                onDismiss()
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(MENU_ITEM_HEIGHT_DP).toInt()
            )
        }
    }

    private fun createDivider(): LinearLayout {
        return LinearLayout(context).apply {
            setBackgroundColor(0x2F556C95)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(MENU_DIVIDER_DP).toInt()
            ).apply {
                marginStart = dp(12f).toInt()
                marginEnd = dp(12f).toInt()
            }
        }
    }

    private fun dp(value: Float): Float {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value,
            resources.displayMetrics
        )
    }
}
