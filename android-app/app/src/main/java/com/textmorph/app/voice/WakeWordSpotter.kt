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
import com.textmorph.app.util.AppLogger
import com.textmorph.app.util.PermissionUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Manages the offline Sherpa-ONNX KeywordSpotter and continuous 16kHz AudioRecord capture loop.
 * Listens for trigger phrases ("Computer" and "Hey Tamim") and pauses capture when a match occurs.
 */
class WakeWordSpotter(private val context: Context) {

    private val TAG = "WakeWordSpotter"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var spotter: KeywordSpotter? = null
    private var stream: OnlineStream? = null
    private var audioRecord: AudioRecord? = null
    private var captureJob: Job? = null

    @Volatile
    private var isRunning = false

    @Volatile
    private var isPaused = false

    companion object {
        const val SAMPLE_RATE = 16000
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

        const val ASSET_ENCODER = "kws/encoder.int8.onnx"
        const val ASSET_DECODER = "kws/decoder.int8.onnx"
        const val ASSET_JOINER = "kws/joiner.int8.onnx"
        const val ASSET_TOKENS = "kws/tokens.txt"
        const val ASSET_KEYWORDS = "kws/keywords.txt"
    }

    /**
     * Initializes Sherpa-ONNX KeywordSpotter from assets and starts audio recording if permissions are met.
     */
    fun start(): Boolean {
        if (!PermissionUtils.hasRecordAudioPermission(context)) {
            AppLogger.w(TAG, "Cannot start WakeWordSpotter: RECORD_AUDIO permission not granted.")
            return false
        }

        if (isRunning) {
            AppLogger.d(TAG, "WakeWordSpotter is already running.")
            return true
        }

        return try {
            initKeywordSpotter()
            initAudioRecord()
            isRunning = true
            isPaused = false
            startCaptureLoop()
            AppLogger.i(TAG, "WakeWordSpotter started successfully.")
            true
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed initializing WakeWordSpotter: ${e.message}", e)
            release()
            false
        }
    }

    private fun initKeywordSpotter() {
        AppLogger.d(TAG, "Initializing Sherpa-ONNX KeywordSpotter from assets...")
        val modelConfig = OnlineModelConfig(
            transducer = OnlineTransducerModelConfig(
                encoder = ASSET_ENCODER,
                decoder = ASSET_DECODER,
                joiner = ASSET_JOINER
            ),
            tokens = ASSET_TOKENS,
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
            keywordsFile = ASSET_KEYWORDS,
            keywordsScore = 1.0f,
            keywordsThreshold = 0.25f
        )

        spotter = KeywordSpotter(assetManager = context.assets, config = spotterConfig)
        stream = spotter?.createStream()
        AppLogger.i(TAG, "Sherpa-ONNX KeywordSpotter and OnlineStream initialized.")
    }

    @SuppressLint("MissingPermission")
    private fun initAudioRecord() {
        val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        val bufferSize = maxOf(minBufferSize, 3200) // At least 200ms of audio buffer

        AppLogger.d(TAG, "Initializing AudioRecord with bufferSize=$bufferSize bytes")
        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            bufferSize * 2
        )

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw IllegalStateException("AudioRecord failed to initialize (state=${record.state})")
        }

        audioRecord = record
        record.startRecording()
    }

    private fun startCaptureLoop() {
        captureJob = scope.launch {
            AppLogger.d(TAG, "Starting audio capture coroutine loop.")
            val shortBuffer = ShortArray(1600) // 100ms chunks at 16kHz
            val floatBuffer = FloatArray(1600)

            while (isActive && isRunning) {
                if (isPaused) {
                    delay(100L)
                    continue
                }

                val currentRecord = audioRecord
                if (currentRecord == null || currentRecord.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    delay(50L)
                    continue
                }

                val readSamples = try {
                    currentRecord.read(shortBuffer, 0, shortBuffer.size)
                } catch (e: Exception) {
                    AppLogger.e(TAG, "Error reading from AudioRecord: ${e.message}", e)
                    -1
                }

                if (readSamples > 0) {
                    for (i in 0 until readSamples) {
                        floatBuffer[i] = shortBuffer[i] / 32768.0f
                    }

                    val samplesToAccept = if (readSamples == floatBuffer.size) {
                        floatBuffer
                    } else {
                        floatBuffer.copyOf(readSamples)
                    }

                    val currentStream = stream
                    val currentSpotter = spotter

                    if (currentStream != null && currentSpotter != null) {
                        currentStream.acceptWaveform(samplesToAccept, SAMPLE_RATE)

                        while (currentSpotter.isReady(currentStream)) {
                            currentSpotter.decode(currentStream)
                        }

                        val result = currentSpotter.getResult(currentStream)
                        val keyword = result.keyword.trim()

                        if (keyword.isNotEmpty()) {
                            AppLogger.i(TAG, "Wake word spotted: '$keyword'")
                            pauseListening()
                            currentSpotter.reset(currentStream)

                            VoiceCommandManager.onWakeWordDetected(keyword) {
                                resumeListening()
                            }
                        }
                    }
                } else {
                    delay(10L)
                }
            }
            AppLogger.d(TAG, "Audio capture loop finished.")
        }
    }

    @Synchronized
    fun pauseListening() {
        if (!isPaused) {
            isPaused = true
            try {
                audioRecord?.stop()
                AppLogger.d(TAG, "AudioRecord paused to release microphone.")
            } catch (e: Exception) {
                AppLogger.e(TAG, "Error stopping AudioRecord: ${e.message}", e)
            }
        }
    }

    @Synchronized
    fun resumeListening() {
        if (isRunning && isPaused) {
            try {
                audioRecord?.startRecording()
                isPaused = false
                AppLogger.i(TAG, "AudioRecord resumed listening.")
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed resuming AudioRecord: ${e.message}", e)
            }
        }
    }

    fun stop() {
        AppLogger.i(TAG, "Stopping WakeWordSpotter...")
        release()
    }

    fun release() {
        isRunning = false
        isPaused = true
        captureJob?.cancel()
        captureJob = null

        try {
            audioRecord?.stop()
        } catch (e: Exception) {
            AppLogger.w(TAG, "Error stopping AudioRecord: ${e.message}")
        }
        try {
            audioRecord?.release()
        } catch (e: Exception) {
            AppLogger.w(TAG, "Error releasing AudioRecord: ${e.message}")
        }
        audioRecord = null

        try {
            stream?.release()
        } catch (e: Exception) {
            AppLogger.w(TAG, "Error releasing OnlineStream: ${e.message}")
        }
        stream = null

        try {
            spotter?.release()
        } catch (e: Exception) {
            AppLogger.w(TAG, "Error releasing KeywordSpotter: ${e.message}")
        }
        spotter = null
        AppLogger.d(TAG, "WakeWordSpotter native resources released.")
    }
}
