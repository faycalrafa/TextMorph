package com.textmorph.app.llm

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.InputData
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.ResponseCallback
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.Session
import com.google.ai.edge.litertlm.ThinkingConfig
import com.textmorph.app.data.repository.SettingsRepository
import com.textmorph.app.util.AppLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import com.textmorph.app.util.NativeFlightRecorder
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LiteRtLmEngine(private val context: Context) : LlmEngine {

    private val TAG = "LiteRtLmEngine"
    private val inferenceMutex = Mutex()

    override val maxContextTokens: Int
        get() = 4096

    private var nativeEngine: Engine? = null
    private var activePfd: ParcelFileDescriptor? = null
    private var createdSymlinkFile: File? = null
    private var isModelVisionCapable = false
    private var currentModelPath: String? = null
    private var currentModelName: String = "No Model Loaded"
    private var isSessionActive = false
    private var activeBackend = "GPU (Vulkan/OpenCL)"

    private val _sessionState = MutableStateFlow(ModelSessionState())
    val sessionState: StateFlow<ModelSessionState> = _sessionState.asStateFlow()

    private val _loadingProgress = MutableStateFlow(ModelLoadingProgress())
    val loadingProgress: StateFlow<ModelLoadingProgress> = _loadingProgress.asStateFlow()

    private fun addProgressLog(message: String, progress: Float? = null, statusText: String? = null) {
        val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        val formatted = "[$time] $message"
        AppLogger.i(TAG, message)
        _loadingProgress.update { current ->
            val updatedLogs = (current.logLines + formatted).takeLast(100)
            current.copy(
                progress = progress ?: current.progress,
                statusText = statusText ?: current.statusText,
                logLines = updatedLogs
            )
        }
    }

    override suspend fun initialize(modelPath: String?): Boolean = initialize(modelPath, null, null, "AUTO", null)

    suspend fun initialize(
        modelPath: String?,
        modelName: String? = null,
        modelUri: Uri? = null,
        preferredAccelerator: String = "AUTO",
        probeTimeoutMs: Long? = null
    ): Boolean = withContext(Dispatchers.IO) {
        inferenceMutex.withLock {
            _loadingProgress.value = ModelLoadingProgress(
                isLoading = true,
                progress = 0.05f,
                statusText = "Preparing model loading...",
                logLines = emptyList(),
                error = null,
                success = false
            )
            val startTime = System.currentTimeMillis()

            try {
                val effectiveTimeout = probeTimeoutMs
                    ?: runCatching { SettingsRepository.getInstance(context).settingsState.value.probeTimeoutMs }.getOrNull()
                    ?: 60000L

                val effectiveName = modelName ?: modelPath?.let { File(it).name } ?: modelUri?.lastPathSegment ?: "Custom Model"
                addProgressLog("Initializing LiteRT-LM session for: $effectiveName (Accelerator: $preferredAccelerator, Probe Timeout: ${effectiveTimeout}ms)", 0.1f, "Inspecting model file...")

                // Clean previous engine if any
                unloadInternal()

                // 1. Resolve path from direct file or ParcelFileDescriptor
                val resolved = LiteRtLmSessionDelegate.resolveModelPathAndSymlink(
                    context = context,
                    modelPath = modelPath,
                    modelUri = modelUri,
                    effectiveName = effectiveName,
                    onProgress = ::addProgressLog
                )
                val effectivePath = resolved.effectivePath
                activePfd = resolved.pfd
                createdSymlinkFile = resolved.symlinkFile

                if (effectivePath != null) {
                    currentModelPath = effectivePath
                    currentModelName = effectiveName

                    // Inspect header for tf_lite_vision_encoder
                    val hasVisionEncoder = LiteRtLmSessionDelegate.checkVisionEncoder(activePfd, effectivePath)
                    addProgressLog(
                        if (hasVisionEncoder) "Model contains vision encoder (Multimodal mode enabled)."
                        else "Model is pure Text-Only (Vision encoder absent).",
                        0.4f
                    )

                    val probeResult = LiteRtLmSessionDelegate.probeAndSelectEngine(
                        context = context,
                        modelPath = effectivePath,
                        preferredAccelerator = preferredAccelerator,
                        hasVisionEncoder = hasVisionEncoder,
                        effectiveTimeout = effectiveTimeout,
                        onProgress = ::addProgressLog
                    )
                    val activeEng = probeResult.engine
                    val resolvedBackend = probeResult.backend
                    val actualVisionCapable = probeResult.isVisionCapable

                    if (activeEng != null) {
                        nativeEngine = activeEng
                        activeBackend = resolvedBackend
                        isSessionActive = true
                        isModelVisionCapable = actualVisionCapable
                        val elapsed = System.currentTimeMillis() - startTime
                        addProgressLog("Session active on $activeBackend in ${elapsed}ms!", 1.0f, "Model Ready")

                        _loadingProgress.update { it.copy(isLoading = false, success = true, error = null) }
                        _sessionState.value = ModelSessionState(
                            isLoaded = true,
                            modelName = currentModelName,
                            modelPath = effectivePath,
                            backend = activeBackend,
                            isVisionCapable = actualVisionCapable
                        )
                        true
                    } else {
                        addProgressLog("[FAILED] Model could not be loaded into memory on any backend.")
                        _loadingProgress.update { it.copy(isLoading = false, success = false, error = "Hardware initialization failed") }
                        _sessionState.value = ModelSessionState(
                            isLoaded = false,
                            modelName = currentModelName,
                            backend = "Standby (Simulated)"
                        )
                        false
                    }
                } else {
                    val msg = "Model path or URI is invalid or inaccessible: $modelPath"
                    addProgressLog("[WARN] $msg. Assistant is in standby mode.")
                    currentModelPath = modelPath
                    currentModelName = effectiveName
                    unloadInternal()
                    isSessionActive = false
                    _loadingProgress.update { it.copy(isLoading = false, success = false, error = msg) }
                    _sessionState.value = ModelSessionState(
                        isLoaded = false,
                        modelName = currentModelName,
                        backend = "Standby (Simulated)"
                    )
                    false
                }
            } catch (e: Throwable) {
                AppLogger.e(TAG, "Fatal error initializing LiteRT-LM engine: ${e.message}", e)
                addProgressLog("[FATAL] Exception: ${e.message}")
                unloadInternal()
                isSessionActive = false
                _loadingProgress.update { it.copy(isLoading = false, success = false, error = e.message) }
                _sessionState.value = ModelSessionState(
                    isLoaded = false,
                    modelName = currentModelName,
                    backend = "Standby (Simulated)"
                )
                false
            }
        }
    }


    override fun generateStream(
        prompt: String,
        systemInstruction: String?,
        imageBytes: ByteArray?
    ): Flow<String> = generateStreamWithMetrics(prompt, systemInstruction, imageBytes, null)

    fun generateStreamWithMetrics(
        prompt: String,
        systemInstruction: String? = null,
        imageBytes: ByteArray? = null,
        onMetricsUpdated: ((InferenceMetrics) -> Unit)? = null
    ): Flow<String> = channelFlow {
        inferenceMutex.withLock {
            val startTime = System.currentTimeMillis()
            var firstTokenTime: Long? = null
            var tokenCount = 0

            AppLogger.i(TAG, "Starting stream inference. Image attached: ${imageBytes != null}, prompt length: ${prompt.length}")
            val cleanInstruction = systemInstruction ?: "You are a helpful AI assistant. Enforce concise, direct output."
            val effectivePrompt = if (prompt.contains("<start_of_turn>") || prompt.contains("<|im_start|>")) {
                prompt
            } else {
                "<start_of_turn>user\n$cleanInstruction\n\n$prompt<end_of_turn>\n<start_of_turn>model\n"
            }

            val eng = nativeEngine
            if (isSessionActive && eng != null) {
                AppLogger.i(TAG, "Executing native forward-pass on $activeBackend...")
                NativeFlightRecorder.recordFlightStart(context, "LITERT_INFERENCE", currentModelName, activeBackend)
                var conversation: Conversation? = null
                var session: Session? = null
                try {
                    val contents = mutableListOf<Content>()
                    if (imageBytes != null && imageBytes.isNotEmpty()) {
                        if (isModelVisionCapable) {
                            val pngBytes = LiteRtLmSessionDelegate.preparePngBytes(imageBytes)
                            contents.add(Content.ImageBytes(pngBytes))
                        } else {
                            AppLogger.w(TAG, "Current model ($currentModelName) is pure text-only. Omitting image payload.")
                        }
                    }

                    val rawUserText = if (systemInstruction.isNullOrBlank()) {
                        prompt
                    } else {
                        "$cleanInstruction\n\n$prompt"
                    }
                    val userText = if (com.textmorph.app.util.TokenEstimator.estimateTextTokens(rawUserText) > 3800) {
                        AppLogger.w(TAG, "Prompt tokens exceed LiteRT-LM hardware capacity. Clamping safely.")
                        val notice = "\n\n[Warning: Input truncated to fit 4096 hardware context window]\n\n"
                        val maxChars = 3800 * 3
                        if (rawUserText.length > maxChars) rawUserText.take(500) + notice + rawUserText.takeLast(maxChars - 500 - notice.length) else rawUserText
                    } else {
                        rawUserText
                    }
                    contents.add(Content.Text(userText))

                    val completionDeferred = CompletableDeferred<Unit>()

                    var usedConversation = false
                    try {
                        val settings = runCatching { SettingsRepository.getInstance(context).settingsState.value }.getOrNull()
                        val maxOutTokens = (settings?.maxTokens ?: 1024).coerceIn(64, 4096)
                        val convConfig = ConversationConfig(
                            samplerConfig = if (activeBackend.contains("GPU") || activeBackend.contains("CPU")) {
                                SamplerConfig(
                                    topK = settings?.topK ?: 40,
                                    topP = (settings?.topP ?: 0.95f).toDouble(),
                                    temperature = (settings?.temperature ?: 0.7f).toDouble()
                                )
                            } else null,
                            maxOutputToken = maxOutTokens,
                            thinkingConfig = ThinkingConfig(enableThinking = settings?.enableThinking ?: false)
                        )
                        conversation = eng.createConversation(convConfig)
                        usedConversation = true

                        conversation.sendMessageAsync(
                            Contents.of(contents),
                            object : MessageCallback {
                                override fun onMessage(message: Message) {
                                    val now = System.currentTimeMillis()
                                    if (firstTokenTime == null) {
                                        firstTokenTime = now
                                    }
                                    tokenCount++
                                    val sanitized = LiteRtLmSessionDelegate.sanitizeControlTokens(message.toString())
                                    if (sanitized.isNotEmpty()) {
                                        trySend(sanitized)
                                    }

                                    val ft = firstTokenTime ?: now
                                    val elapsed = (now - ft).coerceAtLeast(1L)
                                    val tps = if (tokenCount > 1) (tokenCount - 1) / (elapsed / 1000f) else 0f

                                    onMetricsUpdated?.invoke(
                                        InferenceMetrics(
                                            timeToFirstTokenMs = ft - startTime,
                                            totalDurationMs = now - startTime,
                                            tokenCount = tokenCount,
                                            tokensPerSecond = tps,
                                            activeBackend = activeBackend,
                                            modelName = currentModelName
                                        )
                                    )
                                }

                                override fun onDone() {
                                    completionDeferred.complete(Unit)
                                }

                                override fun onError(throwable: Throwable) {
                                    if (throwable is java.util.concurrent.CancellationException || throwable is kotlinx.coroutines.CancellationException) {
                                        AppLogger.d(TAG, "Conversation stream cancelled: ${throwable.message}")
                                    } else {
                                        AppLogger.e(TAG, "Conversation stream error: ${throwable.message}", throwable)
                                    }
                                    completionDeferred.completeExceptionally(throwable)
                                }
                            }
                        )
                    } catch (convEx: Throwable) {
                        AppLogger.w(TAG, "Conversation setup failed, falling back to Session: ${convEx.message}", convEx)
                        usedConversation = false
                        session = eng.createSession()
                        val inputs = mutableListOf<InputData>()
                        if (imageBytes != null && imageBytes.isNotEmpty() && isModelVisionCapable) {
                            inputs.add(InputData.Image(imageBytes))
                        }
                        inputs.add(InputData.Text(effectivePrompt))

                        session.generateContentStream(inputs, object : ResponseCallback {
                            override fun onNext(response: String) {
                                val now = System.currentTimeMillis()
                                if (firstTokenTime == null) {
                                    firstTokenTime = now
                                }
                                tokenCount++
                                val sanitized = LiteRtLmSessionDelegate.sanitizeControlTokens(response)
                                if (sanitized.isNotEmpty()) {
                                    trySend(sanitized)
                                }

                                val ft = firstTokenTime ?: now
                                val elapsed = (now - ft).coerceAtLeast(1L)
                                val tps = if (tokenCount > 1) (tokenCount - 1) / (elapsed / 1000f) else 0f

                                onMetricsUpdated?.invoke(
                                    InferenceMetrics(
                                        timeToFirstTokenMs = ft - startTime,
                                        totalDurationMs = now - startTime,
                                        tokenCount = tokenCount,
                                        tokensPerSecond = tps,
                                        activeBackend = activeBackend,
                                        modelName = currentModelName
                                    )
                                )
                            }

                            override fun onDone() {
                                completionDeferred.complete(Unit)
                            }

                            override fun onError(throwable: Throwable) {
                                if (throwable is java.util.concurrent.CancellationException || throwable is kotlinx.coroutines.CancellationException) {
                                    AppLogger.d(TAG, "Native stream cancelled: ${throwable.message}")
                                } else {
                                    AppLogger.e(TAG, "Native stream error: ${throwable.message}", throwable)
                                }
                                completionDeferred.completeExceptionally(throwable)
                            }
                        })
                    }

                    completionDeferred.await()
                    val totalMs = System.currentTimeMillis() - startTime
                    val ftFinal = firstTokenTime ?: startTime
                    val finalTps = if (totalMs > 0) (tokenCount / (totalMs / 1000f)) else 0f
                    AppLogger.i(TAG, "Inference completed. Tokens: $tokenCount, TTFT: ${ftFinal - startTime}ms, TPS: ${"%.1f".format(finalTps)}")
                } catch (ce: CancellationException) {
                    AppLogger.i(TAG, "Inference stream was cancelled by user.")
                    try {
                        conversation?.cancelProcess()
                        session?.cancelProcess()
                    } catch (e: Exception) {
                        AppLogger.w(TAG, "Error cancelling native inference: ${e.message}")
                    }
                    throw ce
                } catch (t: Throwable) {
                    AppLogger.e(TAG, "Native forward pass failed: ${t.message}", t)
                    trySend("\n[LiteRT-LM Native Error: ${t.message ?: "Forward pass failure"}]")
                } finally {
                    try {
                        conversation?.close()
                    } catch (e: Exception) {
                        AppLogger.w(TAG, "Error closing conversation: ${e.message}")
                    }
                    try {
                        session?.close()
                    } catch (e: Exception) {
                        AppLogger.w(TAG, "Error closing session: ${e.message}")
                    }
                    NativeFlightRecorder.recordFlightEnd(context)
                }
            } else {
                AppLogger.d(TAG, "No native model session active. Generating simulated standby response.")
                LiteRtLmSessionDelegate.streamStandbyFallback(
                    scope = this,
                    prompt = prompt,
                    instruction = cleanInstruction,
                    hasImage = imageBytes != null,
                    startTime = startTime,
                    currentModelName = currentModelName,
                    onMetricsUpdated = onMetricsUpdated
                )
            }
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun generate(
        prompt: String,
        systemInstruction: String?,
        imageBytes: ByteArray?
    ): String = withContext(Dispatchers.IO) {
        val sb = StringBuilder()
        generateStream(prompt, systemInstruction, imageBytes).collect { token ->
            sb.append(token)
        }
        sb.toString()
    }

    override fun isLoaded(): Boolean = isSessionActive && nativeEngine != null

    fun getActiveBackend(): String = activeBackend

    fun getActiveModelName(): String = currentModelName

    override suspend fun unload() = withContext(Dispatchers.IO) {
        inferenceMutex.withLock {
            unloadInternal()
        }
    }

    private fun unloadInternal() {
        try {
            AppLogger.i(TAG, "Unloading native LiteRT-LM engine.")
            nativeEngine?.close()
            nativeEngine = null
            activePfd?.close()
            activePfd = null
            createdSymlinkFile?.let { link ->
                try {
                    if (link.exists()) link.delete()
                } catch (e: Exception) {
                    AppLogger.w(TAG, "Error cleaning symlink: ${e.message}")
                }
                createdSymlinkFile = null
            }
            isSessionActive = false
            currentModelPath = null
            _sessionState.value = ModelSessionState()
        } catch (e: Throwable) {
            AppLogger.e(TAG, "Error unloading LiteRT-LM engine: ${e.message}", e)
        }
    }

}
