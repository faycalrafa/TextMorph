package com.textmorph.app.agent.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.textmorph.app.util.AppLogger
import java.util.Locale

/**
 * Manages low-level Android SpeechRecognizer dictation, native timeout bridging,
 * and speech start/partial/final callbacks for VoiceChatController.
 */
class VoiceSpeechRecognizerClient(
    private val context: Context,
    private val onSpeechStarted: () -> Unit,
    private val onPartialText: (String) -> Unit,
    private val onFinalText: (String) -> Unit,
    private val onTimeoutOrError: () -> Unit
) {
    private val TAG = "VoiceSpeechRecognizerClient"
    private val mainHandler = Handler(Looper.getMainLooper())
    private var speechRecognizer: SpeechRecognizer? = null

    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    fun startListening(watchdogDeadlineProvider: () -> Long, isListeningState: () -> Boolean) {
        if (!isAvailable()) {
            AppLogger.w(TAG, "SpeechRecognizer is not available on this device.")
            onTimeoutOrError()
            return
        }

        stop()

        try {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        AppLogger.d(TAG, "SpeechRecognizer is ready for user speech.")
                    }

                    override fun onBeginningOfSpeech() {
                        AppLogger.d(TAG, "Speech input began.")
                        onSpeechStarted()
                    }

                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}

                    override fun onEndOfSpeech() {
                        AppLogger.d(TAG, "Speech input ended.")
                    }

                    override fun onError(error: Int) {
                        AppLogger.w(TAG, "SpeechRecognizer error code: $error")
                        val deadline = watchdogDeadlineProvider()
                        val elapsed = SystemClock.elapsedRealtime()

                        if (elapsed < deadline && isListeningState()) {
                            AppLogger.d(TAG, "Silently bridging native STT timeout (within 15s window)...")
                            mainHandler.postDelayed({
                                if (isListeningState()) {
                                    startListening(watchdogDeadlineProvider, isListeningState)
                                }
                            }, 250L)
                        } else if (isListeningState()) {
                            onTimeoutOrError()
                        }
                    }

                    override fun onResults(results: Bundle?) {
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val text = matches?.firstOrNull()?.trim() ?: ""

                        if (text.isNotBlank()) {
                            AppLogger.i(TAG, "Transcribed final speech text: '$text'")
                            onFinalText(text)
                        } else {
                            val deadline = watchdogDeadlineProvider()
                            if (SystemClock.elapsedRealtime() < deadline && isListeningState()) {
                                startListening(watchdogDeadlineProvider, isListeningState)
                            } else {
                                onTimeoutOrError()
                            }
                        }
                    }

                    override fun onPartialResults(partialResults: Bundle?) {
                        val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val partial = matches?.firstOrNull()?.trim() ?: ""
                        if (partial.isNotBlank()) {
                            onPartialText(partial)
                        }
                    }

                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
            }

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            }
            speechRecognizer?.startListening(intent)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed starting SpeechRecognizer: ${e.message}", e)
            onTimeoutOrError()
        }
    }

    fun stop() {
        try {
            speechRecognizer?.stopListening()
            speechRecognizer?.destroy()
            speechRecognizer = null
        } catch (e: Exception) {
            AppLogger.d(TAG, "Error cleaning SpeechRecognizer: ${e.message}")
        }
    }
}
