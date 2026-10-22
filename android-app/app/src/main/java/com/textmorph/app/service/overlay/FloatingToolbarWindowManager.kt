package com.textmorph.app.service.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowManager
import androidx.compose.runtime.mutableStateOf
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.hypot
import kotlin.math.roundToInt

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
    private val windowScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var userX: Int? = null
    private var userY: Int? = null

    private var isRadialMenuOpen = false
    private val isDraggingState = mutableStateOf(false)
    private var singleTapHandler: (() -> Unit)? = null

    // Raw physical screen touch tracking to eliminate dragging lag / jitter
    private var startRawX = 0f
    private var startRawY = 0f
    private var startWinX = 0
    private var startWinY = 0
    private var isDragging = false
    private var hasMovedPastSlop = false
    private val longPressHandler = Handler(Looper.getMainLooper())
    private val longPressRunnable = Runnable {
        if (!isRadialMenuOpen && isShowing) {
            isDragging = true
            isDraggingState.value = true
            composeView?.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            AppLogger.d(TAG, "Long-press activated: Drag mode engaged")
        }
    }

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
            val buttonSizePx = (48 * displayMetrics.density).roundToInt()

            val minX = 0
            val maxX = (screenWidth - buttonSizePx).coerceAtLeast(0)
            val minY = (40 * displayMetrics.density).roundToInt()
            val maxY = (screenHeight - buttonSizePx - (40 * displayMetrics.density).roundToInt()).coerceAtLeast(minY)

            val settings = settingsRepository.settingsState.value
            val savedX = settings.floatingToolbarX
            val savedY = settings.floatingToolbarY

            val initialX = (savedX ?: userX ?: (screenWidth - buttonSizePx - 24)).coerceIn(minX, maxX)
            val initialY = (savedY ?: userY ?: (screenHeight / 2 - buttonSizePx / 2)).coerceIn(minY, maxY)

            userX = initialX
            userY = initialY

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
                setViewTreeLifecycleOwner(serviceLifecycleOwner)
                setViewTreeSavedStateRegistryOwner(serviceLifecycleOwner)
                setViewTreeViewModelStoreOwner(serviceLifecycleOwner)

                setOnTouchListener { _, event ->
                    if (isRadialMenuOpen) {
                        // When radial menu is open, let Compose handle clicks on items & outside dismissal
                        return@setOnTouchListener false
                    }

                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            startRawX = event.rawX
                            startRawY = event.rawY
                            val p = (layoutParams as? WindowManager.LayoutParams) ?: params
                            startWinX = p.x
                            startWinY = p.y
                            hasMovedPastSlop = false
                            isDragging = false
                            isDraggingState.value = false
                            longPressHandler.removeCallbacks(longPressRunnable)
                            longPressHandler.postDelayed(longPressRunnable, 260)
                            true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            val dx = event.rawX - startRawX
                            val dy = event.rawY - startRawY
                            val touchSlop = ViewConfiguration.get(service).scaledTouchSlop.coerceAtLeast(16)

                            if (!isDragging) {
                                val dist = hypot(dx.toDouble(), dy.toDouble()).toFloat()
                                if (dist > touchSlop) {
                                    // Moved too early before 260ms elapsed -> cancel long press
                                    longPressHandler.removeCallbacks(longPressRunnable)
                                    hasMovedPastSlop = true
                                }
                            } else {
                                // Actively dragging! Update window coordinates smoothly via raw hardware coordinates
                                val curMetrics = service.resources.displayMetrics
                                val curWidth = curMetrics.widthPixels
                                val curHeight = curMetrics.heightPixels
                                val btnPx = (48 * curMetrics.density).roundToInt()
                                val boundMinX = 0
                                val boundMaxX = (curWidth - btnPx).coerceAtLeast(0)
                                val boundMinY = (40 * curMetrics.density).roundToInt()
                                val boundMaxY = (curHeight - btnPx - (40 * curMetrics.density).roundToInt()).coerceAtLeast(boundMinY)

                                val p = (layoutParams as? WindowManager.LayoutParams) ?: params
                                p.x = (startWinX + dx).roundToInt().coerceIn(boundMinX, boundMaxX)
                                p.y = (startWinY + dy).roundToInt().coerceIn(boundMinY, boundMaxY)
                                userX = p.x
                                userY = p.y
                                windowManager.updateViewLayout(this, p)
                            }
                            true
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            longPressHandler.removeCallbacks(longPressRunnable)
                            if (isDragging) {
                                isDragging = false
                                isDraggingState.value = false
                                val p = (layoutParams as? WindowManager.LayoutParams) ?: params
                                userX = p.x
                                userY = p.y
                                AppLogger.d(TAG, "Overlay dropped at ($userX, $userY). Persisting position to settings.")
                                windowScope.launch(Dispatchers.IO) {
                                    try {
                                        settingsRepository.setFloatingToolbarPosition(p.x, p.y)
                                        AppLogger.i(TAG, "Persisted floating toolbar position: (${p.x}, ${p.y})")
                                    } catch (e: Exception) {
                                        AppLogger.e(TAG, "Failed persisting floating toolbar position: ${e.message}", e)
                                    }
                                }
                            } else if (!hasMovedPastSlop && event.actionMasked == MotionEvent.ACTION_UP) {
                                // Clean single tap!
                                singleTapHandler?.invoke()
                            }
                            true
                        }
                        else -> false
                    }
                }

                setContent {
                    val curSettings = settingsRepository.settingsState.value
                    val prompts = promptRepository.promptsState.value
                    val sessionState = liteRtEngine.sessionState.value

                    TextMorphTheme(themeMode = curSettings.theme) {
                        FloatingToolbarContent(
                            getLiveInputText = { service.getLiveInputText() },
                            prompts = prompts,
                            isVisionCapable = {
                                val s = settingsRepository.settingsState.value
                                if (s.selectedEngine == EngineType.CLOUD_OPENAI) {
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
                                val curScreenWidth = service.resources.displayMetrics.widthPixels
                                currentX > (curScreenWidth / 2)
                            },
                            isDragging = isDraggingState.value,
                            onRegisterSingleTapHandler = { handler ->
                                singleTapHandler = handler
                            },
                            onRadialStateChanged = { isOpen, offsetX, offsetY ->
                                onRadialMenuStateChanged(isOpen, offsetX, offsetY)
                            },
                            onTakeScreenshotContext = onTakeScreenshot,
                            onExtractTranscript = onExtractTranscript,
                            onDismiss = { hide() },
                            engineProvider = {
                                val s = settingsRepository.settingsState.value
                                val useCloud = s.selectedEngine == EngineType.CLOUD_OPENAI &&
                                        (s.cloudApiKey.isNotBlank() ||
                                                s.cloudBaseUrl.contains("localhost") ||
                                                s.cloudBaseUrl.contains("127.0.0.1"))
                                if (useCloud) {
                                    cloudEngine.updateConfig(
                                        s.cloudBaseUrl,
                                        s.cloudApiKey,
                                        s.cloudModelId
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
            isRadialMenuOpen = isOpen
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

    fun setOverlayVisible(visible: Boolean) {
        service.mainHandler.post {
            try {
                composeView?.visibility = if (visible) android.view.View.VISIBLE else android.view.View.INVISIBLE
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed setting overlay visibility to $visible: ${e.message}", e)
            }
        }
    }

    fun isShowing(): Boolean = isShowing

    fun hide() {
        if (!isShowing || composeView == null) return
        try {
            longPressHandler.removeCallbacks(longPressRunnable)
            isDragging = false
            isDraggingState.value = false
            isRadialMenuOpen = false
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
        windowScope.cancel()
    }
}
