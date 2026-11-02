package com.textmorph.app.llm

import android.util.Base64
import com.textmorph.app.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class OpenAiCloudEngine(
    private var baseUrl: String = "https://api.openai.com/v1",
    private var apiKey: String = "",
    private var modelId: String = "gpt-4o-mini",
    private var thinkingMode: com.textmorph.app.data.model.ThinkingMode = com.textmorph.app.data.model.ThinkingMode.LOW
) : LlmEngine {

    private val TAG = "OpenAiCloudEngine"
    private val json = Json { ignoreUnknownKeys = true }
    private var isReady = false

    override val maxContextTokens: Int
        get() = 128000

    override val isVisionCapable: Boolean
        get() = isReady

    fun updateConfig(baseUrl: String, apiKey: String, modelId: String) {
        this.baseUrl = baseUrl.trimEnd('/')
        this.apiKey = apiKey.trim()
        this.modelId = modelId.trim()
        this.isReady = this.baseUrl.isNotBlank() && (
            this.apiKey.isNotEmpty() ||
            this.baseUrl.startsWith("http://", ignoreCase = true) ||
            this.baseUrl.contains("localhost") ||
            this.baseUrl.contains("127.0.0.1") ||
            this.baseUrl.contains("192.168.") ||
            this.baseUrl.contains("10.") ||
            this.baseUrl.contains("172.")
        )
        AppLogger.i(TAG, "Cloud config updated: url=$baseUrl, model=$modelId, ready=$isReady")
    }

    fun updateThinkingMode(mode: com.textmorph.app.data.model.ThinkingMode) {
        this.thinkingMode = mode
    }

    suspend fun fetchAvailableModels(rawBaseUrl: String, rawApiKey: String): Result<List<String>> = withContext(Dispatchers.IO) {
        val cleanBase = rawBaseUrl.trim().trimEnd('/')
        if (cleanBase.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("Base URL cannot be blank."))
        }
        val endpoint = if (cleanBase.endsWith("/models")) cleanBase else "$cleanBase/models"
        AppLogger.i(TAG, "Fetching models from: $endpoint")

        try {
            val url = URL(endpoint)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 12000
                readTimeout = 20000
                setRequestProperty("Accept", "application/json")
                if (rawApiKey.isNotBlank()) {
                    setRequestProperty("Authorization", "Bearer ${rawApiKey.trim()}")
                }
            }

            val responseCode = conn.responseCode
            if (responseCode !in 200..299) {
                val errorMsg = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "HTTP $responseCode"
                AppLogger.w(TAG, "Fetching models failed with HTTP $responseCode: $errorMsg")
                return@withContext Result.failure(Exception("HTTP $responseCode: $errorMsg"))
            }

            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val parsed = json.parseToJsonElement(body).jsonObject
            val dataArray = parsed["data"]?.jsonArray ?: parsed["models"]?.jsonArray
            val modelList = mutableListOf<String>()

            dataArray?.forEach { elem ->
                val id = elem.jsonObject["id"]?.jsonPrimitive?.content
                if (!id.isNullOrBlank()) {
                    modelList.add(id)
                }
            }

            modelList.sort()
            AppLogger.i(TAG, "Successfully fetched ${modelList.size} models from $endpoint")
            Result.success(modelList)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Exception while fetching models from $endpoint: ${e.message}", e)
            Result.failure(e)
        }
    }

    override suspend fun initialize(modelPath: String?): Boolean = withContext(Dispatchers.IO) {
        isReady = baseUrl.isNotBlank() && (
            apiKey.isNotEmpty() ||
            baseUrl.startsWith("http://", ignoreCase = true) ||
            baseUrl.contains("localhost") ||
            baseUrl.contains("127.0.0.1") ||
            baseUrl.contains("192.168.") ||
            baseUrl.contains("10.") ||
            baseUrl.contains("172.")
        )
        isReady
    }

    override fun generateStream(
        prompt: String,
        systemInstruction: String?,
        imageBytes: ByteArray?,
        temperature: Float?
    ): Flow<String> = flow {
        if (apiKey.isBlank() && !baseUrl.contains("localhost") && !baseUrl.contains("127.0.0.1")) {
            val msg = "Error: OpenAI API key is missing. Please configure your API key in Settings or switch to On-Device LiteRT-LM."
            AppLogger.w(TAG, msg)
            emit(msg)
            return@flow
        }

        AppLogger.i(TAG, "Starting Cloud streaming inference with model: $modelId")
        val endpoint = "$baseUrl/chat/completions"
        val url = URL(endpoint)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            doInput = true
            connectTimeout = 15000
            readTimeout = 40000
            setRequestProperty("Content-Type", "application/json")
            if (apiKey.isNotEmpty()) {
                setRequestProperty("Authorization", "Bearer $apiKey")
            }
        }

        val effectiveTemp = temperature?.toDouble() ?: if (
            systemInstruction?.contains("Code-as-Action", ignoreCase = true) == true ||
            systemInstruction?.contains("Benchmark", ignoreCase = true) == true ||
            systemInstruction?.contains("autonomous", ignoreCase = true) == true ||
            systemInstruction?.contains("automation", ignoreCase = true) == true ||
            systemInstruction?.contains("planner", ignoreCase = true) == true ||
            prompt.contains("Code-as-Action", ignoreCase = true)
        ) 0.0 else 0.7

        val isThinking = thinkingMode != com.textmorph.app.data.model.ThinkingMode.LOW
        val budget = thinkingMode.maxBudgetTokens

        val effectiveSystemInstruction = buildString {
            if (!systemInstruction.isNullOrBlank()) {
                append(systemInstruction.trim())
                append("\n\n")
            }
            if (isThinking) {
                append("Think carefully step-by-step before answering. Wrap your entire reasoning in <think>...</think> tags.")
            } else {
                append("Answer directly. Do NOT output thinking tags or chain-of-thought.")
            }
        }

        val requestPayload = buildJsonObject {
            put("model", modelId)
            put("stream", true)
            put("temperature", effectiveTemp)
            put("max_tokens", 1024 + budget)
            if (modelId.startsWith("o1") || modelId.startsWith("o3")) {
                put("reasoning_effort", when (thinkingMode) {
                    com.textmorph.app.data.model.ThinkingMode.LOW -> "low"
                    com.textmorph.app.data.model.ThinkingMode.MEDIUM -> "medium"
                    com.textmorph.app.data.model.ThinkingMode.HIGH -> "high"
                })
            }
            put("stop", buildJsonArray {
                add(JsonPrimitive("<end_of_turn>"))
                add(JsonPrimitive("<|end_of_turn|>"))
                add(JsonPrimitive("<|eot_id|>"))
                add(JsonPrimitive("<|im_end|>"))
            })
            put("messages", buildJsonArray {
                if (effectiveSystemInstruction.isNotBlank()) {
                    add(buildJsonObject {
                        put("role", "system")
                        put("content", effectiveSystemInstruction)
                    })
                }
                add(buildJsonObject {
                    put("role", "user")
                    if (imageBytes != null) {
                        val base64Img = Base64.encodeToString(imageBytes, Base64.NO_WRAP)
                        val mimeType = if (imageBytes.size > 8 &&
                            imageBytes[0] == 0x89.toByte() &&
                            imageBytes[1] == 0x50.toByte() &&
                            imageBytes[2] == 0x4E.toByte() &&
                            imageBytes[3] == 0x47.toByte()
                        ) "image/png" else "image/jpeg"
                        put("content", buildJsonArray {
                            add(buildJsonObject {
                                put("type", "text")
                                put("text", prompt)
                            })
                            add(buildJsonObject {
                                put("type", "image_url")
                                put("image_url", buildJsonObject {
                                    put("url", "data:$mimeType;base64,$base64Img")
                                })
                            })
                        })
                    } else {
                        put("content", prompt)
                    }
                })
            })
        }

        try {
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(requestPayload.toString())
                writer.flush()
            }

            val responseCode = conn.responseCode
            if (responseCode !in 200..299) {
                val errorMsg = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "HTTP $responseCode"
                AppLogger.e(TAG, "Cloud API request failed with code $responseCode: $errorMsg")
                emit("Error ($responseCode): $errorMsg")
                return@flow
            }

            var hasOpenedThink = false
            var hasClosedThink = false

            BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val trimmed = line?.trim() ?: continue
                    if (trimmed.isEmpty() || trimmed.startsWith(":")) continue
                    if (trimmed == "data: [DONE]") break

                    if (trimmed.startsWith("data:")) {
                        val dataJson = trimmed.substring(5).trim()
                        try {
                            val parsed = json.parseToJsonElement(dataJson).jsonObject
                            val choices = parsed["choices"]?.jsonArray
                            val delta = choices?.firstOrNull()?.jsonObject?.get("delta")?.jsonObject

                            // 1. Process reasoning content (DeepSeek-R1 / OpenRouter / Groq)
                            val reasoningElem = delta?.get("reasoning_content") ?: delta?.get("reasoning")
                            if (reasoningElem != null && reasoningElem !is kotlinx.serialization.json.JsonNull) {
                                val reasoningChunk = reasoningElem.jsonPrimitive.contentOrNull
                                if (!reasoningChunk.isNullOrEmpty() && reasoningChunk != "null") {
                                    if (isThinking) {
                                        if (!hasOpenedThink) {
                                            hasOpenedThink = true
                                            emit("<think>\n")
                                        }
                                        emit(reasoningChunk)
                                    }
                                }
                            }

                            // 2. Process primary answer content
                            val contentElem = delta?.get("content")
                            if (contentElem != null && contentElem !is kotlinx.serialization.json.JsonNull) {
                                val content = contentElem.jsonPrimitive.contentOrNull
                                if (!content.isNullOrEmpty() && content != "null") {
                                    if (hasOpenedThink && !hasClosedThink) {
                                        hasClosedThink = true
                                        emit("\n</think>\n\n")
                                    }
                                    emit(content)
                                }
                            }
                        } catch (e: Exception) {
                            AppLogger.d(TAG, "Non-fatal parse issue on chunk boundary: ${e.message}")
                        }
                    }
                }
            }
            if (hasOpenedThink && !hasClosedThink) {
                emit("\n</think>\n\n")
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Exception during Cloud streaming request: ${e.message}", e)
            emit("Connection error: ${e.message}")
        } finally {
            conn.disconnect()
        }
    }.flowOn(Dispatchers.IO)

    fun generateStreamWithMetrics(
        prompt: String,
        systemInstruction: String? = null,
        imageBytes: ByteArray? = null,
        temperature: Float? = null,
        onMetricsUpdated: ((InferenceMetrics) -> Unit)? = null
    ): Flow<String> = channelFlow {
        val startTime = System.currentTimeMillis()
        var firstTokenTime: Long? = null
        var tokenCount = 0

        generateStream(prompt, systemInstruction, imageBytes, temperature).collect { chunk ->
            if (firstTokenTime == null && chunk.isNotBlank()) {
                firstTokenTime = System.currentTimeMillis()
            }
            val words = chunk.split(Regex("\\s+")).filter { it.isNotEmpty() }.size
            tokenCount += words.coerceAtLeast(1)
            val now = System.currentTimeMillis()
            val totalSec = ((now - (firstTokenTime ?: startTime)) / 1000.0).coerceAtLeast(0.001)
            val ttftMs = (firstTokenTime ?: now) - startTime
            val speed = (tokenCount / totalSec).toFloat()

            val metrics = InferenceMetrics(
                tokensPerSecond = speed,
                timeToFirstTokenMs = ttftMs,
                tokenCount = tokenCount,
                totalDurationMs = now - startTime,
                activeBackend = "Cloud API ($modelId)",
                modelName = modelId
            )
            onMetricsUpdated?.invoke(metrics)
            send(chunk)
        }
    }

    override suspend fun generate(
        prompt: String,
        systemInstruction: String?,
        imageBytes: ByteArray?,
        temperature: Float?
    ): String = withContext(Dispatchers.IO) {
        val sb = StringBuilder()
        generateStream(prompt, systemInstruction, imageBytes, temperature).collect { chunk ->
            sb.append(chunk)
        }
        sb.toString()
    }

    override fun isLoaded(): Boolean = isReady

    override suspend fun unload() {
        // Cloud client does not maintain heavy VRAM state
        AppLogger.d(TAG, "Unload requested for cloud engine.")
    }
}
