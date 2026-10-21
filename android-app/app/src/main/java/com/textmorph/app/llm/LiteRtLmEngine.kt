package com.textmorph.app.llm

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.InputData
import com.google.ai.edge.litertlm.ResponseCallback
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
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ModelSessionState(
    val isLoaded: Boolean = false,
    val modelName: String = "No Model Loaded",
    val modelPath: String? = null,
    val backend: String = "Standby (Simulated)",
    val isVisionCapable: Boolean = true
)

data class ModelLoadingProgress(
    val isLoading: Boolean = false,
    val progress: Float = 0f,
    val statusText: String = "Idle",
    val logLines: List<String> = emptyList(),
    val error: String? = null,
    val success: Boolean = false
)

class LiteRtLmEngine(private val context: Context) : LlmEngine {

    private val TAG = "LiteRtLmEngine"
    private val inferenceMutex = Mutex()

    private var nativeEngine: Engine? = null
    private var activePfd: ParcelFileDescriptor? = null
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

    override suspend fun initialize(modelPath: String?): Boolean = initialize(modelPath, null, null, "AUTO")

    suspend fun initialize(
        modelPath: String?,
        modelName: String? = null,
        modelUri: Uri? = null,
        preferredAccelerator: String = "AUTO"
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
                val effectiveName = modelName ?: modelPath?.let { File(it).name } ?: modelUri?.lastPathSegment ?: "Custom Model"
                addProgressLog("Initializing LiteRT-LM session for: $effectiveName (Accelerator: $preferredAccelerator)", 0.1f, "Inspecting model file...")

                // Clean previous engine if any
                unloadInternal()

                var effectivePath: String? = null

                // 1. Resolve path from direct file or ParcelFileDescriptor
                if (modelPath != null && !modelPath.startsWith("/proc/self/fd/") && File(modelPath).exists() && File(modelPath).canRead()) {
                    effectivePath = modelPath
                    val sizeMb = File(modelPath).length() / (1024 * 1024)
                    addProgressLog("Direct file path confirmed: $modelPath ($sizeMb MB)", 0.25f, "Mapping tensors via zero-copy mmap...")
                } else if (modelUri != null) {
                    addProgressLog("Opening zero-copy ParcelFileDescriptor for URI: $modelUri", 0.2f, "Opening SAF file descriptor...")
                    val pfd = try {
                        context.contentResolver.openFileDescriptor(modelUri, "r")
                    } catch (e: Exception) {
                        AppLogger.e(TAG, "Failed to open FileDescriptor for $modelUri: ${e.message}", e)
                        null
                    }

                    if (pfd != null) {
                        activePfd = pfd
                        val fd = pfd.fd
                        val procFdPath = "/proc/self/fd/$fd"
                        effectivePath = procFdPath
                        addProgressLog("Obtained open ParcelFileDescriptor fd=$fd ($procFdPath)", 0.35f, "Mapping tensors from descriptor...")
                    } else {
                        addProgressLog("[ERROR] Failed to open SAF file descriptor for: $modelUri")
                    }
                } else if (modelPath != null && !modelPath.startsWith("/proc/self/fd/") && File(modelPath).exists()) {
                    effectivePath = modelPath
                    addProgressLog("Attempting direct file path: $modelPath", 0.25f)
                }

                if (effectivePath != null) {
                    currentModelPath = effectivePath
                    currentModelName = effectiveName

                    var activeEng: Engine? = null
                    var resolvedBackend = ""

                    val wantsGpu = preferredAccelerator.equals("GPU", ignoreCase = true) || preferredAccelerator.equals("AUTO", ignoreCase = true)

                    // Phase A: Probe GPU delegate if AUTO or GPU
                    if (wantsGpu) {
                        addProgressLog("Probing GPU backend (Vulkan/OpenCL) for hardware acceleration...", 0.45f, "Compiling GPU shaders & weights...")
                        try {
                            val gpuConfig = EngineConfig(
                                modelPath = effectivePath,
                                backend = Backend.GPU(),
                                visionBackend = Backend.GPU(),
                                cacheDir = context.cacheDir.absolutePath
                            )
                            val eng = Engine(gpuConfig)
                            eng.initialize()
                            addProgressLog("Native GPU compilation succeeded. Running GPU liveliness probe...", 0.7f, "Testing GPU session...")

                            val isGpuAlive = testEngineLiveliness(eng, "GPU")
                            if (isGpuAlive) {
                                activeEng = eng
                                resolvedBackend = "GPU (Vulkan/OpenCL)"
                            } else {
                                eng.close()
                                if (!preferredAccelerator.equals("GPU", ignoreCase = true)) {
                                    addProgressLog("[WARN] GPU liveliness probe failed. Falling back to multi-threaded CPU (XNNPACK)...", 0.6f, "Falling back to CPU...")
                                }
                            }
                        } catch (gpuEx: Throwable) {
                            AppLogger.w(TAG, "GPU compilation failed: ${gpuEx.message}", gpuEx)
                            if (!preferredAccelerator.equals("GPU", ignoreCase = true)) {
                                addProgressLog("[WARN] GPU init failed (${gpuEx.message}). Falling back to multi-threaded CPU (XNNPACK)...", 0.6f, "Falling back to CPU...")
                            } else {
                                addProgressLog("[ERROR] Force GPU requested but failed: ${gpuEx.message}")
                            }
                        }
                    }

                    // Phase B: Fallback or direct CPU backend (if GPU not active and CPU not disabled)
                    if (activeEng == null && !preferredAccelerator.equals("GPU", ignoreCase = true)) {
                        addProgressLog("Initializing native CPU backend (multi-threaded XNNPACK)...", 0.75f, "Compiling CPU tensors...")
                        try {
                            val cpuConfig = EngineConfig(
                                modelPath = effectivePath,
                                backend = Backend.CPU(),
                                visionBackend = Backend.CPU(),
                                cacheDir = context.cacheDir.absolutePath
                            )
                            val eng = Engine(cpuConfig)
                            eng.initialize()
                            addProgressLog("Native CPU compilation succeeded. Running CPU liveliness probe...", 0.85f, "Testing CPU session...")

                            val isCpuAlive = testEngineLiveliness(eng, "CPU")
                            if (isCpuAlive) {
                                activeEng = eng
                                resolvedBackend = "CPU (XNNPACK)"
                            } else {
                                eng.close()
                                addProgressLog("[ERROR] CPU liveliness probe failed.")
                            }
                        } catch (cpuEx: Throwable) {
                            AppLogger.e(TAG, "CPU initialization failed: ${cpuEx.message}", cpuEx)
                            addProgressLog("[ERROR] CPU LiteRT-LM initialization failed: ${cpuEx.message}")
                        }
                    }

                    if (activeEng != null) {
                        nativeEngine = activeEng
                        activeBackend = resolvedBackend
                        isSessionActive = true
                        val elapsed = System.currentTimeMillis() - startTime
                        addProgressLog("Session active on $activeBackend in ${elapsed}ms!", 1.0f, "Model Ready")

                        _loadingProgress.update { it.copy(isLoading = false, success = true, error = null) }
                        _sessionState.value = ModelSessionState(
                            isLoaded = true,
                            modelName = currentModelName,
                            modelPath = effectivePath,
                            backend = activeBackend,
                            isVisionCapable = currentModelName.contains("vision", true) || currentModelName.contains("gemma-4", true)
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

    private suspend fun testEngineLiveliness(eng: Engine, backendLabel: String): Boolean {
        val testPrompt = "hi how do you feel?"
        addProgressLog("[TEST] Probing $backendLabel liveliness with prompt: '$testPrompt'", null, "Verifying liveliness on $backendLabel...")

        val testInstruction = "You are a helpful AI assistant. Answer directly in one concise sentence without thinking traces or conversational intro."
        val promptWithTags = buildString {
            append("<start_of_turn>user\n$testInstruction\n\n$testPrompt<end_of_turn>\n<start_of_turn>model\n")
        }

        val testStartTime = System.currentTimeMillis()
        var testFirstTokenTime: Long? = null
        var testTokenCount = 0
        val sb = StringBuilder()

        return try {
            val session = eng.createSession()
            try {
                val completionDeferred = CompletableDeferred<Unit>()
                session.generateContentStream(listOf(InputData.Text(promptWithTags)), object : ResponseCallback {
                    override fun onNext(response: String) {
                        if (testFirstTokenTime == null) {
                            testFirstTokenTime = System.currentTimeMillis()
                        }
                        testTokenCount++
                        val sanitized = sanitizeNoThinking(response)
                        sb.append(sanitized)
                    }

                    override fun onDone() {
                        completionDeferred.complete(Unit)
                    }

                    override fun onError(throwable: Throwable) {
                        AppLogger.w(TAG, "[$backendLabel] Probe stream notice: ${throwable.message}", throwable)
                        completionDeferred.completeExceptionally(throwable)
                    }
                })

                withTimeout(15000L) {
                    completionDeferred.await()
                }

                val now = System.currentTimeMillis()
                val totalMs = (now - testStartTime).coerceAtLeast(1L)
                val ttft = (testFirstTokenTime ?: now) - testStartTime
                val tps = if (totalMs > 0) (testTokenCount / (totalMs / 1000f)) else 0f
                val responseOutput = sb.toString().trim()
                val displayOutput = if (responseOutput.length > 100) responseOutput.take(100) + "..." else responseOutput

                addProgressLog("[TEST][$backendLabel] Prompt: \"$testPrompt\"")
                addProgressLog("[TEST][$backendLabel] Output: \"$displayOutput\"")
                addProgressLog("[SUCCESS] Model Verified Alive on $backendLabel! ($testTokenCount tokens, ${"%.1f".format(tps)} tok/s, TTFT: ${ttft}ms)")
                true
            } finally {
                try {
                    session.close()
                } catch (closeEx: Exception) {
                    AppLogger.w(TAG, "Error closing liveliness test session: ${closeEx.message}")
                }
            }
        } catch (t: Throwable) {
            AppLogger.w(TAG, "Liveliness evaluation on $backendLabel failed: ${t.message}", t)
            addProgressLog("[WARN] $backendLabel liveliness check failed: ${t.message}")
            false
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
            val effectivePrompt = buildString {
                append("<start_of_turn>user\n$cleanInstruction\nStrict rule: Do not output thinking or reasoning traces. Respond directly.\n\n$prompt<end_of_turn>\n<start_of_turn>model\n")
            }

            val eng = nativeEngine
            if (isSessionActive && eng != null) {
                AppLogger.i(TAG, "Executing native forward-pass on $activeBackend...")
                var session: com.google.ai.edge.litertlm.Session? = null
                try {
                    session = eng.createSession()
                    val inputs = mutableListOf<InputData>()
                    if (imageBytes != null && imageBytes.isNotEmpty()) {
                        inputs.add(InputData.Image(imageBytes))
                    }
                    inputs.add(InputData.Text(effectivePrompt))

                    val completionDeferred = CompletableDeferred<Unit>()

                    session.generateContentStream(inputs, object : ResponseCallback {
                        override fun onNext(response: String) {
                            val now = System.currentTimeMillis()
                            if (firstTokenTime == null) {
                                firstTokenTime = now
                            }
                            tokenCount++
                            val sanitized = sanitizeNoThinking(response)
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
                            AppLogger.e(TAG, "Native stream error: ${throwable.message}", throwable)
                            completionDeferred.completeExceptionally(throwable)
                        }
                    })

                    completionDeferred.await()
                    val totalMs = System.currentTimeMillis() - startTime
                    val ftFinal = firstTokenTime ?: startTime
                    val finalTps = if (totalMs > 0) (tokenCount / (totalMs / 1000f)) else 0f
                    AppLogger.i(TAG, "Inference completed. Tokens: $tokenCount, TTFT: ${ftFinal - startTime}ms, TPS: ${"%.1f".format(finalTps)}")
                } catch (ce: CancellationException) {
                    AppLogger.i(TAG, "Inference stream was cancelled by user.")
                    throw ce
                } catch (t: Throwable) {
                    AppLogger.e(TAG, "Native forward pass failed: ${t.message}", t)
                    trySend("\n[LiteRT-LM Native Error: ${t.message ?: "Forward pass failure"}]")
                } finally {
                    try {
                        session?.close()
                    } catch (e: Exception) {
                        AppLogger.w(TAG, "Error closing session: ${e.message}")
                    }
                }
            } else {
                AppLogger.d(TAG, "No native model session active. Generating simulated standby response.")
                streamStandbyFallback(prompt, cleanInstruction, imageBytes != null, startTime, onMetricsUpdated)
            }
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun kotlinx.coroutines.channels.ProducerScope<String>.streamStandbyFallback(
        prompt: String,
        instruction: String,
        hasImage: Boolean,
        startTime: Long,
        onMetricsUpdated: ((InferenceMetrics) -> Unit)?
    ) {
        val fullText = generateStandbyResponse(prompt, instruction, hasImage)
        val words = fullText.split(" ")
        var firstTokenTime: Long? = null
        var tokenCount = 0

        for ((index, word) in words.withIndex()) {
            val now = System.currentTimeMillis()
            if (firstTokenTime == null) {
                firstTokenTime = now
            }
            val ftTime = firstTokenTime ?: now
            val space = if (index < words.size - 1) "$word " else word
            tokenCount++
            send(space)

            val ttft = ftTime - startTime
            val elapsed = (now - ftTime).coerceAtLeast(1L)
            val speed = if (tokenCount > 1) (tokenCount - 1) / (elapsed / 1000f) else 25f

            onMetricsUpdated?.invoke(
                InferenceMetrics(
                    timeToFirstTokenMs = ttft,
                    totalDurationMs = now - startTime,
                    tokenCount = tokenCount,
                    tokensPerSecond = speed,
                    activeBackend = "Standby (Simulated)",
                    modelName = currentModelName
                )
            )
            delay(16)
        }

        val totalMs = System.currentTimeMillis() - startTime
        val ftFinal = firstTokenTime ?: startTime
        onMetricsUpdated?.invoke(
            InferenceMetrics(
                timeToFirstTokenMs = ftFinal - startTime,
                totalDurationMs = totalMs,
                tokenCount = tokenCount,
                tokensPerSecond = if (totalMs > 0) (tokenCount / (totalMs / 1000f)) else 25f,
                activeBackend = "Standby (Simulated)",
                modelName = currentModelName
            )
        )
    }

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
            isSessionActive = false
            currentModelPath = null
            _sessionState.value = ModelSessionState()
        } catch (e: Throwable) {
            AppLogger.e(TAG, "Error unloading LiteRT-LM engine: ${e.message}", e)
        }
    }

    private fun sanitizeNoThinking(text: String): String {
        var clean = text
        clean = clean.replace(Regex("(?s)<think>.*?</think>"), "").trim()
        clean = clean.replace("<think>", "").replace("</think>", "")
        return clean
    }

    private fun generateStandbyResponse(prompt: String, system: String, hasImage: Boolean): String {
        return when {
            system.contains("writing assistant", ignoreCase = true) || system.contains("Execute", ignoreCase = true) -> {
                when {
                    prompt.contains("translate to french", ignoreCase = true) -> "Bonjour, comment allez-vous ?"
                    prompt.contains("translate to spanish", ignoreCase = true) -> "Hola, ¿cómo estás?"
                    prompt.contains("summarize", ignoreCase = true) -> prompt.split(" ").take(8).joinToString(" ") + "..."
                    prompt.contains("professional", ignoreCase = true) -> "I would like to articulate that: " + prompt.replace(Regex("(?i)rewrite(:)?|professional(:)?"), "").trim()
                    prompt.contains("fix", ignoreCase = true) -> prompt.replace(Regex("(?i)fix(:)?|grammar(:)?"), "").trim().replaceFirstChar { it.uppercase() } + "."
                    else -> prompt.replace(Regex("(?i)execute(:)?|please(:)?"), "").trim()
                }
            }
            system.contains("Fix Grammar", ignoreCase = true) ->
                prompt.trim().replaceFirstChar { it.uppercase() } + (if (!prompt.endsWith(".")) "." else "")
            system.contains("Make Concise", ignoreCase = true) ->
                "Concise summary: ${prompt.take(60)}..."
            system.contains("Professional Tone", ignoreCase = true) ->
                "Thank you for sharing your thoughts. In regard to your message: \"${prompt.trim()}\", we appreciate your insights and will follow up accordingly."
            system.contains("Casual Tone", ignoreCase = true) ->
                "Hey there! Looks great: ${prompt.trim()} 🙌"
            hasImage ->
                "Visual analysis: The image depicts an on-screen view with clear textual content and structured layout."
            else ->
                "Note: No local model weights loaded. You can select and load a .litertlm model in Model Hub.\n\nInput received: \"${prompt.trim()}\""
        }
    }
}
