package com.knowyourphone.app

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.knowyourphone.app.model.Bounds
import com.knowyourphone.app.model.ScreenInfo
import com.knowyourphone.app.model.UiNode
import com.knowyourphone.app.model.UiSnapshot
import java.util.concurrent.Executors

class KypAccessibilityService : AccessibilityService() {
    companion object {
        private const val TAG = "KypAccessibility"
        var instance: KypAccessibilityService? = null
            private set

        fun isRunning(): Boolean = instance != null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "Accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Not actively processing events - we collect on demand
    }

    override fun onInterrupt() {
        Log.d(TAG, "Accessibility service interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        Log.d(TAG, "Accessibility service destroyed")
    }

    /**
     * Collects the current UI tree from the active window.
     */
    fun collectUiTree(): UiSnapshot? {
        val root = rootInActiveWindow ?: run {
            Log.w(TAG, "rootInActiveWindow is null")
            return null
        }

        val packageName = root.packageName?.toString() ?: "unknown"
        val nodes = mutableListOf<UiNode>()
        var nodeCounter = 0

        fun traverse(node: AccessibilityNodeInfo, depth: Int) {
            val text = node.text?.toString()
            val contentDesc = node.contentDescription?.toString()
            val resourceId = node.viewIdResourceName

            // Relaxed filter: include nodes with text, contentDescription, clickable,
            // resourceId, scrollable, or checked/selected state
            val include = !text.isNullOrBlank()
                    || !contentDesc.isNullOrBlank()
                    || node.isClickable
                    || !resourceId.isNullOrBlank()
                    || node.isScrollable
                    || node.isChecked
                    || node.isSelected

            if (include) {
                val rect = Rect()
                node.getBoundsInScreen(rect)

                val id = "n_${Integer.toHexString(nodeCounter)}"
                nodeCounter++

                nodes.add(
                    UiNode(
                        id = id,
                        text = text,
                        contentDescription = contentDesc,
                        className = node.className?.toString(),
                        clickable = node.isClickable,
                        enabled = node.isEnabled,
                        bounds = Bounds(rect.left, rect.top, rect.right, rect.bottom),
                        resourceId = resourceId,
                        checked = if (node.isCheckable) node.isChecked else null,
                        selected = if (node.isSelected) true else null,
                        focused = if (node.isFocused) true else null,
                        scrollable = if (node.isScrollable) true else null,
                        depth = depth,
                        childCount = node.childCount,
                        inputType = node.inputType.takeIf { it != 0 },
                        isPassword = if (node.isPassword) true else null
                    )
                )
            }

            for (i in 0 until node.childCount) {
                val child = node.getChild(i)
                if (child != null) {
                    traverse(child, depth + 1)
                    child.recycle()
                }
            }
        }

        traverse(root, 0)
        root.recycle()

        return UiSnapshot(
            screen = ScreenInfo(
                packageName = packageName,
                timestamp = System.currentTimeMillis()
            ),
            nodes = nodes
        )
    }

    /**
     * Look up bounds for a given element ID by re-traversing the accessibility tree.
     * Returns null if the element is no longer present (e.g. screen changed).
     */
    fun findBoundsForId(targetId: String): Bounds? {
        val snapshot = collectUiTree() ?: return null
        val node = snapshot.nodes.find { it.id == targetId }
        return node?.bounds
    }

    /**
     * Takes a screenshot using the AccessibilityService API (API 30+).
     * Returns a Bitmap via callback on success.
     */
    fun captureScreenshot(callback: (Bitmap?) -> Unit) {
        try {
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                Executors.newSingleThreadExecutor(),
                object : TakeScreenshotCallback {
                    override fun onSuccess(result: ScreenshotResult) {
                        try {
                            val hwBitmap = Bitmap.wrapHardwareBuffer(
                                result.hardwareBuffer,
                                result.colorSpace
                            )
                            // Must copy from hardware buffer to software bitmap for compress()
                            val softBitmap = hwBitmap?.copy(Bitmap.Config.ARGB_8888, false)
                            hwBitmap?.recycle()
                            result.hardwareBuffer.close()
                            callback(softBitmap)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error processing screenshot: ${e.message}")
                            callback(null)
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        Log.e(TAG, "Screenshot failed with error code: $errorCode")
                        callback(null)
                    }
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "takeScreenshot exception: ${e.message}")
            callback(null)
        }
    }
}
