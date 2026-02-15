package com.knowyourphone.app.privacy

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.knowyourphone.app.model.UiNode
import kotlinx.coroutines.tasks.await

data class VisualRedaction(
    val bounds: Rect,
    val type: String,
    val label: String
)

object VisualPiiDetector {

    private val faceDetector by lazy {
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .build()
        FaceDetection.getClient(options)
    }

    private val barcodeScanner by lazy {
        val options = BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()
        BarcodeScanning.getClient(options)
    }

    private val textRecognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    suspend fun detectFaces(bitmap: Bitmap): List<VisualRedaction> {
        val inputImage = InputImage.fromBitmap(bitmap, 0)
        val faces = faceDetector.process(inputImage).await()
        return faces.mapNotNull { face ->
            face.boundingBox?.let { box ->
                VisualRedaction(box, "FACE", "FACE")
            }
        }
    }

    suspend fun detectPiiQrCodes(bitmap: Bitmap): List<VisualRedaction> {
        val inputImage = InputImage.fromBitmap(bitmap, 0)
        val barcodes = barcodeScanner.process(inputImage).await()
        return barcodes.mapNotNull { barcode ->
            val rawValue = barcode.rawValue ?: return@mapNotNull null
            val boundingBox = barcode.boundingBox ?: return@mapNotNull null

            // Check if QR content contains PII
            val hasPii = rawValue.startsWith("upi://", ignoreCase = true) ||
                PiiRedactor.scan(rawValue).isNotEmpty()

            if (hasPii) {
                VisualRedaction(boundingBox, "QR_CODE", "QR CODE")
            } else null
        }
    }

    /**
     * Full-bitmap OCR: runs text recognition on the entire screen, then checks
     * each text block against PII patterns. Catches PII rendered inside images,
     * screenshots, chat bubbles, or any view type the accessibility tree doesn't
     * expose as text.
     */
    suspend fun ocrScan(bitmap: Bitmap, alreadyRedactedNodeIds: Set<String>, nodes: List<UiNode>): List<VisualRedaction> {
        val inputImage = InputImage.fromBitmap(bitmap, 0)
        val visionText = textRecognizer.process(inputImage).await()

        // Collect bounds of nodes already redacted by text layers so we skip duplicates
        val redactedRects = nodes
            .filter { it.id in alreadyRedactedNodeIds }
            .map { Rect(it.bounds.left, it.bounds.top, it.bounds.right, it.bounds.bottom) }

        val results = mutableListOf<VisualRedaction>()

        for (block in visionText.textBlocks) {
            val piiMatches = PiiRedactor.scan(block.text)
            if (piiMatches.isEmpty()) continue

            val blockBox = block.boundingBox ?: continue

            // Skip if this region overlaps with an already-redacted node
            if (redactedRects.any { it.intersect(blockBox) }) continue

            val label = piiMatches.first().type.placeholder
            results.add(VisualRedaction(blockBox, piiMatches.first().type.name, label))
        }

        return results
    }
}
