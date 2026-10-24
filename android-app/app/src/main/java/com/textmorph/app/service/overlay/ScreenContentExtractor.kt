package com.textmorph.app.service.overlay

import android.content.res.Resources
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.textmorph.app.util.AppLogger

/**
 * Extracts visible conversation bubbles and active screen text from the accessibility node hierarchy.
 */
object ScreenContentExtractor {

    private const val TAG = "ScreenContentExtractor"

    data class ChatMessage(val sender: String, val text: String, val isIncoming: Boolean)
    private data class ChatBubble(val text: String, val isMe: Boolean, val top: Int)

    fun getRecentChatMessages(rootSupplier: () -> AccessibilityNodeInfo?, resources: Resources): List<ChatMessage> {
        val root = rootSupplier() ?: return emptyList()
        val bubbles = mutableListOf<ChatBubble>()
        val displayMetrics = resources.displayMetrics
        val screenCenterX = displayMetrics.widthPixels / 2

        try {
            fun traverse(node: AccessibilityNodeInfo?) {
                if (node == null) return
                try {
                    val text = node.text?.toString()?.trim()
                    if (!node.isEditable && !text.isNullOrBlank() && text.length > 1) {
                        val rect = Rect()
                        node.getBoundsInScreen(rect)
                        if (rect.width() > 30 && rect.height() > 15 && rect.bottom > 0 && rect.top < displayMetrics.heightPixels) {
                            val isMe = rect.centerX() > screenCenterX
                            bubbles.add(ChatBubble(text, isMe, rect.top))
                        }
                    }

                    for (i in 0 until node.childCount) {
                        val child = node.getChild(i)
                        traverse(child)
                        try { child?.recycle() } catch (recycleEx: Exception) { AppLogger.d(TAG, "Child recycle: ${recycleEx.message}") }
                    }
                } catch (e: Exception) {
                    AppLogger.w(TAG, "Error traversing accessibility node: ${e.message}")
                }
            }

            traverse(root)
        } finally {
            try { root.recycle() } catch (recycleEx: Exception) { AppLogger.d(TAG, "Root recycle: ${recycleEx.message}") }
        }

        bubbles.sortBy { it.top }

        val result = mutableListOf<ChatMessage>()
        var lastText = ""
        for (bubble in bubbles) {
            if (bubble.text != lastText) {
                val sender = if (bubble.isMe) "Me" else "Them"
                result.add(ChatMessage(sender = sender, text = bubble.text, isIncoming = !bubble.isMe))
                lastText = bubble.text
            }
        }
        return result
    }

    fun extractConversationTranscript(rootSupplier: () -> AccessibilityNodeInfo?, resources: Resources): String {
        val messages = getRecentChatMessages(rootSupplier, resources)
        val sb = StringBuilder()
        for (msg in messages) {
            sb.append("${msg.sender}: ${msg.text}\n")
        }
        val transcript = sb.toString().trim()
        AppLogger.i(TAG, "Extracted conversation transcript: ${messages.size} bubbles, length=${transcript.length}")
        return transcript
    }

    fun isConversationContext(rootSupplier: () -> AccessibilityNodeInfo?, resources: Resources): Boolean {
        val messages = getRecentChatMessages(rootSupplier, resources)
        if (messages.size >= 2 || messages.any { it.isIncoming }) {
            return true
        }
        val root = rootSupplier()
        try {
            val pkg = root?.packageName?.toString()?.lowercase() ?: ""
            val knownChatApps = listOf(
                "com.whatsapp", "org.telegram", "org.thoughtcrime.securesms",
                "com.discord", "com.slack", "com.facebook.orca", "com.google.android.apps.messaging",
                "com.samsung.android.messaging", "com.twitter.android", "com.instagram.android"
            )
            if (knownChatApps.any { pkg.contains(it) }) {
                return true
            }
        } finally {
            try { root?.recycle() } catch (recycleEx: Exception) { AppLogger.d(TAG, "Root recycle: ${recycleEx.message}") }
        }
        return false
    }

    fun extractScreenVisibleText(rootSupplier: () -> AccessibilityNodeInfo?): String {
        val root = rootSupplier() ?: return ""
        val sb = StringBuilder()
        try {
            fun traverse(node: AccessibilityNodeInfo?) {
                if (node == null) return
                try {
                    val text = node.text?.toString()?.trim()
                    if (!text.isNullOrBlank() && text.length > 1) {
                        sb.append(text).append("\n")
                    }
                    for (i in 0 until node.childCount) {
                        val child = node.getChild(i)
                        traverse(child)
                        try { child?.recycle() } catch (recycleEx: Exception) { AppLogger.d(TAG, "Child recycle: ${recycleEx.message}") }
                    }
                } catch (e: Exception) {
                    AppLogger.w(TAG, "Error traversing node for screen text: ${e.message}")
                }
            }
            traverse(root)
        } finally {
            try { root.recycle() } catch (recycleEx: Exception) { AppLogger.d(TAG, "Root recycle: ${recycleEx.message}") }
        }
        return sb.toString().trim()
    }
}
