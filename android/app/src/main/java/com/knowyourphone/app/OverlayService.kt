package com.knowyourphone.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import com.knowyourphone.app.overlay.ConfirmPillView
import com.knowyourphone.app.overlay.DotView
import com.knowyourphone.app.overlay.HighlightOverlayView
import com.knowyourphone.app.state.AssistantState
import com.knowyourphone.app.state.AssistantViewModel
import kotlinx.coroutines.*

class OverlayService : Service() {
    companion object {
        private const val TAG = "OverlayService"
        private const val CHANNEL_ID = "kyp_overlay"
        private const val NOTIFICATION_ID = 1

        var instance: OverlayService? = null
            private set

        fun start(context: Context) {
            val intent = Intent(context, OverlayService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, OverlayService::class.java)
            context.stopService(intent)
        }
    }

    private lateinit var windowManager: WindowManager
    private lateinit var viewModel: AssistantViewModel

    private var dotView: DotView? = null
    private var highlightView: HighlightOverlayView? = null
    private var confirmPillView: ConfirmPillView? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        // Read server URL from shared prefs (default: ws://localhost:8765)
        val prefs = getSharedPreferences("kyp_prefs", MODE_PRIVATE)
        val serverUrl = prefs.getString("server_url", "ws://localhost:8765") ?: "ws://localhost:8765"

        viewModel = AssistantViewModel(applicationContext, serverUrl)

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        addDotOverlay()
        addHighlightOverlay()

        viewModel.connect()
        observeState()

        Log.d(TAG, "Overlay service created, connecting to $serverUrl")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        viewModel.destroy()
        removeDotOverlay()
        removeHighlightOverlay()
        removeConfirmPill()
        scope.cancel()
        Log.d(TAG, "Overlay service destroyed")
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Know Your Phone",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Voice assistant overlay"
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Know Your Phone")
            .setContentText("Voice assistant is active")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    // --- Dot overlay ---

    private fun addDotOverlay() {
        val dotSizePx = dp(DotView.DOT_SIZE_DP)

        dotView = DotView(this)
        val params = WindowManager.LayoutParams(
            dotSizePx,
            dotSizePx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(16)
            y = dp(200)
        }

        // Make dot draggable
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false

        dotView?.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY
                    if (!isDragging && (dx * dx + dy * dy > 100)) {
                        isDragging = true
                    }
                    if (isDragging) {
                        params.x = initialX + dx.toInt()
                        params.y = initialY + dy.toInt()
                        windowManager.updateViewLayout(dotView, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        viewModel.onDotTap()
                    }
                    true
                }
                else -> false
            }
        }

        windowManager.addView(dotView, params)
    }

    private fun removeDotOverlay() {
        dotView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        dotView = null
    }

    // --- Highlight overlay ---

    private fun addHighlightOverlay() {
        highlightView = HighlightOverlayView(this)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )

        highlightView?.visibility = View.GONE
        windowManager.addView(highlightView, params)
    }

    private fun removeHighlightOverlay() {
        highlightView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        highlightView = null
    }

    // --- Confirm pill ---

    private fun showConfirmPill(reason: String) {
        removeConfirmPill()

        confirmPillView = ConfirmPillView(
            context = this,
            reason = reason,
            onConfirm = {
                removeConfirmPill()
                viewModel.onScreenshotConfirm()
            },
            onDecline = {
                removeConfirmPill()
                viewModel.onScreenshotDecline()
            }
        )

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }

        windowManager.addView(confirmPillView, params)
    }

    private fun removeConfirmPill() {
        confirmPillView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        confirmPillView = null
    }

    // --- State observation ---

    private fun observeState() {
        scope.launch {
            viewModel.state.collect { state ->
                dotView?.setState(state)

                when (state) {
                    AssistantState.NEED_SCREENSHOT -> {
                        showConfirmPill(viewModel.screenshotReason.value)
                    }
                    AssistantState.HIGHLIGHTING -> {
                        // Show highlight overlay
                    }
                    else -> {
                        removeConfirmPill()
                    }
                }
            }
        }

        scope.launch {
            viewModel.highlights.collect { targets ->
                if (targets.isNotEmpty()) {
                    highlightView?.setHighlights(targets)
                    highlightView?.visibility = View.VISIBLE
                } else {
                    highlightView?.clear()
                    highlightView?.visibility = View.GONE
                }
            }
        }
    }

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics
        ).toInt()
    }
}
