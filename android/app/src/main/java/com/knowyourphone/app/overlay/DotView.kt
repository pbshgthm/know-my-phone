package com.knowyourphone.app.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.TypedValue
import android.view.View
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import androidx.core.graphics.PathParser
import com.knowyourphone.app.state.AssistantState
import kotlin.math.max

class DotView(context: Context) : View(context) {
    companion object {
        const val DOT_SIZE_DP = 68
        private const val PILL_HEIGHT_DP = 48f
        private const val SHADOW_PAD_DP = 6f
        private const val ICON_SIZE_DP = 20f
        private const val PILL_MIN_WIDTH_DP = 200f

        private const val COLOR_WHITE = Color.WHITE
        private const val COLOR_ICON = 0xFF1F1F1F.toInt()
        private const val COLOR_ICON_MUTED = 0xFF6B6B6B.toInt()
        private const val COLOR_BORDER = 0x22000000
        private const val COLOR_DISCONNECTED = 0xFF2B2B2B.toInt()
        private const val COLOR_RECONNECTING = 0xFF5A5A5A.toInt()
    }

    enum class ConnectionState { CONNECTED, DISCONNECTED, RECONNECTING }
    enum class PillAction { X_BUTTON, CONFIRM, NONE }

    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(50, 0, 0, 0)
        maskFilter = BlurMaskFilter(dp(SHADOW_PAD_DP), BlurMaskFilter.Blur.NORMAL)
    }

    private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = COLOR_WHITE
    }

    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        color = COLOR_ICON
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = COLOR_ICON
        textSize = dp(14f)
    }

    private val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        color = COLOR_BORDER
    }

    private val statusDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private var currentState = AssistantState.IDLE
    private var connectionState = ConnectionState.CONNECTED

    private var loaderRotation = 0f
    private val loaderAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = 1000
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            loaderRotation = it.animatedValue as Float
            invalidate()
        }
    }

    private var eqAlpha = 255
    private val eqAnimator = ValueAnimator.ofInt(120, 255).apply {
        duration = 600
        repeatMode = ValueAnimator.REVERSE
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            eqAlpha = it.animatedValue as Int
            invalidate()
        }
    }

    // Width animation for smooth pill size transitions
    private var animatedWidth = 0f
    private var widthAnimator: ValueAnimator? = null

    private val tempPath = Path()
    private val tempMatrix = Matrix()

    private val xButtonRect = RectF()
    private val confirmRect = RectF()

    // Lucide icons (24x24 viewBox)
    private val micPaths: List<Path> = listOf(
        path("M12 19v3"),
        path("M19 10v2a7 7 0 0 1-14 0v-2"),
        Path().apply {
            addRoundRect(RectF(9f, 2f, 15f, 15f), 3f, 3f, Path.Direction.CW)
        }
    )

    private val loaderPaths: List<Path> = listOf(
        path("M12 2v4"),
        path("m16.2 7.8 2.9-2.9"),
        path("M18 12h4"),
        path("m16.2 16.2 2.9 2.9"),
        path("M12 18v4"),
        path("m4.9 19.1 2.9-2.9"),
        path("M2 12h4"),
        path("m4.9 4.9 2.9 2.9")
    )

    private val audioPaths: List<Path> = listOf(
        path("M2 10v3"),
        path("M6 6v11"),
        path("M10 3v18"),
        path("M14 8v7"),
        path("M18 5v13"),
        path("M22 10v3")
    )

    private val sparklesPaths: List<Path> = listOf(
        path("M11.017 2.814a1 1 0 0 1 1.966 0l1.051 5.558a2 2 0 0 0 1.594 1.594l5.558 1.051a1 1 0 0 1 0 1.966l-5.558 1.051a2 2 0 0 0-1.594 1.594l-1.051 5.558a1 1 0 0 1-1.966 0l-1.051-5.558a2 2 0 0 0-1.594-1.594l-5.558-1.051a1 1 0 0 1 0-1.966l5.558-1.051a2 2 0 0 0 1.594-1.594z"),
        path("M20 2v4"),
        path("M22 4h-4"),
        path("M4 18a2 2 0 1 0 0 4a2 2 0 0 0 0-4")
    )

    private val checkPaths: List<Path> = listOf(
        path("M20 6 9 17l-5-5")
    )

    private val xPaths: List<Path> = listOf(
        path("M18 6 6 18"),
        path("m6 6 12 12")
    )

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    fun setState(state: AssistantState) {
        currentState = state
        iconPaint.color = if (state == AssistantState.IDLE) COLOR_ICON_MUTED else COLOR_ICON

        loaderAnimator.cancel()
        eqAnimator.cancel()
        loaderRotation = 0f
        eqAlpha = 255

        when (state) {
            AssistantState.LISTENING -> eqAnimator.start()
            AssistantState.THINKING -> loaderAnimator.start()
            AssistantState.SPEAKING -> eqAnimator.start()
            else -> { /* no animation */ }
        }

        animateToTargetWidth()
        invalidate()
    }

    fun setConnectionState(state: ConnectionState) {
        connectionState = state
        invalidate()
    }

    private fun computeTargetWidthPx(): Float {
        val pillHeight = dp(PILL_HEIGHT_DP)
        val shadowPad = dp(SHADOW_PAD_DP)

        val text = statusText()
        val textWidth = if (text.isNotBlank()) textPaint.measureText(text) else 0f

        // Left: X icon zone (pillHeight) | center: text or eq | right: icon zone (pillHeight)
        val contentWidth = pillHeight + dp(8f) + textWidth + dp(8f) + pillHeight
        val minWidth = dp(PILL_MIN_WIDTH_DP)
        val width = max(contentWidth, minWidth)
        return width + shadowPad * 2f
    }

    fun getDesiredWidthPx(): Int {
        return if (animatedWidth > 0f) animatedWidth.toInt() else computeTargetWidthPx().toInt()
    }

    fun getDesiredHeightPx(): Int {
        return (dp(PILL_HEIGHT_DP) + dp(SHADOW_PAD_DP * 2)).toInt()
    }

    private fun animateToTargetWidth() {
        val target = computeTargetWidthPx()
        val current = if (animatedWidth > 0f) animatedWidth else target

        if (current == target) {
            animatedWidth = target
            requestLayout()
            return
        }

        widthAnimator?.cancel()
        widthAnimator = ValueAnimator.ofFloat(current, target).apply {
            duration = 200
            interpolator = OvershootInterpolator(0.8f)
            addUpdateListener {
                animatedWidth = it.animatedValue as Float
                requestLayout()
                invalidate()
            }
            start()
        }
    }

    fun hitTestAction(x: Float, y: Float): PillAction {
        return when {
            xButtonRect.contains(x, y) -> PillAction.X_BUTTON
            currentState == AssistantState.NEED_SCREENSHOT && confirmRect.contains(x, y) -> PillAction.CONFIRM
            else -> PillAction.NONE
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(getDesiredWidthPx(), getDesiredHeightPx())
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val shadowPad = dp(SHADOW_PAD_DP)
        val pillHeight = dp(PILL_HEIGHT_DP)
        val pillRadius = pillHeight / 2f
        val pillWidth = (getDesiredWidthPx() - shadowPad * 2f)

        val left = shadowPad
        val top = (height - pillHeight) / 2f
        val rect = RectF(left, top, left + pillWidth, top + pillHeight)

        // Shadow + pill (always pill, never circle)
        canvas.drawRoundRect(rect, pillRadius, pillRadius, shadowPaint)
        canvas.drawRoundRect(rect, pillRadius, pillRadius, pillPaint)

        val centerY = rect.centerY()

        // --- Left zone: X button ---
        val xCenterX = rect.left + pillRadius
        xButtonRect.set(
            rect.left,
            rect.top,
            rect.left + pillHeight,
            rect.bottom
        )
        drawLucide(canvas, xPaths, xCenterX, centerY, 16f, 0f, if (currentState == AssistantState.IDLE) 100 else 180)

        // --- Right zone: state-specific icon ---
        val rightCenterX = rect.right - pillRadius
        confirmRect.setEmpty()

        when (currentState) {
            AssistantState.IDLE -> {
                drawLucide(canvas, micPaths, rightCenterX, centerY, ICON_SIZE_DP, 0f, 140)
            }
            AssistantState.LISTENING -> {
                drawLucide(canvas, micPaths, rightCenterX, centerY, ICON_SIZE_DP, 0f, 255)
            }
            AssistantState.THINKING -> {
                drawLucide(canvas, loaderPaths, rightCenterX, centerY, ICON_SIZE_DP, loaderRotation, 255)
            }
            AssistantState.NEED_SCREENSHOT -> {
                confirmRect.set(
                    rect.right - pillHeight,
                    rect.top,
                    rect.right,
                    rect.bottom
                )
                drawLucide(canvas, checkPaths, rightCenterX, centerY, ICON_SIZE_DP, 0f, 255)
            }
            AssistantState.SPEAKING -> {
                drawLucide(canvas, sparklesPaths, rightCenterX, centerY, ICON_SIZE_DP, 0f, 255)
            }
        }

        // --- Center zone: text or dynamic EQ ---
        val centerZoneLeft = rect.left + pillHeight + dp(4f)
        val centerZoneRight = rect.right - pillHeight - dp(4f)

        when (currentState) {
            AssistantState.LISTENING, AssistantState.SPEAKING -> {
                // Dynamic equalizer animation in center
                val eqCenterX = (centerZoneLeft + centerZoneRight) / 2f
                drawLucide(canvas, audioPaths, eqCenterX, centerY, 18f, 0f, eqAlpha)
            }
            else -> {
                // Text in center
                val text = statusText()
                if (text.isNotBlank()) {
                    val maxTextWidth = centerZoneRight - centerZoneLeft
                    val displayText = ellipsize(text, maxTextWidth)
                    val textY = centerY - (textPaint.ascent() + textPaint.descent()) / 2f
                    // Center the text
                    val textWidth = textPaint.measureText(displayText)
                    val textX = centerZoneLeft + (maxTextWidth - textWidth) / 2f
                    canvas.drawText(displayText, textX, textY, textPaint)
                }
            }
        }

        // Connection dot (minimal)
        if (connectionState != ConnectionState.CONNECTED) {
            val color = if (connectionState == ConnectionState.RECONNECTING) COLOR_RECONNECTING else COLOR_DISCONNECTED
            statusDotPaint.color = color
            canvas.drawCircle(rect.right - dp(10f), rect.bottom - dp(6f), dp(4f), statusDotPaint)
        }
    }

    private fun drawLucide(
        canvas: Canvas,
        paths: List<Path>,
        cx: Float,
        cy: Float,
        sizeDp: Float,
        rotation: Float,
        alpha: Int
    ) {
        val sizePx = dp(sizeDp)
        val scale = sizePx / 24f
        val left = cx - sizePx / 2f
        val top = cy - sizePx / 2f

        iconPaint.alpha = alpha
        canvas.save()
        if (rotation != 0f) {
            canvas.rotate(rotation, cx, cy)
        }
        tempMatrix.reset()
        tempMatrix.setScale(scale, scale)
        tempMatrix.postTranslate(left, top)
        for (p in paths) {
            tempPath.set(p)
            tempPath.transform(tempMatrix)
            canvas.drawPath(tempPath, iconPaint)
        }
        canvas.restore()
        iconPaint.alpha = 255
    }

    private fun statusText(): String {
        return when (currentState) {
            AssistantState.IDLE -> "Hold to talk"
            AssistantState.LISTENING -> ""
            AssistantState.THINKING -> "Thinking..."
            AssistantState.NEED_SCREENSHOT -> "Share screen"
            AssistantState.SPEAKING -> ""
        }
    }

    private fun ellipsize(text: String, maxWidth: Float): String {
        if (maxWidth <= 0f) return ""
        if (textPaint.measureText(text) <= maxWidth) return text
        var trimmed = text
        while (trimmed.isNotEmpty() && textPaint.measureText("$trimmed...") > maxWidth) {
            trimmed = trimmed.dropLast(1)
        }
        return if (trimmed.isEmpty()) "" else "$trimmed..."
    }

    private fun dp(value: Float): Float {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics
        )
    }

    private fun path(data: String): Path {
        return requireNotNull(PathParser.createPathFromPathData(data)) {
            "Invalid path data"
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        loaderAnimator.cancel()
        eqAnimator.cancel()
        widthAnimator?.cancel()
    }
}
