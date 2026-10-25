package com.textmorph.app.service.overlay

import android.content.Context
import android.content.res.Configuration
import android.view.HapticFeedbackConstants
import android.view.WindowManager
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.textmorph.app.data.model.EngineType
import com.textmorph.app.data.repository.ChatSessionRepository
import com.textmorph.app.data.repository.PromptRepository
import com.textmorph.app.data.repository.SettingsRepository
import com.textmorph.app.data.repository.SlashCommandRepository
import com.textmorph.app.llm.LiteRtLmEngine
import com.textmorph.app.llm.LlamaCppEngine
import com.textmorph.app.llm.LlamaCppEngineManager
import com.textmorph.app.llm.OpenAiCloudEngine
import com.textmorph.app.service.overlay.components.FloatingEdgePill
import com.textmorph.app.theme.TextMorphTheme
import com.textmorph.app.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Manages the unified window lifecycle, touch gestures, focus states, and transitions across:
 * - COLLAPSED_BUBBLE: Minimal draggable Messenger bubble head.
 * - EXPANDED_TOOLBAR: Ultra-low footprint companion ribbon.
 * - EXPANDED_CHAT: Floating full-featured chat bubble.
 * - AUTOMATING_ACTION: Target-seeking coordinate gliding with circular thinking animation.
 */
class FloatingToolbarWindowManager(
    private val service: TextMorphAccessibilityService,
    private val promptRepository: PromptRepository,
    private val settingsRepository: SettingsRepository,
    private val liteRtEngine: LiteRtLmEngine,
    private val cloudEngine: OpenAiCloudEngine,
    private val llamaCppEngine: LlamaCppEngine = LlamaCppEngineManager.getInstance(service),
    private val slashCommandRepository: SlashCommandRepository = SlashCommandRepository.getInstance(service),
    private val chatSessionRepository: ChatSessionRepository = ChatSessionRepository.getInstance(service)
) {
    private val TAG = "FloatingWindowManager"
    private val windowManager = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var overlayContainer: FloatingOverlayContainer? = null
    private var composeView: ComposeView? = null
    private var serviceLifecycleOwner: ServiceLifecycleOwner? = null
    private var isShowing = false
    val isShowingActive: Boolean get() = isShowing && overlayContainer != null
    private val windowScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var bubbleAnchorX: Int? = null
    private var bubbleAnchorY: Int? = null

    // Overlay state orchestration
    var displayState by mutableStateOf(OverlayDisplayState.COLLAPSED_BUBBLE)
        private set
    private val isDraggingState = mutableStateOf(false)
    private var isBusyState = false
    private var isChatInputFocused = false

    private var singleTapHandler: (() -> Unit)? = null
    private var collapseHandler: (() -> Unit)? = null
    private var userTypedHandler: ((String) -> Unit)? = null
    private var userNavigatedBackHandler: (() -> Unit)? = null
    private var windowParams: WindowManager.LayoutParams? = null
    private var startDragWinX = 0
    private var startDragWinY = 0

    // Automation Controller & Coordinate Animator
    private val coordinateAnimator = OverlayCoordinateAnimator(windowManager, { overlayContainer }, { windowParams })
    private val automationController = ScreenAutomationController(service)
    val actionExecutor = DeviceActionExecutor(service, automationController, liteRtEngine, llamaCppEngine, cloudEngine)

    fun isBusy(): Boolean = isBusyState || displayState == OverlayDisplayState.AUTOMATING_ACTION
    fun isExpanded(): Boolean = displayState != OverlayDisplayState.COLLAPSED_BUBBLE

    fun onUserTyped(newText: String) {
        service.mainHandler.post { userTypedHandler?.invoke(newText) }
    }

    fun onUserNavigatedBack() {
        service.mainHandler.post { userNavigatedBackHandler?.invoke() }
    }

    fun handleCollapsedBubbleClick() {
        val settings = settingsRepository.settingsState.value
        val isKeyboardOpen = service.isKeyboardVisible()
        val hasInputText = service.getLiveInputText().isNotBlank()
        if (settings.alwaysShowFloatingToolbar && !isKeyboardOpen && !hasInputText) {
            AppLogger.d(TAG, "Direct to chat triggered (no keyboard, blank input)")
            transitionTo(OverlayDisplayState.EXPANDED_CHAT)
        } else {
            transitionTo(OverlayDisplayState.EXPANDED_TOOLBAR)
        }
    }

    fun collapse() {
        service.mainHandler.post {
            try {
                userNavigatedBackHandler?.invoke()
                if (displayState == OverlayDisplayState.EXPANDED_TOOLBAR) {
                    collapseHandler?.invoke() ?: transitionTo(OverlayDisplayState.COLLAPSED_BUBBLE)
                } else if (displayState == OverlayDisplayState.EXPANDED_CHAT) {
                    transitionTo(OverlayDisplayState.COLLAPSED_BUBBLE)
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed collapsing overlay: ${e.message}", e)
            }
        }
    }

    fun setChatInputFocused(focused: Boolean) {
        if (isChatInputFocused == focused) return
        isChatInputFocused = focused
        val container = overlayContainer ?: return
        val params = windowParams ?: return
        try {
            if (focused) {
                params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
            } else {
                params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            }
            windowManager.updateViewLayout(container, params)
            AppLogger.d(TAG, "Overlay soft-keyboard focus updated to: $focused")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed updating chat input focus: ${e.message}", e)
        }
    }

    fun transitionTo(newState: OverlayDisplayState) {
        service.mainHandler.post {
            try {
                if (displayState == newState) return@post
                displayState = newState
                val container = overlayContainer ?: return@post
                val params = windowParams ?: return@post
                val bounds = OverlayLayoutParamsHelper.calculateBounds(service.resources.displayMetrics)

                when (newState) {
                    OverlayDisplayState.COLLAPSED_BUBBLE -> {
                        OverlayLayoutParamsHelper.applyCollapsedBubble(params, bubbleAnchorX ?: bounds.maxX, bubbleAnchorY ?: (bounds.screenHeight / 2))
                        setChatInputFocused(false)
                        service.onRibbonManuallyDismissed()
                    }
                    OverlayDisplayState.EXPANDED_TOOLBAR -> {
                        OverlayLayoutParamsHelper.applyExpandedToolbar(params, bounds, bubbleAnchorY)
                        setChatInputFocused(false)
                        service.onRibbonManuallyOpened()
                    }
                    OverlayDisplayState.EXPANDED_CHAT -> {
                        OverlayLayoutParamsHelper.applyExpandedChat(params, bounds)
                        setChatInputFocused(true)
                        service.onRibbonManuallyOpened()
                    }
                    OverlayDisplayState.AUTOMATING_ACTION -> {
                        OverlayLayoutParamsHelper.applyAutomatingAction(params, bounds, bubbleAnchorX ?: bounds.maxX, bubbleAnchorY ?: (bounds.screenHeight / 2))
                        setChatInputFocused(false)
                    }
                }
                windowManager.updateViewLayout(container, params)
                AppLogger.d(TAG, "Transitioned overlay state to: $newState")
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed transitioning to state $newState: ${e.message}", e)
            }
        }
    }

    fun show(
        autoExpand: Boolean = false,
        onTakeScreenshot: (((ByteArray) -> Unit) -> Unit)? = null,
        onExtractTranscript: (() -> String)? = null
    ) {
        try {
            if (isShowing && overlayContainer != null) {
                if (autoExpand && displayState == OverlayDisplayState.COLLAPSED_BUBBLE) {
                    transitionTo(OverlayDisplayState.EXPANDED_TOOLBAR)
                }
                return
            }

            serviceLifecycleOwner = ServiceLifecycleOwner()
            val bounds = OverlayLayoutParamsHelper.calculateBounds(service.resources.displayMetrics)
            val settings = settingsRepository.settingsState.value
            val initialX = (settings.floatingToolbarX ?: bubbleAnchorX ?: bounds.maxX).coerceIn(bounds.minX, bounds.maxX)
            val initialY = (settings.floatingToolbarY ?: bubbleAnchorY ?: (bounds.screenHeight / 2)).coerceIn(bounds.minY, bounds.maxY)
            bubbleAnchorX = initialX
            bubbleAnchorY = initialY

            val windowType = OverlayLayoutParamsHelper.resolveOverlayWindowType(service)
            val params = OverlayLayoutParamsHelper.createInitialParams(initialX, initialY, windowType)
            windowParams = params

            overlayContainer = FloatingOverlayContainer(
                context = service,
                isExpandedProvider = { displayState != OverlayDisplayState.COLLAPSED_BUBBLE },
                onDragStart = {
                    startDragWinX = params.x
                    startDragWinY = params.y
                    isDraggingState.value = true
                },
                onDragMove = { deltaX, deltaY ->
                    val curBounds = OverlayLayoutParamsHelper.calculateBounds(service.resources.displayMetrics)
                    params.x = (startDragWinX + deltaX).roundToInt().coerceIn(curBounds.minX, curBounds.maxX)
                    params.y = (startDragWinY + deltaY).roundToInt().coerceIn(curBounds.minY, curBounds.maxY)
                    overlayContainer?.let { windowManager.updateViewLayout(it, params) }
                },
                onDragEnd = {
                    val curBounds = OverlayLayoutParamsHelper.calculateBounds(service.resources.displayMetrics)
                    val snapX = if (params.x < curBounds.screenWidth / 2) curBounds.minX else curBounds.maxX
                    params.x = snapX
                    bubbleAnchorX = snapX
                    bubbleAnchorY = params.y
                    isDraggingState.value = false
                    overlayContainer?.let {
                        windowManager.updateViewLayout(it, params)
                        it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    }
                    windowScope.launch(Dispatchers.IO) {
                        try {
                            settingsRepository.setFloatingToolbarPosition(snapX, params.y)
                        } catch (e: Exception) {
                            AppLogger.e(TAG, "Failed persisting companion position: ${e.message}", e)
                        }
                    }
                },
                onSingleTap = {
                    AppLogger.d(TAG, "Single tap on overlay container. Current state: $displayState")
                    when (displayState) {
                        OverlayDisplayState.COLLAPSED_BUBBLE -> handleCollapsedBubbleClick()
                        OverlayDisplayState.EXPANDED_TOOLBAR -> singleTapHandler?.invoke() ?: transitionTo(OverlayDisplayState.COLLAPSED_BUBBLE)
                        OverlayDisplayState.EXPANDED_CHAT -> transitionTo(OverlayDisplayState.COLLAPSED_BUBBLE)
                        OverlayDisplayState.AUTOMATING_ACTION -> actionExecutor.stopExecution()
                    }
                },
                onOutsideTap = {
                    AppLogger.d(TAG, "Outside tap intercepted in state $displayState")
                    if (displayState == OverlayDisplayState.EXPANDED_CHAT || displayState == OverlayDisplayState.EXPANDED_TOOLBAR) {
                        collapse()
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
                        Crossfade(targetState = displayState, label = "OverlayStateCrossfade") { targetState ->
                            when (targetState) {
                                OverlayDisplayState.COLLAPSED_BUBBLE -> {
                                    FloatingEdgePill(
                                        state = FloatingButtonState.IDLE,
                                        isDragging = isDraggingState.value,
                                        onClick = { handleCollapsedBubbleClick() }
                                    )
                                }
                                OverlayDisplayState.EXPANDED_TOOLBAR -> {
                                    FloatingToolbarContent(
                                        getLiveInputText = { service.getLiveInputText() },
                                        getConversationTranscript = { service.extractConversationTranscript() },
                                        prompts = prompts,
                                        isVisionCapable = {
                                            val s = settingsRepository.settingsState.value
                                            when (s.selectedEngine) {
                                                EngineType.CLOUD_OPENAI -> true
                                                EngineType.ON_DEVICE_LLAMA_CPP -> llamaCppEngine.sessionState.value.isVisionCapable
                                                else -> sessionState.isVisionCapable
                                            }
                                        },
                                        onInsertText = { result, _ -> service.replaceLiveInputText(result, recordHistory = false) },
                                        onSnapshotOriginalText = { original -> service.pushInputHistory(original) },
                                        onRevertText = { service.revertPreviousTransformation() },
                                        hasUndo = { service.hasPreviousTransformation() },
                                        getRecentClipboardText = { service.getRecentClipboardText() },
                                        onQuickPaste = { service.pasteClipboardText() },
                                        isPlacedOnRight = { (bubbleAnchorX ?: initialX) > (service.resources.displayMetrics.widthPixels / 2) },
                                        isDragging = isDraggingState.value,
                                        onRegisterSingleTapHandler = { singleTapHandler = it },
                                        onRegisterCollapseHandler = { collapseHandler = it },
                                        onRegisterUserTypedHandler = { userTypedHandler = it },
                                        onRegisterUserNavigatedBackHandler = { userNavigatedBackHandler = it },
                                        onExpandedStateChanged = { expanded -> if (!expanded) transitionTo(OverlayDisplayState.COLLAPSED_BUBBLE) },
                                        onBusyStateChanged = { busy -> isBusyState = busy },
                                        onTakeScreenshotContext = onTakeScreenshot,
                                        isConversationContext = { service.isConversationContext() },
                                        getScreenVisibleText = { service.extractScreenVisibleText() },
                                        onQuickEditSave = { updated -> windowScope.launch(Dispatchers.IO) { promptRepository.updatePrompt(updated) } },
                                        onQuickEditDelete = { promptId -> windowScope.launch(Dispatchers.IO) { promptRepository.deletePrompt(promptId) } },
                                        onEditorFocusMode = { focused -> setChatInputFocused(focused) },
                                        compactChipsMode = curSettings.compactChipsMode,
                                        quickFixEnabled = curSettings.quickFixEnabled,
                                        onToggleQuickFix = { enabled -> windowScope.launch { settingsRepository.setQuickFixEnabled(enabled) } },
                                        slashCommandRepository = slashCommandRepository,
                                        onDismiss = { hide() },
                                        onOpenFloatingChat = { transitionTo(OverlayDisplayState.EXPANDED_CHAT) },
                                        engineProvider = { getActiveEngine() }
                                    )
                                }
                                OverlayDisplayState.EXPANDED_CHAT -> {
                                    FloatingChatBubbleContent(
                                        chatSessionRepository = chatSessionRepository,
                                        liteRtEngine = liteRtEngine,
                                        llamaCppEngine = llamaCppEngine,
                                        cloudEngine = cloudEngine,
                                        activeModelName = curSettings.activeModelName,
                                        onMinimizeToBubble = { transitionTo(OverlayDisplayState.COLLAPSED_BUBBLE) },
                                        onSwitchToToolbar = { transitionTo(OverlayDisplayState.EXPANDED_TOOLBAR) },
                                        onAutomateAction = { goal ->
                                            transitionTo(OverlayDisplayState.AUTOMATING_ACTION)
                                            windowScope.launch {
                                                actionExecutor.executeGoal(
                                                    goal = goal,
                                                    onProgress = { _, _ -> },
                                                    onTargetAnimate = { x, y -> coordinateAnimator.animateTo(x, y) }
                                                )
                                                transitionTo(OverlayDisplayState.COLLAPSED_BUBBLE)
                                            }
                                        },
                                        onFocusRequested = { isFocused -> setChatInputFocused(isFocused) },
                                        autoSendVoiceInput = curSettings.autoSendVoiceInput
                                    )
                                }
                                OverlayDisplayState.AUTOMATING_ACTION -> {
                                    Box(
                                        modifier = Modifier.size(56.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        FloatingEdgePill(
                                            state = FloatingButtonState.GENERATING,
                                            isDragging = false,
                                            onClick = { actionExecutor.stopExecution() }
                                        )
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(52.dp),
                                            color = Color(0xFFA27B5C),
                                            strokeWidth = 3.dp
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            overlayContainer?.addView(composeView)
            windowManager.addView(overlayContainer, params)
            isShowing = true
            AppLogger.d(TAG, "Unified companion overlay displayed at ($initialX, $initialY)")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error displaying unified companion overlay: ${e.message}", e)
        }
    }

    private fun getActiveEngine(): com.textmorph.app.llm.LlmEngine {
        val s = settingsRepository.settingsState.value
        val useCloud = s.selectedEngine == EngineType.CLOUD_OPENAI &&
                (s.cloudApiKey.isNotBlank() || s.cloudBaseUrl.contains("localhost") || s.cloudBaseUrl.contains("127.0.0.1"))
        return if (useCloud) {
            cloudEngine.updateConfig(s.cloudBaseUrl, s.cloudApiKey, s.cloudModelId)
            cloudEngine
        } else if (s.selectedEngine == EngineType.ON_DEVICE_LLAMA_CPP) {
            llamaCppEngine
        } else {
            liteRtEngine
        }
    }

    fun onConfigurationChanged(newConfig: Configuration) {
        try {
            val bounds = OverlayLayoutParamsHelper.calculateBounds(service.resources.displayMetrics)
            bubbleAnchorX = (bubbleAnchorX ?: bounds.maxX).coerceIn(bounds.minX, bounds.maxX)
            bubbleAnchorY = (bubbleAnchorY ?: (bounds.screenHeight / 2)).coerceIn(bounds.minY, bounds.maxY)
            val container = overlayContainer ?: return
            val params = windowParams ?: return

            when (displayState) {
                OverlayDisplayState.COLLAPSED_BUBBLE -> OverlayLayoutParamsHelper.applyCollapsedBubble(params, bubbleAnchorX ?: bounds.maxX, bubbleAnchorY ?: (bounds.screenHeight / 2))
                OverlayDisplayState.EXPANDED_TOOLBAR -> OverlayLayoutParamsHelper.applyExpandedToolbar(params, bounds, bubbleAnchorY)
                OverlayDisplayState.EXPANDED_CHAT -> OverlayLayoutParamsHelper.applyExpandedChat(params, bounds, isChatInputFocused)
                OverlayDisplayState.AUTOMATING_ACTION -> OverlayLayoutParamsHelper.applyAutomatingAction(params, bounds, bubbleAnchorX ?: bounds.maxX, bubbleAnchorY ?: (bounds.screenHeight / 2))
            }
            windowManager.updateViewLayout(container, params)
            AppLogger.i(TAG, "Adapted layout to new orientation (${newConfig.orientation})")
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
            userNavigatedBackHandler?.invoke()
            isDraggingState.value = false
            displayState = OverlayDisplayState.COLLAPSED_BUBBLE
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
            AppLogger.e(TAG, "Error removing companion overlay: ${e.message}", e)
        }
    }

    fun destroy() {
        hide()
        windowScope.cancel()
    }
}
