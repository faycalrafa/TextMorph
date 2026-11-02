package com.textmorph.app.agent.projections

import com.textmorph.app.agent.events.AgentEventRecord
import com.textmorph.app.data.model.AgentStepTrace
import com.textmorph.app.data.model.ChatMessageEntity
import com.textmorph.app.util.AppLogger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Materializes append-only event streams into clean ChatMessageEntity projections
 * for the Jetpack Compose UI. Ensures 100% backward compatibility with existing Chat UI screens.
 */
object ChatMessageProjector {

    private const val TAG = "ChatMessageProjector"
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Rebuilds the current chat message state from an ordered list of events.
     */
    fun projectMessages(events: List<AgentEventRecord>): List<ChatMessageEntity> {
        val messages = mutableListOf<ChatMessageEntity>()
        var activeAssistantMsg: ChatMessageEntity? = null
        val activeTraces = mutableListOf<AgentStepTrace>()

        for (event in events) {
            try {
                when (event.eventType) {
                    "PROMPT_ADMITTED" -> {
                        // Finalize any open assistant message before adding user prompt
                        if (activeAssistantMsg != null) {
                            messages.add(activeAssistantMsg)
                            activeAssistantMsg = null
                            activeTraces.clear()
                        }
                        val payload = json.parseToJsonElement(event.payloadJson) as? JsonObject
                        val promptText = payload?.get("prompt")?.jsonPrimitive?.content.orEmpty()
                        val attachJson = payload?.get("attachmentsJson")?.jsonPrimitive?.content ?: "[]"
                        val userMsg = ChatMessageEntity(
                            id = "msg_${event.eventId}",
                            sessionId = event.sessionId,
                            role = "user",
                            content = promptText,
                            attachmentsJson = attachJson,
                            isAgentMode = true,
                            timestamp = event.timestamp
                        )
                        messages.add(userMsg)
                    }

                    "INFERENCE_STARTED" -> {
                        if (activeAssistantMsg == null) {
                            activeAssistantMsg = ChatMessageEntity(
                                id = "msg_asst_${event.eventId}",
                                sessionId = event.sessionId,
                                role = "assistant",
                                content = "",
                                isAgentMode = true,
                                timestamp = event.timestamp
                            )
                        }
                    }

                    "TOKEN_CHUNK" -> {
                        val payload = json.parseToJsonElement(event.payloadJson) as? JsonObject
                        val delta = payload?.get("tokenDelta")?.jsonPrimitive?.content.orEmpty()
                        val isThought = payload?.get("isThought")?.jsonPrimitive?.content?.toBoolean() ?: false

                        if (activeAssistantMsg != null) {
                            activeAssistantMsg = if (isThought) {
                                val prevThought = activeAssistantMsg.thoughtContent.orEmpty()
                                activeAssistantMsg.copy(thoughtContent = prevThought + delta)
                            } else {
                                val prevContent = activeAssistantMsg.content
                                activeAssistantMsg.copy(content = prevContent + delta)
                            }
                        }
                    }

                    "INFERENCE_COMPLETED" -> {
                        val payload = json.parseToJsonElement(event.payloadJson) as? JsonObject
                        val raw = payload?.get("rawOutput")?.jsonPrimitive?.content.orEmpty()
                        val thought = payload?.get("thoughtContent")?.jsonPrimitive?.content
                        val tps = payload?.get("tokensPerSecond")?.jsonPrimitive?.content?.toFloatOrNull()
                        val totalTokens = payload?.get("totalTokens")?.jsonPrimitive?.content?.toIntOrNull()
                        val duration = payload?.get("durationMs")?.jsonPrimitive?.content?.toLongOrNull()

                        if (activeAssistantMsg != null) {
                            activeAssistantMsg = activeAssistantMsg.copy(
                                content = if (thought != null && raw.contains(thought)) raw.replace(thought, "").trim() else raw,
                                thoughtContent = thought ?: activeAssistantMsg.thoughtContent,
                                rawNakedOutput = raw,
                                tokensPerSecond = tps,
                                totalTokens = totalTokens,
                                durationMs = duration
                            )
                        }
                    }

                    "TOOL_COMPLETED" -> {
                        val payload = json.parseToJsonElement(event.payloadJson) as? JsonObject
                        val toolName = payload?.get("toolName")?.jsonPrimitive?.content.orEmpty()
                        val outputSnippet = payload?.get("outputSnippet")?.jsonPrimitive?.content.orEmpty()
                        val stepIndex = payload?.get("stepIndex")?.jsonPrimitive?.content?.toIntOrNull() ?: 1
                        val duration = payload?.get("durationMs")?.jsonPrimitive?.content?.toLongOrNull() ?: 0L

                        val trace = AgentStepTrace(
                            stepIndex = stepIndex,
                            toolName = toolName,
                            inputQuery = toolName,
                            status = "success",
                            durationMs = duration,
                            outputSnippet = outputSnippet
                        )
                        activeTraces.add(trace)

                        if (activeAssistantMsg != null) {
                            activeAssistantMsg = activeAssistantMsg.copy(
                                stepTracesJson = ChatMessageEntity.serializeStepTraces(activeTraces)
                            )
                        }
                    }

                    "TOOL_FAILED" -> {
                        val payload = json.parseToJsonElement(event.payloadJson) as? JsonObject
                        val toolName = payload?.get("toolName")?.jsonPrimitive?.content.orEmpty()
                        val errorMsg = payload?.get("errorMessage")?.jsonPrimitive?.content.orEmpty()
                        val stepIndex = payload?.get("stepIndex")?.jsonPrimitive?.content?.toIntOrNull() ?: 1

                        val trace = AgentStepTrace(
                            stepIndex = stepIndex,
                            toolName = toolName,
                            inputQuery = toolName,
                            status = "failed",
                            durationMs = 0L,
                            failureReason = errorMsg
                        )
                        activeTraces.add(trace)

                        if (activeAssistantMsg != null) {
                            activeAssistantMsg = activeAssistantMsg.copy(
                                stepTracesJson = ChatMessageEntity.serializeStepTraces(activeTraces)
                            )
                        }
                    }

                    "CRASH_RECOVERY" -> {
                        if (activeAssistantMsg != null) {
                            messages.add(activeAssistantMsg)
                            activeAssistantMsg = null
                            activeTraces.clear()
                        }
                        val payload = json.parseToJsonElement(event.payloadJson) as? JsonObject
                        val diag = payload?.get("diagnosticMessage")?.jsonPrimitive?.content.orEmpty()
                        val notice = ChatMessageEntity(
                            id = "msg_rec_${event.eventId}",
                            sessionId = event.sessionId,
                            role = "system",
                            content = "⚠️ Task recovered after process interruption: $diag",
                            timestamp = event.timestamp
                        )
                        messages.add(notice)
                    }
                }
            } catch (t: Throwable) {
                AppLogger.w(TAG, "Error projecting event ${event.eventId}: ${t.message}")
            }
        }

        if (activeAssistantMsg != null) {
            messages.add(activeAssistantMsg)
        }

        return messages
    }
}
