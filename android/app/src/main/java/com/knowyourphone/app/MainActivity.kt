package com.knowyourphone.app

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {
    companion object {
        private const val REQUEST_MIC = 100
    }

    private lateinit var micStatus: ImageView
    private lateinit var overlayStatus: ImageView
    private lateinit var accessibilityStatus: ImageView
    private lateinit var micBtn: Button
    private lateinit var overlayBtn: Button
    private lateinit var accessibilityBtn: Button
    private lateinit var startBtn: Button
    private lateinit var serverUrlInput: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStatus()
    }

    private fun buildUi() {
        val padding = dp(24)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }

        // Title
        root.addView(TextView(this).apply {
            text = "Know Your Phone"
            textSize = 28f
            setTextColor(0xFF212121.toInt())
            setPadding(0, dp(16), 0, dp(8))
        })

        // Subtitle
        root.addView(TextView(this).apply {
            text = "AI-powered voice assistant that helps you understand your screen."
            textSize = 14f
            setTextColor(0xFF757575.toInt())
            setPadding(0, 0, 0, dp(24))
        })

        // Permissions section title
        root.addView(TextView(this).apply {
            text = "Setup Checklist"
            textSize = 18f
            setTextColor(0xFF424242.toInt())
            setPadding(0, 0, 0, dp(16))
        })

        // 1. Microphone
        val micRow = makePermissionRow("Microphone Permission")
        micStatus = micRow.first
        micBtn = micRow.third
        micBtn.setOnClickListener {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MIC
            )
        }
        root.addView(micRow.second)

        // 2. Overlay
        val overlayRow = makePermissionRow("Display Over Other Apps")
        overlayStatus = overlayRow.first
        overlayBtn = overlayRow.third
        overlayBtn.setOnClickListener {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }
        root.addView(overlayRow.second)

        // 3. Accessibility
        val accessRow = makePermissionRow("Accessibility Service")
        accessibilityStatus = accessRow.first
        accessibilityBtn = accessRow.third
        accessibilityBtn.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        root.addView(accessRow.second)

        // Server URL input
        root.addView(TextView(this).apply {
            text = "Server URL"
            textSize = 14f
            setTextColor(0xFF424242.toInt())
            setPadding(0, dp(24), 0, dp(4))
        })

        val prefs = getSharedPreferences("kyp_prefs", MODE_PRIVATE)
        val savedUrl = prefs.getString("server_url", "ws://localhost:8765") ?: "ws://localhost:8765"

        serverUrlInput = EditText(this).apply {
            setText(savedUrl)
            textSize = 14f
            isSingleLine = true
            hint = "ws://ip:port"
        }
        root.addView(serverUrlInput)

        // Start button
        startBtn = Button(this).apply {
            text = "Start Assistant"
            textSize = 16f
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setOnClickListener { onStartClicked() }
        }
        val startParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(24) }
        root.addView(startBtn, startParams)

        // Stop button
        val stopBtn = Button(this).apply {
            text = "Stop Assistant"
            textSize = 14f
            setOnClickListener {
                OverlayService.stop(this@MainActivity)
                Toast.makeText(this@MainActivity, "Assistant stopped", Toast.LENGTH_SHORT).show()
            }
        }
        val stopParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) }
        root.addView(stopBtn, stopParams)

        setContentView(root)
    }

    private fun makePermissionRow(label: String): Triple<ImageView, LinearLayout, Button> {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, dp(6))
        }

        val statusIcon = ImageView(this).apply {
            setImageResource(android.R.drawable.presence_busy)
            layoutParams = LinearLayout.LayoutParams(dp(24), dp(24)).apply {
                gravity = android.view.Gravity.CENTER_VERTICAL
                marginEnd = dp(12)
            }
        }

        val labelView = TextView(this).apply {
            text = label
            textSize = 15f
            setTextColor(0xFF333333.toInt())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
        }

        val button = Button(this).apply {
            text = "Grant"
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        row.addView(statusIcon)
        row.addView(labelView)
        row.addView(button)

        return Triple(statusIcon, row, button)
    }

    private fun updatePermissionStatus() {
        val hasMic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        val hasOverlay = Settings.canDrawOverlays(this)
        val hasAccessibility = isAccessibilityServiceEnabled()

        micStatus.setImageResource(
            if (hasMic) android.R.drawable.presence_online else android.R.drawable.presence_busy
        )
        overlayStatus.setImageResource(
            if (hasOverlay) android.R.drawable.presence_online else android.R.drawable.presence_busy
        )
        accessibilityStatus.setImageResource(
            if (hasAccessibility) android.R.drawable.presence_online else android.R.drawable.presence_busy
        )

        micBtn.isEnabled = !hasMic
        overlayBtn.isEnabled = !hasOverlay
        accessibilityBtn.isEnabled = !hasAccessibility

        val allGranted = hasMic && hasOverlay && hasAccessibility
        startBtn.isEnabled = allGranted
        startBtn.alpha = if (allGranted) 1f else 0.5f
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        val myServiceName = "$packageName/${KypAccessibilityService::class.java.canonicalName}"
        return enabledServices.any { info ->
            info.resolveInfo?.serviceInfo?.let {
                "${it.packageName}/${it.name}" == myServiceName
            } == true
        }
    }

    private fun onStartClicked() {
        // Save server URL
        val url = serverUrlInput.text.toString().trim()
        if (url.isBlank()) {
            Toast.makeText(this, "Please enter a server URL", Toast.LENGTH_SHORT).show()
            return
        }
        getSharedPreferences("kyp_prefs", MODE_PRIVATE)
            .edit()
            .putString("server_url", url)
            .apply()

        // Start overlay service
        OverlayService.start(this)
        Toast.makeText(this, "Assistant started! Look for the floating dot.", Toast.LENGTH_LONG).show()

        // Minimize the activity
        moveTaskToBack(true)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        updatePermissionStatus()
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }
}
