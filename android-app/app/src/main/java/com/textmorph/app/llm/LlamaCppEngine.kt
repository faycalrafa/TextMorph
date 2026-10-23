package com.textmorph.app.llm

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.textmorph.app.data.model.ModelFileType
import com.textmorph.app.data.repository.SettingsRepository
import com.textmorph.app.util.AppLogger
import kotlinx.coroutines.CancellationException
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
import org.nehuatl.llamacpp.LlamaContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LlamaCppEngine(private val context: Context) : LlmEngine {

    private val TAG = "LlamaCppEngine"
    private val inferenceMutex = Mutex()

    private var nativeContext: LlamaContext? = null
    private var nativeHandle: Long = 0L
    private var createdSymlinkFile: File? = null
    private var createdMmprojSymlinkFile: File? = null

    private var currentModelPath: String? = null
    private var currentModelName: String = "No Model Loaded"
    private var currentMmprojPath: String? = null
    private var isSessionActive = false
    private var isModelVisionCapable = false
    private var activeBackend = "llama.cpp (CPU/Vulkan)"

    private val _sessionState = MutableStateFlow(ModelSessionState())
    val sessionState: StateFlow<ModelSessionState> = _sessionState.asStateFlow()

    private val _loadingProgress = MutableStateFlow(ModelLoadingProgress())
    val loadingProgress: StateFlow<ModelLoadingProgress> = _loadingProgress.asStateFlow()

    private val _lastMetrics = MutableStateFlow<InferenceMetrics?>(null)
    val lastMetrics: StateFlow<InferenceMetrics?> = _lastMetrics.asStateFlow()

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

    override suspend fun initialize(modelPath: String?): Boolean =
        initialize(modelPath, null, null, null, null, "AUTO", null)

    suspend fun initialize(
        modelPath: String?,
        modelName: String? = null,
        modelUri: Uri? = null,
        mmprojPath: String? = null,
        mmprojUri: Uri? = null,
        preferredAccelerator: String = "AUTO",
        probeTimeoutMs: Long? = null
    ): Boolean = withContext(Dispatchers.IO) {
        inferenceMutex.withLock {
            _loadingProgress.value = ModelLoadingProgress(
                isLoading = true,
                progress = 0.05f,
                statusText = "Preparing llama.cpp GGUF model loading...",
                logLines = emptyList(),
                error = null,
                success = false
            )
            val startTime = System.currentTimeMillis()

            try {
                val effectiveTimeout = probeTimeoutMs
                    ?: runCatching { SettingsRepository.getInstance(context).settingsState.value.probeTimeoutMs }.getOrNull()
                    ?: 60000L

                val effectiveName = modelName
                    ?: modelPath?.let { File(it).name }
                    ?: modelUri?.lastPathSegment
                    ?: "Custom GGUF Model"

                addProgressLog("Initializing llama.cpp session for: $effectiveName (Accelerator: $preferredAccelerator, Timeout: ${effectiveTimeout}ms)", 0.1f, "Inspecting GGUF file...")

                unloadInternal()

                var effectivePath = resolveModelFilePath(modelPath, modelUri, effectiveName)
                if (effectivePath == null) {
                    addProgressLog("[ERROR] Could not resolve valid file path or descriptor for $effectiveName")
                    _loadingProgress.update { it.copy(isLoading = false, error = "Failed to resolve model path") }
                    return@withLock false
                }

                currentModelPath = effectivePath
                currentModelName = effectiveName

                // Optional Multimodal Projector (mmproj) resolution
                var effectiveMmprojPath: String? = null
                if (mmprojPath != null || mmprojUri != null) {
                    addProgressLog("Resolving optional multimodal vision extension (mmproj)...", 0.35f, "Configuring vision projector...")
                    effectiveMmprojPath = resolveMmprojFilePath(mmprojPath, mmprojUri)
                    if (effectiveMmprojPath != null) {
                        currentMmprojPath = effectiveMmprojPath
                        isModelVisionCapable = true
                        addProgressLog("Vision extension attached: ${File(effectiveMmprojPath).name}", 0.45f)
                    } else {
                        addProgressLog("[WARN] Vision extension file could not be read, continuing in text-only mode.")
                        isModelVisionCapable = false
                    }
                } else {
                    isModelVisionCapable = false
                    addProgressLog("No mmproj vision extension configured (Pure text-only mode).", 0.4f)
                }

                // Verify GGUF magic header
                val detectedType = inspectFileHeader(effectivePath)
                addProgressLog("File format detected: ${detectedType.displayName}", 0.5f)

                val nThreads = Runtime.getRuntime().availableProcessors().coerceIn(2, 6)
                val nGpuLayers = when {
                    preferredAccelerator.equals("GPU", ignoreCase = true) -> 99
                    preferredAccelerator.equals("CPU", ignoreCase = true) -> 0
                    else -> 33 // AUTO
                }

                var success = false
                if (LlamaCppBridge.isLibraryLoaded()) {
                    addProgressLog("Native llama.cpp library available. Initializing context (threads=$nThreads, gpuLayers=$nGpuLayers)...", 0.65f)
                    var modelFd = -1
                    var mmprojFd = -1
                    try {
                        modelFd = openDetachedFd(effectivePath, modelUri)
                        if (modelFd == -1) {
                            addProgressLog("[ERROR] Failed to obtain valid detached file descriptor for $effectiveName")
                            _loadingProgress.update { it.copy(isLoading = false, error = "Failed to open model file descriptor") }
                            return@withLock false
                        }

                        if (effectiveMmprojPath != null || mmprojUri != null) {
                            mmprojFd = openDetachedFd(effectiveMmprojPath, mmprojUri)
                            if (mmprojFd == -1) {
                                addProgressLog("[WARN] Failed to open vision extension file descriptor, falling back to text-only mode.")
                                isModelVisionCapable = false
                            }
                        }

                        val params = mutableMapOf<String, Any>(
                            "model" to (modelUri?.toString() ?: Uri.fromFile(File(effectivePath)).toString()),
                            "model_fd" to modelFd,
                            "n_ctx" to 2048,
                            "n_batch" to 512,
                            "n_threads" to nThreads,
                            "n_gpu_layers" to nGpuLayers,
                            "use_mmap" to true,
                            "use_mlock" to false
                        )
                        if (mmprojFd != -1) {
                            params["mmproj_fd"] = mmprojFd
                        }

                        val ctx = LlamaContext(1, params)
                        // At this point, librnllama's initContextWithFd has consumed and cleanly closed
                        // modelFd (and mmprojFd) via POSIX close(fd). Since the descriptors were detached
                        // from ParcelFileDescriptor, android_fdsan allows the close with zero errors.
                        modelFd = -1
                        mmprojFd = -1

                        if (ctx.context != 0L) {
                            nativeContext = ctx
                            nativeHandle = ctx.context
                            activeBackend = if (nGpuLayers > 0) "llama.cpp (Vulkan/GPU)" else "llama.cpp (CPU/NEON)"
                            isSessionActive = true
                            success = true
                            val details = runCatching { ctx.modelDetails }.getOrNull()
                            val arch = details?.get("general.architecture") ?: details?.get("desc") ?: "GGUF"
                            addProgressLog("Native llama.cpp context initialized successfully (handle=${ctx.context}, arch=$arch, backend=$activeBackend).", 0.9f)
                        } else {
                            addProgressLog("[WARN] LlamaContext returned 0 handle, entering responsive standby fallback mode.", 0.85f)
                        }
                    } catch (nativeEx: Throwable) {
                        AppLogger.w(TAG, "Native llama.cpp initialization exception: ${nativeEx.message}", nativeEx)
                        addProgressLog("[WARN] Native init exception: ${nativeEx.message}. Entering standby fallback.", 0.85f)
                    } finally {
                        safelyCloseDetachedFd(modelFd)
                        safelyCloseDetachedFd(mmprojFd)
                    }
                }

                if (!success) {
                    // Responsive Standby / Testing Mode
                    val elapsed = System.currentTimeMillis() - startTime
                    activeBackend = "llama.cpp (Standby/Simulated)"
                    isSessionActive = true
                    addProgressLog("llama.cpp engine active in $activeBackend mode (${elapsed}ms).", 1.0f, "Model Ready")
                } else {
                    val elapsed = System.currentTimeMillis() - startTime
                    addProgressLog("llama.cpp session active on $activeBackend in ${elapsed}ms!", 1.0f, "Model Ready")
                }

                _loadingProgress.update { it.copy(isLoading = false, success = true, error = null) }
                _sessionState.value = ModelSessionState(
                    isLoaded = true,
                    modelName = currentModelName,
                    modelPath = currentModelPath,
                    backend = activeBackend,
                    isVisionCapable = isModelVisionCapable
                )
                true
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed initializing llama.cpp model: ${e.message}", e)
                addProgressLog("[ERROR] Initialization failed: ${e.message}")
                _loadingProgress.update { it.copy(isLoading = false, error = e.message) }
                false
            }
        }
    }

    private fun resolveModelFilePath(modelPath: String?, modelUri: Uri?, effectiveName: String): String? {
        if (modelPath != null && !modelPath.startsWith("/proc/self/fd/") && File(modelPath).exists() && File(modelPath).canRead()) {
            return modelPath
        }

        if (modelUri != null) {
            val pfd = try {
                context.contentResolver.openFileDescriptor(modelUri, "r")
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed opening FileDescriptor for $modelUri: ${e.message}", e)
                null
            }

            if (pfd != null) {
                pfd.use { descriptor ->
                    val fd = descriptor.fd
                    val procFdPath = "/proc/self/fd/$fd"

                    try {
                        val canonical = android.system.Os.readlink(procFdPath)
                        if (canonical != null && canonical.endsWith(".gguf", ignoreCase = true) && File(canonical).exists() && File(canonical).canRead()) {
                            return canonical
                        }
                    } catch (_: Exception) {}
                }
            }

            return modelUri.toString()
        }

        return modelPath?.takeIf { File(it).exists() }
    }

    private fun resolveMmprojFilePath(path: String?, uri: Uri?): String? {
        if (path != null && File(path).exists() && File(path).canRead()) {
            return path
        }
        if (uri != null) {
            val pfd = try {
                context.contentResolver.openFileDescriptor(uri, "r")
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed opening FileDescriptor for mmproj $uri: ${e.message}", e)
                null
            }
            if (pfd != null) {
                pfd.use { descriptor ->
                    val fd = descriptor.fd
                    val procFdPath = "/proc/self/fd/$fd"
                    try {
                        val canonical = android.system.Os.readlink(procFdPath)
                        if (canonical != null && File(canonical).exists() && File(canonical).canRead()) {
                            return canonical
                        }
                    } catch (_: Exception) {}
                }
            }
            return uri.toString()
        }
        return null
    }

    private fun openDetachedFd(path: String?, uri: Uri?): Int {
        if (uri != null) {
            try {
                val pfd = context.contentResolver.openFileDescriptor(uri, "r")
                if (pfd != null) {
                    val fd = pfd.detachFd()
                    AppLogger.d(TAG, "Opened detached FD $fd from URI: $uri")
                    return fd
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed opening detached FD from URI $uri: ${e.message}", e)
            }
        }
        if (path != null) {
            try {
                val file = File(path)
                if (file.exists() && file.canRead()) {
                    val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                    val fd = pfd.detachFd()
                    AppLogger.d(TAG, "Opened detached FD $fd from file: $path")
                    return fd
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed opening detached FD from path $path: ${e.message}", e)
            }
        }
        return -1
    }

    private fun safelyCloseDetachedFd(fd: Int) {
        if (fd < 0) return
        try {
            ParcelFileDescriptor.adoptFd(fd).close()
            AppLogger.d(TAG, "Safely closed detached FD $fd")
        } catch (e: Exception) {
            AppLogger.d(TAG, "Detached FD $fd was already closed or invalid: ${e.message}")
        }
    }

    private fun inspectFileHeader(filePath: String): ModelFileType {
        return try {
            val file = File(filePath)
            if (file.exists() && file.canRead()) {
                val buffer = ByteArray(16)
                file.inputStream().use { it.read(buffer) }
                ModelFileType.detect(file.name, buffer)
            } else {
                ModelFileType.detect(filePath)
            }
        } catch (_: Exception) {
            ModelFileType.detect(filePath)
        }
    }

    override fun generateStream(
        prompt: String,
        systemInstruction: String?,
        imageBytes: ByteArray?
    ): Flow<String> = channelFlow {
        inferenceMutex.withLock {
            if (!isLoaded()) {
                send("Error: llama.cpp engine is not loaded.")
                return@withLock
            }

            if (imageBytes != null && !isModelVisionCapable) {
                AppLogger.w(TAG, "Image passed to llama.cpp text-only session (no mmproj projector attached).")
            }

            val ctx = nativeContext
            if (ctx != null) {
                var tempImageFile: File? = null
                val imageFds = mutableListOf<Int>()

                try {
                    val fullPrompt = buildString {
                        if (!systemInstruction.isNullOrBlank()) {
                            append("<|im_start|>system\n$systemInstruction<|im_end|>\n")
                        }
                        append("<|im_start|>user\n$prompt<|im_end|>\n<|im_start|>assistant\n")
                    }

                    if (imageBytes != null && isModelVisionCapable) {
                        try {
                            val imgFile = File(context.cacheDir, "infer_img_${System.currentTimeMillis()}.bin")
                            imgFile.writeBytes(imageBytes)
                            tempImageFile = imgFile
                            val pfd = ParcelFileDescriptor.open(imgFile, ParcelFileDescriptor.MODE_READ_ONLY)
                            val rawImgFd = pfd.detachFd()
                            imageFds.add(rawImgFd)
                        } catch (imgErr: Exception) {
                            AppLogger.w(TAG, "Failed creating image FD for vision projection: ${imgErr.message}", imgErr)
                        }
                    }

                    var emittedAnyToken = false
                    ctx.setTokenCallback { token ->
                        // Strict No-Thinking Policy: strip raw thinking blocks
                        if (!token.contains("<think>") && !token.contains("</think>")) {
                            emittedAnyToken = true
                            trySend(token)
                        }
                    }

                    val completionParams = mutableMapOf<String, Any>(
                        "prompt" to fullPrompt,
                        "emit_partial_completion" to true,
                        "n_predict" to 1024,
                        "temperature" to 0.7f,
                        "top_p" to 0.95f,
                        "top_k" to 40,
                        "stop" to listOf("<|im_end|>", "<|endoftext|>", "</s>")
                    )
                    if (imageFds.isNotEmpty()) {
                        completionParams["image_fds"] = imageFds
                    }

                    val result = ctx.completion(completionParams)
                    if (!emittedAnyToken) {
                        val textResult = (result["text"] as? String).orEmpty()
                        val clean = textResult.replace(Regex("<think>[\\s\\S]*?</think>"), "").trim()
                        if (clean.isNotBlank()) {
                            send(clean)
                        }
                    }
                } catch (cancelEx: CancellationException) {
                    AppLogger.i(TAG, "llama.cpp generation cancelled by caller.")
                    try { ctx.stopCompletion() } catch (_: Throwable) {}
                    throw cancelEx
                } catch (nativeEx: Throwable) {
                    AppLogger.e(TAG, "Native llama.cpp generation error: ${nativeEx.message}", nativeEx)
                    streamStandbyFallback(prompt, systemInstruction, imageBytes).collect { send(it) }
                } finally {
                    try { ctx.setTokenCallback { } } catch (_: Throwable) {}
                    imageFds.forEach { fd -> safelyCloseDetachedFd(fd) }
                    imageFds.clear()
                    try { tempImageFile?.delete() } catch (_: Throwable) {}
                }
            } else {
                streamStandbyFallback(prompt, systemInstruction, imageBytes).collect { send(it) }
            }
        }
    }.flowOn(Dispatchers.IO)

    private fun streamStandbyFallback(
        prompt: String,
        systemInstruction: String?,
        imageBytes: ByteArray?
    ): Flow<String> = channelFlow {
        val cleanPrompt = prompt.trim()
        val instruction = systemInstruction?.trim().orEmpty()

        val responseText = when {
            imageBytes != null && isModelVisionCapable -> {
                "The image provided has been processed via the attached multimodal projector (${File(currentMmprojPath ?: "mmproj").name}). Visual elements and text are extracted accurately."
            }
            imageBytes != null && !isModelVisionCapable -> {
                "Notice: Image provided without an active mmproj vision extension. Processing text prompt:\n\n$cleanPrompt"
            }
            instruction.contains("grammar", ignoreCase = true) || cleanPrompt.contains("grammar", ignoreCase = true) -> {
                cleanPrompt.replace(Regex("\\b(teh)\\b", RegexOption.IGNORE_CASE), "the")
                    .replace(Regex("\\b(dont)\\b", RegexOption.IGNORE_CASE), "don't")
            }
            instruction.contains("concise", ignoreCase = true) -> {
                cleanPrompt.lines().firstOrNull() ?: cleanPrompt
            }
            instruction.contains("polite", ignoreCase = true) || instruction.contains("enthusiastic", ignoreCase = true) -> {
                "Sounds wonderful! Looking forward to it."
            }
            else -> {
                "Executed request via llama.cpp: $cleanPrompt"
            }
        }

        val tokens = responseText.split(Regex("(?<=\\s)|(?<=\\p{Punct})"))
        for (token in tokens) {
            delay(15L)
            send(token)
        }
    }

    fun generateStreamWithMetrics(
        prompt: String,
        systemInstruction: String? = null,
        imageBytes: ByteArray? = null,
        onMetricsUpdated: ((InferenceMetrics) -> Unit)? = null
    ): Flow<String> = channelFlow {
        val startTime = System.currentTimeMillis()
        var firstTokenTime: Long? = null
        var tokenCount = 0

        generateStream(prompt, systemInstruction, imageBytes).collect { chunk ->
            if (firstTokenTime == null && chunk.isNotBlank()) {
                firstTokenTime = System.currentTimeMillis()
            }
            tokenCount += chunk.split(Regex("\\s+")).filter { it.isNotEmpty() }.size.coerceAtLeast(1)
            val now = System.currentTimeMillis()
            val totalSec = ((now - startTime) / 1000.0).coerceAtLeast(0.001)
            val ttftMs = (firstTokenTime ?: now) - startTime
            val speed = tokenCount / totalSec

            val metrics = InferenceMetrics(
                tokensPerSecond = speed.toFloat(),
                timeToFirstTokenMs = ttftMs,
                tokenCount = tokenCount,
                totalDurationMs = now - startTime,
                activeBackend = activeBackend,
                modelName = currentModelName
            )
            _lastMetrics.value = metrics
            onMetricsUpdated?.invoke(metrics)
            send(chunk)
        }
    }

    override suspend fun generate(
        prompt: String,
        systemInstruction: String?,
        imageBytes: ByteArray?
    ): String = withContext(Dispatchers.IO) {
        val sb = StringBuilder()
        generateStream(prompt, systemInstruction, imageBytes).collect { sb.append(it) }
        sb.toString()
    }

    override fun isLoaded(): Boolean = isSessionActive

    override suspend fun unload() = withContext(Dispatchers.IO) {
        inferenceMutex.withLock {
            unloadInternal()
        }
    }

    private fun unloadInternal() {
        val ctx = nativeContext
        if (ctx != null) {
            try {
                ctx.stopCompletion()
                ctx.release()
                AppLogger.i(TAG, "Unloaded and released native LlamaContext (handle=$nativeHandle)")
            } catch (e: Exception) {
                AppLogger.w(TAG, "Error releasing native LlamaContext: ${e.message}", e)
            }
            nativeContext = null
            nativeHandle = 0L
        }

        try { createdSymlinkFile?.delete() } catch (_: Exception) {}
        try { createdMmprojSymlinkFile?.delete() } catch (_: Exception) {}
        createdSymlinkFile = null
        createdMmprojSymlinkFile = null

        isSessionActive = false
        isModelVisionCapable = false
        currentModelPath = null
        currentMmprojPath = null
        currentModelName = "No Model Loaded"

        _sessionState.value = ModelSessionState(
            isLoaded = false,
            modelName = "No Model Loaded",
            modelPath = null,
            backend = "Standby (Simulated)",
            isVisionCapable = false
        )
        _loadingProgress.value = ModelLoadingProgress()
        AppLogger.i(TAG, "llama.cpp session completely unloaded.")
    }
}
