package com.textmorph.app.service.overlay

import android.content.Context
import android.content.res.Configuration
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

/**
 * Manages the window lifecycle, overlay layout params, and touch routing
 * for the TextMorph Companion (Collapsed Edge Pill & Expanded Horizontal Ribbon).
 */
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

    private var isExpanded = false
    private val isDraggingState = mutableStateOf(false)
    private var isBusyState = false
    private var singleTapHandler: (() -> Unit)? = null
    private var collapseHandler: (() -> Unit)? = null

    // Touch tracking for drag & edge snapping
    private var startRawX = 0f
    private var startRawY = 0f
    private var startWinX = 0
    private var startWinY = 0
    private var isDragging = false
    private var hasMovedPastSlop = false
    private val longPressHandler = Handler(Looper.getMainLooper())
    private val longPressRunnable = Runnable {
        if (!isExpanded && isShowing) {
            isDragging = true
            isDraggingState.value = true
            composeView?.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            AppLogger.d(TAG, "Companion drag mode engaged via long press")
        }
    }

    fun isBusy(): Boolean = isBusyState

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
            val pillSizePx = (36 * displayMetrics.density).roundToInt()
            val marginPx = (6 * displayMetrics.density).roundToInt()

            val minX = marginPx
            val maxX = (screenWidth - pillSizePx - marginPx).coerceAtLeast(minX)
            val minY = (40 * displayMetrics.density).roundToInt()
            val maxY = (screenHeight - pillSizePx - (40 * displayMetrics.density).roundToInt()).coerceAtLeast(minY)

            val settings = settingsRepository.settingsState.value
            val savedX = settings.floatingToolbarX
            val savedY = settings.floatingToolbarY

            val initialX = (savedX ?: userX ?: maxX).coerceIn(minX, maxX)
            val initialY = (savedY ?: userY ?: (screenHeight / 2 - pillSizePx / 2)).coerceIn(minY, maxY)

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
                    // Handle outside tap when ribbon is expanded to dismiss smoothly
                    if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                        AppLogger.d(TAG, "ACTION_OUTSIDE detected: collapsing companion ribbon")
                        collapseHandler?.invoke()
                        return@setOnTouchListener true
                    }

                    if (isExpanded) {
                        // When ribbon is open, let Compose handle clicks on horizontal action chips
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
                            longPressHandler.postDelayed(longPressRunnable, 350)
                            true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            val dx = event.rawX - startRawX
                            val dy = event.rawY - startRawY
                            val touchSlop = ViewConfiguration.get(service).scaledTouchSlop.coerceAtLeast(16)

                            if (!isDragging) {
                                val dist = hypot(dx.toDouble(), dy.toDouble()).toFloat()
                                if (dist > touchSlop) {
                                    longPressHandler.removeCallbacks(longPressRunnable)
                                    hasMovedPastSlop = true
                                }
                            } else {
                                val curMetrics = service.resources.displayMetrics
                                val curWidth = curMetrics.widthPixels
                                val curHeight = curMetrics.heightPixels
                                val btnPx = (36 * curMetrics.density).roundToInt()
                                val mPx = (6 * curMetrics.density).roundToInt()
                                val boundMinX = mPx
                                val boundMaxX = (curWidth - btnPx - mPx).coerceAtLeast(boundMinX)
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
                                val curMetrics = service.resources.displayMetrics
                                val curWidth = curMetrics.widthPixels
                                val btnPx = (36 * curMetrics.density).roundToInt()
                                val mPx = (6 * curMetrics.density).roundToInt()
                                val boundMinX = mPx
                                val boundMaxX = (curWidth - btnPx - mPx).coerceAtLeast(boundMinX)

                                val p = (layoutParams as? WindowManager.LayoutParams) ?: params
                                // Magnetic snap to nearest edge
                                val snapX = if (p.x < curWidth / 2) boundMinX else boundMaxX
                                p.x = snapX
                                userX = p.x
                                userY = p.y
                                windowManager.updateViewLayout(this, p)
                                composeView?.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)

                                windowScope.launch(Dispatchers.IO) {
                                    try {
                                        settingsRepository.setFloatingToolbarPosition(p.x, p.y)
                                        AppLogger.i(TAG, "Persisted snapped companion position: (${p.x}, ${p.y})")
                                    } catch (e: Exception) {
                                        AppLogger.e(TAG, "Failed persisting companion position: ${e.message}", e)
                                    }
                                }
                            } else if (!hasMovedPastSlop && event.actionMasked == MotionEvent.ACTION_UP) {
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
                            getLatestIncomingMessage = { service.getLatestIncomingMessage() },
                            getConversationTranscript = { service.extractConversationTranscript() },
                            prompts = prompts,
                            isVisionCapable = {
                                val s = settingsRepository.settingsState.value
                                if (s.selectedEngine == EngineType.CLOUD_OPENAI) {
                                    true
                                } else {
                                    sessionState.isVisionCapable
                                }
                            },
                            onInsertText = { result, isIntermediate ->
                                service.replaceLiveInputText(result, recordHistory = !isIntermediate)
                            },
                            onSnapshotOriginalText = { original ->
                                service.setPreviousTransformation(original)
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
                            onRegisterCollapseHandler = { handler ->
                                collapseHandler = handler
                            },
                            onExpandedStateChanged = { expanded ->
                                onRibbonExpansionChanged(expanded)
                            },
                            onBusyStateChanged = { busy ->
                                isBusyState = busy
                            },
                            onTakeScreenshotContext = onTakeScreenshot,
                            autoSuggestReplies = curSettings.autoSuggestReplies,
                            onToggleAutoSuggestReplies = { enabled ->
                                windowScope.launch(Dispatchers.IO) {
                                    try {
                                        settingsRepository.setAutoSuggestReplies(enabled)
                                        AppLogger.i(TAG, "Updated autoSuggestReplies: $enabled")
                                    } catch (e: Exception) {
                                        AppLogger.e(TAG, "Failed updating autoSuggestReplies: ${e.message}", e)
                                    }
                                }
                            },
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
            AppLogger.d(TAG, "TextMorph companion displayed successfully at ($initialX, $initialY)")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error displaying TextMorph companion: ${e.message}", e)
        }
    }

    private fun onRibbonExpansionChanged(expanded: Boolean) {
        try {
            isExpanded = expanded
            val view = composeView ?: return
            val params = view.layoutParams as? WindowManager.LayoutParams ?: return
            val displayMetrics = service.resources.displayMetrics
            val screenWidth = displayMetrics.widthPixels
            val screenHeight = displayMetrics.heightPixels
            val density = displayMetrics.density

            val marginPx = (8 * density).roundToInt()
            val minY = (40 * density).roundToInt()
            val maxY = (screenHeight - (56 * density).roundToInt() - (40 * density).roundToInt()).coerceAtLeast(minY)

            if (expanded) {
                // Expanded companion ribbon: horizontal dock with outside touch watch
                val ribbonWidth = screenWidth - (marginPx * 2)
                params.width = ribbonWidth
                params.height = WindowManager.LayoutParams.WRAP_CONTENT
                params.flags = params.flags or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                params.x = marginPx
                // Keep y near the user's selected height, safely clamped
                params.y = (userY ?: (screenHeight / 2)).coerceIn(minY, maxY)
            } else {
                // Collapsed edge pill
                params.width = WindowManager.LayoutParams.WRAP_CONTENT
                params.height = WindowManager.LayoutParams.WRAP_CONTENT
                params.flags = params.flags and WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH.inv()
                params.x = userX ?: (screenWidth - (40 * density).roundToInt())
                params.y = (userY ?: (screenHeight / 2)).coerceIn(minY, maxY)
            }
            windowManager.updateViewLayout(view, params)
            AppLogger.d(TAG, "Ribbon expansion changed to $expanded: layout at (${params.x}, ${params.y}) w=${params.width}")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed updating view layout for companion expansion: ${e.message}", e)
        }
    }

    fun onConfigurationChanged(newConfig: Configuration) {
        try {
            val displayMetrics = service.resources.displayMetrics
            val screenWidth = displayMetrics.widthPixels
            val screenHeight = displayMetrics.heightPixels
            val pillSizePx = (36 * displayMetrics.density).roundToInt()
            val marginPx = (6 * displayMetrics.density).roundToInt()

            val minX = marginPx
            val maxX = (screenWidth - pillSizePx - marginPx).coerceAtLeast(minX)
            val minY = (40 * displayMetrics.density).roundToInt()
            val maxY = (screenHeight - pillSizePx - (40 * displayMetrics.density).roundToInt()).coerceAtLeast(minY)

            userX = (userX ?: maxX).coerceIn(minX, maxX)
            userY = (userY ?: (screenHeight / 2)).coerceIn(minY, maxY)

            val view = composeView ?: return
            val params = view.layoutParams as? WindowManager.LayoutParams ?: return

            if (isExpanded) {
                val ribbonWidth = screenWidth - (8 * displayMetrics.density).roundToInt() * 2
                params.width = ribbonWidth
                params.x = (8 * displayMetrics.density).roundToInt()
                params.y = (userY ?: (screenHeight / 2)).coerceIn(minY, maxY)
            } else {
                params.x = userX ?: maxX
                params.y = userY ?: (screenHeight / 2)
            }
            windowManager.updateViewLayout(view, params)
            AppLogger.i(TAG, "Adapted companion layout to new orientation (${newConfig.orientation}): (${params.x}, ${params.y})")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error adapting to configuration change: ${e.message}", e)
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
            isExpanded = false
            singleTapHandler = null
            collapseHandler = null
            windowManager.removeView(composeView)
            composeView = null
            serviceLifecycleOwner?.destroy()
            serviceLifecycleOwner = null
            isShowing = false
            AppLogger.d(TAG, "TextMorph companion hidden.")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error removing TextMorph companion overlay: ${e.message}", e)
        }
    }

    fun destroy() {
        hide()
        windowScope.cancel()
    }
}
