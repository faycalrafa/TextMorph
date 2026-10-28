package com.textmorph.app.service.overlay

import android.content.Context
import android.content.res.Configuration
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.WindowManager
import androidx.compose.animation.Crossfade
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
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
import com.textmorph.app.ui.chat.components.VoiceInputHelper
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
 * - EXPANDED_TOOLBAR: Ultra-low footprint companion ribbon with Quick Agent Request.
 * - LISTENING_REQUEST: Companion bubble displaying ear 👂 icon while listening for agent requests.
 * - AUTOMATING_ACTION: Living companion bubble smoothly gliding across coordinates during execution.
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

    private var singleTapHandler: (() -> Unit)? = null
    private var collapseHandler: (() -> Unit)? = null
    private var userTypedHandler: ((String) -> Unit)? = null
    private var userNavigatedBackHandler: (() -> Unit)? = null
    private var windowParams: WindowManager.LayoutParams? = null
    private var startDragWinX = 0
    private var startDragWinY = 0
    private var wasShownForTask = false
    private var automatingStateTimestamp = 0L

    // Coordinate Animator & Automation Controller
    private val coordinateAnimator = OverlayCoordinateAnimator(windowManager, { overlayContainer }, { windowParams })
    private val automationController = ScreenAutomationController(service).apply {
        onTapDispatched = { x, y -> service.getInteractionOverlayManager()?.showTap(x, y) }
        onTargetFocused = { x, y -> coordinateAnimator.animateTo(x, y, durationMs = 380L) }
    }
    val agentEngine by lazy {
        com.textmorph.app.agent.AgentEngine(
            context = service,
            controllerProvider = { automationController },
            serviceProvider = { service },
            engineProvider = { getActiveEngine() }
        )
    }
    private val voiceInputHelper = VoiceInputHelper(service)
    val voiceTriggerDispatcher by lazy { VoiceTriggerDispatcher(service, { service }, { agentEngine }) }

    init {
        windowScope.launch {
            com.textmorph.app.agent.coordinator.TaskExecutionCoordinator.state.collect { taskState ->
                if (taskState.isRunning) {
                    if (!isShowingActive) {
                        wasShownForTask = true
                        show(autoExpand = false, onTakeScreenshot = { cb -> service.captureScreenContext(cb) }, onExtractTranscript = { service.extractConversationTranscript() })
                    }
                    setOverlayVisible(true)
                    if (displayState != OverlayDisplayState.AUTOMATING_ACTION) transitionTo(OverlayDisplayState.AUTOMATING_ACTION)
                } else if (displayState == OverlayDisplayState.AUTOMATING_ACTION) {
                    val settings = settingsRepository.settingsState.value
                    if (wasShownForTask && !settings.alwaysShowFloatingToolbar) {
                        wasShownForTask = false
                        hide()
                    } else {
                        transitionTo(OverlayDisplayState.COLLAPSED_BUBBLE)
                    }
                }
            }
        }
    }

    fun isBusy(): Boolean = isBusyState || displayState == OverlayDisplayState.AUTOMATING_ACTION
    fun isExpanded(): Boolean = displayState != OverlayDisplayState.COLLAPSED_BUBBLE
    fun onUserTyped(newText: String) = service.mainHandler.post { userTypedHandler?.invoke(newText) }
    fun onUserNavigatedBack() = service.mainHandler.post { userNavigatedBackHandler?.invoke() }

    fun handleCollapsedBubbleClick() {
        if (service.isKeyboardVisible()) {
            transitionTo(OverlayDisplayState.EXPANDED_TOOLBAR)
        } else {
            startQuickAgentListening()
        }
    }

    fun collapse() {
        service.mainHandler.post {
            try {
                userNavigatedBackHandler?.invoke()
                if (displayState == OverlayDisplayState.EXPANDED_TOOLBAR) {
                    collapseHandler?.invoke() ?: transitionTo(OverlayDisplayState.COLLAPSED_BUBBLE)
                } else if (displayState == OverlayDisplayState.LISTENING_REQUEST) {
                    voiceInputHelper.stopListening()
                    transitionTo(OverlayDisplayState.COLLAPSED_BUBBLE)
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed collapsing overlay: ${e.message}", e)
            }
        }
    }

    fun startQuickAgentListening() {
        service.mainHandler.post {
            try {
                if (displayState == OverlayDisplayState.LISTENING_REQUEST) {
                    voiceInputHelper.stopListening()
                    transitionTo(OverlayDisplayState.COLLAPSED_BUBBLE)
                    return@post
                }
                transitionTo(OverlayDisplayState.LISTENING_REQUEST)
                voiceInputHelper.startListening { spokenText ->
                    service.mainHandler.post {
                        if (spokenText.isNotBlank()) {
                            AppLogger.i(TAG, "Quick agent captured speech input: '$spokenText'")
                            executeAgentGoal(spokenText)
                        } else {
                            transitionTo(OverlayDisplayState.COLLAPSED_BUBBLE)
                        }
                    }
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "Error starting voice recognition: ${e.message}", e)
                transitionTo(OverlayDisplayState.COLLAPSED_BUBBLE)
            }
        }
    }

    fun executeAgentGoal(goal: String) {
        service.mainHandler.post {
            try {
                voiceInputHelper.stopListening()
                transitionTo(OverlayDisplayState.AUTOMATING_ACTION)
                windowScope.launch {
                    try {
                        val dispatchRes = voiceTriggerDispatcher.dispatchVoiceInput(goal)
                        if (dispatchRes is VoiceDispatchResult.Handled) {
                            AppLogger.i(TAG, "Direct trigger handled: ${dispatchRes.title} (success=${dispatchRes.success})")
                        } else {
                            val result = VoiceAgentMissionCoordinator.executeMission(
                                context = service,
                                agentEngine = agentEngine,
                                goal = goal,
                                onProgress = { status -> AppLogger.d(TAG, "Voice mission progress: $status") }
                            )
                            AppLogger.i(TAG, "Quick agent goal result: $result")
                        }
                    } catch (e: Exception) {
                        AppLogger.e(TAG, "Quick agent goal execution failed: ${e.message}", e)
                    } finally {
                        transitionTo(OverlayDisplayState.COLLAPSED_BUBBLE)
                    }
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed executing quick agent goal: ${e.message}", e)
            }
        }
    }

    private fun applyLayoutForState(state: OverlayDisplayState, params: WindowManager.LayoutParams, bounds: OverlayLayoutParamsHelper.ScreenBounds) {
        val anchorX = bubbleAnchorX ?: bounds.maxX
        val anchorY = bubbleAnchorY ?: (bounds.screenHeight / 2)
        when (state) {
            OverlayDisplayState.COLLAPSED_BUBBLE -> OverlayLayoutParamsHelper.applyCollapsedBubble(params, anchorX, anchorY)
            OverlayDisplayState.EXPANDED_TOOLBAR -> OverlayLayoutParamsHelper.applyExpandedToolbar(params, bounds, bubbleAnchorY)
            OverlayDisplayState.LISTENING_REQUEST -> OverlayLayoutParamsHelper.applyListeningRequest(params, bounds, anchorX, anchorY)
            OverlayDisplayState.AUTOMATING_ACTION -> OverlayLayoutParamsHelper.applyAutomatingAction(params, bounds, anchorX, anchorY)
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

                applyLayoutForState(newState, params, bounds)
                if (newState == OverlayDisplayState.EXPANDED_TOOLBAR) service.onRibbonManuallyOpened()
                else if (newState == OverlayDisplayState.COLLAPSED_BUBBLE || newState == OverlayDisplayState.LISTENING_REQUEST) service.onRibbonManuallyDismissed()
                else if (newState == OverlayDisplayState.AUTOMATING_ACTION) automatingStateTimestamp = SystemClock.uptimeMillis()

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
                isExpandedProvider = { displayState != OverlayDisplayState.COLLAPSED_BUBBLE && displayState != OverlayDisplayState.LISTENING_REQUEST },
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
                        OverlayDisplayState.LISTENING_REQUEST -> {
                            voiceInputHelper.stopListening()
                            transitionTo(OverlayDisplayState.COLLAPSED_BUBBLE)
                        }
                        OverlayDisplayState.AUTOMATING_ACTION -> {
                            val elapsed = SystemClock.uptimeMillis() - automatingStateTimestamp
                            if (elapsed >= 450L) {
                                com.textmorph.app.agent.coordinator.TaskExecutionCoordinator.interrupt()
                                agentEngine.cancelRunningTask()
                            } else {
                                AppLogger.d(TAG, "Ignoring premature tap on AUTOMATING_ACTION within debounce window (${elapsed}ms < 450ms)")
                            }
                        }
                    }
                },
                onOutsideTap = {
                    AppLogger.d(TAG, "Outside tap intercepted in state $displayState")
                    if (displayState == OverlayDisplayState.EXPANDED_TOOLBAR) {
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
                                OverlayDisplayState.LISTENING_REQUEST -> {
                                    FloatingEdgePill(
                                        state = FloatingButtonState.LISTENING,
                                        isDragging = isDraggingState.value,
                                        onClick = {
                                            voiceInputHelper.stopListening()
                                            transitionTo(OverlayDisplayState.COLLAPSED_BUBBLE)
                                        }
                                    )
                                }
                                OverlayDisplayState.EXPANDED_TOOLBAR -> {
                                    FloatingToolbarContent(
                                        getLiveInputText = { service.getLiveInputText() },
                                        getConversationTranscript = { service.extractConversationTranscript() },
                                        prompts = prompts,
                                        isVisionCapable = {
                                            when (settingsRepository.settingsState.value.selectedEngine) {
                                                EngineType.CLOUD_OPENAI -> true
                                                EngineType.ON_DEVICE_LLAMA_CPP -> llamaCppEngine.sessionState.value.isVisionCapable
                                                else -> sessionState.isVisionCapable
                                            }
                                        },
                                        onInsertText = { res, _ -> service.replaceLiveInputText(res, recordHistory = false) },
                                        onSnapshotOriginalText = { orig -> service.pushInputHistory(orig) },
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
                                        onExpandedStateChanged = { exp -> if (!exp) transitionTo(OverlayDisplayState.COLLAPSED_BUBBLE) },
                                        onBusyStateChanged = { busy -> isBusyState = busy },
                                        onTakeScreenshotContext = onTakeScreenshot,
                                        isConversationContext = { service.isConversationContext() },
                                        getScreenVisibleText = { service.extractScreenVisibleText() },
                                        onQuickEditSave = { u -> windowScope.launch(Dispatchers.IO) { promptRepository.updatePrompt(u) } },
                                        onQuickEditDelete = { id -> windowScope.launch(Dispatchers.IO) { promptRepository.deletePrompt(id) } },
                                        compactChipsMode = curSettings.compactChipsMode,
                                        quickFixEnabled = curSettings.quickFixEnabled,
                                        onToggleQuickFix = { en -> windowScope.launch { settingsRepository.setQuickFixEnabled(en) } },
                                        slashCommandRepository = slashCommandRepository,
                                        onDismiss = { hide() },
                                        onQuickAgentRequest = { startQuickAgentListening() },
                                        engineProvider = { getActiveEngine() }
                                    )
                                }
                                OverlayDisplayState.AUTOMATING_ACTION -> {
                                    val taskState by com.textmorph.app.agent.coordinator.TaskExecutionCoordinator.state.collectAsState()
                                    com.textmorph.app.service.overlay.components.TaskExecutionBubble(
                                        currentStep = taskState.currentStep,
                                        isThinking = taskState.isThinking,
                                        onInterrupt = {
                                            com.textmorph.app.agent.coordinator.TaskExecutionCoordinator.interrupt()
                                            agentEngine.cancelRunningTask()
                                        }
                                    )
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

            applyLayoutForState(displayState, params, bounds)
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
            voiceInputHelper.stopListening()
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
        voiceInputHelper.stopListening()
        hide()
        windowScope.cancel()
    }
}
