package com.textmorph.app.llm

import android.util.Base64
import com.textmorph.app.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
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
    private var modelId: String = "gpt-4o-mini"
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
        imageBytes: ByteArray?
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

        val requestPayload = buildJsonObject {
            put("model", modelId)
            put("stream", true)
            put("temperature", 0.7)
            put("messages", buildJsonArray {
                if (!systemInstruction.isNullOrBlank()) {
                    add(buildJsonObject {
                        put("role", "system")
                        put("content", if (systemInstruction.contains("think", ignoreCase = true)) systemInstruction else "$systemInstruction. Answer directly.")
                    })
                }
                add(buildJsonObject {
                    put("role", "user")
                    if (imageBytes != null) {
                        val base64Img = Base64.encodeToString(imageBytes, Base64.NO_WRAP)
                        put("content", buildJsonArray {
                            add(buildJsonObject {
                                put("type", "text")
                                put("text", prompt)
                            })
                            add(buildJsonObject {
                                put("type", "image_url")
                                put("image_url", buildJsonObject {
                                    put("url", "data:image/jpeg;base64,$base64Img")
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
                            val content = delta?.get("content")?.jsonPrimitive?.content
                            if (!content.isNullOrEmpty()) {
                                emit(content)
                            }
                        } catch (e: Exception) {
                            // Non-fatal parse issue on chunk boundary
                        }
                    }
                }
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Exception during Cloud streaming request: ${e.message}", e)
            emit("Connection error: ${e.message}")
        } finally {
            conn.disconnect()
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun generate(
        prompt: String,
        systemInstruction: String?,
        imageBytes: ByteArray?
    ): String = withContext(Dispatchers.IO) {
        val sb = StringBuilder()
        generateStream(prompt, systemInstruction, imageBytes).collect { chunk ->
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
