package com.textmorph.app.service.overlay

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.textmorph.app.util.AppLogger

/**
 * Screen element representation with spatial bounds and interactive capabilities.
 */
data class UiElementNode(
    val index: Int,
    val text: String,
    val contentDescription: String,
    val className: String,
    val bounds: Rect,
    val isClickable: Boolean,
    val isEditable: Boolean,
    val isScrollable: Boolean,
    val depth: Int
) {
    val centerX: Int get() = bounds.centerX()
    val centerY: Int get() = bounds.centerY()
    val displayText: String get() = text.ifEmpty { contentDescription }
}

/**
 * Controls screen reading, accessibility hierarchy parsing, and gesture dispatching.
 * Strictly adheres to zero-context-leaks, immediate node recycling, and near-logging.
 */
class ScreenAutomationController(private val service: AccessibilityService) {

    private val TAG = "ScreenAutomationCtrl"
    private val ownPackage = "com.textmorph.app"

    fun dumpScreenHierarchy(): List<UiElementNode> {
        val result = mutableListOf<UiElementNode>()
        try {
            val allWindows = service.windows
            if (allWindows.isNullOrEmpty()) {
                val root = service.rootInActiveWindow ?: return emptyList()
                try {
                    if (root.packageName?.toString() != ownPackage) {
                        traverseNode(root, result, 0)
                    }
                } finally {
                    try { root.recycle() } catch (e: Exception) { AppLogger.d(TAG, "Recycle: ${e.message}") }
                }
                return result
            }

            for (window in allWindows) {
                val root = window.root ?: continue
                try {
                    if (root.packageName?.toString() == ownPackage) continue
                    traverseNode(root, result, 0)
                } finally {
                    try { root.recycle() } catch (e: Exception) { AppLogger.d(TAG, "Recycle: ${e.message}") }
                }
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error dumping screen hierarchy: ${e.message}", e)
        }
        return result
    }

    private fun traverseNode(node: AccessibilityNodeInfo, list: MutableList<UiElementNode>, depth: Int) {
        val rect = Rect()
        node.getBoundsInScreen(rect)

        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""
        val className = (node.className?.toString() ?: "").substringAfterLast('.')

        val isZeroSize = rect.width() <= 0 || rect.height() <= 0
        if (!node.isVisibleToUser || isZeroSize) {
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                try {
                    traverseNode(child, list, depth + 1)
                } finally {
                    try { child.recycle() } catch (e: Exception) { AppLogger.d(TAG, "Child recycle: ${e.message}") }
                }
            }
            return
        }

        if (text.isNotEmpty() || desc.isNotEmpty() || node.isClickable || node.isEditable || node.isScrollable) {
            list.add(
                UiElementNode(
                    index = list.size,
                    text = text,
                    contentDescription = desc,
                    className = className,
                    bounds = Rect(rect),
                    isClickable = node.isClickable,
                    isEditable = node.isEditable,
                    isScrollable = node.isScrollable,
                    depth = depth
                )
            )
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                traverseNode(child, list, depth + 1)
            } finally {
                try { child.recycle() } catch (e: Exception) { AppLogger.d(TAG, "Child recycle: ${e.message}") }
            }
        }
    }

    fun getCompressedScreenDump(taskQuery: String): String {
        val nodes = dumpScreenHierarchy()
        if (nodes.isEmpty()) return "Screen is empty or unavailable."

        val sb = StringBuilder()
        val currentPkg = getCurrentPackage()
        if (currentPkg != null) {
            sb.appendLine("APP: $currentPkg")
        }

        val stopWords = setOf("to", "and", "the", "a", "in", "of", "for", "on", "with", "at", "by", "from", "go", "turn", "open")
        val keywords = taskQuery.lowercase()
            .replace(Regex("[^a-z0-9\\s]"), "")
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() && it !in stopWords }

        for (node in nodes) {
            val label = node.displayText.trim()
            val lowerLabel = label.lowercase()

            if (lowerLabel.contains("battery") || lowerLabel.contains("percent") ||
                lowerLabel.contains("do not disturb") || lowerLabel.matches(Regex("^\\d{1,2}:\\d{2}$"))
            ) {
                continue
            }

            val tags = mutableListOf<String>()
            if (node.isClickable) tags.add("tap")
            if (node.isEditable) tags.add("edit")
            if (node.isScrollable) tags.add("scroll")

            val isMatch = keywords.any { kw -> lowerLabel.contains(kw) }
            val star = if (isMatch) "*" else ""
            val truncated = if (label.length > 60) label.take(60) + "…" else label
            val quoted = if (truncated.isNotEmpty()) "\"$truncated\"" else "(no-label)"
            val tagStr = if (tags.isNotEmpty()) "[${tags.joinToString(",")}]" else ""

            sb.appendLine("[${node.index}]$star ${node.className} $quoted $tagStr center:(${node.centerX},${node.centerY})")
        }
        return sb.toString().trim()
    }

    fun clickAtCoordinates(x: Float, y: Float): Boolean {
        return try {
            val path = Path().apply { moveTo(x, y) }
            val stroke = GestureDescription.StrokeDescription(path, 0, 100)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            val dispatched = service.dispatchGesture(gesture, null, null)
            AppLogger.d(TAG, "Click at ($x, $y) dispatched=$dispatched")
            dispatched
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed clicking at ($x, $y): ${e.message}", e)
            false
        }
    }

    fun clickByText(targetText: String): Boolean {
        try {
            val allWindows = service.windows
            if (allWindows != null) {
                for (window in allWindows) {
                    val root = window.root ?: continue
                    try {
                        if (root.packageName?.toString() == ownPackage) continue
                        val found = findAndClickNode(root, targetText)
                        if (found) return true
                    } finally {
                        try { root.recycle() } catch (e: Exception) { AppLogger.d(TAG, "Recycle: ${e.message}") }
                    }
                }
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error clicking text '$targetText': ${e.message}", e)
        }
        return false
    }

    private fun findAndClickNode(node: AccessibilityNodeInfo, targetText: String): Boolean {
        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""
        val matches = text.contains(targetText, ignoreCase = true) || desc.contains(targetText, ignoreCase = true)

        if (matches && !node.isEditable) {
            var target: AccessibilityNodeInfo? = node
            while (target != null && !target.isClickable) {
                target = target.parent
            }
            if (target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) {
                return true
            }
            val rect = Rect()
            node.getBoundsInScreen(rect)
            if (!rect.isEmpty) {
                return clickAtCoordinates(rect.centerX().toFloat(), rect.centerY().toFloat())
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                if (findAndClickNode(child, targetText)) return true
            } finally {
                try { child.recycle() } catch (e: Exception) { AppLogger.d(TAG, "Child recycle: ${e.message}") }
            }
        }
        return false
    }

    fun typeIntoField(text: String, fieldHint: String? = null): Boolean {
        try {
            val allWindows = service.windows ?: return false
            for (window in allWindows) {
                val root = window.root ?: continue
                try {
                    if (root.packageName?.toString() == ownPackage) continue
                    val editNode = findEditableNode(root, fieldHint) ?: findEditableNode(root, null)
                    if (editNode != null) {
                        try {
                            editNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                            val args = Bundle().apply {
                                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                            }
                            return editNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                        } finally {
                            try { editNode.recycle() } catch (e: Exception) { AppLogger.d(TAG, "Edit recycle: ${e.message}") }
                        }
                    }
                } finally {
                    try { root.recycle() } catch (e: Exception) { AppLogger.d(TAG, "Root recycle: ${e.message}") }
                }
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error typing into field: ${e.message}", e)
        }
        return false
    }

    private fun findEditableNode(node: AccessibilityNodeInfo, hint: String?): AccessibilityNodeInfo? {
        if (node.isEditable) {
            if (hint.isNullOrBlank()) return AccessibilityNodeInfo.obtain(node)
            val text = node.text?.toString() ?: ""
            val desc = node.contentDescription?.toString() ?: ""
            val hintText = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) node.hintText?.toString() ?: "" else ""
            if (text.contains(hint, true) || desc.contains(hint, true) || hintText.contains(hint, true)) {
                return AccessibilityNodeInfo.obtain(node)
            }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                val found = findEditableNode(child, hint)
                if (found != null) return found
            } finally {
                try { child.recycle() } catch (e: Exception) { AppLogger.d(TAG, "Child recycle: ${e.message}") }
            }
        }
        return null
    }

    fun pressImeEnter(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val focused = service.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                if (focused != null) {
                    try {
                        val ok = focused.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
                        if (ok) return true
                    } finally {
                        try { focused.recycle() } catch (e: Exception) { AppLogger.d(TAG, "Recycle: ${e.message}") }
                    }
                }
            } catch (e: Exception) {
                AppLogger.d(TAG, "IME Enter direct error: ${e.message}")
            }
        }
        return clickByText("Search") || clickByText("Enter") || clickByText("Go") || clickByText("Done")
    }

    fun performScroll(direction: String): Boolean {
        val isDown = direction.equals("down", ignoreCase = true)
        val action = if (isDown) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        try {
            val allWindows = service.windows ?: return false
            for (window in allWindows) {
                val root = window.root ?: continue
                try {
                    if (root.packageName?.toString() == ownPackage) continue
                    val scrollable = findScrollable(root)
                    if (scrollable != null) {
                        try {
                            return scrollable.performAction(action)
                        } finally {
                            try { scrollable.recycle() } catch (e: Exception) { AppLogger.d(TAG, "Recycle: ${e.message}") }
                        }
                    }
                } finally {
                    try { root.recycle() } catch (e: Exception) { AppLogger.d(TAG, "Recycle: ${e.message}") }
                }
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Scroll error: ${e.message}", e)
        }
        // Fallback swipe
        val metrics = service.resources.displayMetrics
        val cx = (metrics.widthPixels / 2).toFloat()
        val startY = if (isDown) metrics.heightPixels * 0.75f else metrics.heightPixels * 0.25f
        val endY = if (isDown) metrics.heightPixels * 0.25f else metrics.heightPixels * 0.75f
        return performSwipe(cx, startY, cx, endY, 400)
    }

    private fun findScrollable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isScrollable) return AccessibilityNodeInfo.obtain(node)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                val found = findScrollable(child)
                if (found != null) return found
            } finally {
                try { child.recycle() } catch (e: Exception) { AppLogger.d(TAG, "Child recycle: ${e.message}") }
            }
        }
        return null
    }

    fun performSwipe(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long = 300): Boolean {
        return try {
            val path = Path().apply {
                moveTo(startX, startY)
                lineTo(endX, endY)
            }
            val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            service.dispatchGesture(gesture, null, null)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Swipe error: ${e.message}", e)
            false
        }
    }

    fun pressBack(): Boolean = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
    fun pressHome(): Boolean = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)

    fun getCurrentPackage(): String? {
        return try {
            service.windows?.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }?.root?.use {
                it.packageName?.toString()
            }
        } catch (e: Exception) {
            null
        }
    }

    private inline fun <T> AccessibilityNodeInfo.use(block: (AccessibilityNodeInfo) -> T): T {
        return try {
            block(this)
        } finally {
            try { recycle() } catch (e: Exception) { AppLogger.d(TAG, "Recycle: ${e.message}") }
        }
    }
}
