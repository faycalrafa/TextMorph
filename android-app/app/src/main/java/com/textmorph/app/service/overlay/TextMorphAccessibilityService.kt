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
        liteRtEngine = com.textmorph.app.llm.LiteRtLmEngineManager.getInstance(applicationContext)
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

        val source = event.source
        try {
            val keyboardVisible = isKeyboardVisible()

            if (keyboardVisible) {
                // Keyboard is open, verify if there is an editable field
                val isEditable = source?.isEditable == true || isCurrentInputEditable()
                if (isEditable) {
                    overlayManager?.show(
                        onTakeScreenshot = { captureScreenContext() }
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
            findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                ?: rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
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

    fun replaceLiveInputText(newText: String): Boolean {
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
