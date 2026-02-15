package com.knowyourphone.app.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.TypedValue
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.graphics.PathParser
import com.knowyourphone.app.i18n.LanguageManager
import com.knowyourphone.app.i18n.PillStrings
import com.knowyourphone.app.state.AssistantState
import kotlin.math.pow
import kotlin.math.sin

class DotView(context: Context) : View(context) {
    companion object {
        const val DOT_SIZE_DP = 68
        private const val PILL_HEIGHT_DP = 48f
        private const val PILL_WIDTH_DP = 188f
        private const val SHADOW_PAD_DP = 8f
        private const val ICON_SIZE_DP = 20f
        private const val ICON_STROKE_DP = 2f
        private const val INLINE_TEXT_ICON_SIZE_DP = 14f
        private const val INLINE_TEXT_ICON_STROKE_DP = 1.35f
        private const val INLINE_TEXT_ICON_GAP_DP = 4f

        // Dark pill palette
        private const val COLOR_PILL_BG_START = 0xFF08080F.toInt()
        private const val COLOR_PILL_BG_END = 0xFF1E1E38.toInt()
        private const val COLOR_OUTLINE = 0xFF8888A0.toInt()
        private const val COLOR_TEXT = 0xFFD4D4DC.toInt()
        private const val COLOR_TEXT_MUTED = 0xFF8888A0.toInt()
        private const val COLOR_DISCONNECTED = 0xFFEF4444.toInt()
        private const val COLOR_RECONNECTING = 0xFFFBBF24.toInt()
    }

    enum class ConnectionState { CONNECTED, DISCONNECTED, RECONNECTING }
    enum class PillAction { MIC_ICON, X_BUTTON, CONFIRM, NONE }

    private var pillStrings: PillStrings = PillStrings("Tap", "to talk", "Thinking", "Share screen")

    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(100, 0, 0, 0)
        maskFilter = BlurMaskFilter(dp(SHADOW_PAD_DP), BlurMaskFilter.Blur.NORMAL)
    }

    private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
        color = COLOR_OUTLINE
    }

    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(ICON_STROKE_DP)
        color = COLOR_TEXT
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = COLOR_TEXT
        textSize = dp(14f)
    }

    private val eqBarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2.2f)
        strokeCap = Paint.Cap.ROUND
    }

    private val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val circleStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
    }

    private val statusDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    // State-specific circle colors (center, edge for gradient)
    private val circleColorIdleCenter = 0xFF38BDF8.toInt() // bright sky blue
    private val circleColorIdleEdge = 0xFF0284C7.toInt()
    private val circleColorListeningCenter = 0xFF4B9CFF.toInt()
    private val circleColorListeningEdge = 0xFF2563EB.toInt()
    private val circleColorThinkingCenter = 0xFFA78BFA.toInt()
    private val circleColorThinkingEdge = 0xFF7C3AED.toInt()
    private val circleColorNeedScreenshotCenter = 0xFF34D399.toInt()
    private val circleColorNeedScreenshotEdge = 0xFF16A34A.toInt()
    private val circleColorSpeakingCenter = 0xFF6366F1.toInt()
    private val circleColorSpeakingEdge = 0xFF4338CA.toInt()
    private val circleColorXStroke = 0xFF7A7A90.toInt()

    // Waveform colors per state
    private val eqColorListening = 0xFF60A5FA.toInt()
    private val eqColorSpeaking = 0xFF818CF8.toInt()

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

    // Animated dots phase for "Thinking..." (0f..3f, each integer = one more dot visible)
    private var thinkingDotPhase = 0f
    private val thinkingDotsAnimator = ValueAnimator.ofFloat(0f, 4f).apply {
        duration = 2000
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            thinkingDotPhase = it.animatedValue as Float
            invalidate()
        }
    }

    private var audioLevel = 0f
    private var playbackLevel = 0f
    private val waveformHistory = FloatArray(180) { 0f }

    // Cached fixed width for the pill.
    private var animatedWidth = 0f
    private var lastPillRect: RectF? = null

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

        loaderAnimator.cancel()
        thinkingDotsAnimator.cancel()
        loaderRotation = 0f
        thinkingDotPhase = 0f

        when (state) {
            AssistantState.THINKING -> {
                loaderAnimator.start()
                thinkingDotsAnimator.start()
            }
            else -> {
                audioLevel = 0f
                playbackLevel = 0f
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
        // Amplify input for better visual range
        val amplified = (clamped * 2.5f).coerceIn(0f, 1f)
        audioLevel = audioLevel * 0.15f + amplified * 0.85f
        appendWaveSample(shapeWaveSample(audioLevel, gate = 0.008f, exp = 0.42f))
        invalidate()
    }

    fun setPlaybackLevel(level: Float) {
        if (currentState != AssistantState.SPEAKING) return
        val clamped = level.coerceIn(0f, 1f)
        // Compress output for less peaking
        val compressed = clamped.pow(1.4f)
        playbackLevel = playbackLevel * 0.30f + compressed * 0.70f
        appendWaveSample(shapeWaveSample(playbackLevel, gate = 0.025f, exp = 0.72f))
        invalidate()
    }

    fun setLanguageStrings(strings: PillStrings) {
        pillStrings = strings
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
        lastPillRect = rect

        // Shadow
        canvas.drawRoundRect(rect, pillRadius, pillRadius, shadowPaint)

        // Dark gradient background
        pillPaint.shader = LinearGradient(
            rect.left, rect.top, rect.right, rect.bottom,
            COLOR_PILL_BG_START, COLOR_PILL_BG_END,
            Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(rect, pillRadius, pillRadius, pillPaint)
        pillPaint.shader = null

        // Light gray outline
        canvas.drawRoundRect(rect, pillRadius, pillRadius, outlinePaint)

        val centerY = rect.centerY()

        // --- Left zone: state-specific icon in colored circle ---
        val leftCenterX = rect.left + pillRadius
        confirmRect.setEmpty()
        micIconRect.setEmpty()

        val circleRadius = dp(PILL_HEIGHT_DP) / 2f - dp(4f)
        val (centerColor, edgeColor) = when (currentState) {
            AssistantState.IDLE -> circleColorIdleCenter to circleColorIdleEdge
            AssistantState.LISTENING -> circleColorListeningCenter to circleColorListeningEdge
            AssistantState.THINKING -> circleColorThinkingCenter to circleColorThinkingEdge
            AssistantState.NEED_SCREENSHOT -> circleColorNeedScreenshotCenter to circleColorNeedScreenshotEdge
            AssistantState.SPEAKING -> circleColorSpeakingCenter to circleColorSpeakingEdge
        }

        when (currentState) {
            AssistantState.IDLE -> {
                micIconRect.set(rect.left, rect.top, rect.left + pillHeight, rect.bottom)
                drawGradientCircleWithIcon(canvas, micPaths, leftCenterX, centerY, circleRadius, ICON_SIZE_DP, 0f, centerColor, edgeColor)
            }
            AssistantState.LISTENING -> {
                micIconRect.set(rect.left, rect.top, rect.left + pillHeight, rect.bottom)
                drawGradientCircleWithIcon(
                    canvas,
                    listOf(path("M22 2 11 13"), path("M22 2 15 22 11 13 2 9 22 2z")),
                    leftCenterX, centerY, circleRadius, ICON_SIZE_DP, 0f, centerColor, edgeColor
                )
            }
            AssistantState.THINKING -> {
                drawGradientCircleWithIcon(canvas, loaderPaths, leftCenterX, centerY, circleRadius, ICON_SIZE_DP, loaderRotation, centerColor, edgeColor)
            }
            AssistantState.NEED_SCREENSHOT -> {
                confirmRect.set(rect.left, rect.top, rect.left + pillHeight, rect.bottom)
                drawGradientCircleWithIcon(canvas, checkPaths, leftCenterX, centerY, circleRadius, ICON_SIZE_DP, 0f, centerColor, edgeColor)
            }
            AssistantState.SPEAKING -> {
                drawGradientCircleWithIcon(canvas, sparklesPaths, leftCenterX, centerY, circleRadius, ICON_SIZE_DP, 0f, centerColor, edgeColor)
            }
        }

        // --- Right zone: X button in outline circle ---
        val rightCenterX = rect.right - pillRadius
        xButtonRect.set(rect.right - pillHeight, rect.top, rect.right, rect.bottom)
        val xRadius = dp(14f)
        circleStrokePaint.color = circleColorXStroke
        canvas.drawCircle(rightCenterX, centerY, xRadius, circleStrokePaint)
        drawIcon(canvas, xPaths, rightCenterX, centerY, 14f, 0f, circleColorXStroke)

        // --- Center zone: text or dynamic EQ ---
        val centerZoneLeft = rect.left + pillHeight + dp(4f)
        val centerZoneRight = rect.right - pillHeight - dp(4f)

        when (currentState) {
            AssistantState.LISTENING, AssistantState.SPEAKING -> {
                drawDynamicEqualizer(canvas, centerZoneLeft, centerZoneRight, centerY)
            }
            else -> {
                when (currentState) {
                    AssistantState.IDLE -> drawIdlePrompt(canvas, centerZoneLeft, centerZoneRight, centerY)
                    AssistantState.THINKING -> drawThinkingText(canvas, centerZoneLeft, centerZoneRight, centerY)
                    else -> {
                        val text = statusText()
                        if (text.isNotBlank()) {
                            val maxTextWidth = centerZoneRight - centerZoneLeft
                            val displayText = ellipsize(text, maxTextWidth)
                            val textY = centerY - (textPaint.ascent() + textPaint.descent()) / 2f
                            val textWidth = textPaint.measureText(displayText)
                            val textX = centerZoneLeft + (maxTextWidth - textWidth) / 2f
                            canvas.drawText(displayText, textX, textY, textPaint)
                        }
                    }
                }
            }
        }

        // Connection status dot
        if (connectionState != ConnectionState.CONNECTED) {
            val color = if (connectionState == ConnectionState.RECONNECTING) COLOR_RECONNECTING else COLOR_DISCONNECTED
            statusDotPaint.color = color
            canvas.drawCircle(rect.right - dp(10f), rect.bottom - dp(6f), dp(3.5f), statusDotPaint)
        }
    }

    private fun drawGradientCircleWithIcon(
        canvas: Canvas,
        paths: List<Path>,
        cx: Float,
        cy: Float,
        radius: Float,
        iconSizeDp: Float,
        rotation: Float,
        centerColor: Int,
        edgeColor: Int
    ) {
        circlePaint.shader = RadialGradient(
            cx, cy, radius,
            centerColor, edgeColor,
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, radius, circlePaint)
        circlePaint.shader = null

        drawIcon(canvas, paths, cx, cy, iconSizeDp, rotation, Color.WHITE)
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
        val minHalfWave = dp(1.2f)
        val maxHalfWave = dp(12f)
        val barCount = (zoneWidth / spacing).toInt().coerceIn(12, waveformHistory.size)
        val usedWidth = spacing * (barCount - 1)
        var x = left + (zoneWidth - usedWidth) / 2f
        val historyStart = (waveformHistory.size - barCount).coerceAtLeast(0)

        val baseColor = if (currentState == AssistantState.LISTENING) eqColorListening else eqColorSpeaking

        for (i in 0 until barCount) {
            val historyIndex = historyStart + i
            val sample = waveformHistory[historyIndex].coerceIn(0f, 1f)

            // Edge windowing: bars at edges stay small, center grows naturally
            val t = if (barCount <= 1) 0.5f else i.toFloat() / (barCount - 1).toFloat()
            val edgeWindow = sin(t * Math.PI).toFloat().pow(0.55f)

            val tapered = sample * edgeWindow
            val halfWave = minHalfWave + (maxHalfWave - minHalfWave) * tapered

            // Older bars fade, newest bars brightest
            val olderFade = if (barCount <= 1) 1f else i.toFloat() / (barCount - 1).toFloat()
            val alpha = (80f + 175f * olderFade).toInt().coerceIn(0, 255)
            eqBarPaint.color = baseColor
            eqBarPaint.alpha = alpha
            canvas.drawLine(x, centerY - halfWave, x, centerY + halfWave, eqBarPaint)
            x += spacing
        }
    }

    private fun appendWaveSample(sample: Float) {
        val clamped = sample.coerceIn(0f, 1f)
        for (i in 0 until waveformHistory.lastIndex) {
            waveformHistory[i] = waveformHistory[i + 1]
        }
        waveformHistory[waveformHistory.lastIndex] = clamped
    }

    private fun shapeWaveSample(level: Float, gate: Float, exp: Float): Float {
        val gated = ((level - gate) / (1f - gate)).coerceIn(0f, 1f)
        return gated.pow(exp).coerceIn(0f, 1f)
    }

    private fun clearHistory() {
        for (i in waveformHistory.indices) {
            waveformHistory[i] = 0f
        }
    }

    private fun drawIdlePrompt(canvas: Canvas, left: Float, right: Float, centerY: Float) {
        val maxTextWidth = right - left
        if (maxTextWidth <= 0f) return

        val mutedPaint = textPaint
        val savedColor = mutedPaint.color
        mutedPaint.color = COLOR_TEXT_MUTED

        val prefix = pillStrings.tapPrefix
        val suffix = pillStrings.tapSuffix
        val prefixWidth = mutedPaint.measureText(prefix)
        val suffixWidth = mutedPaint.measureText(suffix)
        val iconSizePx = dp(INLINE_TEXT_ICON_SIZE_DP)
        val gapPx = dp(INLINE_TEXT_ICON_GAP_DP)
        val totalWidth = prefixWidth + gapPx + iconSizePx + gapPx + suffixWidth

        if (totalWidth > maxTextWidth) {
            val fallback = ellipsize("$prefix $suffix", maxTextWidth)
            if (fallback.isBlank()) {
                mutedPaint.color = savedColor
                return
            }
            val textY = centerY - (mutedPaint.ascent() + mutedPaint.descent()) / 2f
            val textX = left + (maxTextWidth - mutedPaint.measureText(fallback)) / 2f
            canvas.drawText(fallback, textX, textY, mutedPaint)
            mutedPaint.color = savedColor
            return
        }

        val startX = left + (maxTextWidth - totalWidth) / 2f
        val textY = centerY - (mutedPaint.ascent() + mutedPaint.descent()) / 2f
        canvas.drawText(prefix, startX, textY, mutedPaint)

        val iconCenterX = startX + prefixWidth + gapPx + iconSizePx / 2f
        drawIcon(
            canvas = canvas,
            paths = micPaths,
            cx = iconCenterX,
            cy = centerY,
            sizeDp = INLINE_TEXT_ICON_SIZE_DP,
            rotation = 0f,
            color = COLOR_TEXT_MUTED,
            strokeWidthDp = INLINE_TEXT_ICON_STROKE_DP
        )

        val suffixX = iconCenterX + iconSizePx / 2f + gapPx
        canvas.drawText(suffix, suffixX, textY, mutedPaint)

        mutedPaint.color = savedColor
    }

    private fun drawThinkingText(canvas: Canvas, left: Float, right: Float, centerY: Float) {
        val maxTextWidth = right - left
        if (maxTextWidth <= 0f) return

        val base = pillStrings.thinking
        val dots = "..."
        val fullText = base + dots
        val fullWidth = textPaint.measureText(fullText)
        if (fullWidth > maxTextWidth) return

        // Position so "Thinking..." is always centered
        val startX = left + (maxTextWidth - fullWidth) / 2f
        val textY = centerY - (textPaint.ascent() + textPaint.descent()) / 2f

        // Draw "Thinking" at full alpha
        canvas.drawText(base, startX, textY, textPaint)

        // Draw each dot with fade-in based on phase
        val baseWidth = textPaint.measureText(base)
        val dotWidth = textPaint.measureText(".")
        val savedAlpha = textPaint.alpha
        for (i in 0 until 3) {
            // phase 0..4: dot i fades in during phase i..i+1, stays visible until phase wraps
            val dotAlpha = ((thinkingDotPhase - i).coerceIn(0f, 1f) * 255).toInt()
            textPaint.alpha = dotAlpha
            canvas.drawText(".", startX + baseWidth + dotWidth * i, textY, textPaint)
        }
        textPaint.alpha = savedAlpha
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

    private fun statusText(): String {
        return when (currentState) {
            AssistantState.IDLE -> "${pillStrings.tapPrefix} ${pillStrings.tapSuffix}"
            AssistantState.LISTENING -> ""
            AssistantState.THINKING -> "" // handled by drawThinkingText
            AssistantState.NEED_SCREENSHOT -> pillStrings.shareScreen
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
        thinkingDotsAnimator.cancel()
    }
}
