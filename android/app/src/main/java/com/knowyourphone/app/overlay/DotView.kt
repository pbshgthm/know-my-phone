package com.knowyourphone.app.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import android.view.animation.LinearInterpolator
import com.knowyourphone.app.state.AssistantState

class DotView(context: Context) : View(context) {
    companion object {
        const val DOT_SIZE_DP = 48
        private const val COLOR_IDLE = Color.GRAY
        private const val COLOR_LISTENING = 0xFF4CAF50.toInt()   // green
        private const val COLOR_THINKING = 0xFFFFC107.toInt()    // amber
        private const val COLOR_SPEAKING = 0xFF2196F3.toInt()    // blue
        private const val COLOR_SCREENSHOT = 0xFFFF9800.toInt()  // orange
        private const val COLOR_HIGHLIGHT = 0xFF9C27B0.toInt()   // purple

        // Connection state border colors
        private const val BORDER_CONNECTED = Color.WHITE
        private const val BORDER_DISCONNECTED = Color.RED
        private const val BORDER_RECONNECTING = 0xFFFFEB3B.toInt() // yellow
    }

    enum class ConnectionState { CONNECTED, DISCONNECTED, RECONNECTING }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = COLOR_IDLE
    }

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = BORDER_CONNECTED
    }

    private var currentState = AssistantState.IDLE
    private var connectionState = ConnectionState.CONNECTED
    private var pulseScale = 1f
    private var borderAlpha = 255

    private val pulseAnimator = ValueAnimator.ofFloat(0.85f, 1.15f).apply {
        duration = 800
        repeatMode = ValueAnimator.REVERSE
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            pulseScale = it.animatedValue as Float
            invalidate()
        }
    }

    private var rotationAngle = 0f
    private val rotateAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = 1500
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            rotationAngle = it.animatedValue as Float
            invalidate()
        }
    }

    private val borderPulseAnimator = ValueAnimator.ofInt(80, 255).apply {
        duration = 1000
        repeatMode = ValueAnimator.REVERSE
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            borderAlpha = it.animatedValue as Int
            invalidate()
        }
    }

    fun setState(state: AssistantState) {
        currentState = state
        paint.color = when (state) {
            AssistantState.IDLE -> COLOR_IDLE
            AssistantState.LISTENING -> COLOR_LISTENING
            AssistantState.THINKING -> COLOR_THINKING
            AssistantState.SPEAKING -> COLOR_SPEAKING
            AssistantState.NEED_SCREENSHOT -> COLOR_SCREENSHOT
            AssistantState.HIGHLIGHTING -> COLOR_HIGHLIGHT
        }

        // Stop fill animations first
        pulseAnimator.cancel()
        rotateAnimator.cancel()
        pulseScale = 1f
        rotationAngle = 0f

        when (state) {
            AssistantState.LISTENING, AssistantState.SPEAKING -> pulseAnimator.start()
            AssistantState.THINKING -> rotateAnimator.start()
            else -> {}
        }
        invalidate()
    }

    fun setConnectionState(state: ConnectionState) {
        connectionState = state
        borderPulseAnimator.cancel()
        borderAlpha = 255

        when (state) {
            ConnectionState.CONNECTED -> {
                borderPaint.color = BORDER_CONNECTED
                borderPaint.strokeWidth = 3f
            }
            ConnectionState.DISCONNECTED -> {
                borderPaint.color = BORDER_DISCONNECTED
                borderPaint.strokeWidth = 4f
            }
            ConnectionState.RECONNECTING -> {
                borderPaint.color = BORDER_RECONNECTING
                borderPaint.strokeWidth = 4f
                borderPulseAnimator.start()
            }
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val baseRadius = (minOf(width, height) / 2f) - 4f

        canvas.save()
        if (currentState == AssistantState.THINKING) {
            canvas.rotate(rotationAngle, cx, cy)
        }

        val radius = baseRadius * pulseScale
        canvas.drawCircle(cx, cy, radius, paint)

        // Apply alpha for reconnecting pulse
        borderPaint.alpha = borderAlpha
        canvas.drawCircle(cx, cy, radius, borderPaint)

        // Draw a small indicator for thinking state
        if (currentState == AssistantState.THINKING) {
            val indicatorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                style = Paint.Style.FILL
            }
            canvas.drawCircle(cx, cy - radius + 6f, 4f, indicatorPaint)
        }

        canvas.restore()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        pulseAnimator.cancel()
        rotateAnimator.cancel()
        borderPulseAnimator.cancel()
    }
}
