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
    var onTapDispatched: ((Float, Float) -> Unit)? = null
    var onTargetFocused: ((Float, Float) -> Unit)? = null

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
        return ScreenLinearizer.formatPrivateAgentWay(nodes, taskQuery, getCurrentPackage())
    }

    fun getSemanticScreenDump(): String {
        val nodes = dumpScreenHierarchy()
        return ScreenLinearizer.formatSemanticTree(nodes, getCurrentPackage())
    }

    fun clickAtCoordinates(x: Float, y: Float): Boolean {
        return try {
            onTargetFocused?.invoke(x, y)
            onTapDispatched?.invoke(x, y)
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

    /**
     * 1-to-1 port of PrivateAgent's 4-pass click disambiguation.
     * Pass 1: exact match, skip editable (prefer buttons/links)
     * Pass 2: substring match, skip editable
     * Pass 3: exact match, allow editable
     * Pass 4: substring match, allow editable
     */
    fun clickByText(targetText: String): Boolean {
        try {
            val allWindows = service.windows
            if (allWindows != null) {
                for (window in allWindows) {
                    val root = window.root ?: continue
                    try {
                        if (root.packageName?.toString() == ownPackage) continue
                        val found = findAndClickNode(root, targetText, exactOnly = true, skipEditable = true)
                            || findAndClickNode(root, targetText, exactOnly = false, skipEditable = true)
                            || findAndClickNode(root, targetText, exactOnly = true, skipEditable = false)
                            || findAndClickNode(root, targetText, exactOnly = false, skipEditable = false)
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

    private fun findAndClickNode(
        node: AccessibilityNodeInfo,
        targetText: String,
        exactOnly: Boolean = false,
        skipEditable: Boolean = true
    ): Boolean {
        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""
        val exactMatch = text.equals(targetText, ignoreCase = true) || desc.equals(targetText, ignoreCase = true)
        val containsMatch = text.contains(targetText, ignoreCase = true) || desc.contains(targetText, ignoreCase = true)
        val matches = if (exactOnly) exactMatch else containsMatch

        if (matches && (!skipEditable || !node.isEditable) && clickNodeOrParent(node)) {
            return true
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                if (findAndClickNode(child, targetText, exactOnly, skipEditable)) return true
            } finally {
                try { child.recycle() } catch (e: Exception) { AppLogger.d(TAG, "Child recycle: ${e.message}") }
            }
        }
        return false
    }

    private fun clickNodeOrParent(node: AccessibilityNodeInfo): Boolean {
        var target: AccessibilityNodeInfo? = node
        while (target != null && !target.isClickable) {
            target = target.parent
        }
        if (target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            if (!rect.isEmpty) {
                onTargetFocused?.invoke(rect.centerX().toFloat(), rect.centerY().toFloat())
                onTapDispatched?.invoke(rect.centerX().toFloat(), rect.centerY().toFloat())
            }
            return true
        }
        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (!rect.isEmpty) {
            return clickAtCoordinates(rect.centerX().toFloat(), rect.centerY().toFloat())
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
                            val rect = Rect()
                            editNode.getBoundsInScreen(rect)
                            if (!rect.isEmpty) {
                                onTargetFocused?.invoke(rect.centerX().toFloat(), rect.centerY().toFloat())
                            }
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
        // Tier 1: ACTION_IME_ENTER on focused input
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val allWindows = service.windows
                if (allWindows != null) {
                    for (window in allWindows) {
                        val root = window.root ?: continue
                        try {
                            if (root.packageName?.toString() == ownPackage) continue
                            val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                            if (focused != null) {
                                try {
                                    val submitted = focused.performAction(
                                        AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id
                                    )
                                    if (submitted) return true
                                } finally {
                                    try { focused.recycle() } catch (e: Exception) { AppLogger.d(TAG, "Focused recycle: ${e.message}") }
                                }
                            }
                        } finally {
                            try { root.recycle() } catch (e: Exception) { AppLogger.d(TAG, "Root recycle: ${e.message}") }
                        }
                    }
                }
            } catch (e: Exception) {
                AppLogger.d(TAG, "IME Enter direct error: ${e.message}")
            }
        }

        // Tier 2: Search windows for keyboard action node
        try {
            val allWindows = service.windows
            if (allWindows != null) {
                for (window in allWindows) {
                    val root = window.root ?: continue
                    try {
                        val actionNode = findKeyboardActionNode(root)
                        if (actionNode != null) {
                            try {
                                if (clickNodeOrParent(actionNode)) return true
                            } finally {
                                try { actionNode.recycle() } catch (e: Exception) { AppLogger.d(TAG, "ActionNode recycle: ${e.message}") }
                            }
                        }
                    } finally {
                        try { root.recycle() } catch (e: Exception) { AppLogger.d(TAG, "Root recycle: ${e.message}") }
                    }
                }
            }
        } catch (e: Exception) {
            AppLogger.d(TAG, "Keyboard action node search error: ${e.message}")
        }

        // Tier 3: Tap inside the actual IME window bottom-right corner
        try {
            val allWindows = service.windows
            if (allWindows != null) {
                for (window in allWindows) {
                    if (window.type != AccessibilityWindowInfo.TYPE_INPUT_METHOD) continue
                    val bounds = Rect()
                    window.getBoundsInScreen(bounds)
                    if (!bounds.isEmpty) {
                        val x = bounds.right - (bounds.width() * 0.10f)
                        val y = bounds.bottom - (bounds.height() * 0.14f)
                        AppLogger.i(TAG, "Executing IME window bottom-right corner tap at ($x, $y)")
                        return clickAtCoordinates(x, y)
                    }
                }
            }
        } catch (e: Exception) {
            AppLogger.d(TAG, "IME window corner tap error: ${e.message}")
        }

        return false
    }

    private fun findKeyboardActionNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val label = (node.text?.toString().orEmpty().ifEmpty {
            node.contentDescription?.toString().orEmpty()
        }).trim().lowercase()
        val actionLabels = setOf("search", "enter", "go", "done", "send", "next")
        if (node.isClickable && (label in actionLabels || label.endsWith(" search"))) {
            return AccessibilityNodeInfo.obtain(node)
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                val found = findKeyboardActionNode(child)
                if (found != null) return found
            } finally {
                try { child.recycle() } catch (e: Exception) { AppLogger.d(TAG, "Child recycle: ${e.message}") }
            }
        }
        return null
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
