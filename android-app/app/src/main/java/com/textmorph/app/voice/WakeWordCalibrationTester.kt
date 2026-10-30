package com.textmorph.app.voice

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.textmorph.app.data.model.WakeWordEntity
import com.textmorph.app.service.WakeWordForegroundService
import com.textmorph.app.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Dedicated acoustic calibration tester for offline wake-words.
 * Manages an isolated microphone session to evaluate 3 consecutive successful detections
 * of a candidate trigger phrase before certifying it as calibrated.
 */
class WakeWordCalibrationTester(
    private val context: Context,
    private val targetEntity: WakeWordEntity,
    private val onAttemptPassed: (attemptNumber: Int) -> Unit,
    private val onCalibrationFinished: () -> Unit,
    private val onError: (message: String) -> Unit
) {
    private val TAG = "WakeWordCalibTester"
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private var audioRecord: AudioRecord? = null
    private var localSpotter: KeywordSpotter? = null
    private var testStream: OnlineStream? = null
    private var captureJob: Job? = null

    @Volatile
    private var isRunning = false
    private var attemptsPassed = 0

    companion object {
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    @Synchronized
    fun start(): Boolean {
        if (isRunning) return true
        AppLogger.i(TAG, "Starting calibration test for '${targetEntity.displayName}'...")

        return try {
            // Pause background foreground service to eliminate microphone contention
            WakeWordForegroundService.pauseListening()

            initSpotterAndStream()
            initAudioRecord()

            isRunning = true
            attemptsPassed = 0
            startAudioLoop()
            true
        } catch (t: Throwable) {
            AppLogger.e(TAG, "Failed starting calibration tester: ${t.message}", t)
            release()
            onError(t.message ?: "Failed initializing microphone or acoustic model.")
            false
        }
    }

    private fun initSpotterAndStream() {
        val modelConfig = OnlineModelConfig(
            transducer = OnlineTransducerModelConfig(
                encoder = "kws/encoder.int8.onnx",
                decoder = "kws/decoder.int8.onnx",
                joiner = "kws/joiner.int8.onnx"
            ),
            tokens = "kws/tokens.txt",
            numThreads = 2,
            provider = "cpu"
        )

        val featConfig = FeatureConfig(
            sampleRate = SAMPLE_RATE,
            featureDim = 80
        )

        val spotterConfig = KeywordSpotterConfig(
            featConfig = featConfig,
            modelConfig = modelConfig,
            maxActivePaths = 4,
            keywordsFile = "kws/keywords.txt",
            keywordsScore = 1.0f,
            keywordsThreshold = 0.25f
        )

        val spotter = KeywordSpotter(assetManager = context.assets, config = spotterConfig)
        localSpotter = spotter

        val tag = if (targetEntity.aliasTag.startsWith("@")) targetEntity.aliasTag else "@${targetEntity.aliasTag}"
        val testKeywords = targetEntity.phonemes.lineSequence()
            .map { it.trim().substringBefore("@").trim() }
            .filter { it.isNotBlank() }
            .map { "$it $tag" }
            .joinToString("\n")

        AppLogger.d(TAG, "Calibration keywords syntax:\n$testKeywords")
        val stream = if (testKeywords.isNotBlank()) {
            spotter.createStream(testKeywords)
        } else {
            spotter.createStream()
        }

        testStream = stream
        AppLogger.i(TAG, "Calibration KeywordSpotter & testStream ready for '${targetEntity.displayName}'")
    }

    @SuppressLint("MissingPermission")
    private fun initAudioRecord() {
        val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        val bufferSize = maxOf(minBufferSize, 3200)

        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            bufferSize * 2
        )

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw IllegalStateException("Microphone initialization failed (state=${record.state}).")
        }

        audioRecord = record
        record.startRecording()
    }

    private fun startAudioLoop() {
        captureJob = scope.launch {
            val shortBuffer = ShortArray(1600) // 100ms
            val floatBuffer = FloatArray(1600)

            while (isActive && isRunning) {
                val record = audioRecord ?: break
                val stream = testStream ?: break
                val spotter = localSpotter ?: break

                val readCount = try {
                    record.read(shortBuffer, 0, shortBuffer.size)
                } catch (t: Throwable) {
                    AppLogger.e(TAG, "Error reading AudioRecord in calibration: ${t.message}", t)
                    -1
                }

                if (readCount > 0) {
                    for (i in 0 until readCount) {
                        floatBuffer[i] = shortBuffer[i] / 32768.0f
                    }

                    val samples = if (readCount == floatBuffer.size) floatBuffer else floatBuffer.copyOf(readCount)
                    try {
                        stream.acceptWaveform(samples, SAMPLE_RATE)
                        while (spotter.isReady(stream)) {
                            spotter.decode(stream)
                        }

                        val result = spotter.getResult(stream)
                        val detected = result.keyword.trim()
                        if (detected.isNotEmpty()) {
                            attemptsPassed++
                            AppLogger.i(TAG, "Calibration pass $attemptsPassed/3: '$detected'")
                            spotter.reset(stream)

                            onAttemptPassed(attemptsPassed)

                            if (attemptsPassed >= 3) {
                                AppLogger.i(TAG, "Calibration testing 3/3 passed for '${targetEntity.displayName}'!")
                                onCalibrationFinished()
                                stop()
                                break
                            }
                            // Debounce slightly between attempts
                            delay(400L)
                        }
                    } catch (t: Throwable) {
                        AppLogger.e(TAG, "Error decoding calibration waveform: ${t.message}", t)
                    }
                } else {
                    delay(10L)
                }
            }
        }
    }

    fun resetAttempts() {
        attemptsPassed = 0
        val spotter = localSpotter
        val stream = testStream
        if (spotter != null && stream != null) {
            try {
                spotter.reset(stream)
            } catch (t: Throwable) {
                AppLogger.w(TAG, "Error resetting stream: ${t.message}")
            }
        }
    }

    fun stop() {
        release()
    }

    fun release() {
        isRunning = false
        captureJob?.cancel()
        captureJob = null

        try {
            audioRecord?.stop()
        } catch (t: Throwable) {
            AppLogger.w(TAG, "Error stopping audioRecord: ${t.message}")
        }
        try {
            audioRecord?.release()
        } catch (t: Throwable) {
            AppLogger.w(TAG, "Error releasing audioRecord: ${t.message}")
        }
        audioRecord = null

        try {
            testStream?.release()
        } catch (t: Throwable) {
            AppLogger.w(TAG, "Error releasing testStream: ${t.message}")
        }
        testStream = null

        try {
            localSpotter?.release()
        } catch (t: Throwable) {
            AppLogger.w(TAG, "Error releasing localSpotter: ${t.message}")
        }
        localSpotter = null

        try {
            scope.cancel()
        } catch (t: Throwable) {
            AppLogger.d(TAG, "Scope cancellation non-fatal: ${t.message}")
        }

        // Resume background service listening
        WakeWordForegroundService.resumeListening()
        AppLogger.d(TAG, "WakeWordCalibrationTester released successfully.")
    }
}
