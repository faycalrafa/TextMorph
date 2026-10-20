package com.textmorph.app.service.overlay

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.textmorph.app.data.repository.PromptRepository
import com.textmorph.app.data.repository.SettingsRepository
import com.textmorph.app.llm.LiteRtLmEngine
import com.textmorph.app.llm.OpenAiCloudEngine
import com.textmorph.app.util.AppLogger
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

class TextMorphAccessibilityService : AccessibilityService() {

    private val TAG = "TextMorphAccessService"
    private var overlayManager: FloatingToolbarWindowManager? = null
    private lateinit var promptRepository: PromptRepository
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var liteRtEngine: LiteRtLmEngine
    private lateinit var cloudEngine: OpenAiCloudEngine

    override fun onServiceConnected() {
        super.onServiceConnected()
        AppLogger.i(TAG, "TextMorph Accessibility Service connected.")

        promptRepository = PromptRepository(applicationContext)
        settingsRepository = SettingsRepository(applicationContext)
        liteRtEngine = LiteRtLmEngine(applicationContext)
        cloudEngine = OpenAiCloudEngine()

        overlayManager = FloatingToolbarWindowManager(
            service = this,
            promptRepository = promptRepository,
            settingsRepository = settingsRepository,
            liteRtEngine = liteRtEngine,
            cloudEngine = cloudEngine
        )
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val settings = settingsRepository.settingsState.value
        if (!settings.floatingToolbarEnabled) {
            overlayManager?.hide()
            return
        }

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                handleNodeInteraction(event)
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                // If keyboard or layout changed, keep overlay synced
            }
        }
    }

    private fun handleNodeInteraction(event: AccessibilityEvent) {
        val source = event.source ?: return
        try {
            if (source.isEditable) {
                val bounds = Rect()
                source.getBoundsInScreen(bounds)

                // Skip placeholder hint text
                val isHint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    source.isShowingHintText
                } else false

                val text = if (!isHint) source.text?.toString() ?: "" else ""

                overlayManager?.show(
                    inputBounds = bounds,
                    currentText = text,
                    onInsertText = { replacement ->
                        replaceNodeText(source, replacement)
                    },
                    onTakeScreenshot = {
                        captureScreenContext()
                    }
                )
            } else {
                // Non-editable field focused, hide overlay
                overlayManager?.hide()
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error handling accessibility event: ${e.message}", e)
        } finally {
            // Contract: Always recycle AccessibilityNodeInfo to prevent memory leaks and binder exhaustion
            try {
                source.recycle()
            } catch (recycleEx: Exception) {
                // Ignore already recycled exceptions
            }
        }
    }

    private fun replaceNodeText(node: AccessibilityNodeInfo, text: String) {
        try {
            AppLogger.i(TAG, "Replacing node text with length=${text.length}")
            val arguments = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            val success = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
            if (!success) {
                AppLogger.w(TAG, "ACTION_SET_TEXT failed, attempting clipboard paste fallback...")
                node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed replacing node text: ${e.message}", e)
        }
    }

    private fun captureScreenContext() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                AppLogger.i(TAG, "Capturing screen for multimodal vision context...")
                takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    Executors.newSingleThreadExecutor(),
                    object : TakeScreenshotCallback {
                        override fun onSuccess(screenshot: ScreenshotResult) {
                            try {
                                val bitmap = Bitmap.wrapHardwareBuffer(
                                    screenshot.hardwareBuffer,
                                    screenshot.colorSpace
                                )
                                if (bitmap != null) {
                                    val stream = ByteArrayOutputStream()
                                    bitmap.compress(Bitmap.CompressFormat.JPEG, 85, stream)
                                    val bytes = stream.toByteArray()
                                    AppLogger.i(TAG, "Screen captured successfully: ${bytes.size} bytes.")
                                }
                            } catch (e: Exception) {
                                AppLogger.e(TAG, "Error processing screenshot hardware buffer: ${e.message}", e)
                            }
                        }

                        override fun onFailure(errorCode: Int) {
                            AppLogger.w(TAG, "Take screenshot callback failed with code: $errorCode")
                        }
                    }
                )
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to initiate screen capture: ${e.message}", e)
            }
        }
    }

    override fun onInterrupt() {
        AppLogger.w(TAG, "TextMorph Accessibility Service interrupted.")
        overlayManager?.hide()
    }

    override fun onDestroy() {
        super.onDestroy()
        AppLogger.i(TAG, "TextMorph Accessibility Service destroyed.")
        overlayManager?.destroy()
        overlayManager = null
    }
}
