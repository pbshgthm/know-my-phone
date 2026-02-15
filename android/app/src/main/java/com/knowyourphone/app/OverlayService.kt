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
import com.knowyourphone.app.i18n.LanguageManager
import com.knowyourphone.app.overlay.DotView
import com.knowyourphone.app.overlay.ErrorToastView
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
    private var errorToastView: ErrorToastView? = null
    private var dotParams: WindowManager.LayoutParams? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    fun getDotView(): DotView? = dotView

    /**
     * Hide or show all overlay views (dot pill, highlight, error toast).
     * Used to keep overlays out of screenshots.
     */
    fun setOverlayVisibility(visible: Boolean) {
        val visibility = if (visible) View.VISIBLE else View.INVISIBLE
        dotView?.visibility = visibility
        highlightView?.visibility = if (visible && highlightView?.hasHighlights() == true) View.VISIBLE else View.GONE
        errorToastView?.visibility = visibility
    }

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

        LanguageManager.init(applicationContext)
        val langCode = prefs.getString("language_code", "en") ?: "en"

        addDotOverlay()
        dotView?.setLanguageStrings(LanguageManager.getPillStrings(langCode))
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
        removeErrorToast()
        scope.cancel()
        Log.d(TAG, "Overlay service destroyed")
    }

    fun resetSession() {
        viewModel.resetSession()
    }

    fun setLanguage(languageCode: String) {
        viewModel.setLanguage(languageCode)
        dotView?.setLanguageStrings(LanguageManager.getPillStrings(languageCode))
    }

    fun setAutoScreenshot(enabled: Boolean) {
        viewModel.setAutoScreenshot(enabled)
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
        dotView = DotView(this)
        val params = WindowManager.LayoutParams(
            dotView!!.getDesiredWidthPx(),
            dotView!!.getDesiredHeightPx(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(16)
            y = dp(200)
        }
        dotParams = params

        // Touch handler: tap-to-record/send, drag, X button, check button
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false
        var pressedAction = DotView.PillAction.NONE

        dotView?.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false

                    val action = dotView?.hitTestAction(event.x, event.y) ?: DotView.PillAction.NONE
                    pressedAction = action
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
                        val action = dotView?.hitTestAction(event.x, event.y) ?: DotView.PillAction.NONE
                        when (action) {
                            DotView.PillAction.MIC_ICON -> {
                                if (pressedAction == DotView.PillAction.MIC_ICON) {
                                    val state = viewModel.state.value
                                    if (state == AssistantState.LISTENING) {
                                        viewModel.onPressEnd()
                                    } else if (state != AssistantState.NEED_SCREENSHOT) {
                                        viewModel.onPressStart()
                                    }
                                }
                            }
                            DotView.PillAction.X_BUTTON -> {
                                if (viewModel.state.value == AssistantState.IDLE) {
                                    // Hide pill, stop service
                                    hideOverlay()
                                } else {
                                    viewModel.onCancel()
                                }
                            }
                            DotView.PillAction.CONFIRM -> {
                                viewModel.onScreenshotConfirm()
                            }
                            DotView.PillAction.NONE -> { /* no-op */ }
                        }
                    }
                    pressedAction = DotView.PillAction.NONE
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    pressedAction = DotView.PillAction.NONE
                    true
                }
                else -> false
            }
        }

        windowManager.addView(dotView, params)
    }

    private fun hideOverlay() {
        viewModel.resetSession()
        stopSelf()
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

    // --- State observation ---

    private fun observeState() {
        scope.launch {
            viewModel.state.collect { state ->
                dotView?.setState(state)
                updateDotLayoutForState()
            }
        }

        scope.launch {
            viewModel.inputLevel.collect { level ->
                dotView?.setAudioLevel(level)
            }
        }

        scope.launch {
            viewModel.playbackLevel.collect { level ->
                dotView?.setPlaybackLevel(level)
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

        // Observe connection state for dot border indicator
        scope.launch {
            viewModel.connected.collect { connected ->
                if (connected) {
                    dotView?.setConnectionState(DotView.ConnectionState.CONNECTED)
                } else if (viewModel.reconnecting.value) {
                    dotView?.setConnectionState(DotView.ConnectionState.RECONNECTING)
                } else {
                    dotView?.setConnectionState(DotView.ConnectionState.DISCONNECTED)
                }
            }
        }

        scope.launch {
            viewModel.reconnecting.collect { reconnecting ->
                if (reconnecting) {
                    dotView?.setConnectionState(DotView.ConnectionState.RECONNECTING)
                } else if (viewModel.connected.value) {
                    dotView?.setConnectionState(DotView.ConnectionState.CONNECTED)
                } else {
                    dotView?.setConnectionState(DotView.ConnectionState.DISCONNECTED)
                }
            }
        }

        // Observe error messages
        scope.launch {
            viewModel.errorMessage.collect { message ->
                if (message != null) {
                    showErrorToast(message)
                    viewModel.clearError()
                }
            }
        }
    }

    // --- Error toast ---

    private fun showErrorToast(message: String) {
        removeErrorToast()

        val dismissDelay = if (message == "Connected") 1500L else 3000L

        errorToastView = ErrorToastView(this, message)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(60)
        }

        windowManager.addView(errorToastView, params)

        scope.launch {
            delay(dismissDelay)
            removeErrorToast()
        }
    }

    private fun removeErrorToast() {
        errorToastView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        errorToastView = null
    }

    private fun updateDotLayoutForState() {
        val params = dotParams ?: return
        val dot = dotView ?: return

        val desiredWidth = dot.getDesiredWidthPx()
        val desiredHeight = dot.getDesiredHeightPx()
        if (params.width == desiredWidth && params.height == desiredHeight) return

        params.width = desiredWidth
        params.height = desiredHeight

        // Keep pill within screen bounds
        val bounds = windowManager.currentWindowMetrics.bounds
        val maxX = bounds.width() - params.width - dp(16)
        if (params.x > maxX) {
            params.x = maxX.coerceAtLeast(dp(16))
        }

        try {
            windowManager.updateViewLayout(dot, params)
        } catch (_: Exception) {}
    }

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics
        ).toInt()
    }
}
