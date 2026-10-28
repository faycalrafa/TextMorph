package com.textmorph.app.voice

import com.textmorph.app.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Coordinates wake-word events triggered by Sherpa-ONNX KWS and routes them
 * to the active listening UI (Floating companion toolbar or Chat screen).
 */
object VoiceCommandManager {

    private const val TAG = "VoiceCommandManager"
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val _wakeWordEvents = MutableSharedFlow<WakeWordTrigger>(extraBufferCapacity = 1)
    val wakeWordEvents: SharedFlow<WakeWordTrigger> = _wakeWordEvents.asSharedFlow()

    private val _isDictating = MutableStateFlow(false)
    val isDictating: StateFlow<Boolean> = _isDictating.asStateFlow()

    @Volatile
    private var listeningHandler: ((WakeWordTrigger, () -> Unit) -> Unit)? = null

    /**
     * Registers the active UI handler (e.g. FloatingToolbarWindowManager) capable of
     * displaying the listening UI and starting SpeechRecognizer dictation.
     */
    fun registerListeningHandler(handler: (WakeWordTrigger, () -> Unit) -> Unit) {
        AppLogger.d(TAG, "Registering active listening handler.")
        this.listeningHandler = handler
    }

    fun unregisterListeningHandler() {
        AppLogger.d(TAG, "Unregistering active listening handler.")
        this.listeningHandler = null
    }

    /**
     * Invoked by [WakeWordSpotter] when either "Computer" or "Hey Tamim" is detected.
     *
     * @param rawKeyword The raw keyword string returned from Sherpa-ONNX (e.g. "@computer" or "@hey_tamim").
     * @param onListeningFinished Callback invoked when the user finishes dictation, allowing the wake-word
     * detector to resume capturing microphone input.
     */
    fun onWakeWordDetected(rawKeyword: String, onListeningFinished: () -> Unit) {
        val trigger = WakeWordTrigger.fromKeyword(rawKeyword)
        AppLogger.i(TAG, "Wake-word triggered: '${trigger.displayName}' (raw='$rawKeyword')")

        scope.launch {
            _wakeWordEvents.tryEmit(trigger)
            _isDictating.value = true

            val currentHandler = listeningHandler
            if (currentHandler != null) {
                AppLogger.d(TAG, "Dispatching wake-word trigger to registered UI handler.")
                currentHandler.invoke(trigger) {
                    AppLogger.d(TAG, "Speech dictation completed. Resuming wake-word spotter.")
                    _isDictating.value = false
                    onListeningFinished()
                }
            } else {
                AppLogger.w(TAG, "No UI listening handler registered; notifying completion immediately.")
                _isDictating.value = false
                onListeningFinished()
            }
        }
    }
}
