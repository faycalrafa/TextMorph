package com.textmorph.app.service.overlay

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.view.Gravity
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.textmorph.app.data.model.AppSettings
import com.textmorph.app.data.model.EngineType
import com.textmorph.app.data.repository.PromptRepository
import com.textmorph.app.data.repository.SettingsRepository
import com.textmorph.app.llm.LiteRtLmEngine
import com.textmorph.app.llm.LlmEngine
import com.textmorph.app.llm.OpenAiCloudEngine
import com.textmorph.app.theme.TextMorphTheme
import com.textmorph.app.util.AppLogger

class FloatingToolbarWindowManager(
    private val service: AccessibilityService,
    private val promptRepository: PromptRepository,
    private val settingsRepository: SettingsRepository,
    private val liteRtEngine: LiteRtLmEngine,
    private val cloudEngine: OpenAiCloudEngine
) {

    private val TAG = "FloatingWindowManager"
    private val windowManager = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var composeView: ComposeView? = null
    private var serviceLifecycleOwner: ServiceLifecycleOwner? = null
    private var isShowing = false

    fun show(
        inputBounds: Rect,
        currentText: String,
        onInsertText: (String) -> Unit,
        onTakeScreenshot: (() -> Unit)? = null
    ) {
        try {
            if (isShowing && composeView != null) {
                // Update position if already shown
                updatePosition(inputBounds)
                return
            }

            serviceLifecycleOwner = ServiceLifecycleOwner()

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = (inputBounds.left).coerceAtLeast(16)
                y = (inputBounds.top - 120).coerceAtLeast(100)
            }

            composeView = ComposeView(service).apply {
                setViewTreeLifecycleOwner(serviceLifecycleOwner)
                setViewTreeSavedStateRegistryOwner(serviceLifecycleOwner)
                setViewTreeViewModelStoreOwner(serviceLifecycleOwner)

                setContent {
                    val settings = settingsRepository.settingsState.value
                    val prompts = promptRepository.promptsState.value

                    TextMorphTheme(themeMode = settings.theme) {
                        FloatingToolbarContent(
                            currentFieldText = currentText,
                            prompts = prompts,
                            onInsertText = { result ->
                                onInsertText(result)
                            },
                            onCopyText = { copyText ->
                                val cm = service.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cm.setPrimaryClip(ClipData.newPlainText("TextMorph", copyText))
                                Toast.makeText(service, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                            },
                            onTakeScreenshotContext = onTakeScreenshot,
                            onDismiss = { hide() },
                            engineProvider = {
                                if (settings.selectedEngine == EngineType.CLOUD_OPENAI) {
                                    cloudEngine.updateConfig(settings.cloudBaseUrl, settings.cloudApiKey, settings.cloudModelId)
                                    cloudEngine
                                } else {
                                    liteRtEngine
                                }
                            }
                        )
                    }
                }
            }

            windowManager.addView(composeView, params)
            isShowing = true
            AppLogger.d(TAG, "Floating toolbar overlay displayed successfully above bounds: $inputBounds")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error displaying floating toolbar overlay: ${e.message}", e)
        }
    }

    private fun updatePosition(inputBounds: Rect) {
        try {
            val view = composeView ?: return
            val params = view.layoutParams as? WindowManager.LayoutParams ?: return
            params.x = (inputBounds.left).coerceAtLeast(16)
            params.y = (inputBounds.top - 120).coerceAtLeast(100)
            windowManager.updateViewLayout(view, params)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to update overlay position: ${e.message}", e)
        }
    }

    fun hide() {
        if (!isShowing || composeView == null) return
        try {
            windowManager.removeView(composeView)
            composeView = null
            serviceLifecycleOwner?.destroy()
            serviceLifecycleOwner = null
            isShowing = false
            AppLogger.d(TAG, "Floating toolbar overlay hidden.")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error removing overlay view: ${e.message}", e)
        }
    }

    fun destroy() {
        hide()
    }
}
