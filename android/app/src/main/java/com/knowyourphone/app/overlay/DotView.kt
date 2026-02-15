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
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin

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

    private val eqBarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = COLOR_ICON
    }

    private val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val circleStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
    }

    private val statusDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    // State-specific circle colors
    private val circleColorIdle = 0xFF9CA3AF.toInt()
    private val circleColorListening = 0xFF3B82F6.toInt()
    private val circleColorThinking = 0xFF8B5CF6.toInt()
    private val circleColorNeedScreenshot = 0xFF22C55E.toInt()
    private val circleColorSpeaking = 0xFF111827.toInt()
    private val circleColorX = 0xFFEF4444.toInt()

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

    private var audioLevel = 0f
    private var playbackLevel = 0f
    private var inputReference = 0.18f
    private var playbackReference = 0.18f
    private var inputDrive = 0f
    private var playbackDrive = 0f
    private var previousInputDrive = 0f
    private var previousPlaybackDrive = 0f
    private val eqBarLevels = FloatArray(21) { 0f }
    private val eqBarSignature = FloatArray(21) { i ->
        val wave = 0.6f * sin((i + 1) * 1.37f) + 0.4f * sin((i + 1) * 0.71f)
        wave * 0.18f
    }
    private val eqAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 32
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            val sample = when (currentState) {
                AssistantState.LISTENING -> inputDrive
                AssistantState.SPEAKING -> playbackDrive
                else -> 0f
            }
            pushHistorySample(sample)
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
        val previousState = currentState
        currentState = state
        // Icon paint color is now only used for equalizer bars in center zone
        iconPaint.color = if (state == AssistantState.IDLE) COLOR_ICON_MUTED else COLOR_ICON
        eqBarPaint.color = if (state == AssistantState.IDLE) COLOR_ICON_MUTED else COLOR_ICON

        loaderAnimator.cancel()
        eqAnimator.cancel()
        loaderRotation = 0f

        when (state) {
            AssistantState.LISTENING -> eqAnimator.start()
            AssistantState.THINKING -> loaderAnimator.start()
            AssistantState.SPEAKING -> eqAnimator.start()
            else -> {
                audioLevel = 0f
                playbackLevel = 0f
                inputReference = 0.18f
                playbackReference = 0.18f
                inputDrive = 0f
                playbackDrive = 0f
                previousInputDrive = 0f
                previousPlaybackDrive = 0f
                clearHistory()
                /* no animation */
            }
        }

        if ((state == AssistantState.LISTENING || state == AssistantState.SPEAKING) && state != previousState) {
            previousInputDrive = 0f
            previousPlaybackDrive = 0f
            clearHistory()
        }

        animateToTargetWidth()
        invalidate()
    }

    fun setAudioLevel(level: Float) {
        val clamped = level.coerceIn(0f, 1f)
        audioLevel = audioLevel * 0.55f + clamped * 0.45f
        inputReference = updateReference(inputReference, audioLevel)
        val targetDrive = normalizedDrive(audioLevel, inputReference)
        val transient = abs(targetDrive - previousInputDrive)
        previousInputDrive = targetDrive
        val motion = (targetDrive * 0.72f + transient * 1.10f).coerceIn(0f, 1f)
        inputDrive = inputDrive * 0.38f + motion * 0.62f
        if (currentState == AssistantState.LISTENING) {
            invalidate()
        }
    }

    fun setPlaybackLevel(level: Float) {
        val clamped = level.coerceIn(0f, 1f)
        playbackLevel = playbackLevel * 0.52f + clamped * 0.48f
        playbackReference = updateReference(playbackReference, playbackLevel)
        val targetDrive = normalizedDrive(playbackLevel, playbackReference)
        val transient = abs(targetDrive - previousPlaybackDrive)
        previousPlaybackDrive = targetDrive
        val motion = (targetDrive * 0.74f + transient * 1.00f).coerceIn(0f, 1f)
        playbackDrive = playbackDrive * 0.42f + motion * 0.58f
        if (currentState == AssistantState.SPEAKING) {
            invalidate()
        }
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

        // --- Left zone: state-specific icon in colored circle ---
        val leftCenterX = rect.left + pillRadius
        confirmRect.setEmpty()

        val stateCircleColor = when (currentState) {
            AssistantState.IDLE -> circleColorIdle
            AssistantState.LISTENING -> circleColorListening
            AssistantState.THINKING -> circleColorThinking
            AssistantState.NEED_SCREENSHOT -> circleColorNeedScreenshot
            AssistantState.SPEAKING -> circleColorSpeaking
        }

        when (currentState) {
            AssistantState.IDLE -> {
                drawIconInCircle(canvas, micPaths, leftCenterX, centerY, ICON_SIZE_DP, 0f, stateCircleColor, filled = true)
            }
            AssistantState.LISTENING -> {
                drawIconInCircle(canvas, micPaths, leftCenterX, centerY, ICON_SIZE_DP, 0f, stateCircleColor, filled = true)
            }
            AssistantState.THINKING -> {
                drawIconInCircle(canvas, loaderPaths, leftCenterX, centerY, ICON_SIZE_DP, loaderRotation, stateCircleColor, filled = true)
            }
            AssistantState.NEED_SCREENSHOT -> {
                confirmRect.set(
                    rect.left,
                    rect.top,
                    rect.left + pillHeight,
                    rect.bottom
                )
                drawIconInCircle(canvas, checkPaths, leftCenterX, centerY, ICON_SIZE_DP, 0f, stateCircleColor, filled = true)
            }
            AssistantState.SPEAKING -> {
                drawIconInCircle(canvas, sparklesPaths, leftCenterX, centerY, ICON_SIZE_DP, 0f, stateCircleColor, filled = true)
            }
        }

        // --- Right zone: X button in red outline circle ---
        val rightCenterX = rect.right - pillRadius
        xButtonRect.set(
            rect.right - pillHeight,
            rect.top,
            rect.right,
            rect.bottom
        )
        drawIconInCircle(canvas, xPaths, rightCenterX, centerY, 14f, 0f, circleColorX, filled = false, circleRadiusOverride = dp(14f))

        // --- Center zone: text or dynamic EQ ---
        val centerZoneLeft = rect.left + pillHeight + dp(4f)
        val centerZoneRight = rect.right - pillHeight - dp(4f)

        when (currentState) {
            AssistantState.LISTENING, AssistantState.SPEAKING -> {
                drawDynamicEqualizer(canvas, centerZoneLeft, centerZoneRight, centerY)
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

    private fun drawDynamicEqualizer(
        canvas: Canvas,
        left: Float,
        right: Float,
        centerY: Float
    ) {
        val zoneWidth = right - left
        if (zoneWidth <= 0f) return

        val gap = dp(2f)
        val maxBarWidth = dp(3f)
        var barCount = ((zoneWidth + gap) / (maxBarWidth + gap)).toInt().coerceIn(13, 21)
        if (barCount % 2 == 0) barCount -= 1

        val barWidth = ((zoneWidth - gap * (barCount - 1)) / barCount)
            .coerceAtLeast(dp(1.5f))
            .coerceAtMost(maxBarWidth)
        val usedWidth = barCount * barWidth + (barCount - 1) * gap
        var x = left + (zoneWidth - usedWidth) / 2f

        val minBarHeight = dp(2f)
        val maxBarHeight = dp(30f)
        val historyStart = (eqBarLevels.size - barCount).coerceAtLeast(0)

        for (i in 0 until barCount) {
            val historyIndex = historyStart + i
            val levelHistory = eqBarLevels[historyIndex].coerceIn(0f, 1f)
            val signature = (1f + eqBarSignature[historyIndex] * 0.03f).coerceIn(0.97f, 1.03f)
            val level = (levelHistory * signature).coerceIn(0f, 1f)

            val barHeight = minBarHeight + (maxBarHeight - minBarHeight) * level
            val top = centerY - barHeight / 2f
            val bottom = centerY + barHeight / 2f
            val radius = barWidth / 2f

            canvas.drawRoundRect(x, top, x + barWidth, bottom, radius, radius, eqBarPaint)
            x += barWidth + gap
        }
    }

    private fun updateReference(reference: Float, current: Float): Float {
        if (current > reference) {
            return reference * 0.70f + current * 0.30f
        }
        // Slow decay keeps normalization stable across short pauses.
        return reference * 0.985f + current * 0.015f
    }

    private fun normalizedDrive(current: Float, reference: Float): Float {
        val denom = (reference * 1.65f + 0.045f).coerceAtLeast(0.085f)
        val normalized = (current / denom).coerceIn(0f, 1.2f)
        val compressed = normalized.pow(0.95f).coerceIn(0f, 1f)
        val gate = ((current - 0.020f) / 0.045f).coerceIn(0f, 1f)
        return (compressed * gate).coerceIn(0f, 1f)
    }

    private fun pushHistorySample(sample: Float) {
        val clamped = sample.coerceIn(0f, 1f)
        for (i in 0 until eqBarLevels.lastIndex) {
            eqBarLevels[i] = eqBarLevels[i + 1]
        }
        eqBarLevels[eqBarLevels.lastIndex] = clamped
    }

    private fun clearHistory() {
        for (i in eqBarLevels.indices) {
            eqBarLevels[i] = 0f
        }
    }

    private fun drawIconInCircle(
        canvas: Canvas,
        paths: List<Path>,
        cx: Float,
        cy: Float,
        sizeDp: Float,
        rotation: Float,
        circleColor: Int,
        filled: Boolean,
        circleRadiusOverride: Float = 0f
    ) {
        val circleRadius = if (circleRadiusOverride > 0f) circleRadiusOverride else dp(PILL_HEIGHT_DP) / 2f - dp(4f)

        if (filled) {
            circlePaint.color = circleColor
            canvas.drawCircle(cx, cy, circleRadius, circlePaint)
        } else {
            circleStrokePaint.color = circleColor
            canvas.drawCircle(cx, cy, circleRadius, circleStrokePaint)
        }

        // Draw icon: white on filled circles, circle color on outline circles
        val sizePx = dp(sizeDp)
        val scale = sizePx / 24f
        val left = cx - sizePx / 2f
        val top = cy - sizePx / 2f

        val savedColor = iconPaint.color
        val savedAlpha = iconPaint.alpha
        iconPaint.color = if (filled) Color.WHITE else circleColor
        iconPaint.alpha = 255

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

        iconPaint.color = savedColor
        iconPaint.alpha = savedAlpha
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
