package com.textmorph.app.service.overlay

import android.content.Context
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.HapticFeedbackConstants
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
import kotlin.math.roundToInt

/**
 * Manages the window lifecycle and layout updates for the TextMorph Companion overlay.
 * Uses FloatingOverlayContainer to guarantee touch gesture interception for instant swipe dragging.
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
    private var overlayContainer: FloatingOverlayContainer? = null
    private var composeView: ComposeView? = null
    private var serviceLifecycleOwner: ServiceLifecycleOwner? = null
    private var isShowing = false
    private val windowScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var userX: Int? = null
    private var userY: Int? = null

    private var isExpanded = false
    private var lastExpandedTimeMs = 0L
    private val isDraggingState = mutableStateOf(false)
    private var isBusyState = false
    private var singleTapHandler: (() -> Unit)? = null
    private var collapseHandler: (() -> Unit)? = null

    // Window layout params reference
    private var windowParams: WindowManager.LayoutParams? = null
    private var startDragWinX = 0
    private var startDragWinY = 0

    fun isBusy(): Boolean = isBusyState

    fun show(
        onTakeScreenshot: (((ByteArray) -> Unit) -> Unit)? = null,
        onExtractTranscript: (() -> String)? = null
    ) {
        try {
            if (isShowing && overlayContainer != null) {
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
            windowParams = params

            overlayContainer = FloatingOverlayContainer(
                context = service,
                isExpandedProvider = { isExpanded },
                onDragStart = {
                    startDragWinX = params.x
                    startDragWinY = params.y
                    isDraggingState.value = true
                },
                onDragMove = { deltaX, deltaY ->
                    val curMetrics = service.resources.displayMetrics
                    val curWidth = curMetrics.widthPixels
                    val curHeight = curMetrics.heightPixels
                    val btnPx = (36 * curMetrics.density).roundToInt()
                    val mPx = (6 * curMetrics.density).roundToInt()
                    val boundMinX = mPx
                    val boundMaxX = (curWidth - btnPx - mPx).coerceAtLeast(boundMinX)
                    val boundMinY = (40 * curMetrics.density).roundToInt()
                    val boundMaxY = (curHeight - btnPx - (40 * curMetrics.density).roundToInt()).coerceAtLeast(boundMinY)

                    params.x = (startDragWinX + deltaX).roundToInt().coerceIn(boundMinX, boundMaxX)
                    params.y = (startDragWinY + deltaY).roundToInt().coerceIn(boundMinY, boundMaxY)
                    userX = params.x
                    userY = params.y
                    overlayContainer?.let { windowManager.updateViewLayout(it, params) }
                },
                onDragEnd = {
                    val curMetrics = service.resources.displayMetrics
                    val curWidth = curMetrics.widthPixels
                    val btnPx = (36 * curMetrics.density).roundToInt()
                    val mPx = (6 * curMetrics.density).roundToInt()
                    val boundMinX = mPx
                    val boundMaxX = (curWidth - btnPx - mPx).coerceAtLeast(boundMinX)

                    val snapX = if (params.x < curWidth / 2) boundMinX else boundMaxX
                    params.x = snapX
                    userX = snapX
                    isDraggingState.value = false
                    overlayContainer?.let {
                        windowManager.updateViewLayout(it, params)
                        it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    }

                    windowScope.launch(Dispatchers.IO) {
                        try {
                            settingsRepository.setFloatingToolbarPosition(snapX, params.y)
                            AppLogger.i(TAG, "Persisted snapped companion position: ($snapX, ${params.y})")
                        } catch (e: Exception) {
                            AppLogger.e(TAG, "Failed persisting companion position: ${e.message}", e)
                        }
                    }
                },
                onSingleTap = {
                    AppLogger.d(TAG, "onSingleTap received from container. isExpanded=$isExpanded, singleTapHandlerSet=${singleTapHandler != null}")
                    if (singleTapHandler != null) {
                        singleTapHandler?.invoke()
                    } else {
                        AppLogger.w(TAG, "singleTapHandler not yet bound, toggling ribbon expansion directly")
                        onRibbonExpansionChanged(!isExpanded)
                    }
                },
                onOutsideTap = {
                    val now = android.os.SystemClock.uptimeMillis()
                    val elapsed = now - lastExpandedTimeMs
                    if (elapsed > 400L) {
                        AppLogger.d(TAG, "onOutsideTap triggered after ${elapsed}ms (> 400ms), collapsing ribbon")
                        collapseHandler?.invoke() ?: onRibbonExpansionChanged(false)
                    } else {
                        AppLogger.d(TAG, "Ignoring outside tap during expansion debounce window (${elapsed}ms <= 400ms)")
                    }
                }
            ).apply {
                setViewTreeLifecycleOwner(serviceLifecycleOwner)
                setViewTreeSavedStateRegistryOwner(serviceLifecycleOwner)
                setViewTreeViewModelStoreOwner(serviceLifecycleOwner)
            }

            composeView = ComposeView(service).apply {
                setViewTreeLifecycleOwner(serviceLifecycleOwner)
                setViewTreeSavedStateRegistryOwner(serviceLifecycleOwner)
                setViewTreeViewModelStoreOwner(serviceLifecycleOwner)

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

            overlayContainer?.addView(composeView)
            windowManager.addView(overlayContainer, params)
            isShowing = true
            AppLogger.d(TAG, "TextMorph companion displayed successfully at ($initialX, $initialY)")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error displaying TextMorph companion: ${e.message}", e)
        }
    }

    private fun onRibbonExpansionChanged(expanded: Boolean) {
        try {
            isExpanded = expanded
            val container = overlayContainer ?: return
            val params = windowParams ?: return
            val displayMetrics = service.resources.displayMetrics
            val screenWidth = displayMetrics.widthPixels
            val screenHeight = displayMetrics.heightPixels
            val density = displayMetrics.density

            val marginPx = (8 * density).roundToInt()
            val minY = (40 * density).roundToInt()
            val maxY = (screenHeight - (56 * density).roundToInt() - (40 * density).roundToInt()).coerceAtLeast(minY)

            if (expanded) {
                lastExpandedTimeMs = android.os.SystemClock.uptimeMillis()
                // Expanded companion ribbon: horizontal dock with outside touch watch
                val ribbonWidth = screenWidth - (marginPx * 2)
                params.width = ribbonWidth
                params.height = WindowManager.LayoutParams.WRAP_CONTENT
                params.flags = params.flags or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                params.x = marginPx
                params.y = (userY ?: (screenHeight / 2)).coerceIn(minY, maxY)
            } else {
                // Collapsed edge pill
                params.width = WindowManager.LayoutParams.WRAP_CONTENT
                params.height = WindowManager.LayoutParams.WRAP_CONTENT
                params.flags = params.flags and WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH.inv()
                params.x = userX ?: (screenWidth - (40 * density).roundToInt())
                params.y = (userY ?: (screenHeight / 2)).coerceIn(minY, maxY)
            }
            windowManager.updateViewLayout(container, params)
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

            val container = overlayContainer ?: return
            val params = windowParams ?: return

            if (isExpanded) {
                val ribbonWidth = screenWidth - (8 * displayMetrics.density).roundToInt() * 2
                params.width = ribbonWidth
                params.x = (8 * displayMetrics.density).roundToInt()
                params.y = (userY ?: (screenHeight / 2)).coerceIn(minY, maxY)
            } else {
                params.x = userX ?: maxX
                params.y = userY ?: (screenHeight / 2)
            }
            windowManager.updateViewLayout(container, params)
            AppLogger.i(TAG, "Adapted companion layout to new orientation (${newConfig.orientation}): (${params.x}, ${params.y})")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error adapting to configuration change: ${e.message}", e)
        }
    }

    fun setOverlayVisible(visible: Boolean) {
        service.mainHandler.post {
            try {
                overlayContainer?.visibility = if (visible) android.view.View.VISIBLE else android.view.View.INVISIBLE
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed setting overlay visibility to $visible: ${e.message}", e)
            }
        }
    }

    fun isShowing(): Boolean = isShowing

    fun hide() {
        if (!isShowing || overlayContainer == null) return
        try {
            isDraggingState.value = false
            isExpanded = false
            singleTapHandler = null
            collapseHandler = null
            windowParams = null
            overlayContainer?.let { windowManager.removeView(it) }
            composeView = null
            overlayContainer = null
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
