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

class DotView(context: Context) : View(context) {
    companion object {
        // Outer size includes shadow padding
        const val DOT_SIZE_DP = 72
        private const val ORB_DIAMETER_DP = 56f
        private const val ICON_SIZE_DP = 24f

        private const val COLOR_WHITE = Color.WHITE
        private const val COLOR_ICON = 0xFF222222.toInt()
        private const val COLOR_ICON_MUTED = 0xFF666666.toInt()
        private const val COLOR_BADGE_BG = 0xE6FFFFFF.toInt()
        private const val COLOR_BADGE_TEXT = 0xFF222222.toInt()
        private const val COLOR_DISCONNECTED = 0xFFFF5252.toInt()
        private const val COLOR_RECONNECTING = 0xFFFFC107.toInt()
    }

    enum class ConnectionState { CONNECTED, DISCONNECTED, RECONNECTING }

    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(50, 0, 0, 0)
        maskFilter = BlurMaskFilter(dp(8f), BlurMaskFilter.Blur.NORMAL)
    }

    private val orbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = COLOR_WHITE
    }

    private val listeningRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
        color = COLOR_ICON
    }

    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        color = COLOR_ICON
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = COLOR_BADGE_BG
    }

    private val badgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = COLOR_BADGE_TEXT
        textSize = dp(11f)
        textAlign = Paint.Align.CENTER
    }

    private val statusDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private var currentState = AssistantState.IDLE
    private var connectionState = ConnectionState.CONNECTED
    private var userCount = 0
    private var assistantCount = 0

    private var listeningAlpha = 0
    private val listeningAnimator = ValueAnimator.ofInt(40, 160).apply {
        duration = 800
        repeatMode = ValueAnimator.REVERSE
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            listeningAlpha = it.animatedValue as Int
            invalidate()
        }
    }

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

    private var speakingAlpha = 255
    private val speakingAnimator = ValueAnimator.ofInt(120, 255).apply {
        duration = 600
        repeatMode = ValueAnimator.REVERSE
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            speakingAlpha = it.animatedValue as Int
            invalidate()
        }
    }

    private val tempPath = Path()
    private val tempMatrix = Matrix()

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

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    fun setState(state: AssistantState) {
        currentState = state
        iconPaint.color = if (state == AssistantState.IDLE) COLOR_ICON_MUTED else COLOR_ICON

        listeningAnimator.cancel()
        loaderAnimator.cancel()
        speakingAnimator.cancel()
        listeningAlpha = 0
        loaderRotation = 0f
        speakingAlpha = 255

        when (state) {
            AssistantState.LISTENING -> listeningAnimator.start()
            AssistantState.THINKING -> loaderAnimator.start()
            AssistantState.SPEAKING -> speakingAnimator.start()
            else -> { /* no animation */ }
        }
        invalidate()
    }

    fun setConnectionState(state: ConnectionState) {
        connectionState = state
        invalidate()
    }

    fun setMessageCounts(user: Int, assistant: Int) {
        userCount = user
        assistantCount = assistant
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val orbRadius = dp(ORB_DIAMETER_DP / 2f)

        // Shadow
        canvas.drawCircle(cx, cy + dp(3f), orbRadius, shadowPaint)

        // Orb
        canvas.drawCircle(cx, cy, orbRadius, orbPaint)

        // Listening ring
        if (currentState == AssistantState.LISTENING) {
            listeningRingPaint.alpha = listeningAlpha
            canvas.drawCircle(cx, cy, orbRadius + dp(2f), listeningRingPaint)
        }

        // Icon (lucide)
        when (currentState) {
            AssistantState.THINKING -> drawLucide(canvas, loaderPaths, cx, cy, ICON_SIZE_DP, loaderRotation, 255)
            AssistantState.SPEAKING -> drawLucide(canvas, audioPaths, cx, cy, ICON_SIZE_DP, 0f, speakingAlpha)
            else -> drawLucide(canvas, micPaths, cx, cy, ICON_SIZE_DP, 0f, 255)
        }

        // Message counter badge
        val total = userCount + assistantCount
        if (total > 0) {
            drawBadge(canvas, cx, cy, orbRadius)
        }

        // Connection dot (minimal)
        if (connectionState != ConnectionState.CONNECTED) {
            val color = if (connectionState == ConnectionState.RECONNECTING) COLOR_RECONNECTING else COLOR_DISCONNECTED
            statusDotPaint.color = color
            canvas.drawCircle(cx + orbRadius - dp(6f), cy + orbRadius - dp(6f), dp(4f), statusDotPaint)
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

    private fun drawBadge(canvas: Canvas, cx: Float, cy: Float, orbRadius: Float) {
        val text = "${userCount}/${assistantCount}"
        val textWidth = badgeTextPaint.measureText(text)
        val padding = dp(4f)
        val badgeW = textWidth + padding * 2f
        val badgeH = dp(16f)
        val left = cx + orbRadius - badgeW
        val top = cy - orbRadius - dp(10f)
        val right = left + badgeW
        val bottom = top + badgeH

        canvas.drawRoundRect(left, top, right, bottom, dp(8f), dp(8f), badgePaint)
        val textY = top + badgeH / 2f - (badgeTextPaint.ascent() + badgeTextPaint.descent()) / 2f
        canvas.drawText(text, left + badgeW / 2f, textY, badgeTextPaint)
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
        listeningAnimator.cancel()
        loaderAnimator.cancel()
        speakingAnimator.cancel()
    }
}
