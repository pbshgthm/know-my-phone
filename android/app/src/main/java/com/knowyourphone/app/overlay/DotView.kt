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
import androidx.core.graphics.PathParser
import com.knowyourphone.app.state.AssistantState
import kotlin.math.pow

class DotView(context: Context) : View(context) {
    companion object {
        const val DOT_SIZE_DP = 68
        private const val PILL_HEIGHT_DP = 48f
        private const val PILL_WIDTH_DP = 212f
        private const val SHADOW_PAD_DP = 6f
        private const val ICON_SIZE_DP = 20f
        private const val ICON_STROKE_DP = 2f
        private const val INLINE_TEXT_ICON_SIZE_DP = 14f
        private const val INLINE_TEXT_ICON_STROKE_DP = 1.35f
        private const val INLINE_TEXT_ICON_GAP_DP = 4f

        private const val COLOR_WHITE = Color.WHITE
        private const val COLOR_ICON = 0xFF1F1F1F.toInt()
        private const val COLOR_ICON_MUTED = 0xFF6B6B6B.toInt()
        private const val COLOR_BORDER = 0x22000000
        private const val COLOR_DISCONNECTED = 0xFF2B2B2B.toInt()
        private const val COLOR_RECONNECTING = 0xFF5A5A5A.toInt()

        private const val IDLE_TEXT_PREFIX = "Tap"
        private const val IDLE_TEXT_SUFFIX = "to talk"
    }

    enum class ConnectionState { CONNECTED, DISCONNECTED, RECONNECTING }
    enum class PillAction { MIC_ICON, X_BUTTON, CONFIRM, NONE }

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
        strokeWidth = dp(ICON_STROKE_DP)
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
        style = Paint.Style.STROKE
        strokeWidth = dp(2.2f)
        strokeCap = Paint.Cap.ROUND
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
    private val circleColorIdle = 0xFF4B5563.toInt()
    private val circleColorListening = 0xFF3B82F6.toInt()
    private val circleColorThinking = 0xFF8B5CF6.toInt()
    private val circleColorNeedScreenshot = 0xFF22C55E.toInt()
    private val circleColorSpeaking = 0xFF111827.toInt()
    private val circleColorX = 0xFFC7CDD4.toInt()

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
    private val waveformHistory = FloatArray(180) { 0f }

    // Cached fixed width for the pill.
    private var animatedWidth = 0f

    private val tempPath = Path()
    private val tempMatrix = Matrix()

    private val xButtonRect = RectF()
    private val confirmRect = RectF()
    private val micIconRect = RectF()

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
        loaderRotation = 0f

        when (state) {
            AssistantState.THINKING -> loaderAnimator.start()
            else -> {
                audioLevel = 0f
                playbackLevel = 0f
                /* no animation */
            }
        }

        if (state != previousState) {
            when (state) {
                AssistantState.LISTENING -> playbackLevel = 0f
                AssistantState.SPEAKING -> audioLevel = 0f
                else -> {
                    audioLevel = 0f
                    playbackLevel = 0f
                }
            }
            clearHistory()
        }

        animateToTargetWidth()
        invalidate()
    }

    fun setAudioLevel(level: Float) {
        if (currentState != AssistantState.LISTENING) return
        val clamped = level.coerceIn(0f, 1f)
        audioLevel = audioLevel * 0.20f + clamped * 0.80f
        appendWaveSample(shapeWaveSample(audioLevel, gate = 0.018f))
        invalidate()
    }

    fun setPlaybackLevel(level: Float) {
        if (currentState != AssistantState.SPEAKING) return
        val clamped = level.coerceIn(0f, 1f)
        playbackLevel = playbackLevel * 0.28f + clamped * 0.72f
        appendWaveSample(shapeWaveSample(playbackLevel, gate = 0.012f))
        invalidate()
    }

    fun setConnectionState(state: ConnectionState) {
        connectionState = state
        invalidate()
    }

    private fun computeTargetWidthPx(): Float {
        val shadowPad = dp(SHADOW_PAD_DP)
        return dp(PILL_WIDTH_DP) + shadowPad * 2f
    }

    fun getDesiredWidthPx(): Int {
        return if (animatedWidth > 0f) animatedWidth.toInt() else computeTargetWidthPx().toInt()
    }

    fun getDesiredHeightPx(): Int {
        return (dp(PILL_HEIGHT_DP) + dp(SHADOW_PAD_DP * 2)).toInt()
    }

    private fun animateToTargetWidth() {
        val target = computeTargetWidthPx()
        if (animatedWidth != target) {
            animatedWidth = target
            requestLayout()
            invalidate()
        }
    }

    fun hitTestAction(x: Float, y: Float): PillAction {
        return when {
            xButtonRect.contains(x, y) -> PillAction.X_BUTTON
            currentState == AssistantState.NEED_SCREENSHOT && confirmRect.contains(x, y) -> PillAction.CONFIRM
            (currentState == AssistantState.IDLE || currentState == AssistantState.LISTENING) &&
                    micIconRect.contains(x, y) -> PillAction.MIC_ICON
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
        micIconRect.setEmpty()

        val stateCircleColor = when (currentState) {
            AssistantState.IDLE -> circleColorIdle
            AssistantState.LISTENING -> circleColorListening
            AssistantState.THINKING -> circleColorThinking
            AssistantState.NEED_SCREENSHOT -> circleColorNeedScreenshot
            AssistantState.SPEAKING -> circleColorSpeaking
        }

        when (currentState) {
            AssistantState.IDLE -> {
                micIconRect.set(rect.left, rect.top, rect.left + pillHeight, rect.bottom)
                drawIconInCircle(canvas, micPaths, leftCenterX, centerY, ICON_SIZE_DP, 0f, stateCircleColor, filled = true)
            }
            AssistantState.LISTENING -> {
                micIconRect.set(rect.left, rect.top, rect.left + pillHeight, rect.bottom)
                drawIconInCircle(
                    canvas = canvas,
                    paths = listOf(
                        path("M22 2 11 13"),
                        path("M22 2 15 22 11 13 2 9 22 2z")
                    ),
                    cx = leftCenterX,
                    cy = centerY,
                    sizeDp = ICON_SIZE_DP,
                    rotation = 0f,
                    circleColor = stateCircleColor,
                    filled = true
                )
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

        // --- Right zone: X button in outline circle ---
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
                if (currentState == AssistantState.IDLE) {
                    drawIdlePrompt(canvas, centerZoneLeft, centerZoneRight, centerY)
                } else {
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

        val spacing = dp(3.8f)
        val minHalfWave = dp(1.4f)
        val maxHalfWave = dp(13f)
        val barCount = (zoneWidth / spacing).toInt().coerceIn(12, waveformHistory.size)
        val usedWidth = spacing * (barCount - 1)
        var x = left + (zoneWidth - usedWidth) / 2f
        val historyStart = (waveformHistory.size - barCount).coerceAtLeast(0)

        eqBarPaint.alpha = 255
        for (i in 0 until barCount) {
            val historyIndex = historyStart + i
            val sample = waveformHistory[historyIndex].coerceIn(0f, 1f)
            val halfWave = minHalfWave + (maxHalfWave - minHalfWave) * sample
            val olderFade = if (barCount <= 1) 1f else i.toFloat() / (barCount - 1).toFloat()
            eqBarPaint.alpha = (96f + 159f * olderFade).toInt().coerceIn(0, 255)
            canvas.drawLine(x, centerY - halfWave, x, centerY + halfWave, eqBarPaint)
            x += spacing
        }

        eqBarPaint.alpha = 255
    }

    private fun appendWaveSample(sample: Float) {
        val clamped = sample.coerceIn(0f, 1f)
        for (i in 0 until waveformHistory.lastIndex) {
            waveformHistory[i] = waveformHistory[i + 1]
        }
        waveformHistory[waveformHistory.lastIndex] = clamped
    }

    private fun shapeWaveSample(level: Float, gate: Float): Float {
        val gated = ((level - gate) / (1f - gate)).coerceIn(0f, 1f)
        return gated.pow(0.58f).coerceIn(0f, 1f)
    }

    private fun clearHistory() {
        for (i in waveformHistory.indices) {
            waveformHistory[i] = 0f
        }
    }

    private fun drawIdlePrompt(canvas: Canvas, left: Float, right: Float, centerY: Float) {
        val maxTextWidth = right - left
        if (maxTextWidth <= 0f) return

        val prefixWidth = textPaint.measureText(IDLE_TEXT_PREFIX)
        val suffixWidth = textPaint.measureText(IDLE_TEXT_SUFFIX)
        val iconSizePx = dp(INLINE_TEXT_ICON_SIZE_DP)
        val gapPx = dp(INLINE_TEXT_ICON_GAP_DP)
        val totalWidth = prefixWidth + gapPx + iconSizePx + gapPx + suffixWidth

        if (totalWidth > maxTextWidth) {
            val fallback = ellipsize("Tap to talk", maxTextWidth)
            if (fallback.isBlank()) return
            val textY = centerY - (textPaint.ascent() + textPaint.descent()) / 2f
            val textX = left + (maxTextWidth - textPaint.measureText(fallback)) / 2f
            canvas.drawText(fallback, textX, textY, textPaint)
            return
        }

        val startX = left + (maxTextWidth - totalWidth) / 2f
        val textY = centerY - (textPaint.ascent() + textPaint.descent()) / 2f
        canvas.drawText(IDLE_TEXT_PREFIX, startX, textY, textPaint)

        val iconCenterX = startX + prefixWidth + gapPx + iconSizePx / 2f
        drawIcon(
            canvas = canvas,
            paths = micPaths,
            cx = iconCenterX,
            cy = centerY,
            sizeDp = INLINE_TEXT_ICON_SIZE_DP,
            rotation = 0f,
            color = textPaint.color,
            strokeWidthDp = INLINE_TEXT_ICON_STROKE_DP
        )

        val suffixX = iconCenterX + iconSizePx / 2f + gapPx
        canvas.drawText(IDLE_TEXT_SUFFIX, suffixX, textY, textPaint)
    }

    private fun drawIcon(
        canvas: Canvas,
        paths: List<Path>,
        cx: Float,
        cy: Float,
        sizeDp: Float,
        rotation: Float,
        color: Int,
        strokeWidthDp: Float = ICON_STROKE_DP
    ) {
        val sizePx = dp(sizeDp)
        val scale = sizePx / 24f
        val left = cx - sizePx / 2f
        val top = cy - sizePx / 2f

        val savedColor = iconPaint.color
        val savedAlpha = iconPaint.alpha
        val savedStrokeWidth = iconPaint.strokeWidth
        iconPaint.color = color
        iconPaint.alpha = 255
        iconPaint.strokeWidth = dp(strokeWidthDp)

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
        iconPaint.strokeWidth = savedStrokeWidth
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
        drawIcon(
            canvas = canvas,
            paths = paths,
            cx = cx,
            cy = cy,
            sizeDp = sizeDp,
            rotation = rotation,
            color = if (filled) Color.WHITE else circleColor
        )
    }

    private fun statusText(): String {
        return when (currentState) {
            AssistantState.IDLE -> "Tap to talk"
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
    }
}
