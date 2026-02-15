package com.knowyourphone.app

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.accessibility.AccessibilityManager
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputLayout

class MainActivity : AppCompatActivity() {
    companion object {
        private const val REQUEST_MIC = 100

        // Palette
        private const val COLOR_BG = 0xFFFAFAFA.toInt()
        private const val COLOR_SURFACE = 0xFFFFFFFF.toInt()
        private const val COLOR_PRIMARY = 0xFF1A1A2E.toInt()
        private const val COLOR_ACCENT = 0xFF4361EE.toInt()
        private const val COLOR_TEXT_PRIMARY = 0xFF1A1A2E.toInt()
        private const val COLOR_TEXT_SECONDARY = 0xFF6B7280.toInt()
        private const val COLOR_TEXT_TERTIARY = 0xFF9CA3AF.toInt()
        private const val COLOR_BORDER = 0xFFE5E7EB.toInt()
        private const val COLOR_WARNING_BG = 0xFFFFF7ED.toInt()
        private const val COLOR_WARNING_BORDER = 0xFFFED7AA.toInt()
        private const val COLOR_WARNING_TEXT = 0xFF9A3412.toInt()
    }

    private lateinit var coordinatorLayout: CoordinatorLayout
    private lateinit var permissionsCard: MaterialCardView
    private lateinit var micRow: LinearLayout
    private lateinit var overlayRow: LinearLayout
    private lateinit var accessibilityRow: LinearLayout
    private lateinit var startResetBtn: MaterialButton
    private lateinit var autoShareSwitch: MaterialSwitch
    private lateinit var languageDropdown: MaterialAutoCompleteTextView

    private val languages = listOf(
        LanguageOption("English", "en"),
        LanguageOption("Tamil", "ta"),
        LanguageOption("Hindi", "hi"),
        LanguageOption("Kannada", "kn"),
        LanguageOption("Telugu", "te")
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStatus()
    }

    private fun buildUi() {
        val pad = dp(24)

        coordinatorLayout = CoordinatorLayout(this).apply {
            setBackgroundColor(COLOR_BG)
        }

        val scrollView = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            clipToPadding = false
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, dp(48), pad, dp(32))
        }

        // --- Header ---
        root.addView(TextView(this).apply {
            text = "Know Your Phone"
            textSize = 28f
            setTextColor(COLOR_TEXT_PRIMARY)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setPadding(0, 0, 0, dp(4))
        })

        root.addView(TextView(this).apply {
            text = "Voice-first assistant for screen help"
            textSize = 15f
            setTextColor(COLOR_TEXT_SECONDARY)
            setPadding(0, 0, 0, dp(28))
        })

        // --- Settings Card ---
        val settingsCard = makeCard()
        val settingsContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }

        // Section label
        settingsContent.addView(TextView(this).apply {
            text = "SETTINGS"
            textSize = 11f
            setTextColor(COLOR_TEXT_TERTIARY)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            letterSpacing = 0.1f
            setPadding(0, 0, 0, dp(16))
        })

        // Language dropdown
        settingsContent.addView(TextView(this).apply {
            text = "Language"
            textSize = 14f
            setTextColor(COLOR_TEXT_SECONDARY)
            setPadding(0, 0, 0, dp(6))
        })

        val textInputLayout = TextInputLayout(this, null, com.google.android.material.R.attr.textInputOutlinedExposedDropdownMenuStyle).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                bottomMargin = dp(20)
            }
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            boxBackgroundColor = COLOR_SURFACE
            boxStrokeColor = COLOR_BORDER
            boxStrokeWidthFocused = dp(2)
            setBoxCornerRadii(dp(12f), dp(12f), dp(12f), dp(12f))
        }

        languageDropdown = MaterialAutoCompleteTextView(textInputLayout.context).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            setTextColor(COLOR_TEXT_PRIMARY)
            textSize = 16f
            inputType = 0 // Non-editable
        }

        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, languages.map { it.label })
        languageDropdown.setAdapter(adapter)

        textInputLayout.addView(languageDropdown)
        settingsContent.addView(textInputLayout)

        // Divider
        settingsContent.addView(makeDivider())

        // Auto-share toggle
        val autoShareRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(16), 0, dp(8))
        }

        val autoShareTextColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        }
        autoShareTextColumn.addView(TextView(this).apply {
            text = "Auto-share screen"
            textSize = 15f
            setTextColor(COLOR_TEXT_PRIMARY)
        })
        autoShareTextColumn.addView(TextView(this).apply {
            text = "Send screenshot with every voice message"
            textSize = 12f
            setTextColor(COLOR_TEXT_TERTIARY)
            setPadding(0, dp(2), 0, 0)
        })

        autoShareSwitch = MaterialSwitch(this).apply {
            val prefs = getSharedPreferences("kyp_prefs", MODE_PRIVATE)
            isChecked = prefs.getBoolean("auto_screenshot", false)
            trackTintList = ColorStateList(
                arrayOf(
                    intArrayOf(android.R.attr.state_checked),
                    intArrayOf()
                ),
                intArrayOf(COLOR_ACCENT, COLOR_BORDER)
            )
            thumbTintList = ColorStateList(
                arrayOf(
                    intArrayOf(android.R.attr.state_checked),
                    intArrayOf()
                ),
                intArrayOf(Color.WHITE, 0xFFF3F4F6.toInt())
            )
            setOnCheckedChangeListener { _, isChecked ->
                prefs.edit().putBoolean("auto_screenshot", isChecked).apply()
                OverlayService.instance?.setAutoScreenshot(isChecked)
            }
        }

        autoShareRow.addView(autoShareTextColumn)
        autoShareRow.addView(autoShareSwitch)
        settingsContent.addView(autoShareRow)

        settingsCard.addView(settingsContent)
        root.addView(settingsCard, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            bottomMargin = dp(16)
        })

        // --- Permissions Card ---
        permissionsCard = MaterialCardView(this).apply {
            radius = dp(16f)
            cardElevation = 0f
            strokeWidth = dp(1)
            strokeColor = COLOR_WARNING_BORDER
            setCardBackgroundColor(COLOR_WARNING_BG)
        }

        val permContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }

        permContent.addView(TextView(this).apply {
            text = "PERMISSIONS NEEDED"
            textSize = 11f
            setTextColor(COLOR_WARNING_TEXT)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            letterSpacing = 0.1f
            setPadding(0, 0, 0, dp(12))
        })

        micRow = makePermissionRow("Microphone", "Required for voice input") {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MIC
            )
        }
        permContent.addView(micRow)

        overlayRow = makePermissionRow("Overlay", "Required for floating assistant") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        permContent.addView(overlayRow)

        accessibilityRow = makePermissionRow("Accessibility", "Required for screen reading") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        permContent.addView(accessibilityRow)

        permissionsCard.addView(permContent)
        root.addView(permissionsCard, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            bottomMargin = dp(16)
        })

        // --- Action Buttons ---
        startResetBtn = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonStyle).apply {
            text = "Start / Reset Session"
            setBackgroundColor(COLOR_PRIMARY)
            setTextColor(Color.WHITE)
            cornerRadius = dp(12)
            textSize = 15f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            isAllCaps = false
            setPadding(dp(16), dp(14), dp(16), dp(14))
            insetTop = 0
            insetBottom = 0
            setOnClickListener { onStartOrResetClicked() }
        }
        root.addView(startResetBtn, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            topMargin = dp(8)
        })

        val stopBtn = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "Stop Agent"
            setTextColor(COLOR_TEXT_PRIMARY)
            strokeColor = ColorStateList.valueOf(COLOR_BORDER)
            cornerRadius = dp(12)
            textSize = 14f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            isAllCaps = false
            rippleColor = ColorStateList.valueOf(0x111A1A2E)
            insetTop = 0
            insetBottom = 0
            setOnClickListener {
                OverlayService.stop(this@MainActivity)
                showSnackbar("Agent stopped")
            }
        }
        root.addView(stopBtn, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            topMargin = dp(8)
        })

        // --- Set up language dropdown selection ---
        val prefs = getSharedPreferences("kyp_prefs", MODE_PRIVATE)
        val savedLang = prefs.getString("language_code", "en") ?: "en"
        val index = languages.indexOfFirst { it.code == savedLang }.let { if (it >= 0) it else 0 }
        languageDropdown.setText(languages[index].label, false)
        languageDropdown.setOnItemClickListener { _, _, position, _ ->
            val code = languages[position].code
            prefs.edit().putString("language_code", code).apply()
            OverlayService.instance?.setLanguage(code)
        }

        scrollView.addView(root, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        coordinatorLayout.addView(scrollView, CoordinatorLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        setContentView(coordinatorLayout)
    }

    private fun makeCard(): MaterialCardView {
        return MaterialCardView(this).apply {
            radius = dp(16f)
            cardElevation = 0f
            strokeWidth = dp(1)
            strokeColor = COLOR_BORDER
            setCardBackgroundColor(COLOR_SURFACE)
        }
    }

    private fun makeDivider(): View {
        return View(this).apply {
            setBackgroundColor(COLOR_BORDER)
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(1)).apply {
                topMargin = dp(4)
            }
        }
    }

    private fun makePermissionRow(label: String, subtitle: String, onClick: () -> Unit): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
        }

        val textColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        }
        textColumn.addView(TextView(this).apply {
            text = label
            textSize = 15f
            setTextColor(COLOR_TEXT_PRIMARY)
        })
        textColumn.addView(TextView(this).apply {
            text = subtitle
            textSize = 12f
            setTextColor(COLOR_TEXT_TERTIARY)
        })

        val btn = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "Grant"
            textSize = 12f
            isAllCaps = false
            cornerRadius = dp(8)
            strokeColor = ColorStateList.valueOf(COLOR_WARNING_BORDER)
            setTextColor(COLOR_WARNING_TEXT)
            insetTop = 0
            insetBottom = 0
            minHeight = dp(36)
            minimumHeight = dp(36)
            setPadding(dp(12), 0, dp(12), 0)
            setOnClickListener { onClick() }
        }

        row.addView(textColumn)
        row.addView(btn)
        return row
    }

    private fun updatePermissionStatus() {
        val hasMic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        val hasOverlay = Settings.canDrawOverlays(this)
        val hasAccessibility = isAccessibilityServiceEnabled()

        micRow.visibility = if (hasMic) View.GONE else View.VISIBLE
        overlayRow.visibility = if (hasOverlay) View.GONE else View.VISIBLE
        accessibilityRow.visibility = if (hasAccessibility) View.GONE else View.VISIBLE

        val allGranted = hasMic && hasOverlay && hasAccessibility
        startResetBtn.isEnabled = allGranted
        startResetBtn.alpha = if (allGranted) 1f else 0.4f

        permissionsCard.visibility = if (allGranted) View.GONE else View.VISIBLE
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
            showSnackbar("Grant required permissions first")
            return
        }

        if (OverlayService.instance == null) {
            OverlayService.start(this)
        } else {
            OverlayService.instance?.resetSession()
            showSnackbar("Session reset")
        }

        moveTaskToBack(true)
    }

    private fun showSnackbar(message: String) {
        Snackbar.make(coordinatorLayout, message, Snackbar.LENGTH_SHORT)
            .setBackgroundTint(COLOR_PRIMARY)
            .setTextColor(Color.WHITE)
            .show()
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

    private fun dp(value: Float): Float {
        return value * resources.displayMetrics.density
    }

    private data class LanguageOption(val label: String, val code: String)
}
