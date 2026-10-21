package com.textmorph.app.service.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.textmorph.app.data.model.EngineType
import com.textmorph.app.data.repository.PromptRepository
import com.textmorph.app.data.repository.SettingsRepository
import com.textmorph.app.llm.LiteRtLmEngine
import com.textmorph.app.llm.OpenAiCloudEngine
import com.textmorph.app.theme.TextMorphTheme
import com.textmorph.app.util.AppLogger

class FloatingToolbarWindowManager(
    private val service: TextMorphAccessibilityService,
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

    private var userX: Int? = null
    private var userY: Int? = null

    // Raw screen touch tracking to eliminate dragging lag / jitter
    private var currentRawX = 0f
    private var currentRawY = 0f
    private var dragStartRawX = 0f
    private var dragStartRawY = 0f
    private var dragStartWinX = 0
    private var dragStartWinY = 0

    fun show(
        onTakeScreenshot: (((ByteArray) -> Unit) -> Unit)? = null,
        onExtractTranscript: (() -> String)? = null
    ) {
        try {
            if (isShowing && composeView != null) {
                return
            }

            serviceLifecycleOwner = ServiceLifecycleOwner()

            val displayMetrics = service.resources.displayMetrics
            val screenWidth = displayMetrics.widthPixels
            val screenHeight = displayMetrics.heightPixels

            val initialX = userX ?: (screenWidth - 200).coerceAtLeast(16)
            val initialY = userY ?: (screenHeight / 2 - 100).coerceAtLeast(100)

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
                x = initialX
                y = initialY
            }

            composeView = ComposeView(service).apply {
                setOnTouchListener { _, ev ->
                    currentRawX = ev.rawX
                    currentRawY = ev.rawY
                    false
                }

                setViewTreeLifecycleOwner(serviceLifecycleOwner)
                setViewTreeSavedStateRegistryOwner(serviceLifecycleOwner)
                setViewTreeViewModelStoreOwner(serviceLifecycleOwner)

                setContent {
                    val settings = settingsRepository.settingsState.value
                    val prompts = promptRepository.promptsState.value
                    val sessionState = liteRtEngine.sessionState.value

                    TextMorphTheme(themeMode = settings.theme) {
                        FloatingToolbarContent(
                            getLiveInputText = { service.getLiveInputText() },
                            prompts = prompts,
                            isVisionCapable = {
                                val currentSettings = settingsRepository.settingsState.value
                                if (currentSettings.selectedEngine == EngineType.CLOUD_OPENAI) {
                                    true
                                } else {
                                    sessionState.isVisionCapable
                                }
                            },
                            onInsertText = { result ->
                                service.replaceLiveInputText(result)
                            },
                            onRevertText = {
                                service.revertPreviousTransformation()
                            },
                            hasUndo = { service.hasPreviousTransformation() },
                            isPlacedOnRight = {
                                val currentX = userX ?: initialX
                                currentX > (screenWidth / 2)
                            },
                            onRadialStateChanged = { isOpen, offsetX, offsetY ->
                                onRadialMenuStateChanged(isOpen, offsetX, offsetY)
                            },
                            onDragStart = {
                                onDragStart()
                            },
                            onDragMove = {
                                onDragMove(screenWidth, screenHeight)
                            },
                            onTakeScreenshotContext = onTakeScreenshot,
                            onExtractTranscript = onExtractTranscript,
                            onDismiss = { hide() },
                            engineProvider = {
                                val currentSettings = settingsRepository.settingsState.value
                                val useCloud = currentSettings.selectedEngine == EngineType.CLOUD_OPENAI &&
                                        (currentSettings.cloudApiKey.isNotBlank() ||
                                                currentSettings.cloudBaseUrl.contains("localhost") ||
                                                currentSettings.cloudBaseUrl.contains("127.0.0.1"))
                                if (useCloud) {
                                    cloudEngine.updateConfig(
                                        currentSettings.cloudBaseUrl,
                                        currentSettings.cloudApiKey,
                                        currentSettings.cloudModelId
                                    )
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
            AppLogger.d(TAG, "Floating assistant displayed successfully at ($initialX, $initialY)")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error displaying floating assistant: ${e.message}", e)
        }
    }

    private fun onRadialMenuStateChanged(isOpen: Boolean, offsetX: Int, offsetY: Int) {
        try {
            val view = composeView ?: return
            val params = view.layoutParams as? WindowManager.LayoutParams ?: return
            if (isOpen) {
                params.x = (userX ?: params.x) - offsetX
                params.y = (userY ?: params.y) - offsetY
            } else {
                params.x = userX ?: params.x
                params.y = userY ?: params.y
            }
            windowManager.updateViewLayout(view, params)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed updating view layout for radial menu: ${e.message}", e)
        }
    }

    private fun onDragStart() {
        dragStartRawX = currentRawX
        dragStartRawY = currentRawY
        val params = composeView?.layoutParams as? WindowManager.LayoutParams
        if (params != null) {
            dragStartWinX = params.x
            dragStartWinY = params.y
        }
    }

    private fun onDragMove(screenWidth: Int, screenHeight: Int) {
        try {
            val view = composeView ?: return
            val params = view.layoutParams as? WindowManager.LayoutParams ?: return
            val dx = (currentRawX - dragStartRawX).toInt()
            val dy = (currentRawY - dragStartRawY).toInt()
            params.x = (dragStartWinX + dx).coerceIn(0, (screenWidth - 80).coerceAtLeast(0))
            params.y = (dragStartWinY + dy).coerceIn(60, (screenHeight - 120).coerceAtLeast(60))
            userX = params.x
            userY = params.y
            windowManager.updateViewLayout(view, params)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed updating dragged overlay layout: ${e.message}", e)
        }
    }

    fun isShowing(): Boolean = isShowing

    fun hide() {
        if (!isShowing || composeView == null) return
        try {
            windowManager.removeView(composeView)
            composeView = null
            serviceLifecycleOwner?.destroy()
            serviceLifecycleOwner = null
            isShowing = false
            AppLogger.d(TAG, "Floating assistant hidden.")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error removing floating assistant overlay: ${e.message}", e)
        }
    }

    fun destroy() {
        hide()
    }
}
