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
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.graphics.Color
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.AdapterView

class MainActivity : AppCompatActivity() {
    companion object {
        private const val REQUEST_MIC = 100
    }

    private lateinit var permissionsContainer: LinearLayout
    private lateinit var micRow: LinearLayout
    private lateinit var overlayRow: LinearLayout
    private lateinit var accessibilityRow: LinearLayout
    private lateinit var micBtn: TextView
    private lateinit var overlayBtn: TextView
    private lateinit var accessibilityBtn: TextView
    private lateinit var startResetBtn: TextView
    private lateinit var stopBtn: TextView
    private lateinit var languageSpinner: Spinner

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

        root.addView(TextView(this).apply {
            text = "Know Your Phone"
            textSize = 24f
            setTextColor(0xFF111111.toInt())
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(12), 0, dp(6))
        })

        root.addView(TextView(this).apply {
            text = "Voice-first assistant for screen help."
            textSize = 14f
            setTextColor(0xFF666666.toInt())
            setPadding(0, 0, 0, dp(20))
        })

        // Language selector
        val languageLabel = TextView(this).apply {
            text = "Language"
            textSize = 13f
            setTextColor(0xFF666666.toInt())
            setPadding(0, 0, 0, dp(6))
        }
        root.addView(languageLabel)

        languageSpinner = Spinner(this)
        val languages = listOf(
            LanguageOption("English", "en"),
            LanguageOption("Tamil", "ta"),
            LanguageOption("Hindi", "hi"),
            LanguageOption("Kannada", "kn"),
            LanguageOption("Telugu", "te")
        )
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            languages.map { it.label }
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        languageSpinner.adapter = adapter
        languageSpinner.background = GradientDrawable().apply {
            cornerRadius = dp(10).toFloat()
            setStroke(dp(1), 0x22000000)
            setColor(Color.TRANSPARENT)
        }
        root.addView(languageSpinner, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(16) })

        // Permissions (only show missing)
        permissionsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(16))
        }

        val mic = makePermissionRow("Microphone")
        micRow = mic.first
        micBtn = mic.second
        micBtn.setOnClickListener {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MIC
            )
        }
        permissionsContainer.addView(micRow)

        val overlay = makePermissionRow("Overlay")
        overlayRow = overlay.first
        overlayBtn = overlay.second
        overlayBtn.setOnClickListener {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }
        permissionsContainer.addView(overlayRow)

        val access = makePermissionRow("Accessibility")
        accessibilityRow = access.first
        accessibilityBtn = access.second
        accessibilityBtn.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        permissionsContainer.addView(accessibilityRow)

        root.addView(permissionsContainer)

        // Start / Reset button
        startResetBtn = makePrimaryButton("Start / Reset Session").apply {
            setOnClickListener { onStartOrResetClicked() }
        }
        root.addView(startResetBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(12) })

        // Stop button
        stopBtn = makeSecondaryButton("Stop Agent").apply {
            setOnClickListener {
                OverlayService.stop(this@MainActivity)
                Toast.makeText(this@MainActivity, "Agent stopped", Toast.LENGTH_SHORT).show()
            }
        }
        root.addView(stopBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) })

        // Set selection + listener
        val prefs = getSharedPreferences("kyp_prefs", MODE_PRIVATE)
        val savedLang = prefs.getString("language_code", "en") ?: "en"
        val index = languages.indexOfFirst { it.code == savedLang }.let { if (it >= 0) it else 0 }
        languageSpinner.setSelection(index, false)
        languageSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: android.view.View?, position: Int, id: Long) {
                val code = languages[position].code
                prefs.edit().putString("language_code", code).apply()
                OverlayService.instance?.setLanguage(code)
            }

            override fun onNothingSelected(parent: AdapterView<*>) {
                // no-op
            }
        }

        setContentView(root)
    }

    private fun makePermissionRow(label: String): Pair<LinearLayout, TextView> {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(6))
        }

        val labelView = TextView(this).apply {
            text = label
            textSize = 15f
            setTextColor(0xFF222222.toInt())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val button = makeTextButton("Enable")

        row.addView(labelView)
        row.addView(button)

        return Pair(row, button)
    }

    private fun updatePermissionStatus() {
        val hasMic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        val hasOverlay = Settings.canDrawOverlays(this)
        val hasAccessibility = isAccessibilityServiceEnabled()

        micRow.visibility = if (hasMic) android.view.View.GONE else android.view.View.VISIBLE
        overlayRow.visibility = if (hasOverlay) android.view.View.GONE else android.view.View.VISIBLE
        accessibilityRow.visibility = if (hasAccessibility) android.view.View.GONE else android.view.View.VISIBLE

        val allGranted = hasMic && hasOverlay && hasAccessibility
        startResetBtn.isEnabled = allGranted
        startResetBtn.alpha = if (allGranted) 1f else 0.4f

        permissionsContainer.visibility = if (allGranted) android.view.View.GONE else android.view.View.VISIBLE
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

    private fun onStartOrResetClicked() {
        val hasMic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        val hasOverlay = Settings.canDrawOverlays(this)
        val hasAccessibility = isAccessibilityServiceEnabled()

        if (!hasMic || !hasOverlay || !hasAccessibility) {
            Toast.makeText(this, "Grant required permissions first", Toast.LENGTH_SHORT).show()
            return
        }

        if (OverlayService.instance == null) {
            OverlayService.start(this)
            Toast.makeText(this, "Agent started. Look for the orb.", Toast.LENGTH_SHORT).show()
        } else {
            OverlayService.instance?.resetSession()
            Toast.makeText(this, "Session reset", Toast.LENGTH_SHORT).show()
        }

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

    private fun makePrimaryButton(label: String): TextView {
        return TextView(this).apply {
            text = label
            textSize = 15f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(0xFF111111.toInt())
            }
        }
    }

    private fun makeSecondaryButton(label: String): TextView {
        return TextView(this).apply {
            text = label
            textSize = 14f
            setTextColor(0xFF222222.toInt())
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setStroke(dp(1), 0x22000000)
                setColor(Color.TRANSPARENT)
            }
        }
    }

    private fun makeTextButton(label: String): TextView {
        return TextView(this).apply {
            text = label
            textSize = 13f
            setTextColor(0xFF222222.toInt())
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setStroke(dp(1), 0x22000000)
                setColor(Color.TRANSPARENT)
            }
        }
    }

    private data class LanguageOption(val label: String, val code: String)
}
