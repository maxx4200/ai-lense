package com.example

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class ChatAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "ChatAccessibilityService connected")
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // No specific event reactions needed since we pull layout dynamically on demand
    }

    override fun onInterrupt() {
        Log.d(TAG, "ChatAccessibilityService interrupted")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        Log.d(TAG, "ChatAccessibilityService unbound")
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        Log.d(TAG, "ChatAccessibilityService destroyed")
        instance = null
        super.onDestroy()
    }

    /**
     * Extracts text from the accessibility node at the given screen coordinates (X, Y).
     */
    fun extractTextAt(x: Int, y: Int) {
        val rootNode = rootInActiveWindow
        if (rootNode == null) {
            Log.e(TAG, "Root in active window is null")
            FloatingWidgetService.instance?.onTextExtracted("")
            return
        }

        val rect = Rect()
        val foundNodes = mutableListOf<AccessibilityNodeInfo>()

        fun findNodesAtCoordinate(node: AccessibilityNodeInfo?) {
            if (node == null) return
            if (node.packageName?.toString() == packageName) return
            try {
                node.getBoundsInScreen(rect)
                if (rect.contains(x, y)) {
                    foundNodes.add(node)
                }
                for (i in 0 until node.childCount) {
                    findNodesAtCoordinate(node.getChild(i))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error traversing node", e)
            }
        }

        findNodesAtCoordinate(rootNode)

        // Find the most specific (deepest) node with non-empty text
        val textNode = foundNodes.lastOrNull { !it.text.isNullOrBlank() }
        if (textNode != null) {
            val extractedText = textNode.text.toString()
            Log.d(TAG, "Value extracted: $extractedText from node: ${textNode.className}")
            FloatingWidgetService.instance?.onTextExtracted(extractedText)
        } else {
            val fallbackNode = foundNodes.firstOrNull { !it.text.isNullOrBlank() }
            if (fallbackNode != null) {
                val extractedText = fallbackNode.text.toString()
                Log.d(TAG, "Extracted fallback text: $extractedText")
                FloatingWidgetService.instance?.onTextExtracted(extractedText)
            } else {
                Log.d(TAG, "No text node found at ($x, $y)")
                FloatingWidgetService.instance?.onTextExtracted("")
            }
        }
    }

    /**
     * Finds the currently focused or editable text field on the screen and pastes the text.
     */
    fun pasteText(text: String): Boolean {
        val rootNode = rootInActiveWindow
        if (rootNode == null) {
            Log.e(TAG, "Root node is null during paste")
            return false
        }

        // 1. Search for the focused, editable EditText node
        fun findAndPasteFocused(node: AccessibilityNodeInfo?): Boolean {
            if (node == null) return false
            try {
                if (node.isFocused && (node.className?.contains("EditText") == true || node.isEditable)) {
                    val arguments = Bundle().apply {
                        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                    }
                    val result = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
                    Log.d(TAG, "Paste result on focused: $result")
                    return result
                }
                for (i in 0 until node.childCount) {
                    if (findAndPasteFocused(node.getChild(i))) return true
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in findAndPasteFocused", e)
            }
            return false
        }

        var success = findAndPasteFocused(rootNode)
        if (success) return true

        // 2. Fallback: Search for any editable EditText node
        fun findAnyEditableAndPaste(node: AccessibilityNodeInfo?): Boolean {
            if (node == null) return false
            try {
                if (node.className?.contains("EditText") == true || node.isEditable) {
                    val arguments = Bundle().apply {
                        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                    }
                    val result = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
                    Log.d(TAG, "Paste result on editable: $result")
                    return result
                }
                for (i in 0 until node.childCount) {
                    if (findAnyEditableAndPaste(node.getChild(i))) return true
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in findAnyEditableAndPaste", e)
            }
            return false
        }

        success = findAnyEditableAndPaste(rootNode)
        Log.d(TAG, "Overall paste success: $success")
        return success
    }

    companion object {
        private const val TAG = "ChatAccessService"
        var instance: ChatAccessibilityService? = null
            private set
    }
}
