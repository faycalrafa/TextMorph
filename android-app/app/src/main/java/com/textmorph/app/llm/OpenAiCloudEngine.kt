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

    fun updateConfig(baseUrl: String, apiKey: String, modelId: String) {
        this.baseUrl = baseUrl.trimEnd('/')
        this.apiKey = apiKey.trim()
        this.modelId = modelId.trim()
        this.isReady = this.apiKey.isNotEmpty() || this.baseUrl.contains("localhost") || this.baseUrl.contains("127.0.0.1")
        AppLogger.i(TAG, "Cloud config updated: url=$baseUrl, model=$modelId, ready=$isReady")
    }

    override suspend fun initialize(modelPath: String?): Boolean = withContext(Dispatchers.IO) {
        isReady = apiKey.isNotEmpty() || baseUrl.contains("localhost") || baseUrl.contains("127.0.0.1")
        isReady
    }

    override fun generateStream(
        prompt: String,
        systemInstruction: String?,
        imageBytes: ByteArray?
    ): Flow<String> = flow {
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
                        put("content", "$systemInstruction. Strict rule: Do not output thinking or reasoning tags. Answer directly.")
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
                                // Filter any think tags
                                val sanitized = content.replace("<think>", "").replace("</think>", "")
                                if (sanitized.isNotEmpty()) {
                                    emit(sanitized)
                                }
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
