package com.textmorph.app.agent.voice

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.textmorph.app.data.repository.SettingsRepository
import com.textmorph.app.service.overlay.TextMorphAccessibilityService
import com.textmorph.app.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

enum class VoiceChatState {
    IDLE,
    GREETING,
    LISTENING,
    THINKING,
    SPEAKING
}

enum class VoiceSessionMode {
    IN_APP_CHAT,
    SCREEN_ASSIST
}

/**
 * Shared service-scoped coordinator for live hands-free voice Q&A.
 * Powering both in-app Chat (AiChatScreen) and system-wide Screen Assist overlays.
 */
class VoiceChatController private constructor(private val appContext: Context) {

    private val TAG = "VoiceChatController"
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _state = MutableStateFlow(VoiceChatState.IDLE)
    val state: StateFlow<VoiceChatState> = _state.asStateFlow()

    private val _sessionMode = MutableStateFlow(VoiceSessionMode.SCREEN_ASSIST)
    val sessionMode: StateFlow<VoiceSessionMode> = _sessionMode.asStateFlow()

    private val _silenceRemainingSeconds = MutableStateFlow(15)
    val silenceRemainingSeconds: StateFlow<Int> = _silenceRemainingSeconds.asStateFlow()

    private val _transcribedUserText = MutableStateFlow("")
    val transcribedUserText: StateFlow<String> = _transcribedUserText.asStateFlow()

    private val _spokenAssistantText = MutableStateFlow("")
    val spokenAssistantText: StateFlow<String> = _spokenAssistantText.asStateFlow()

    private val _ttsSpeed = MutableStateFlow(1.5f)
    val ttsSpeed: StateFlow<Float> = _ttsSpeed.asStateFlow()

    // Android System TextToSpeech & Speech Client
    private var tts: TextToSpeech? = null
    private var isTtsReady = false
    private val speechClient = VoiceSpeechRecognizerClient(
        context = appContext,
        onSpeechStarted = {
            mainHandler.post { cancelWatchdog() }
        },
        onPartialText = { partial ->
            mainHandler.post {
                cancelWatchdog()
                _transcribedUserText.value = partial
            }
        },
        onFinalText = { text ->
            mainHandler.post {
                cancelWatchdog()
                _transcribedUserText.value = text
                handleUserVoiceQuery(text)
            }
        },
        onTimeoutOrError = {
            mainHandler.post { stopSession() }
        }
    )

    // Session State & Watchdog
    private var silenceWatchdogJob: Job? = null
    private var watchdogDeadline = 0L
    private var activeInferenceJob: Job? = null
    private var cachedScreenContext: ScreenAssistVoiceRunner.ScreenContext? = null
    private val turnCounter = AtomicLong(0)
    private var currentFinalUtteranceId: String? = null

    // In-App Chat Turn Delegate (when active in AiChatScreen)
    private var chatTurnHandler: ((String, (String) -> Unit) -> Unit)? = null

    init {
        initTts()
        scope.launch(Dispatchers.IO) {
            try {
                SettingsRepository.getInstance(appContext).settingsState.collect { settings ->
                    _ttsSpeed.value = settings.voiceTtsSpeed
                    mainHandler.post {
                        try {
                            tts?.setSpeechRate(settings.voiceTtsSpeed)
                        } catch (e: Exception) {
                            AppLogger.d(TAG, "Failed setting TTS rate on settings update: ${e.message}")
                        }
                    }
                }
            } catch (t: Throwable) {
                AppLogger.w(TAG, "Error collecting settings for TTS speed: ${t.message}")
            }
        }
    }

    private fun initTts() {
        mainHandler.post {
            try {
                tts = TextToSpeech(appContext) { status ->
                    if (status == TextToSpeech.SUCCESS) {
                        isTtsReady = true
                        try {
                            tts?.language = Locale.getDefault()
                            val audioAttrs = AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build()
                            tts?.setAudioAttributes(audioAttrs)
                            tts?.setSpeechRate(_ttsSpeed.value)
                            setupUtteranceListener()
                            AppLogger.i(TAG, "System TTS initialized with USAGE_ASSISTANT at ${_ttsSpeed.value}x")
                        } catch (e: Exception) {
                            AppLogger.e(TAG, "Error configuring TTS: ${e.message}", e)
                        }
                    } else {
                        AppLogger.w(TAG, "TextToSpeech init returned status: $status")
                    }
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed creating TextToSpeech: ${e.message}", e)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun setupUtteranceListener() {
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                AppLogger.d(TAG, "TTS utterance started: $utteranceId")
                if (_state.value != VoiceChatState.GREETING) {
                    _state.value = VoiceChatState.SPEAKING
                }
            }

            override fun onDone(utteranceId: String?) {
                AppLogger.d(TAG, "TTS utterance completed: $utteranceId (waitingFor=$currentFinalUtteranceId)")
                if (utteranceId != null && utteranceId == currentFinalUtteranceId) {
                    mainHandler.post {
                        if (_state.value != VoiceChatState.IDLE) {
                            AppLogger.i(TAG, "Final TTS chunk finished. Auto-resuming listening with 15s watchdog.")
                            _state.value = VoiceChatState.LISTENING
                            startListeningWithWatchdog()
                        }
                    }
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                AppLogger.w(TAG, "TTS utterance error: $utteranceId")
                if (utteranceId != null && utteranceId == currentFinalUtteranceId) {
                    mainHandler.post {
                        if (_state.value != VoiceChatState.IDLE) {
                            _state.value = VoiceChatState.LISTENING
                            startListeningWithWatchdog()
                        }
                    }
                }
            }
        })
    }

    /**
     * Starts a live Screen Assist voice session over any screen on the device.
     */
    fun startScreenAssistSession(context: Context, service: TextMorphAccessibilityService?) {
        mainHandler.post {
            stopSession()
            _sessionMode.value = VoiceSessionMode.SCREEN_ASSIST
            _state.value = VoiceChatState.GREETING
            _transcribedUserText.value = ""
            _spokenAssistantText.value = ""

            scope.launch(Dispatchers.IO) {
                val visibleText = service?.extractScreenVisibleText()?.takeIf { it.isNotBlank() }
                val imageBytes = service?.captureScreenBitmapBytes()
                cachedScreenContext = ScreenAssistVoiceRunner.ScreenContext(imageBytes, visibleText)
                AppLogger.i(TAG, "Captured screen context for voice session (hasText=${visibleText != null}, hasImage=${imageBytes != null})")
                speakProactiveGreeting()
            }
        }
    }

    /**
     * Starts an in-app hands-free Chat voice session.
     */
    fun startInAppChatSession(
        context: Context,
        turnHandler: (String, (String) -> Unit) -> Unit
    ) {
        mainHandler.post {
            stopSession()
            _sessionMode.value = VoiceSessionMode.IN_APP_CHAT
            _state.value = VoiceChatState.LISTENING
            _transcribedUserText.value = ""
            _spokenAssistantText.value = ""
            this.chatTurnHandler = turnHandler
            startListeningWithWatchdog()
        }
    }

    private fun speakProactiveGreeting() {
        mainHandler.post {
            val greeting = "What can I do to help you?"
            _spokenAssistantText.value = greeting
            val turnId = turnCounter.incrementAndGet()
            val utteranceId = "final_greeting_$turnId"
            currentFinalUtteranceId = utteranceId

            try {
                tts?.setSpeechRate(_ttsSpeed.value)
                val params = Bundle().apply {
                    putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
                }
                tts?.speak(greeting, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
                AppLogger.i(TAG, "Spoke greeting: '$greeting' at ${_ttsSpeed.value}x (utteranceId=$utteranceId)")
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed speaking greeting: ${e.message}", e)
                _state.value = VoiceChatState.LISTENING
                startListeningWithWatchdog()
            }
        }
    }

    /**
     * Starts microphone speech recognition with the 15-second inactivity watchdog active.
     */
    private fun startListeningWithWatchdog() {
        mainHandler.post {
            cancelWatchdog()
            watchdogDeadline = SystemClock.elapsedRealtime() + 15_000L
            _silenceRemainingSeconds.value = 15

            silenceWatchdogJob = scope.launch {
                while (isActive) {
                    delay(500L)
                    val remainingMs = watchdogDeadline - SystemClock.elapsedRealtime()
                    val remainingSec = (remainingMs / 1000L).coerceAtLeast(0).toInt()
                    _silenceRemainingSeconds.value = remainingSec

                    if (remainingSec <= 0) {
                        AppLogger.i(TAG, "15-second silence watchdog expired without speech. Resetting to IDLE.")
                        stopSession()
                        break
                    }
                }
            }

            speechClient.startListening(
                watchdogDeadlineProvider = { watchdogDeadline },
                isListeningState = { _state.value == VoiceChatState.LISTENING }
            )
        }
    }

    private fun handleUserVoiceQuery(spokenPrompt: String) {
        speechClient.stop()
        _state.value = VoiceChatState.THINKING
        _spokenAssistantText.value = ""

        activeInferenceJob?.cancel()
        activeInferenceJob = scope.launch(Dispatchers.IO) {
            try {
                if (_sessionMode.value == VoiceSessionMode.SCREEN_ASSIST) {
                    executeScreenAssistTurn(spokenPrompt)
                } else {
                    executeInAppChatTurn(spokenPrompt)
                }
            } catch (t: Throwable) {
                AppLogger.e(TAG, "Error executing voice turn: ${t.message}", t)
                mainHandler.post {
                    _state.value = VoiceChatState.LISTENING
                    startListeningWithWatchdog()
                }
            }
        }
    }

    private suspend fun executeScreenAssistTurn(userQuestion: String) {
        val stream = ScreenAssistVoiceRunner.runScreenInferenceStream(
            context = appContext,
            screenContext = cachedScreenContext,
            userQuestion = userQuestion
        )

        val sentenceBuffer = VoiceSentenceBuffer()
        val fullResponse = StringBuilder()
        val turnId = turnCounter.incrementAndGet()
        var chunkIndex = 0

        stream.collect { token ->
            fullResponse.append(token)
            val completedSentences = sentenceBuffer.appendToken(token)
            for (sentence in completedSentences) {
                queueTtsSentence(sentence, utteranceId = "say_${turnId}_${chunkIndex++}")
            }
        }

        val finalChunk = sentenceBuffer.flush()
        val finalId = "final_${turnId}_$chunkIndex"
        currentFinalUtteranceId = finalId

        if (!finalChunk.isNullOrBlank()) {
            queueTtsSentence(finalChunk, utteranceId = finalId)
        } else if (sentenceBuffer.totalSentencesEmitted > 0) {
            queueTtsSentence(" ", utteranceId = finalId)
        } else {
            val fallback = "I could not find an answer on your screen."
            queueTtsSentence(fallback, utteranceId = finalId)
        }

        val totalSpoken = VoiceTextSanitizer.sanitizeForTts(fullResponse.toString())
        ScreenAssistVoiceRunner.recordVoiceScreenTurn(appContext, userQuestion, totalSpoken)
    }

    private fun executeInAppChatTurn(userQuestion: String) {
        val handler = chatTurnHandler
        if (handler != null) {
            handler.invoke(userQuestion) { assistantReplyChunk ->
                speakAssistantResponseStream(assistantReplyChunk)
            }
        } else {
            stopSession()
        }
    }

    fun speakAssistantResponseStream(text: String) {
        val turnId = turnCounter.incrementAndGet()
        val sanitized = VoiceTextSanitizer.sanitizeForTts(text)
        val finalId = "final_chat_$turnId"
        currentFinalUtteranceId = finalId
        queueTtsSentence(sanitized, utteranceId = finalId)
    }

    private fun queueTtsSentence(sentence: String, utteranceId: String) {
        mainHandler.post {
            try {
                if (sentence.isNotBlank() && _state.value != VoiceChatState.IDLE) {
                    _spokenAssistantText.value = sentence
                    _state.value = VoiceChatState.SPEAKING
                    tts?.setSpeechRate(_ttsSpeed.value)
                    val params = Bundle().apply {
                        putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
                    }
                    tts?.speak(sentence, TextToSpeech.QUEUE_ADD, params, utteranceId)
                    AppLogger.d(TAG, "Queued TTS sentence ($utteranceId): '$sentence'")
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "Error queueing TTS sentence: ${e.message}", e)
            }
        }
    }

    fun setSpeechRate(rate: Float) {
        val clamped = rate.coerceIn(0.5f, 3.0f)
        _ttsSpeed.value = clamped
        mainHandler.post {
            try {
                tts?.setSpeechRate(clamped)
            } catch (e: Exception) {
                AppLogger.d(TAG, "Error updating speech rate: ${e.message}")
            }
        }
        scope.launch(Dispatchers.IO) {
            SettingsRepository.getInstance(appContext).setVoiceTtsSpeed(clamped)
        }
    }

    private fun cancelWatchdog() {
        silenceWatchdogJob?.cancel()
        silenceWatchdogJob = null
        _silenceRemainingSeconds.value = 0
    }

    /**
     * Immediately halts TTS playback, stops SpeechRecognizer, cancels watchdog,
     * and resets state to IDLE.
     */
    fun stopSession() {
        mainHandler.post {
            try {
                cancelWatchdog()
                activeInferenceJob?.cancel()
                activeInferenceJob = null
                speechClient.stop()
                tts?.stop()
                currentFinalUtteranceId = null
                cachedScreenContext = null
                _state.value = VoiceChatState.IDLE
                _transcribedUserText.value = ""
                _spokenAssistantText.value = ""
                AppLogger.i(TAG, "VoiceChatController stopped and reset to IDLE.")
            } catch (e: Exception) {
                AppLogger.e(TAG, "Error in stopSession: ${e.message}", e)
            }
        }
    }

    fun destroy() {
        stopSession()
        mainHandler.post {
            try {
                tts?.shutdown()
                tts = null
            } catch (e: Exception) {
                AppLogger.d(TAG, "Error shutting down TTS: ${e.message}")
            }
        }
    }

    companion object {
        @Volatile
        private var instance: VoiceChatController? = null

        fun getInstance(context: Context): VoiceChatController {
            return instance ?: synchronized(this) {
                instance ?: VoiceChatController(context.applicationContext).also { instance = it }
            }
        }
    }
}
