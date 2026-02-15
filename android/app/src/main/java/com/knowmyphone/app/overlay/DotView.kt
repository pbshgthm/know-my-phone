package com.knowmyphone.app.overlay

import android.animation.ValueAnimator
import android.content.Context
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
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.LinearInterpolator
import androidx.core.graphics.PathParser
import com.knowmyphone.app.i18n.LanguageManager
import com.knowmyphone.app.i18n.PillStrings
import com.knowmyphone.app.state.AssistantState
import kotlin.math.pow
import kotlin.math.sin

class DotView(context: Context) : View(context) {
    companion object {
        const val DOT_SIZE_DP = 68
        private const val PILL_HEIGHT_DP = 48f
        private const val PILL_MIN_WIDTH_DP = 188f
        private const val PILL_MAX_WIDTH_FRACTION = 0.92f
        private const val SHADOW_PAD_DP = 2f
        private const val ICON_SIZE_DP = 20f
        private const val ICON_STROKE_DP = 2f
        private const val INLINE_TEXT_ICON_SIZE_DP = 14f
        private const val INLINE_TEXT_ICON_STROKE_DP = 1.35f
        private const val INLINE_TEXT_ICON_GAP_DP = 4f
        private const val WIDTH_ANIM_MIN_DURATION_MS = 200L
        private const val WIDTH_ANIM_MAX_DURATION_MS = 360L

        // Dark pill palette
        private const val COLOR_PILL_BG_START = 0xFF072838.toInt()
        private const val COLOR_PILL_BG_MID = 0xFF102A66.toInt()
        private const val COLOR_PILL_BG_END = 0xFF331555.toInt()
        private const val COLOR_OUTLINE_START = 0xFF22D3EE.toInt() // cyan
        private const val COLOR_OUTLINE_MID = 0xFF3B82F6.toInt() // blue
        private const val COLOR_OUTLINE_END = 0xFF8B5CF6.toInt() // purple
        private const val COLOR_TEXT = 0xFFF3F8FF.toInt()
        private const val COLOR_TEXT_MUTED = 0xFFB8C8E8.toInt()
    }

    enum class ConnectionState { CONNECTED, DISCONNECTED, RECONNECTING }
    enum class PillAction { MIC_ICON, MENU_BUTTON, X_BUTTON, CONFIRM, NONE }

    var onSizeChanged: (() -> Unit)? = null

    private var pillStrings: PillStrings = PillStrings(
        tapPrefix = "Tap",
        tapSuffix = "to ask",
        thinking = "Thinking",
        shareScreen = "Share screen",
        idlePrompt = "Tap {mic} to ask"
    )

    private var confirmLabelText: String = ""

    private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
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
        textSize = dp(13.5f)
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

    private val menuDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = COLOR_TEXT_MUTED
    }

    // State-specific circle colors (center, edge for gradient)
    private val circleColorIdleCenter = 0xFF38BDF8.toInt() // bright sky blue
    private val circleColorIdleEdge = 0xFF0284C7.toInt()
    private val circleColorListeningCenter = 0xFF4B9CFF.toInt()
    private val circleColorListeningEdge = 0xFF2563EB.toInt()
    private val circleColorThinkingCenter = 0xFFA78BFA.toInt()
    private val circleColorThinkingEdge = 0xFF7C3AED.toInt()
    private val circleColorConfirmingCenter = 0xFF34D399.toInt()
    private val circleColorConfirmingEdge = 0xFF16A34A.toInt()
    private val circleColorSpeakingCenter = 0xFF6366F1.toInt()
    private val circleColorSpeakingEdge = 0xFF4338CA.toInt()
    private val circleColorOfflineCenter = 0xFF9CA3AF.toInt()
    private val circleColorOfflineEdge = 0xFF6B7280.toInt()
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

    private var thinkingSheenPhase = 0f
    private val thinkingSheenAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1180
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            thinkingSheenPhase = it.animatedValue as Float
            invalidate()
        }
    }

    private var outlineShiftPhase = 0f
    private val outlineFlowAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 2300
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            outlineShiftPhase = it.animatedValue as Float
            invalidate()
        }
    }

    private var audioLevel = 0f
    private var playbackLevel = 0f
    private val waveformHistory = FloatArray(180) { 0f }

    // Animated width for the pill.
    private var animatedWidth = 0f
    private var lastAnimatedWidthInt = 0
    private var widthAnimator: ValueAnimator? = null
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

    fun setState(state: AssistantState) {
        val previousState = currentState
        val wasOutlineAnimated = isOutlineAnimatedState(previousState)
        val isOutlineAnimated = isOutlineAnimatedState(state)
        currentState = state

        if (state == AssistantState.THINKING) {
            if (!loaderAnimator.isRunning) loaderAnimator.start()
            if (!thinkingSheenAnimator.isRunning) thinkingSheenAnimator.start()
        } else {
            loaderAnimator.cancel()
            thinkingSheenAnimator.cancel()
            loaderRotation = 0f
            thinkingSheenPhase = 0f
            audioLevel = 0f
            playbackLevel = 0f
        }

        if (isOutlineAnimated && !outlineFlowAnimator.isRunning) {
            outlineFlowAnimator.start()
        }
        if (!isOutlineAnimated && wasOutlineAnimated) {
            outlineFlowAnimator.cancel()
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

    fun setLanguageStrings(languageCode: String, strings: PillStrings) {
        pillStrings = strings
        textPaint.textSize = dp(if (LanguageManager.isIndicLanguage(languageCode)) 12.8f else 13.5f)
        animateToTargetWidth()
        invalidate()
    }

    fun setConnectionState(state: ConnectionState) {
        connectionState = state
        invalidate()
    }

    fun setConfirmLabel(label: String) {
        confirmLabelText = label
        if (currentState == AssistantState.CONFIRMING) {
            animateToTargetWidth()
            invalidate()
        }
    }

    private fun computeTargetWidthPx(): Float {
        val shadowPad = dp(SHADOW_PAD_DP)
        val pillHeight = dp(PILL_HEIGHT_DP)
        val minTotalWidth = dp(PILL_MIN_WIDTH_DP) + shadowPad * 2f
        val maxTotalWidth = resources.displayMetrics.widthPixels * PILL_MAX_WIDTH_FRACTION

        val centerContentWidth = measureFixedCenterContentWidth() + dp(18f)
        val pillWidth = pillHeight * 2f + dp(8f) + centerContentWidth
        val totalWidth = pillWidth + shadowPad * 2f
        return totalWidth.coerceIn(minTotalWidth, maxTotalWidth)
    }

    fun getDesiredWidthPx(): Int {
        return if (animatedWidth > 0f) kotlin.math.ceil(animatedWidth).toInt() else kotlin.math.ceil(computeTargetWidthPx()).toInt()
    }

    fun getDesiredHeightPx(): Int {
        return (dp(PILL_HEIGHT_DP) + dp(SHADOW_PAD_DP * 2)).toInt()
    }

    private fun animateToTargetWidth() {
        val target = computeTargetWidthPx()
        if (animatedWidth <= 0f) {
            animatedWidth = target
            lastAnimatedWidthInt = kotlin.math.ceil(animatedWidth).toInt()
            onSizeChanged?.invoke()
            requestLayout()
            invalidate()
            return
        }

        if (kotlin.math.abs(animatedWidth - target) < 1f) return

        widthAnimator?.cancel()
        val deltaDp = kotlin.math.abs(target - animatedWidth) / resources.displayMetrics.density
        val duration = (WIDTH_ANIM_MIN_DURATION_MS + deltaDp * 1.9f)
            .toLong()
            .coerceIn(WIDTH_ANIM_MIN_DURATION_MS, WIDTH_ANIM_MAX_DURATION_MS)
        widthAnimator = ValueAnimator.ofFloat(animatedWidth, target).apply {
            this.duration = duration
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                animatedWidth = it.animatedValue as Float
                val widthInt = kotlin.math.ceil(animatedWidth).toInt()
                if (widthInt != lastAnimatedWidthInt) {
                    lastAnimatedWidthInt = widthInt
                    onSizeChanged?.invoke()
                    requestLayout()
                }
                invalidate()
            }
            start()
        }
    }

    private fun measureFixedCenterContentWidth(): Float {
        val idlePromptWidth = measureIdlePromptWidth(textPaint.textSize, includeIcon = true)
        val thinkingWidth = textPaint.measureText(pillStrings.thinking.trim())
        val confirmText = confirmLabelText.ifEmpty { pillStrings.shareScreen }
        val screenshotWidth = textPaint.measureText(confirmText.trim())
        val waveformWidth = dp(92f)
        return maxOf(idlePromptWidth, thinkingWidth, screenshotWidth, waveformWidth)
    }

    fun hitTestAction(x: Float, y: Float): PillAction {
        return when {
            xButtonRect.contains(x, y) -> {
                if (currentState == AssistantState.IDLE) PillAction.MENU_BUTTON else PillAction.X_BUTTON
            }
            currentState == AssistantState.CONFIRMING && confirmRect.contains(x, y) -> PillAction.CONFIRM
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

        // Dark gradient background
        pillPaint.shader = LinearGradient(
            rect.left, rect.top, rect.right, rect.bottom,
            intArrayOf(COLOR_PILL_BG_START, COLOR_PILL_BG_MID, COLOR_PILL_BG_END),
            floatArrayOf(0f, 0.56f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(rect, pillRadius, pillRadius, pillPaint)
        pillPaint.shader = null

        // Animated repeating gradient outline (cyan -> blue -> purple)
        val outlineCycleWidth = rect.width() * 1.65f
        val outlineShift = if (isOutlineAnimatedState(currentState)) {
            -outlineCycleWidth * outlineShiftPhase
        } else {
            0f
        }
        val outlineShader = LinearGradient(
            rect.left + outlineShift,
            rect.top,
            rect.left + outlineShift + outlineCycleWidth,
            rect.bottom,
            intArrayOf(
                COLOR_OUTLINE_START,
                COLOR_OUTLINE_MID,
                COLOR_OUTLINE_END,
                COLOR_OUTLINE_START
            ),
            floatArrayOf(0f, 0.4f, 0.8f, 1f),
            Shader.TileMode.REPEAT
        )
        outlinePaint.shader = outlineShader
        canvas.drawRoundRect(rect, pillRadius, pillRadius, outlinePaint)
        outlinePaint.shader = null

        val centerY = rect.centerY()

        // --- Left zone: state-specific icon in colored circle ---
        val leftCenterX = rect.left + pillRadius
        confirmRect.setEmpty()
        micIconRect.setEmpty()

        val circleRadius = dp(PILL_HEIGHT_DP) / 2f - dp(4f)
        val (stateCenterColor, stateEdgeColor) = when (currentState) {
            AssistantState.IDLE -> circleColorIdleCenter to circleColorIdleEdge
            AssistantState.LISTENING -> circleColorListeningCenter to circleColorListeningEdge
            AssistantState.THINKING -> circleColorThinkingCenter to circleColorThinkingEdge
            AssistantState.CONFIRMING -> circleColorConfirmingCenter to circleColorConfirmingEdge
            AssistantState.SPEAKING -> circleColorSpeakingCenter to circleColorSpeakingEdge
        }
        val (centerColor, edgeColor) = if (connectionState == ConnectionState.CONNECTED) {
            stateCenterColor to stateEdgeColor
        } else {
            circleColorOfflineCenter to circleColorOfflineEdge
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
            AssistantState.CONFIRMING -> {
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
        if (currentState == AssistantState.IDLE) {
            drawMenuDots(canvas, rightCenterX, centerY)
        } else {
            drawIcon(canvas, xPaths, rightCenterX, centerY, 14f, 0f, circleColorXStroke)
        }

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
                            drawCenteredTextAutoFit(
                                canvas = canvas,
                                text = text,
                                left = centerZoneLeft,
                                right = centerZoneRight,
                                centerY = centerY
                            )
                        }
                    }
                }
            }
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

    private data class IdlePromptLayout(
        val leadingText: String,
        val trailingText: String,
        val showMicInline: Boolean
    )

    private fun idlePromptLayout(): IdlePromptLayout {
        val template = pillStrings.idlePrompt?.trim().orEmpty()
        if (template.isNotEmpty()) {
            val token = "{mic}"
            val idx = template.indexOf(token)
            if (idx >= 0) {
                val leading = template.substring(0, idx).trim()
                val trailing = template.substring(idx + token.length).trim()
                return IdlePromptLayout(
                    leadingText = leading,
                    trailingText = trailing,
                    showMicInline = true
                )
            }
            return IdlePromptLayout(
                leadingText = template,
                trailingText = "",
                showMicInline = false
            )
        }

        // Backward-compatible fallback for older language packs.
        return IdlePromptLayout(
            leadingText = pillStrings.tapPrefix,
            trailingText = pillStrings.tapSuffix,
            showMicInline = true
        )
    }

    private fun measureIdlePromptWidth(textSizePx: Float, includeIcon: Boolean): Float {
        val layout = idlePromptLayout()
        val savedTextSize = textPaint.textSize
        textPaint.textSize = textSizePx

        val leadingWidth = textPaint.measureText(layout.leadingText)
        val trailingWidth = textPaint.measureText(layout.trailingText)
        val showIcon = includeIcon && layout.showMicInline
        val iconWidth = if (showIcon) dp(INLINE_TEXT_ICON_SIZE_DP) else 0f
        val gapBefore = if (showIcon && layout.leadingText.isNotBlank()) dp(INLINE_TEXT_ICON_GAP_DP) else 0f
        val gapAfter = if (showIcon && layout.trailingText.isNotBlank()) dp(INLINE_TEXT_ICON_GAP_DP) else 0f

        textPaint.textSize = savedTextSize
        return leadingWidth + gapBefore + iconWidth + gapAfter + trailingWidth
    }

    private fun drawIdlePrompt(canvas: Canvas, left: Float, right: Float, centerY: Float) {
        val maxTextWidth = right - left
        if (maxTextWidth <= 0f) return

        val mutedPaint = textPaint
        val savedColor = mutedPaint.color
        val savedTextSize = mutedPaint.textSize
        mutedPaint.color = COLOR_TEXT_MUTED

        val layout = idlePromptLayout()
        var scale = 1f
        var leadingWidth = mutedPaint.measureText(layout.leadingText)
        var trailingWidth = mutedPaint.measureText(layout.trailingText)
        val showIcon = layout.showMicInline
        var iconSizePx = if (showIcon) dp(INLINE_TEXT_ICON_SIZE_DP) else 0f
        var gapBefore = if (showIcon && layout.leadingText.isNotBlank()) dp(INLINE_TEXT_ICON_GAP_DP) else 0f
        var gapAfter = if (showIcon && layout.trailingText.isNotBlank()) dp(INLINE_TEXT_ICON_GAP_DP) else 0f
        var totalWidth = leadingWidth + gapBefore + iconSizePx + gapAfter + trailingWidth

        if (totalWidth > maxTextWidth) {
            scale = (maxTextWidth / totalWidth).coerceAtLeast(0.82f)
            mutedPaint.textSize = savedTextSize * scale
            leadingWidth = mutedPaint.measureText(layout.leadingText)
            trailingWidth = mutedPaint.measureText(layout.trailingText)
            iconSizePx = if (showIcon) dp(INLINE_TEXT_ICON_SIZE_DP) * scale else 0f
            gapBefore = if (showIcon && layout.leadingText.isNotBlank()) dp(INLINE_TEXT_ICON_GAP_DP) * scale else 0f
            gapAfter = if (showIcon && layout.trailingText.isNotBlank()) dp(INLINE_TEXT_ICON_GAP_DP) * scale else 0f
            totalWidth = leadingWidth + gapBefore + iconSizePx + gapAfter + trailingWidth
        }

        val startX = left + (maxTextWidth - totalWidth) / 2f
        val textY = centerY - (mutedPaint.ascent() + mutedPaint.descent()) / 2f
        var cursorX = startX

        if (layout.leadingText.isNotBlank()) {
            canvas.drawText(layout.leadingText, cursorX, textY, mutedPaint)
            cursorX += leadingWidth
        }

        if (showIcon) {
            cursorX += gapBefore
            val iconCenterX = cursorX + iconSizePx / 2f
            drawIcon(
                canvas = canvas,
                paths = micPaths,
                cx = iconCenterX,
                cy = centerY,
                sizeDp = INLINE_TEXT_ICON_SIZE_DP * scale,
                rotation = 0f,
                color = COLOR_TEXT_MUTED,
                strokeWidthDp = INLINE_TEXT_ICON_STROKE_DP * scale
            )
            cursorX += iconSizePx + gapAfter
        }

        if (layout.trailingText.isNotBlank()) {
            canvas.drawText(layout.trailingText, cursorX, textY, mutedPaint)
        }

        mutedPaint.color = savedColor
        mutedPaint.textSize = savedTextSize
    }

    private fun drawThinkingText(canvas: Canvas, left: Float, right: Float, centerY: Float) {
        val maxTextWidth = right - left
        if (maxTextWidth <= 0f) return

        val text = pillStrings.thinking.trim()
        if (text.isBlank()) return

        val savedTextSize = textPaint.textSize
        var width = textPaint.measureText(text)
        if (width > maxTextWidth) {
            val scale = (maxTextWidth / width).coerceAtLeast(0.82f)
            textPaint.textSize = savedTextSize * scale
            width = textPaint.measureText(text)
        }

        val startX = left + (maxTextWidth - width) / 2f
        val textY = centerY - (textPaint.ascent() + textPaint.descent()) / 2f

        val savedColor = textPaint.color
        val savedShader = textPaint.shader
        val savedAlpha = textPaint.alpha

        textPaint.color = 0xFFD6E5FF.toInt()
        textPaint.shader = null
        textPaint.alpha = 255
        canvas.drawText(text, startX, textY, textPaint)

        val sheenBand = width * 0.66f
        val sheenTravelStart = startX - sheenBand
        val sheenTravelEnd = startX + width + sheenBand
        val sheenCenter = sheenTravelStart + (sheenTravelEnd - sheenTravelStart) * thinkingSheenPhase
        textPaint.shader = LinearGradient(
            sheenCenter - sheenBand,
            textY,
            sheenCenter + sheenBand,
            textY,
            intArrayOf(
                Color.TRANSPARENT,
                0x66FFFFFF,
                0xFFFFFFFF.toInt(),
                0x66FFFFFF,
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.36f, 0.5f, 0.64f, 1f),
            Shader.TileMode.CLAMP
        )
        textPaint.alpha = 255
        canvas.drawText(text, startX, textY, textPaint)

        val hotspotBand = width * 0.20f
        textPaint.shader = LinearGradient(
            sheenCenter - hotspotBand,
            textY,
            sheenCenter + hotspotBand,
            textY,
            intArrayOf(Color.TRANSPARENT, Color.WHITE, Color.TRANSPARENT),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        textPaint.alpha = 238
        canvas.drawText(text, startX, textY, textPaint)

        textPaint.color = savedColor
        textPaint.shader = savedShader
        textPaint.alpha = savedAlpha
        textPaint.textSize = savedTextSize
    }

    private fun drawMenuDots(canvas: Canvas, cx: Float, cy: Float) {
        val dotRadius = dp(1.7f)
        val spacing = dp(4.8f)
        canvas.drawCircle(cx, cy - spacing, dotRadius, menuDotPaint)
        canvas.drawCircle(cx, cy, dotRadius, menuDotPaint)
        canvas.drawCircle(cx, cy + spacing, dotRadius, menuDotPaint)
    }

    private fun isOutlineAnimatedState(state: AssistantState): Boolean {
        return state == AssistantState.LISTENING ||
                state == AssistantState.THINKING ||
                state == AssistantState.SPEAKING
    }

    private fun drawCenteredTextAutoFit(
        canvas: Canvas,
        text: String,
        left: Float,
        right: Float,
        centerY: Float
    ) {
        val maxTextWidth = right - left
        if (maxTextWidth <= 0f) return

        val savedTextSize = textPaint.textSize
        var width = textPaint.measureText(text)
        if (width > maxTextWidth) {
            val scale = (maxTextWidth / width).coerceAtLeast(0.82f)
            textPaint.textSize = savedTextSize * scale
            width = textPaint.measureText(text)
        }

        val textY = centerY - (textPaint.ascent() + textPaint.descent()) / 2f
        val textX = left + (maxTextWidth - width) / 2f
        canvas.drawText(text, textX, textY, textPaint)
        textPaint.textSize = savedTextSize
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
            AssistantState.IDLE -> {
                val layout = idlePromptLayout()
                listOf(layout.leadingText, layout.trailingText)
                    .filter { it.isNotBlank() }
                    .joinToString(" ")
            }
            AssistantState.LISTENING -> ""
            AssistantState.THINKING -> "" // handled by drawThinkingText
            AssistantState.CONFIRMING -> confirmLabelText.ifEmpty { pillStrings.shareScreen }
            AssistantState.SPEAKING -> ""
        }
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
        thinkingSheenAnimator.cancel()
        outlineFlowAnimator.cancel()
        widthAnimator?.cancel()
    }
}
