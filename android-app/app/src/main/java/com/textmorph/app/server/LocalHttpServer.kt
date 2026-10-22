package com.textmorph.app.server

import android.util.Base64
import com.textmorph.app.llm.LlmEngine
import com.textmorph.app.util.AppLogger
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.request.header
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.Dispatchers
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
import java.util.UUID

class LocalHttpServer(
    private val llmEngine: LlmEngine,
    private val port: Int = 8080,
    private val bearerToken: String = "",
    private val activeModelName: String = "gemma-4-E2B-it.litertlm"
) {

    private val TAG = "LocalHttpServer"
    private var server: EmbeddedServer<*, *>? = null
    private val json = Json { ignoreUnknownKeys = true }

    fun start() {
        if (server != null) {
            AppLogger.w(TAG, "Local server already running on port $port.")
            return
        }

        try {
            AppLogger.i(TAG, "Starting Local OpenAI HTTP Server on 127.0.0.1:$port...")
            server = embeddedServer(CIO, host = "127.0.0.1", port = port) {
                install(ContentNegotiation) {
                    json()
                }
                install(CORS) {
                    anyHost()
                    allowHeader("Authorization")
                    allowHeader("Content-Type")
                }
                configureRouting()
            }.start(wait = false)
            AppLogger.i(TAG, "Local OpenAI Server successfully bound to http://127.0.0.1:$port")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to start local HTTP server on port $port: ${e.message}", e)
        }
    }

    fun stop() {
        try {
            AppLogger.i(TAG, "Stopping Local OpenAI Server...")
            server?.stop(gracePeriodMillis = 500, timeoutMillis = 1500)
            server = null
            AppLogger.i(TAG, "Local server stopped.")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error stopping local server: ${e.message}", e)
        }
    }

    private fun Application.configureRouting() {
        routing {
            // Models endpoint
            get("/v1/models") {
                if (!validateAuth(call.request.header("Authorization"))) {
                    call.respond(HttpStatusCode.Unauthorized, "Invalid or missing Bearer token.")
                    return@get
                }

                val modelsResponse = buildJsonObject {
                    put("object", "list")
                    put("data", buildJsonArray {
                        add(buildJsonObject {
                            put("id", activeModelName)
                            put("object", "model")
                            put("created", System.currentTimeMillis() / 1000)
                            put("owned_by", "textmorph-litert")
                        })
                    })
                }
                call.respondText(modelsResponse.toString(), ContentType.Application.Json)
            }

            // Chat completions endpoint
            post("/v1/chat/completions") {
                if (!validateAuth(call.request.header("Authorization"))) {
                    call.respond(HttpStatusCode.Unauthorized, "Invalid or missing Bearer token.")
                    return@post
                }

                val bodyText = call.receiveText()
                val parsed = try {
                    json.parseToJsonElement(bodyText).jsonObject
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.BadRequest, "Malformed JSON request.")
                    return@post
                }

                val isStream = parsed["stream"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
                val messages = parsed["messages"]?.jsonArray ?: JsonArray(emptyList())

                var systemInstruction: String? = null
                var userPrompt = ""
                var imageBytes: ByteArray? = null

                for (msgElement in messages) {
                    val msg = msgElement.jsonObject
                    val role = msg["role"]?.jsonPrimitive?.content ?: ""
                    when (role) {
                        "system" -> {
                            systemInstruction = msg["content"]?.jsonPrimitive?.content
                        }
                        "user" -> {
                            val contentElement = msg["content"]
                            if (contentElement is JsonArray) {
                                for (part in contentElement) {
                                    val partObj = part.jsonObject
                                    val type = partObj["type"]?.jsonPrimitive?.content
                                    if (type == "text") {
                                        userPrompt += partObj["text"]?.jsonPrimitive?.content ?: ""
                                    } else if (type == "image_url") {
                                        val url = partObj["image_url"]?.jsonObject?.get("url")?.jsonPrimitive?.content
                                        if (url != null && url.startsWith("data:image")) {
                                            val commaIdx = url.indexOf(',')
                                            if (commaIdx != -1) {
                                                val b64 = url.substring(commaIdx + 1)
                                                imageBytes = Base64.decode(b64, Base64.DEFAULT)
                                            }
                                        }
                                    }
                                }
                            } else {
                                userPrompt += contentElement?.jsonPrimitive?.content ?: ""
                            }
                        }
                    }
                }

                val completionId = "chatcmpl-" + UUID.randomUUID().toString().take(12)
                val createdTime = System.currentTimeMillis() / 1000

                if (isStream) {
                    call.response.header("Content-Type", "text/event-stream")
                    call.response.header("Cache-Control", "no-cache")
                    call.response.header("Connection", "keep-alive")

                    call.respondTextWriter(ContentType.Text.EventStream) {
                        this@LocalHttpServer.llmEngine.generateStream(userPrompt, systemInstruction, imageBytes).collect { token ->
                            val chunk = buildJsonObject {
                                put("id", completionId)
                                put("object", "chat.completion.chunk")
                                put("created", createdTime)
                                put("model", activeModelName)
                                put("choices", buildJsonArray {
                                    add(buildJsonObject {
                                        put("index", 0)
                                        put("delta", buildJsonObject {
                                            put("content", token)
                                        })
                                        put("finish_reason", null as String?)
                                    })
                                })
                            }
                            write("data: ${chunk}\n\n")
                            flush()
                        }
                        write("data: [DONE]\n\n")
                        flush()
                    }
                } else {
                    val fullResponse = this@LocalHttpServer.llmEngine.generate(userPrompt, systemInstruction, imageBytes)
                    val responseJson = buildJsonObject {
                        put("id", completionId)
                        put("object", "chat.completion")
                        put("created", createdTime)
                        put("model", activeModelName)
                        put("choices", buildJsonArray {
                            add(buildJsonObject {
                                put("index", 0)
                                put("message", buildJsonObject {
                                    put("role", "assistant")
                                    put("content", fullResponse)
                                })
                                put("finish_reason", "stop")
                            })
                        })
                    }
                    call.respondText(responseJson.toString(), ContentType.Application.Json)
                }
            }
        }
    }

    private fun validateAuth(authHeader: String?): Boolean {
        if (bearerToken.isBlank()) return true
        if (authHeader == null) return false
        val expected = "Bearer $bearerToken"
        return authHeader.trim() == expected
    }
}
