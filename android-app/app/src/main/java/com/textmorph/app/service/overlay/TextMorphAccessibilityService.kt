package com.textmorph.app.service.overlay

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.Configuration
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
    private var interactionOverlayManager: AgentInteractionOverlayManager? = null
    private val screenshotExecutor = Executors.newSingleThreadExecutor()
    val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private lateinit var promptRepository: PromptRepository
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var liteRtEngine: LiteRtLmEngine
    private lateinit var cloudEngine: OpenAiCloudEngine

    private var wasRibbonOpenBeforeKeyboardClosed = false
    private var userDismissedRibbonForCurrentKeyboardSession = false
    private val serviceScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main + kotlinx.coroutines.SupervisorJob())

    private var clipboardListener: ClipboardManager.OnPrimaryClipChangedListener? = null
    private var lastCopiedClipText: String? = null
    private var lastCopiedTimestamp: Long = 0L
    private var lastConsumedClipText: String? = null

    @Volatile
    private var isInternalUpdating = false

    fun onRibbonManuallyDismissed() {
        userDismissedRibbonForCurrentKeyboardSession = true
        AppLogger.d(TAG, "Ribbon manually dismissed during keyboard session.")
    }

    fun onRibbonManuallyOpened() {
        userDismissedRibbonForCurrentKeyboardSession = false
        AppLogger.d(TAG, "Ribbon manually opened during keyboard session.")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
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
        interactionOverlayManager = AgentInteractionOverlayManager(this)

        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            clipboardListener = ClipboardManager.OnPrimaryClipChangedListener {
                try {
                    val clip = cm?.primaryClip
                    if (clip != null && clip.itemCount > 0) {
                        val text = clip.getItemAt(0)?.text?.toString()
                        if (!text.isNullOrBlank() && text != lastCopiedClipText) {
                            lastCopiedClipText = text
                            lastCopiedTimestamp = System.currentTimeMillis()
                            AppLogger.d(TAG, "New clipboard copy captured: length=${text.length}")
                        }
                    }
                } catch (e: Exception) {
                    AppLogger.w(TAG, "Error in primary clip listener: ${e.message}")
                }
            }
            cm?.addPrimaryClipChangedListener(clipboardListener)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to register clipboard listener: ${e.message}", e)
        }

        serviceScope.launch {
            settingsRepository.settingsState.collect { settings ->
                if (settings.alwaysShowFloatingToolbar && settings.floatingToolbarEnabled) {
                    overlayManager?.show(
                        autoExpand = false,
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
            if (event.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED && !isInternalUpdating) {
                val liveText = getLiveInputText()
                overlayManager?.onUserTyped(liveText)
            } else if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                overlayManager?.onUserNavigatedBack()
            }

            // Guard: Never collapse or hide if overlay is busy (generating or editing prompt)
            if (overlayManager?.isBusy() == true) {
                return
            }

            val keyboardVisible = isKeyboardVisible()

            if (!keyboardVisible) {
                userDismissedRibbonForCurrentKeyboardSession = false
                overlayManager?.onUserNavigatedBack()
                // Never collapse if the user has manually opened the toolbar or chat overlay
                if (overlayManager?.isBusy() != true && overlayManager?.isExpanded() != true) {
                    overlayManager?.collapse()
                    if (!settings.alwaysShowFloatingToolbar) {
                        overlayManager?.hide()
                    }
                }
                if (settings.alwaysShowFloatingToolbar && overlayManager?.isShowingActive != true) {
                    overlayManager?.show(
                        autoExpand = false,
                        onTakeScreenshot = { callback -> captureScreenContext(callback) },
                        onExtractTranscript = { extractConversationTranscript() }
                    )
                }
                return
            }

            // Keyboard is visible: check if active field is editable
            val isEditable = source?.isEditable == true || isCurrentInputEditable()
            if (isEditable) {
                val shouldAutoExpand = settings.alwaysOpenBarWithKeyboard && !userDismissedRibbonForCurrentKeyboardSession
                overlayManager?.show(
                    autoExpand = shouldAutoExpand,
                    onTakeScreenshot = { callback -> captureScreenContext(callback) },
                    onExtractTranscript = { extractConversationTranscript() }
                )
            } else if (overlayManager?.isBusy() != true && overlayManager?.isExpanded() != true) {
                overlayManager?.collapse()
                if (!settings.alwaysShowFloatingToolbar) {
                    overlayManager?.hide()
                }
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
            try { node.recycle() } catch (recycleEx: Exception) { AppLogger.d(TAG, "Node recycle: ${recycleEx.message}") }
        }
    }

    private fun getLiveInputNode(): AccessibilityNodeInfo? {
        return try {
            val direct = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (direct != null) return direct
            val root = rootInActiveWindow
            val fromRoot = root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            try { root?.recycle() } catch (recycleEx: Exception) { AppLogger.d(TAG, "Root recycle: ${recycleEx.message}") }
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
            try { node.recycle() } catch (recycleEx: Exception) { AppLogger.d(TAG, "Node recycle: ${recycleEx.message}") }
        }
    }

    private val inputHistoryDeque = ArrayDeque<String>(10)

    fun hasPreviousTransformation(): Boolean = inputHistoryDeque.isNotEmpty()

    fun pushInputHistory(text: String) {
        if (text.isBlank()) return
        if (inputHistoryDeque.isNotEmpty() && inputHistoryDeque.last() == text) {
            AppLogger.d(TAG, "Skipping duplicate push to inputHistoryDeque")
            return
        }
        if (inputHistoryDeque.size >= 10) {
            inputHistoryDeque.removeFirst()
        }
        inputHistoryDeque.addLast(text)
        AppLogger.i(TAG, "Pushed to inputHistoryDeque (size=${inputHistoryDeque.size}, length=${text.length})")
    }

    fun revertPreviousTransformation(): Boolean {
        if (inputHistoryDeque.isEmpty()) return false
        val previous = inputHistoryDeque.removeLast()
        AppLogger.i(TAG, "Reverting to previous text (remaining history=${inputHistoryDeque.size}, length=${previous.length})")
        return replaceLiveInputText(previous, recordHistory = false)
    }

    fun setPreviousTransformation(text: String) {
        pushInputHistory(text)
    }

    fun getRecentClipboardText(maxAgeMs: Long = 90_000L): String? {
        return try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
            val clipDesc = cm.primaryClipDescription
            val clipTimestamp = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                clipDesc?.timestamp ?: 0L
            } else 0L
            val effectiveTimestamp = maxOf(lastCopiedTimestamp, clipTimestamp)
            val now = System.currentTimeMillis()
            val age = now - effectiveTimestamp
            if (effectiveTimestamp > 0L && age in 0L..maxAgeMs) {
                val clip = cm.primaryClip
                if (clip != null && clip.itemCount > 0) {
                    val text = clip.getItemAt(0)?.text?.toString()
                    if (!text.isNullOrBlank() && text != lastConsumedClipText) {
                        text
                    } else null
                } else null
            } else null
        } catch (e: Exception) {
            AppLogger.d(TAG, "Error checking recent clipboard: ${e.message}")
            null
        }
    }

    fun pasteClipboardText(): Boolean {
        val recent = getRecentClipboardText() ?: return false
        val node = getLiveInputNode() ?: return false
        return try {
            val current = getLiveInputText()
            pushInputHistory(current)
            lastConsumedClipText = recent
            val pasted = node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            if (!pasted) {
                val updated = if (current.isEmpty()) recent else "$current $recent"
                replaceLiveInputText(updated, recordHistory = false)
            } else {
                AppLogger.i(TAG, "Native ACTION_PASTE successful for recent clipboard.")
                true
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed pasting recent clipboard: ${e.message}", e)
            false
        } finally {
            try { node.recycle() } catch (recycleEx: Exception) { AppLogger.d(TAG, "Node recycle: ${recycleEx.message}") }
        }
    }

    fun replaceLiveInputText(newText: String, recordHistory: Boolean = true): Boolean {
        isInternalUpdating = true
        return try {
            if (recordHistory) {
                val current = getLiveInputText()
                if (current.isNotBlank()) {
                    pushInputHistory(current)
                }
            }
            val node = getLiveInputNode() ?: return false
            try {
                AppLogger.i(TAG, "Replacing live input text with length=${newText.length}")
                val arguments = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText)
                }
                val success = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
                if (!success) {
                    AppLogger.w(TAG, "ACTION_SET_TEXT failed, selecting all and falling back to clipboard paste...")
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("TextMorph", newText))
                    val textLength = node.text?.length ?: 0
                    val selectionArgs = Bundle().apply {
                        putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
                        putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, textLength)
                    }
                    node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selectionArgs)
                    node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                } else {
                    true
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed replacing live input text: ${e.message}", e)
                false
            } finally {
                try { node.recycle() } catch (recycleEx: Exception) { AppLogger.d(TAG, "Node recycle: ${recycleEx.message}") }
            }
        } finally {
            mainHandler.postDelayed({ isInternalUpdating = false }, 100L)
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
                                        try { hardwareBuffer.close() } catch (closeEx: Exception) { AppLogger.d(TAG, "HardwareBuffer close: ${closeEx.message}") }
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

    data class ChatMessage(val sender: String, val text: String, val isIncoming: Boolean)

    fun extractConversationTranscript(): String =
        ScreenContentExtractor.extractConversationTranscript({ rootInActiveWindow }, resources)

    fun isConversationContext(): Boolean =
        ScreenContentExtractor.isConversationContext({ rootInActiveWindow }, resources)

    fun extractScreenVisibleText(): String =
        ScreenContentExtractor.extractScreenVisibleText { rootInActiveWindow }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        AppLogger.d(TAG, "onConfigurationChanged orientation=${newConfig.orientation}")
        overlayManager?.onConfigurationChanged(newConfig)
    }

    override fun onInterrupt() {
        AppLogger.w(TAG, "TextMorph Accessibility Service interrupted.")
        overlayManager?.hide()
    }

    override fun onDestroy() {
        super.onDestroy()
        AppLogger.i(TAG, "TextMorph Accessibility Service destroyed.")
        try {
            clipboardListener?.let {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                cm?.removePrimaryClipChangedListener(it)
                clipboardListener = null
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "Error removing clipboard listener: ${e.message}")
        }
        try {
            screenshotExecutor.shutdown()
        } catch (e: Exception) {
            AppLogger.w(TAG, "Error shutting down screenshot executor: ${e.message}")
        }
        overlayManager?.destroy()
        overlayManager = null
        interactionOverlayManager?.onDestroy()
        interactionOverlayManager = null
        if (instance === this) {
            instance = null
        }
    }

    fun getInteractionOverlayManager(): AgentInteractionOverlayManager? = interactionOverlayManager

    companion object {
        @Volatile
        private var instance: TextMorphAccessibilityService? = null

        fun getInstance(): TextMorphAccessibilityService? = instance

        fun getOverlayManager(): FloatingToolbarWindowManager? = instance?.overlayManager

        fun getInteractionOverlayManager(): AgentInteractionOverlayManager? = instance?.interactionOverlayManager

        fun getScreenAutomationController(): ScreenAutomationController? {
            val service = instance ?: return null
            return ScreenAutomationController(service).apply {
                onTapDispatched = { x, y -> instance?.interactionOverlayManager?.showTap(x, y) }
            }
        }
    }
}
