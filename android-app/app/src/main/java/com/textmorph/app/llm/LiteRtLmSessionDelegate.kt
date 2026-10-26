package com.textmorph.app.llm

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.InputData
import com.google.ai.edge.litertlm.ResponseCallback
import com.textmorph.app.util.AppLogger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Session, probing, and fallback delegate for LiteRT-LM engine.
 */
object LiteRtLmSessionDelegate {
    private const val TAG = "LiteRtLmSessionDelegate"

    data class ResolvedModelPath(
        val effectivePath: String?,
        val pfd: ParcelFileDescriptor?,
        val symlinkFile: File?
    )

    data class EngineProbeResult(
        val engine: Engine?,
        val backend: String,
        val isVisionCapable: Boolean,
        val configuredContextTokens: Int = 131072
    )

    fun resolveModelPathAndSymlink(
        context: Context,
        modelPath: String?,
        modelUri: Uri?,
        effectiveName: String,
        onProgress: (String, Float?, String?) -> Unit
    ): ResolvedModelPath {
        if (modelPath != null && !modelPath.startsWith("/proc/self/fd/") && File(modelPath).exists() && File(modelPath).canRead()) {
            val sizeMb = File(modelPath).length() / (1024 * 1024)
            onProgress("Direct file path confirmed: $modelPath ($sizeMb MB)", 0.25f, "Mapping tensors via zero-copy mmap...")
            return ResolvedModelPath(modelPath, null, null)
        }

        if (modelUri != null) {
            onProgress("Opening zero-copy ParcelFileDescriptor for URI: $modelUri", 0.2f, "Opening SAF file descriptor...")
            val pfd = try {
                context.contentResolver.openFileDescriptor(modelUri, "r")
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to open FileDescriptor for $modelUri: ${e.message}", e)
                null
            }

            if (pfd != null) {
                val fd = pfd.fd
                val procFdPath = "/proc/self/fd/$fd"

                var validCanonicalPath: String? = null
                try {
                    val canonical = android.system.Os.readlink(procFdPath)
                    if (canonical != null && (canonical.endsWith(".litertlm", ignoreCase = true) || canonical.endsWith(".tflite", ignoreCase = true)) && File(canonical).exists() && File(canonical).canRead()) {
                        validCanonicalPath = canonical
                        onProgress("Canonical path resolved via readlink: $canonical", 0.3f, null)
                    }
                } catch (readlinkEx: Exception) {
                    AppLogger.d(TAG, "readlink /proc/self/fd/$fd: ${readlinkEx.message}")
                }

                if (validCanonicalPath != null) {
                    return ResolvedModelPath(validCanonicalPath, pfd, null)
                }

                val safeName = effectiveName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
                val linkName = if (safeName.endsWith(".litertlm", ignoreCase = true)) {
                    "mmap_${fd}_$safeName"
                } else {
                    "mmap_${fd}_${safeName}.litertlm"
                }
                val symlinkFile = File(context.cacheDir, linkName)
                if (symlinkFile.exists()) {
                    symlinkFile.delete()
                }
                var effectivePath: String? = null
                var createdSymlink: File? = null
                try {
                    android.system.Os.symlink(procFdPath, symlinkFile.absolutePath)
                    if (symlinkFile.exists()) {
                        effectivePath = symlinkFile.absolutePath
                        createdSymlink = symlinkFile
                        onProgress("Created zero-copy symlink for SAF descriptor: ${symlinkFile.absolutePath}", 0.35f, "Mapping tensors from descriptor...")
                    } else {
                        effectivePath = procFdPath
                    }
                } catch (symlinkEx: Exception) {
                    AppLogger.w(TAG, "Symlink creation failed (${symlinkEx.message}), using descriptor path.", symlinkEx)
                    effectivePath = procFdPath
                }
                return ResolvedModelPath(effectivePath, pfd, createdSymlink)
            } else {
                onProgress("[ERROR] Failed to open SAF file descriptor for: $modelUri", null, null)
            }
        } else if (modelPath != null && !modelPath.startsWith("/proc/self/fd/") && File(modelPath).exists()) {
            onProgress("Attempting direct file path: $modelPath", 0.25f, null)
            return ResolvedModelPath(modelPath, null, null)
        }

        return ResolvedModelPath(null, null, null)
    }

    fun checkVisionEncoder(pfd: ParcelFileDescriptor?, effectivePath: String): Boolean {
        return try {
            val fileToCheck = if (pfd != null) {
                java.io.FileInputStream(pfd.fileDescriptor)
            } else {
                File(effectivePath).inputStream()
            }
            fileToCheck.use { input ->
                val headerBuffer = ByteArray(32768)
                val read = input.read(headerBuffer)
                if (read > 0) {
                    val headerText = String(headerBuffer, 0, read, Charsets.ISO_8859_1)
                    headerText.contains("tf_lite_vision_encoder")
                } else {
                    false
                }
            }
        } catch (inspectEx: Exception) {
            AppLogger.d(TAG, "Model header inspection notice: ${inspectEx.message}")
            false
        }
    }

    suspend fun probeAndSelectEngine(
        context: Context,
        modelPath: String,
        preferredAccelerator: String,
        hasVisionEncoder: Boolean,
        effectiveTimeout: Long,
        maxContextTokens: Int = 131072,
        onProgress: (String, Float?, String?) -> Unit
    ): EngineProbeResult {
        val wantsGpu = preferredAccelerator.equals("GPU", ignoreCase = true) || preferredAccelerator.equals("AUTO", ignoreCase = true)
        var activeEng: Engine? = null
        var resolvedBackend = ""
        var actualVisionCapable = false
        var resolvedContextTokens = maxContextTokens

        if (wantsGpu) {
            onProgress("Probing GPU backend (Vulkan/OpenCL, capacity: $maxContextTokens tokens)...", 0.45f, "Compiling GPU shaders & weights...")
            try {
                val (gpuEng, isVision) = tryCreateAndInitEngine(
                    context = context,
                    modelPath = modelPath,
                    backend = Backend.GPU(),
                    enableVision = hasVisionEncoder,
                    backendLabel = "GPU",
                    timeoutMs = effectiveTimeout,
                    maxNumTokens = maxContextTokens,
                    onProgress = onProgress
                )
                if (gpuEng != null) {
                    activeEng = gpuEng
                    resolvedBackend = "GPU (Vulkan/OpenCL)"
                    actualVisionCapable = isVision
                } else {
                    if (!preferredAccelerator.equals("GPU", ignoreCase = true)) {
                        onProgress("[WARN] GPU liveliness probe failed. Falling back to multi-threaded CPU (XNNPACK)...", 0.6f, "Falling back to CPU...")
                    }
                }
            } catch (gpuEx: Throwable) {
                AppLogger.w(TAG, "GPU compilation failed: ${gpuEx.message}", gpuEx)
                if (!preferredAccelerator.equals("GPU", ignoreCase = true)) {
                    onProgress("[WARN] GPU init failed (${gpuEx.message}). Falling back to multi-threaded CPU (XNNPACK)...", 0.6f, "Falling back to CPU...")
                } else {
                    onProgress("[ERROR] Force GPU requested but failed: ${gpuEx.message}", null, null)
                }
            }
        }

        if (activeEng == null && !preferredAccelerator.equals("GPU", ignoreCase = true)) {
            onProgress("Initializing native CPU backend (multi-threaded XNNPACK, capacity: $maxContextTokens tokens)...", 0.75f, "Compiling CPU tensors...")
            try {
                val (cpuEng, isVision) = tryCreateAndInitEngine(
                    context = context,
                    modelPath = modelPath,
                    backend = Backend.CPU(),
                    enableVision = hasVisionEncoder,
                    backendLabel = "CPU",
                    timeoutMs = effectiveTimeout,
                    maxNumTokens = maxContextTokens,
                    onProgress = onProgress
                )
                if (cpuEng != null) {
                    activeEng = cpuEng
                    resolvedBackend = "CPU (XNNPACK)"
                    actualVisionCapable = isVision
                } else {
                    onProgress("[ERROR] CPU liveliness probe failed.", null, null)
                }
            } catch (cpuEx: Throwable) {
                AppLogger.e(TAG, "CPU initialization failed: ${cpuEx.message}", cpuEx)
                onProgress("[ERROR] CPU LiteRT-LM initialization failed: ${cpuEx.message}", null, null)
            }
        }

        return EngineProbeResult(activeEng, resolvedBackend, actualVisionCapable, resolvedContextTokens)
    }

    private suspend fun tryCreateAndInitEngine(
        context: Context,
        modelPath: String,
        backend: Backend,
        enableVision: Boolean,
        backendLabel: String,
        timeoutMs: Long,
        maxNumTokens: Int? = 131072,
        onProgress: (String, Float?, String?) -> Unit
    ): Pair<Engine?, Boolean> {
        val initialVisionBackend = if (enableVision) backend else null
        val config = EngineConfig(
            modelPath = modelPath,
            backend = backend,
            visionBackend = initialVisionBackend,
            maxNumTokens = maxNumTokens,
            cacheDir = context.cacheDir.absolutePath
        )
        val eng = Engine(config)
        return try {
            eng.initialize()
            onProgress("Native $backendLabel compilation succeeded ($maxNumTokens tokens). Probing liveliness (${timeoutMs}ms)...", 0.65f, "Testing $backendLabel session...")
            val isAlive = testEngineLiveliness(eng, backendLabel, timeoutMs, onProgress)
            if (isAlive) {
                Pair(eng, enableVision)
            } else {
                eng.close()
                Pair(null, false)
            }
        } catch (initEx: Throwable) {
            eng.close()
            val msg = initEx.message ?: ""
            if (enableVision && (msg.contains("tf_lite_vision_encoder", ignoreCase = true) || msg.contains("NOT_FOUND", ignoreCase = true))) {
                onProgress("[INFO] Model has no vision encoder ($msg). Retrying pure Text-Only mode on $backendLabel...", 0.6f, "Falling back to text-only...")
                val textConfig = EngineConfig(
                    modelPath = modelPath,
                    backend = backend,
                    visionBackend = null,
                    maxNumTokens = maxNumTokens,
                    cacheDir = context.cacheDir.absolutePath
                )
                val textEng = Engine(textConfig)
                try {
                    textEng.initialize()
                    onProgress("Text-only compilation succeeded on $backendLabel. Running liveliness probe (Timeout: ${timeoutMs}ms)...", 0.75f, null)
                    val isTextAlive = testEngineLiveliness(textEng, backendLabel, timeoutMs, onProgress)
                    if (isTextAlive) {
                        Pair(textEng, false)
                    } else {
                        textEng.close()
                        Pair(null, false)
                    }
                } catch (textEx: Throwable) {
                    textEng.close()
                    AppLogger.w(TAG, "Text-only fallback failed on $backendLabel: ${textEx.message}", textEx)
                    throw textEx
                }
            } else {
                throw initEx
            }
        }
    }

    private suspend fun testEngineLiveliness(
        eng: Engine,
        backendLabel: String,
        timeoutMs: Long,
        onProgress: (String, Float?, String?) -> Unit
    ): Boolean {
        val testPrompt = "hi how do you feel?"
        onProgress("[TEST] Probing $backendLabel liveliness (Timeout: ${timeoutMs}ms) with prompt: '$testPrompt'", null, "Verifying liveliness on $backendLabel...")

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

                withTimeout(timeoutMs) {
                    completionDeferred.await()
                }

                val now = System.currentTimeMillis()
                val totalMs = (now - testStartTime).coerceAtLeast(1L)
                val ttft = (testFirstTokenTime ?: now) - testStartTime
                val tps = if (totalMs > 0) (testTokenCount / (totalMs / 1000f)) else 0f
                val responseOutput = sb.toString().replace("<end_of_turn>", "").replace("<start_of_turn>", "").trim()
                val displayOutput = if (responseOutput.length > 100) responseOutput.take(100) + "..." else responseOutput

                onProgress("[TEST][$backendLabel] Prompt: \"$testPrompt\"", null, null)
                onProgress("[TEST][$backendLabel] Output: \"$displayOutput\"", null, null)
                onProgress("[SUCCESS] Model Verified Alive on $backendLabel! ($testTokenCount tokens, ${"%.1f".format(tps)} tok/s, TTFT: ${ttft}ms)", null, null)
                true
            } finally {
                try {
                    session.close()
                } catch (closeEx: Exception) {
                    AppLogger.w(TAG, "Error closing liveliness test session: ${closeEx.message}")
                }
            }
        } catch (t: Throwable) {
            AppLogger.w(TAG, "Liveliness evaluation on $backendLabel failed after ${timeoutMs}ms: ${t.message}", t)
            onProgress("[WARN] $backendLabel liveliness check failed: ${t.message}", null, null)
            false
        }
    }

    fun preparePngBytes(imageBytes: ByteArray): ByteArray {
        return try {
            val bmp = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
            if (bmp != null) {
                val stream = ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.PNG, 100, stream)
                stream.toByteArray()
            } else {
                imageBytes
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "Image PNG conversion fallback: ${e.message}")
            imageBytes
        }
    }

    suspend fun streamStandbyFallback(
        scope: ProducerScope<String>,
        prompt: String,
        instruction: String,
        hasImage: Boolean,
        startTime: Long,
        currentModelName: String,
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
            val ftTime = firstTokenTime
            val space = if (index < words.size - 1) "$word " else word
            tokenCount++
            scope.send(space)

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

    fun sanitizeControlTokens(text: String): String {
        var clean = text
        clean = clean.replace("<end_of_turn>", "")
        clean = clean.replace("<start_of_turn>", "")
        clean = clean.replace("<eos>", "")
        clean = clean.replace("<bos>", "")
        return clean
    }

    fun sanitizeNoThinking(text: String): String {
        var clean = text
        clean = clean.replace(Regex("(?s)<think>.*?</think>"), "")
        clean = clean.replace("<think>", "").replace("</think>", "")
        clean = sanitizeControlTokens(clean)
        return clean
    }

    fun generateStandbyResponse(prompt: String, system: String, hasImage: Boolean): String {
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
