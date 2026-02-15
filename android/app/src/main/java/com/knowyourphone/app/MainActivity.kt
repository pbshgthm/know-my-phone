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
import android.graphics.drawable.GradientDrawable
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
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
import com.knowyourphone.app.i18n.LanguageManager

class MainActivity : AppCompatActivity() {
    companion object {
        private const val REQUEST_MIC = 100

        // Palette
        private const val COLOR_BG = 0xFFF8F9FA.toInt()
        private const val COLOR_SURFACE = 0xFFFFFFFF.toInt()
        private const val COLOR_PRIMARY = 0xFF111827.toInt()
        private const val COLOR_ACCENT = 0xFF4F46E5.toInt()
        private const val COLOR_TEXT_PRIMARY = 0xFF111827.toInt()
        private const val COLOR_TEXT_SECONDARY = 0xFF6B7280.toInt()
        private const val COLOR_TEXT_TERTIARY = 0xFF9CA3AF.toInt()
        private const val COLOR_BORDER = 0xFFE5E7EB.toInt()
        private const val COLOR_WARNING_BG = 0xFFFEF3C7.toInt()
        private const val COLOR_WARNING_BORDER = 0xFFFCD34D.toInt()
        private const val COLOR_WARNING_TEXT = 0xFF92400E.toInt()

        private const val CIRCLE_SIZE_DP = 44
        private const val CIRCLE_SPACING_DP = 12
        private const val COLOR_CIRCLE_BG = 0xFF1E1E2E.toInt()
        private const val COLOR_CIRCLE_BORDER = 0xFF6B7280.toInt()
        private const val COLOR_CIRCLE_TEXT_MUTED = 0xFF9CA3AF.toInt()
    }

    private lateinit var coordinatorLayout: CoordinatorLayout
    private lateinit var permissionsCard: MaterialCardView
    private lateinit var micRow: LinearLayout
    private lateinit var overlayRow: LinearLayout
    private lateinit var accessibilityRow: LinearLayout
    private lateinit var startResetBtn: MaterialButton
    private lateinit var stopBtn: MaterialButton
    private lateinit var autoShareSwitch: MaterialSwitch

    // Views that need localization updates
    private lateinit var titleText: TextView
    private lateinit var subtitleText: TextView
    private lateinit var permissionsTitle: TextView
    private lateinit var languageLabel: TextView
    private lateinit var autoShareTitle: TextView
    private lateinit var autoShareSubtitle: TextView

    private var selectedLanguageCode = "en"
    private val circleViews = mutableListOf<TextView>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LanguageManager.init(this)
        val prefs = getSharedPreferences("kyp_prefs", MODE_PRIVATE)
        selectedLanguageCode = prefs.getString("language_code", "en") ?: "en"
        buildUi()
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStatus()
    }

    private fun buildUi() {
        val pad = dp(20)
        val appStrings = LanguageManager.getAppStrings(selectedLanguageCode)

        coordinatorLayout = CoordinatorLayout(this).apply {
            setBackgroundColor(COLOR_BG)
        }

        val scrollView = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            clipToPadding = false
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, dp(56), pad, dp(40))
        }

        // --- Header ---
        titleText = TextView(this).apply {
            text = appStrings.title
            textSize = 26f
            setTextColor(COLOR_TEXT_PRIMARY)
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
        }
        root.addView(titleText)

        subtitleText = TextView(this).apply {
            text = appStrings.subtitle
            textSize = 14f
            setTextColor(COLOR_TEXT_SECONDARY)
            setPadding(0, dp(4), 0, dp(32))
        }
        root.addView(subtitleText)

        // --- Permissions Card (shown only when needed) ---
        permissionsCard = MaterialCardView(this).apply {
            radius = dp(16f)
            cardElevation = 0f
            strokeWidth = dp(1)
            strokeColor = COLOR_WARNING_BORDER
            setCardBackgroundColor(COLOR_WARNING_BG)
        }

        val permContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        permissionsTitle = TextView(this).apply {
            text = appStrings.permissions
            textSize = 13f
            setTextColor(COLOR_WARNING_TEXT)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(0, 0, 0, dp(8))
        }
        permContent.addView(permissionsTitle)

        micRow = makePermissionRow(appStrings.micLabel, appStrings.micSubtitle, appStrings.grant) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MIC
            )
        }
        permContent.addView(micRow)

        overlayRow = makePermissionRow(appStrings.overlayLabel, appStrings.overlaySubtitle, appStrings.grant) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        permContent.addView(overlayRow)

        accessibilityRow = makePermissionRow(appStrings.accessibilityLabel, appStrings.accessibilitySubtitle, appStrings.grant) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        permContent.addView(accessibilityRow)

        permissionsCard.addView(permContent)
        root.addView(permissionsCard, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            bottomMargin = dp(20)
        })

        // --- Language circle selector ---
        languageLabel = TextView(this).apply {
            text = appStrings.language
            textSize = 13f
            setTextColor(COLOR_TEXT_SECONDARY)
            setPadding(dp(4), 0, 0, dp(6))
        }
        root.addView(languageLabel)

        val scrollContainer = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
        }

        val circleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }

        val allLanguages = LanguageManager.allLanguages()
        for (lang in allLanguages) {
            val circle = makeCircleView(lang.letter, lang.code == selectedLanguageCode)
            circle.setOnClickListener {
                onLanguageCircleTapped(lang.code)
            }
            circleViews.add(circle)
            val lp = LinearLayout.LayoutParams(dp(CIRCLE_SIZE_DP), dp(CIRCLE_SIZE_DP))
            if (circleRow.childCount > 0) lp.leftMargin = dp(CIRCLE_SPACING_DP)
            circleRow.addView(circle, lp)
        }

        scrollContainer.addView(circleRow)
        root.addView(scrollContainer, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            bottomMargin = dp(20)
        })

        // --- Auto-share toggle ---
        val autoShareRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, dp(4), dp(24))
        }

        val autoShareTextColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        }
        autoShareTitle = TextView(this).apply {
            text = appStrings.autoShare
            textSize = 15f
            setTextColor(COLOR_TEXT_PRIMARY)
        }
        autoShareTextColumn.addView(autoShareTitle)
        autoShareSubtitle = TextView(this).apply {
            text = appStrings.autoShareSub
            textSize = 12f
            setTextColor(COLOR_TEXT_TERTIARY)
            setPadding(0, dp(2), 0, 0)
        }
        autoShareTextColumn.addView(autoShareSubtitle)

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
        root.addView(autoShareRow)

        // --- Action Buttons ---
        startResetBtn = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonStyle).apply {
            text = appStrings.startAssistant
            setBackgroundColor(COLOR_PRIMARY)
            setTextColor(Color.WHITE)
            cornerRadius = dp(12)
            textSize = 15f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            isAllCaps = false
            insetTop = 0
            insetBottom = 0
            minimumHeight = dp(48)
            setOnClickListener { onStartOrResetClicked() }
        }
        root.addView(startResetBtn, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        stopBtn = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = appStrings.stop
            setTextColor(COLOR_TEXT_SECONDARY)
            strokeColor = ColorStateList.valueOf(COLOR_BORDER)
            cornerRadius = dp(12)
            textSize = 14f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            isAllCaps = false
            rippleColor = ColorStateList.valueOf(0x11111827)
            insetTop = 0
            insetBottom = 0
            minimumHeight = dp(48)
            setOnClickListener {
                OverlayService.stop(this@MainActivity)
                showSnackbar(LanguageManager.getAppStrings(selectedLanguageCode).stopped)
            }
        }
        root.addView(stopBtn, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            topMargin = dp(8)
        })

        scrollView.addView(root, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        coordinatorLayout.addView(scrollView, CoordinatorLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        setContentView(coordinatorLayout)
    }

    private fun makeCircleView(letter: String, selected: Boolean): TextView {
        val size = dp(CIRCLE_SIZE_DP)
        return TextView(this).apply {
            text = letter
            textSize = 18f
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            background = makeCircleDrawable(selected)
            setTextColor(if (selected) Color.WHITE else COLOR_CIRCLE_TEXT_MUTED)
        }
    }

    private fun makeCircleDrawable(selected: Boolean): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            if (selected) {
                setColor(COLOR_ACCENT)
            } else {
                setColor(COLOR_CIRCLE_BG)
                setStroke(dp(1), COLOR_CIRCLE_BORDER)
            }
        }
    }

    private fun onLanguageCircleTapped(code: String) {
        selectedLanguageCode = code
        val prefs = getSharedPreferences("kyp_prefs", MODE_PRIVATE)
        prefs.edit().putString("language_code", code).apply()
        OverlayService.instance?.setLanguage(code)

        // Update circle selection states
        val allLanguages = LanguageManager.allLanguages()
        for (i in circleViews.indices) {
            val isSelected = allLanguages[i].code == code
            circleViews[i].background = makeCircleDrawable(isSelected)
            circleViews[i].setTextColor(if (isSelected) Color.WHITE else COLOR_CIRCLE_TEXT_MUTED)
        }

        // Update all localized text
        updateLocalizedText()
    }

    private fun updateLocalizedText() {
        val s = LanguageManager.getAppStrings(selectedLanguageCode)
        titleText.text = s.title
        subtitleText.text = s.subtitle
        permissionsTitle.text = s.permissions
        languageLabel.text = s.language
        autoShareTitle.text = s.autoShare
        autoShareSubtitle.text = s.autoShareSub
        stopBtn.text = s.stop

        // Update start/reset button
        startResetBtn.text = if (OverlayService.instance != null) s.resetSession else s.startAssistant
    }

    private fun makePermissionRow(label: String, subtitle: String, grantText: String, onClick: () -> Unit): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(6))
        }

        val textColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        }
        textColumn.addView(TextView(this).apply {
            text = label
            textSize = 14f
            setTextColor(COLOR_TEXT_PRIMARY)
        })
        textColumn.addView(TextView(this).apply {
            text = subtitle
            textSize = 11f
            setTextColor(COLOR_TEXT_TERTIARY)
        })

        val btn = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = grantText
            textSize = 12f
            isAllCaps = false
            cornerRadius = dp(8)
            strokeColor = ColorStateList.valueOf(COLOR_WARNING_BORDER)
            setTextColor(COLOR_WARNING_TEXT)
            insetTop = 0
            insetBottom = 0
            minHeight = dp(32)
            minimumHeight = dp(32)
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

        // Update button text based on service state
        val s = LanguageManager.getAppStrings(selectedLanguageCode)
        startResetBtn.text = if (OverlayService.instance != null) s.resetSession else s.startAssistant
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
        val s = LanguageManager.getAppStrings(selectedLanguageCode)

        if (!hasMic || !hasOverlay || !hasAccessibility) {
            showSnackbar(s.permissionsFirst)
            return
        }

        if (OverlayService.instance == null) {
            OverlayService.start(this)
        } else {
            OverlayService.instance?.resetSession()
            showSnackbar(s.sessionReset)
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
}
