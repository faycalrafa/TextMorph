package com.textmorph.app.service.overlay

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.textmorph.app.data.repository.PromptRepository
import com.textmorph.app.data.repository.SettingsRepository
import com.textmorph.app.llm.LiteRtLmEngine
import com.textmorph.app.llm.OpenAiCloudEngine
import com.textmorph.app.util.AppLogger
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

class TextMorphAccessibilityService : AccessibilityService() {

    private val TAG = "TextMorphAccessService"
    private var overlayManager: FloatingToolbarWindowManager? = null
    private val screenshotExecutor = Executors.newSingleThreadExecutor()
    val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private lateinit var promptRepository: PromptRepository
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var liteRtEngine: LiteRtLmEngine
    private lateinit var cloudEngine: OpenAiCloudEngine

    private val serviceScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main + kotlinx.coroutines.SupervisorJob())

    override fun onServiceConnected() {
        super.onServiceConnected()
        AppLogger.i(TAG, "TextMorph Accessibility Service connected.")

        promptRepository = PromptRepository.getInstance(applicationContext)
        settingsRepository = SettingsRepository.getInstance(applicationContext)
        liteRtEngine = com.textmorph.app.llm.LiteRtLmEngineManager.getInstance(applicationContext)
        cloudEngine = OpenAiCloudEngine()

        overlayManager = FloatingToolbarWindowManager(
            service = this,
            promptRepository = promptRepository,
            settingsRepository = settingsRepository,
            liteRtEngine = liteRtEngine,
            cloudEngine = cloudEngine
        )

        serviceScope.launch {
            settingsRepository.settingsState.collect { settings ->
                if (settings.alwaysShowFloatingToolbar && settings.floatingToolbarEnabled) {
                    overlayManager?.show(
                        onTakeScreenshot = { callback -> captureScreenContext(callback) },
                        onExtractTranscript = { extractConversationTranscript() }
                    )
                } else if (!settings.floatingToolbarEnabled) {
                    overlayManager?.hide()
                }
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val settings = settingsRepository.settingsState.value
        if (!settings.floatingToolbarEnabled) {
            overlayManager?.hide()
            return
        }

        val source = event.source
        try {
            if (settings.alwaysShowFloatingToolbar) {
                overlayManager?.show(
                    onTakeScreenshot = { callback -> captureScreenContext(callback) },
                    onExtractTranscript = { extractConversationTranscript() }
                )
                return
            }

            val keyboardVisible = isKeyboardVisible()

            if (keyboardVisible) {
                // Keyboard is open, verify if there is an editable field
                val isEditable = source?.isEditable == true || isCurrentInputEditable()
                if (isEditable) {
                    overlayManager?.show(
                        onTakeScreenshot = { callback -> captureScreenContext(callback) },
                        onExtractTranscript = { extractConversationTranscript() }
                    )
                } else {
                    overlayManager?.hide()
                }
            } else {
                // Keyboard dismissed or not open: hide floating button immediately
                overlayManager?.hide()
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error processing accessibility event: ${e.message}", e)
        } finally {
            try {
                source?.recycle()
            } catch (recycleEx: Exception) {
                // Ignore already recycled nodes
            }
        }
    }

    fun isKeyboardVisible(): Boolean {
        return try {
            windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        } catch (e: Exception) {
            false
        }
    }

    private fun isCurrentInputEditable(): Boolean {
        val node = getLiveInputNode() ?: return false
        return try {
            node.isEditable
        } finally {
            try { node.recycle() } catch (_: Exception) {}
        }
    }

    private fun getLiveInputNode(): AccessibilityNodeInfo? {
        return try {
            val direct = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (direct != null) return direct
            val root = rootInActiveWindow
            val fromRoot = root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            try { root?.recycle() } catch (_: Exception) {}
            fromRoot
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed finding focused input node: ${e.message}", e)
            null
        }
    }

    fun getLiveInputText(): String {
        val node = getLiveInputNode() ?: return ""
        return try {
            val isHint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                node.isShowingHintText
            } else false
            if (!isHint) node.text?.toString() ?: "" else ""
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error reading live input text: ${e.message}", e)
            ""
        } finally {
            try { node.recycle() } catch (_: Exception) {}
        }
    }

    private var previousTextBeforeTransformation: String? = null

    fun hasPreviousTransformation(): Boolean = !previousTextBeforeTransformation.isNullOrEmpty()

    fun revertPreviousTransformation(): Boolean {
        val previous = previousTextBeforeTransformation ?: return false
        AppLogger.i(TAG, "Reverting to previous text length=${previous.length}")
        val success = replaceLiveInputText(previous, recordHistory = false)
        if (success) {
            previousTextBeforeTransformation = null
        }
        return success
    }

    fun replaceLiveInputText(newText: String, recordHistory: Boolean = true): Boolean {
        if (recordHistory) {
            val current = getLiveInputText()
            if (current.isNotBlank()) {
                previousTextBeforeTransformation = current
            }
        }
        val node = getLiveInputNode() ?: return false
        return try {
            AppLogger.i(TAG, "Replacing live input text with length=${newText.length}")
            val arguments = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText)
            }
            val success = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
            if (!success) {
                AppLogger.w(TAG, "ACTION_SET_TEXT failed, falling back to clipboard paste...")
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("TextMorph", newText))
                node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            } else {
                true
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed replacing live input text: ${e.message}", e)
            false
        } finally {
            try { node.recycle() } catch (_: Exception) {}
        }
    }

    fun captureScreenContext(onCaptured: (ByteArray) -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                AppLogger.i(TAG, "Capturing screen for multimodal vision context (excluding floating overlay)...")
                // Temporarily hide the floating overlay window
                overlayManager?.setOverlayVisible(false)

                // Wait 120ms so SurfaceFlinger composes a clean frame behind the overlay
                mainHandler.postDelayed({
                    try {
                        takeScreenshot(
                            Display.DEFAULT_DISPLAY,
                            screenshotExecutor,
                            object : TakeScreenshotCallback {
                                override fun onSuccess(screenshot: ScreenshotResult) {
                                    try {
                                        val hardwareBuffer = screenshot.hardwareBuffer
                                        val bitmap = Bitmap.wrapHardwareBuffer(
                                            hardwareBuffer,
                                            screenshot.colorSpace
                                        )
                                        try { hardwareBuffer.close() } catch (_: Exception) {}
                                        if (bitmap != null) {
                                            val stream = ByteArrayOutputStream()
                                            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
                                            val bytes = stream.toByteArray()
                                            bitmap.recycle()
                                            AppLogger.i(TAG, "Screen captured successfully: ${bytes.size} bytes.")
                                            onCaptured(bytes)
                                        }
                                    } catch (e: Exception) {
                                        AppLogger.e(TAG, "Error processing screenshot hardware buffer: ${e.message}", e)
                                    } finally {
                                        mainHandler.post {
                                            overlayManager?.setOverlayVisible(true)
                                        }
                                    }
                                }

                                override fun onFailure(errorCode: Int) {
                                    AppLogger.w(TAG, "Take screenshot callback failed with code: $errorCode")
                                    mainHandler.post {
                                        overlayManager?.setOverlayVisible(true)
                                    }
                                }
                            }
                        )
                    } catch (e: Exception) {
                        AppLogger.e(TAG, "Failed to initiate screen capture: ${e.message}", e)
                        overlayManager?.setOverlayVisible(true)
                    }
                }, 120L)

                // Safeguard timeout to ensure overlay visibility is restored
                mainHandler.postDelayed({
                    overlayManager?.setOverlayVisible(true)
                }, 1500L)
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed in captureScreenContext: ${e.message}", e)
                overlayManager?.setOverlayVisible(true)
            }
        }
    }

    private data class ChatBubble(val text: String, val isMe: Boolean, val top: Int)

    /**
     * Traverses the active window UI tree to extract visible conversation chat bubbles,
     * classifying Left vs. Right alignment into incoming ("Them") vs outgoing ("Me").
     */
    fun extractConversationTranscript(): String {
        val root = rootInActiveWindow ?: return ""
        val bubbles = mutableListOf<ChatBubble>()
        val displayMetrics = resources.displayMetrics
        val screenCenterX = displayMetrics.widthPixels / 2

        try {
            fun traverse(node: AccessibilityNodeInfo?) {
                if (node == null) return
                try {
                    val text = node.text?.toString()?.trim()
                    if (!node.isEditable && !text.isNullOrBlank() && text.length > 1) {
                        val rect = android.graphics.Rect()
                        node.getBoundsInScreen(rect)
                        if (rect.width() > 30 && rect.height() > 15 && rect.bottom > 0 && rect.top < displayMetrics.heightPixels) {
                            val isMe = rect.centerX() > screenCenterX
                            bubbles.add(ChatBubble(text, isMe, rect.top))
                        }
                    }

                    for (i in 0 until node.childCount) {
                        val child = node.getChild(i)
                        traverse(child)
                        try { child?.recycle() } catch (_: Exception) {}
                    }
                } catch (e: Exception) {
                    AppLogger.w(TAG, "Error traversing accessibility node: ${e.message}")
                }
            }

            traverse(root)
        } finally {
            try { root.recycle() } catch (_: Exception) {}
        }

        // Sort by vertical position on screen
        bubbles.sortBy { it.top }

        val sb = StringBuilder()
        var lastText = ""
        for (bubble in bubbles) {
            if (bubble.text != lastText) {
                val speaker = if (bubble.isMe) "Me" else "Them"
                sb.append("$speaker: ${bubble.text}\n")
                lastText = bubble.text
            }
        }

        val transcript = sb.toString().trim()
        AppLogger.i(TAG, "Extracted conversation transcript: ${bubbles.size} bubbles, length=${transcript.length}")
        return transcript
    }

    override fun onInterrupt() {
        AppLogger.w(TAG, "TextMorph Accessibility Service interrupted.")
        overlayManager?.hide()
    }

    override fun onDestroy() {
        super.onDestroy()
        AppLogger.i(TAG, "TextMorph Accessibility Service destroyed.")
        try {
            screenshotExecutor.shutdown()
        } catch (e: Exception) {
            AppLogger.w(TAG, "Error shutting down screenshot executor: ${e.message}")
        }
        overlayManager?.destroy()
        overlayManager = null
    }
}
