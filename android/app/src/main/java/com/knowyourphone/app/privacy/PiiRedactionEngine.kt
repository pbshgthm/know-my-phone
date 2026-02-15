package com.knowyourphone.app.privacy

import android.graphics.*
import com.knowyourphone.app.model.UiSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

data class FullRedactionResult(
    val redactedSnapshot: UiSnapshot,
    val redactedBitmap: Bitmap,
    val summaries: List<RedactionSummary>,
    val visualRedactions: List<VisualRedaction>,
    val hasPii: Boolean
)

object PiiRedactionEngine {

    suspend fun redact(bitmap: Bitmap, uiTree: UiSnapshot): FullRedactionResult =
        coroutineScope {
            // Text path (Layer 0 + Layer 1)
            val textResult = async(Dispatchers.Default) {
                PiiRedactor.redactUiTree(uiTree)
            }

            // Visual path (Layer 2) — faces and QR run in parallel with text
            val faceResult = async(Dispatchers.Default) {
                VisualPiiDetector.detectFaces(bitmap)
            }
            val qrResult = async(Dispatchers.Default) {
                VisualPiiDetector.detectPiiQrCodes(bitmap)
            }

            // OCR needs text results first to skip already-redacted regions
            val textRedaction = textResult.await()
            val redactedNodeIds = textRedaction.nodeRedactions.map { it.nodeId }.toSet()

            val ocrResult = async(Dispatchers.Default) {
                VisualPiiDetector.ocrScan(bitmap, redactedNodeIds, uiTree.nodes)
            }

            val visualRedactions = faceResult.await() + qrResult.await() + ocrResult.await()

            val finalBitmap = applyAllRedactions(bitmap, textRedaction.nodeRedactions, visualRedactions)

            FullRedactionResult(
                redactedSnapshot = textRedaction.redactedSnapshot,
                redactedBitmap = finalBitmap,
                summaries = buildMergedSummaries(textRedaction, visualRedactions),
                visualRedactions = visualRedactions,
                hasPii = textRedaction.nodeRedactions.isNotEmpty() || visualRedactions.isNotEmpty()
            )
        }

    private fun applyAllRedactions(
        bitmap: Bitmap,
        nodeRedactions: List<NodeRedaction>,
        visualRedactions: List<VisualRedaction>
    ): Bitmap {
        if (nodeRedactions.isEmpty() && visualRedactions.isEmpty()) return bitmap

        // Start with text-based redactions
        val mutable = if (nodeRedactions.isNotEmpty()) {
            PiiRedactor.redactBitmap(bitmap, nodeRedactions)
        } else {
            bitmap.copy(Bitmap.Config.ARGB_8888, true)
        }

        if (visualRedactions.isEmpty()) return mutable

        val canvas = Canvas(mutable)

        val fillPaint = Paint().apply {
            style = Paint.Style.FILL
            color = Color.BLACK
        }

        val blurPaint = Paint().apply {
            style = Paint.Style.FILL
            color = Color.DKGRAY
        }

        val textPaint = Paint().apply {
            style = Paint.Style.FILL
            color = Color.WHITE
            isAntiAlias = true
            typeface = Typeface.MONOSPACE
        }

        for (vr in visualRedactions) {
            val b = vr.bounds
            if (b.right <= b.left || b.bottom <= b.top) continue
            if (b.right < 0 || b.bottom < 0 || b.left >= mutable.width || b.top >= mutable.height) continue

            val rect = RectF(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat())

            // Faces get a gray fill to indicate blur; QR/OCR get black fill
            val paint = if (vr.type == "FACE") blurPaint else fillPaint
            canvas.drawRect(rect, paint)

            // Draw label with REDACTED: prefix so model can identify it
            val label = "[REDACTED: ${vr.label}]"
            val rectHeight = b.height()
            val rectWidth = b.width()

            var textSize = (rectHeight * 0.15f).coerceAtLeast(8f)
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

    private fun buildMergedSummaries(
        textRedaction: RedactionResult,
        visualRedactions: List<VisualRedaction>
    ): List<RedactionSummary> {
        val summaries = textRedaction.summaries.toMutableList()

        val visualGrouped = visualRedactions.groupBy { it.type }
        for ((type, items) in visualGrouped) {
            summaries.add(
                RedactionSummary(
                    type = "[REDACTED:$type]",
                    count = items.size,
                    nodeIds = emptyList()
                )
            )
        }

        return summaries
    }
}
