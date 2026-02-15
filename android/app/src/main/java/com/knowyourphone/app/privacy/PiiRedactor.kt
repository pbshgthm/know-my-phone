package com.knowyourphone.app.privacy

import android.graphics.*
import android.text.InputType
import com.knowyourphone.app.model.Bounds
import com.knowyourphone.app.model.UiNode
import com.knowyourphone.app.model.UiSnapshot

enum class PiiType(val placeholder: String) {
    EMAIL_ADDRESS("[REDACTED:EMAIL_ADDRESS]"),
    CREDIT_CARD("[REDACTED:CREDIT_CARD]"),
    IP_ADDRESS("[REDACTED:IP_ADDRESS]"),
    URL_WITH_AUTH("[REDACTED:URL_WITH_AUTH]"),
    PHONE_NUMBER("[REDACTED:PHONE_NUMBER]"),
    AADHAAR("[REDACTED:AADHAAR]"),
    PAN("[REDACTED:PAN]"),
    UPI_ID("[REDACTED:UPI_ID]"),
    INDIAN_PHONE("[REDACTED:INDIAN_PHONE]"),
    IFSC("[REDACTED:IFSC]"),
    VEHICLE_REG("[REDACTED:VEHICLE_REG]"),
    GSTIN("[REDACTED:GSTIN]"),
    SENSITIVE_FIELD("[REDACTED:SENSITIVE_FIELD]")
}

data class PiiMatch(
    val type: PiiType,
    val start: Int,
    val end: Int,
    val value: String
)

data class NodeRedaction(
    val nodeId: String,
    val bounds: Bounds,
    val matches: List<PiiMatch>
)

data class RedactionSummary(
    val type: String,
    val count: Int,
    val nodeIds: List<String>
)

data class RedactionResult(
    val redactedSnapshot: UiSnapshot,
    val nodeRedactions: List<NodeRedaction>,
    val summaries: List<RedactionSummary>
)

object PiiRedactor {

    // --- UPI handle allowlist ---
    private val upiHandles = setOf(
        "ybl", "okhdfcbank", "okaxis", "paytm", "upi", "ibl", "axl", "sbi",
        "apl", "freecharge", "icici", "kotak", "hdfcbank", "axisbank", "indus",
        "federal", "boi", "cbin", "pnb", "unionbank", "dbs", "rbl", "kvb",
        "dlb", "kbl", "jkb"
    )

    // --- Indian RTO state codes ---
    private val rtoStateCodes = setOf(
        "AN", "AP", "AR", "AS", "BR", "CG", "CH", "DD", "DL", "DN", "GA",
        "GJ", "HP", "HR", "JH", "JK", "KA", "KL", "LA", "LD", "MH", "ML",
        "MN", "MP", "MZ", "NL", "OD", "PB", "PY", "RJ", "SK", "TN", "TR",
        "TS", "UK", "UP", "WB"
    )

    // --- Sensitive resource ID keywords ---
    private val sensitiveKeywords = listOf(
        "aadhaar", "pan", "cvv", "otp", "password", "pin", "ssn", "account"
    )

    // --- Patterns ordered: UPI before email to prevent clash ---
    private val patterns: List<Pair<PiiType, Regex>> = listOf(
        // 1. UPI ID (must run before email)
        PiiType.UPI_ID to Regex("[\\w.\\-]+@[a-z]{2,64}"),
        // 2. Email (catches non-UPI @ patterns)
        PiiType.EMAIL_ADDRESS to Regex("[a-zA-Z0-9._%+\\-]+@[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,}"),
        // 3. Aadhaar (12 digits in groups of 4)
        PiiType.AADHAAR to Regex("\\b\\d{4}[\\s-]?\\d{4}[\\s-]?\\d{4}\\b"),
        // 4. PAN (ABCDE1234F format, 4th char is entity type)
        PiiType.PAN to Regex("\\b[A-Z]{3}[ABCFGHLJPT][A-Z]\\d{4}[A-Z]\\b"),
        // 5. Credit card
        PiiType.CREDIT_CARD to Regex("\\b(?:\\d[ \\-]*?){13,19}\\b"),
        // 6a. Indian phone - international format
        PiiType.INDIAN_PHONE to Regex("\\+\\d{1,3}[\\s.\\-]?\\d[\\d\\s.\\-]{5,13}\\d"),
        // 6b. Indian phone - bare mobile (starts with 6-9)
        PiiType.INDIAN_PHONE to Regex("\\b[6-9]\\d{3}[\\s.\\-]?\\d{3}[\\s.\\-]?\\d{3,4}\\b"),
        // 6c. Indian phone - landline with STD code
        PiiType.INDIAN_PHONE to Regex("\\b0\\d{2,4}[\\s.\\-]?\\d{6,8}\\b"),
        // 7. IFSC (5th char must be 0)
        PiiType.IFSC to Regex("\\b[A-Z]{4}0[A-Z0-9]{6}\\b"),
        // 8. Vehicle registration
        PiiType.VEHICLE_REG to Regex("\\b[A-Z]{2}[\\s-]?\\d{1,2}[\\s-]?[A-Z]{1,3}[\\s-]?\\d{4}\\b"),
        // 9. GSTIN (15 chars)
        PiiType.GSTIN to Regex("\\b\\d{2}[A-Z]{5}\\d{4}[A-Z]\\d[Z][A-Z\\d]\\b"),
        // 10. IP address
        PiiType.IP_ADDRESS to Regex("\\b(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\b"),
        // 11. URL with auth
        PiiType.URL_WITH_AUTH to Regex("https?://[^@\\s]+@[^\\s]+|https?://[^\\s]*[?&](?:auth|token|key|secret|password|api_key)=[^&\\s]+")
    )

    // --- Layer 0: Metadata heuristics ---

    private fun checkMetadata(node: UiNode): Boolean {
        // Password field
        if (node.isPassword == true) return true

        // Input type flags
        val inputType = node.inputType
        if (inputType != null) {
            val variation = inputType and InputType.TYPE_MASK_VARIATION
            val cls = inputType and InputType.TYPE_MASK_CLASS
            if (variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
                variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
                cls == InputType.TYPE_CLASS_PHONE
            ) return true
        }

        // Sensitive resource ID keywords
        val resId = node.resourceId
        if (resId != null) {
            val lower = resId.lowercase()
            if (sensitiveKeywords.any { lower.contains(it) }) return true
        }

        return false
    }

    // --- Layer 1: Regex scanning ---

    fun scan(text: String): List<PiiMatch> {
        if (text.isBlank()) return emptyList()

        val allMatches = mutableListOf<PiiMatch>()
        for ((type, regex) in patterns) {
            for (result in regex.findAll(text)) {
                val value = result.value

                // Post-filter: UPI ID must have known handle suffix
                if (type == PiiType.UPI_ID) {
                    val suffix = value.substringAfter("@").lowercase()
                    if (suffix !in upiHandles) continue
                }

                // Post-filter: credit card must pass Luhn
                if (type == PiiType.CREDIT_CARD) {
                    val digits = value.replace(Regex("[^\\d]"), "")
                    if (digits.length < 13 || !passesLuhn(digits)) continue
                }

                // Post-filter: Aadhaar must pass Verhoeff checksum
                if (type == PiiType.AADHAAR) {
                    val digits = value.replace(Regex("[^\\d]"), "")
                    if (digits.length != 12 || !passesVerhoeff(digits)) continue
                }

                // Post-filter: Indian phone must have 10-12 digits
                if (type == PiiType.INDIAN_PHONE) {
                    val digits = value.replace(Regex("[^\\d]"), "")
                    if (digits.length < 10 || digits.length > 12) continue
                }

                // Post-filter: IP address octets must be 0-255
                if (type == PiiType.IP_ADDRESS) {
                    val octets = result.groupValues.drop(1)
                    if (octets.any { it.toIntOrNull()?.let { v -> v > 255 } != false }) continue
                }

                // Post-filter: Vehicle registration state code must be valid
                if (type == PiiType.VEHICLE_REG) {
                    val stateCode = value.take(2).uppercase()
                    if (stateCode !in rtoStateCodes) continue
                }

                // Post-filter: GSTIN state code must be 01-37
                if (type == PiiType.GSTIN) {
                    val stateNum = value.take(2).toIntOrNull()
                    if (stateNum == null || stateNum < 1 || stateNum > 37) continue
                }

                allMatches.add(PiiMatch(type, result.range.first, result.range.last + 1, value))
            }
        }
        allMatches.sortWith(compareBy({ it.start }, { -(it.end - it.start) }))
        return removeOverlaps(allMatches)
    }

    fun redactUiTree(snapshot: UiSnapshot): RedactionResult {
        val nodeRedactions = mutableListOf<NodeRedaction>()
        val redactedNodes = snapshot.nodes.map { node ->
            // Layer 0: metadata heuristics — redact entire node if sensitive
            val metadataRedact = checkMetadata(node)
            if (metadataRedact && (!node.text.isNullOrBlank() || !node.contentDescription.isNullOrBlank())) {
                val matches = listOf(
                    PiiMatch(
                        PiiType.SENSITIVE_FIELD,
                        0,
                        (node.text ?: node.contentDescription ?: "").length,
                        node.text ?: node.contentDescription ?: ""
                    )
                )
                nodeRedactions.add(NodeRedaction(node.id, node.bounds, matches))
                return@map node.copy(
                    text = if (!node.text.isNullOrBlank()) PiiType.SENSITIVE_FIELD.placeholder else node.text,
                    contentDescription = if (!node.contentDescription.isNullOrBlank()) PiiType.SENSITIVE_FIELD.placeholder else node.contentDescription
                )
            }

            // Layer 1: regex scan on text and contentDescription
            val textMatches = if (!node.text.isNullOrBlank()) scan(node.text) else emptyList()
            val cdMatches = if (!node.contentDescription.isNullOrBlank()) scan(node.contentDescription) else emptyList()

            if (textMatches.isEmpty() && cdMatches.isEmpty()) return@map node

            val allMatches = textMatches + cdMatches
            nodeRedactions.add(NodeRedaction(node.id, node.bounds, allMatches))

            node.copy(
                text = if (textMatches.isNotEmpty()) redactText(node.text!!, textMatches) else node.text,
                contentDescription = if (cdMatches.isNotEmpty()) redactText(node.contentDescription!!, cdMatches) else node.contentDescription
            )
        }

        val redactedSnapshot = snapshot.copy(nodes = redactedNodes)
        val summaries = buildSummaries(nodeRedactions)
        return RedactionResult(redactedSnapshot, nodeRedactions, summaries)
    }

    fun redactBitmap(bitmap: Bitmap, nodeRedactions: List<NodeRedaction>): Bitmap {
        if (nodeRedactions.isEmpty()) return bitmap

        val mutable = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(mutable)

        val fillPaint = Paint().apply {
            style = Paint.Style.FILL
            color = Color.BLACK
        }

        val textPaint = Paint().apply {
            style = Paint.Style.FILL
            color = Color.WHITE
            isAntiAlias = true
            typeface = Typeface.MONOSPACE
        }

        for (nr in nodeRedactions) {
            val b = nr.bounds
            if (b.right <= b.left || b.bottom <= b.top) continue
            if (b.right < 0 || b.bottom < 0 || b.left >= mutable.width || b.top >= mutable.height) continue

            val rect = RectF(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat())
            canvas.drawRect(rect, fillPaint)

            val label = nr.matches.firstOrNull()?.type?.placeholder ?: "[REDACTED]"
            val rectHeight = b.bottom - b.top
            val rectWidth = b.right - b.left

            var textSize = (rectHeight * 0.7f).coerceAtLeast(8f)
            textPaint.textSize = textSize
            val textWidth = textPaint.measureText(label)
            if (textWidth > rectWidth - 4) {
                textSize *= (rectWidth - 4) / textWidth
                textPaint.textSize = textSize.coerceAtLeast(6f)
            }

            val textMetrics = textPaint.fontMetrics
            val textY = b.top + (rectHeight - textMetrics.ascent - textMetrics.descent) / 2f
            val textX = b.left + (rectWidth - textPaint.measureText(label)) / 2f
            canvas.drawText(label, textX, textY, textPaint)
        }

        return mutable
    }

    // --- Luhn checksum ---

    private fun passesLuhn(digits: String): Boolean {
        var sum = 0
        var alternate = false
        for (i in digits.length - 1 downTo 0) {
            var n = digits[i] - '0'
            if (alternate) {
                n *= 2
                if (n > 9) n -= 9
            }
            sum += n
            alternate = !alternate
        }
        return sum % 10 == 0
    }

    // --- Verhoeff checksum (for Aadhaar validation) ---

    private val verhoeffD = arrayOf(
        intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9),
        intArrayOf(1, 2, 3, 4, 0, 6, 7, 8, 9, 5),
        intArrayOf(2, 3, 4, 0, 1, 7, 8, 9, 5, 6),
        intArrayOf(3, 4, 0, 1, 2, 8, 9, 5, 6, 7),
        intArrayOf(4, 0, 1, 2, 3, 9, 5, 6, 7, 8),
        intArrayOf(5, 9, 8, 7, 6, 0, 4, 3, 2, 1),
        intArrayOf(6, 5, 9, 8, 7, 1, 0, 4, 3, 2),
        intArrayOf(7, 6, 5, 9, 8, 2, 1, 0, 4, 3),
        intArrayOf(8, 7, 6, 5, 9, 3, 2, 1, 0, 4),
        intArrayOf(9, 8, 7, 6, 5, 4, 3, 2, 1, 0)
    )

    private val verhoeffP = arrayOf(
        intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9),
        intArrayOf(1, 5, 7, 6, 2, 8, 3, 0, 9, 4),
        intArrayOf(5, 8, 0, 3, 7, 9, 6, 1, 4, 2),
        intArrayOf(8, 9, 1, 6, 0, 4, 3, 5, 2, 7),
        intArrayOf(9, 4, 5, 3, 1, 2, 6, 8, 7, 0),
        intArrayOf(4, 2, 8, 6, 5, 7, 3, 9, 0, 1),
        intArrayOf(2, 7, 9, 3, 8, 0, 6, 4, 1, 5),
        intArrayOf(7, 0, 4, 6, 9, 1, 3, 2, 5, 8)
    )

    private val verhoeffInv = intArrayOf(0, 4, 3, 2, 1, 5, 6, 7, 8, 9)

    private fun passesVerhoeff(digits: String): Boolean {
        var c = 0
        val len = digits.length
        for (i in len - 1 downTo 0) {
            val ni = len - i - 1
            val digit = digits[i] - '0'
            c = verhoeffD[c][verhoeffP[ni % 8][digit]]
        }
        return c == 0
    }

    // --- Helpers ---

    private fun redactText(text: String, matches: List<PiiMatch>): String {
        val sb = StringBuilder()
        var lastEnd = 0
        for (m in matches) {
            if (m.start > lastEnd) sb.append(text, lastEnd, m.start)
            sb.append(m.type.placeholder)
            lastEnd = m.end
        }
        if (lastEnd < text.length) sb.append(text, lastEnd, text.length)
        return sb.toString()
    }

    private fun removeOverlaps(sorted: List<PiiMatch>): List<PiiMatch> {
        val result = mutableListOf<PiiMatch>()
        var lastEnd = 0
        for (m in sorted) {
            if (m.start >= lastEnd) {
                result.add(m)
                lastEnd = m.end
            }
        }
        return result
    }

    private fun buildSummaries(nodeRedactions: List<NodeRedaction>): List<RedactionSummary> {
        val grouped = mutableMapOf<PiiType, MutableSet<String>>()
        for (nr in nodeRedactions) {
            for (m in nr.matches) {
                grouped.getOrPut(m.type) { mutableSetOf() }.add(nr.nodeId)
            }
        }
        return grouped.map { (type, nodeIds) ->
            RedactionSummary(
                type = type.placeholder,
                count = nodeRedactions.sumOf { nr -> nr.matches.count { it.type == type } },
                nodeIds = nodeIds.toList()
            )
        }
    }
}
