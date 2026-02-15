package com.knowyourphone.app.privacy

import android.graphics.*
import com.knowyourphone.app.model.Bounds
import com.knowyourphone.app.model.UiNode
import com.knowyourphone.app.model.UiSnapshot

enum class PiiType(val placeholder: String) {
    EMAIL_ADDRESS("[REDACTED:EMAIL_ADDRESS]"),
    CREDIT_CARD("[REDACTED:CREDIT_CARD]"),
    SSN("[REDACTED:SSN]"),
    IBAN("[REDACTED:IBAN]"),
    IP_ADDRESS("[REDACTED:IP_ADDRESS]"),
    URL_WITH_AUTH("[REDACTED:URL_WITH_AUTH]"),
    PHONE_NUMBER("[REDACTED:PHONE_NUMBER]")
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

    // Patterns ordered specific → general to minimize false positives
    private val patterns: List<Pair<PiiType, Regex>> = listOf(
        PiiType.EMAIL_ADDRESS to Regex("[a-zA-Z0-9._%+\\-]+@[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,}"),
        PiiType.CREDIT_CARD to Regex("\\b(?:\\d[ \\-]*?){13,19}\\b"),
        PiiType.SSN to Regex("\\b\\d{3}[- ]\\d{2}[- ]\\d{4}\\b"),
        PiiType.IBAN to Regex("\\b[A-Z]{2}\\d{2}[ ]?\\d{4}[ ]?\\d{4}[ ]?\\d{4}[ ]?\\d{4}[ ]?\\d{0,2}\\b"),
        PiiType.IP_ADDRESS to Regex("\\b(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\b"),
        PiiType.URL_WITH_AUTH to Regex("https?://[^@\\s]+@[^\\s]+|https?://[^\\s]*[?&](?:auth|token|key|secret|password|api_key)=[^&\\s]+"),
        // E.164 international: +<country><number> (7-15 digits total)
        PiiType.PHONE_NUMBER to Regex("\\+\\d[\\d\\s.\\-]{5,16}\\d|(?:\\(?\\d{2,4}\\)?[\\s.\\-]?)?\\d{3,4}[\\s.\\-]?\\d{3,4}\\b")
    )

    fun scan(text: String): List<PiiMatch> {
        if (text.isBlank()) return emptyList()

        val allMatches = mutableListOf<PiiMatch>()
        for ((type, regex) in patterns) {
            for (result in regex.findAll(text)) {
                val value = result.value
                // Post-filter: credit card must pass Luhn
                if (type == PiiType.CREDIT_CARD) {
                    val digits = value.replace(Regex("[^\\d]"), "")
                    if (digits.length < 13 || !passesLuhn(digits)) continue
                }
                // Post-filter: IP address octets must be 0-255
                if (type == PiiType.IP_ADDRESS) {
                    val octets = result.groupValues.drop(1)
                    if (octets.any { it.toIntOrNull()?.let { v -> v > 255 } != false }) continue
                }
                // Post-filter: phone number must have enough digits
                if (type == PiiType.PHONE_NUMBER) {
                    val digits = value.replace(Regex("[^\\d]"), "")
                    if (digits.length < 7) continue
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

        for (nr in nodeRedactions) {
            val b = nr.bounds
            // Skip zero-area or off-screen bounds
            if (b.right <= b.left || b.bottom <= b.top) continue
            if (b.right < 0 || b.bottom < 0 || b.left >= mutable.width || b.top >= mutable.height) continue

            val rect = RectF(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat())
            canvas.drawRect(rect, fillPaint)
        }

        return mutable
    }

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
