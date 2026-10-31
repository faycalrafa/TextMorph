package com.textmorph.app.service.overlay

import com.textmorph.app.agent.AgentEngine
import com.textmorph.app.ui.chat.components.VoiceInputHelper
import com.textmorph.app.util.AppLogger
import com.textmorph.app.voice.VoiceCommandManager
import com.textmorph.app.voice.WakeWordTrigger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Handles voice speech recognition, wake-word activation, direct triggers,
 * and quick agent goal routing for the floating toolbar overlay.
 */
class FloatingToolbarVoiceCoordinator(
    private val service: TextMorphAccessibilityService,
    private val windowScope: CoroutineScope,
    private val agentEngineProvider: () -> AgentEngine,
    private val transitionTo: (OverlayDisplayState) -> Unit,
    private val getCurrentDisplayState: () -> OverlayDisplayState,
    private val ensureOverlayShowing: () -> Unit
) {
    private val TAG = "FloatingToolbarVoice"
    private val voiceInputHelper = VoiceInputHelper(service)
    private val voiceTriggerDispatcher by lazy {
        VoiceTriggerDispatcher(service, { service }, agentEngineProvider)
    }

    fun init() {
        AppLogger.d(TAG, "Initializing FloatingToolbarVoiceCoordinator and registering wake-word handler.")
        VoiceCommandManager.registerListeningHandler { trigger, onFinished ->
            service.mainHandler.post {
                try {
                    AppLogger.i(TAG, "Wake-word triggered in floating toolbar overlay: '${trigger.displayName}'")
                    ensureOverlayShowing()
                    startQuickAgentListening(onFinished)
                } catch (e: Exception) {
                    AppLogger.e(TAG, "Error handling wake-word trigger '$trigger': ${e.message}", e)
                    onFinished()
                }
            }
        }

        windowScope.launch {
            val voiceCtrl = com.textmorph.app.agent.voice.VoiceChatController.getInstance(service)
            voiceCtrl.state.collect { voiceState ->
                val mode = voiceCtrl.sessionMode.value
                if (mode == com.textmorph.app.agent.voice.VoiceSessionMode.SCREEN_ASSIST) {
                    service.mainHandler.post {
                        if (voiceState != com.textmorph.app.agent.voice.VoiceChatState.IDLE) {
                            ensureOverlayShowing()
                            if (getCurrentDisplayState() != OverlayDisplayState.VOICE_ASSIST_SESSION) {
                                transitionTo(OverlayDisplayState.VOICE_ASSIST_SESSION)
                            }
                        } else if (getCurrentDisplayState() == OverlayDisplayState.VOICE_ASSIST_SESSION) {
                            transitionTo(OverlayDisplayState.COLLAPSED_BUBBLE)
                        }
                    }
                }
            }
        }
    }

    fun startScreenAssistVoiceSession() {
        ensureOverlayShowing()
        com.textmorph.app.agent.voice.VoiceChatController.getInstance(service)
            .startScreenAssistSession(service, service)
    }

    fun startQuickAgentListening(onDone: (() -> Unit)? = null) {
        service.mainHandler.post {
            try {
                if (getCurrentDisplayState() == OverlayDisplayState.LISTENING_REQUEST) {
                    voiceInputHelper.stopListening()
                    transitionTo(OverlayDisplayState.COLLAPSED_BUBBLE)
                    onDone?.invoke()
                    return@post
                }
                transitionTo(OverlayDisplayState.LISTENING_REQUEST)
                voiceInputHelper.startListening { spokenText ->
                    try {
                        service.mainHandler.post {
                            if (spokenText.isNotBlank()) {
                                AppLogger.i(TAG, "Quick agent captured speech input: '$spokenText'")
                                executeAgentGoal(spokenText)
                            } else {
                                transitionTo(OverlayDisplayState.COLLAPSED_BUBBLE)
                            }
                        }
                    } finally {
                        onDone?.invoke()
                    }
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "Error starting voice recognition: ${e.message}", e)
                transitionTo(OverlayDisplayState.COLLAPSED_BUBBLE)
                onDone?.invoke()
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
                                agentEngine = agentEngineProvider(),
                                goal = goal,
                                onProgress = { status ->
                                    AppLogger.d(TAG, "Voice mission progress: $status")
                                    com.textmorph.app.agent.ui.AgentToastNotifier.showStatusToast(service, status)
                                }
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

    fun stopListening() {
        voiceInputHelper.stopListening()
        com.textmorph.app.agent.voice.VoiceChatController.getInstance(service).stopSession()
    }

    fun destroy() {
        VoiceCommandManager.unregisterListeningHandler()
        voiceInputHelper.stopListening()
        com.textmorph.app.agent.voice.VoiceChatController.getInstance(service).stopSession()
    }
}
