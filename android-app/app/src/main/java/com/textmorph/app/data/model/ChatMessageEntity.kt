package com.textmorph.app.data.model

import com.textmorph.app.agent.model.AgentTaskStep
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

/**
 * Represents a persistent message in a chat conversation thread with performance telemetry.
 */
@Serializable
data class ChatMessageEntity(
    val id: String = java.util.UUID.randomUUID().toString(),
    val sessionId: String,
    val role: String, // "user", "assistant", "system", "action"
    val content: String,
    val thoughtContent: String? = null,
    val rawNakedOutput: String? = null,
    val systemPrompt: String? = null,
    val attachmentsJson: String = "[]", // List<AttachmentItem> serialized
    val actionPayloadJson: String? = null,
    val actionStatus: String? = null, // "pending", "running", "success", "failed", "interrupted"
    val actionStepsJson: String? = null, // List<AgentTaskStep> serialized
    val stepTracesJson: String? = null, // List<AgentStepTrace> serialized
    val sourcesJson: String? = null, // List<SourceCitation> serialized
    val artifactPayloadJson: String? = null, // ArtifactMetadata serialized
    val isAgentMode: Boolean = false,
    val timestamp: Long = System.currentTimeMillis(),
    val tokensPerSecond: Float? = null,
    val totalTokens: Int? = null,
    val durationMs: Long? = null
) {
    val isAgentTurn: Boolean
        get() = isAgentMode || actionStepsJson != null || stepTracesJson != null

    fun getActionSteps(): List<AgentTaskStep> {
        return AgentTaskStep.deserializeList(actionStepsJson)
    }

    fun getStepTraces(): List<AgentStepTrace> {
        if (!stepTracesJson.isNullOrBlank() && stepTracesJson != "[]") {
            try {
                val parsed = json.decodeFromString<List<AgentStepTrace>>(stepTracesJson)
                if (parsed.isNotEmpty()) return parsed
            } catch (e: Exception) {
                // fallback to actionStepsJson below
            }
        }
        if (!actionStepsJson.isNullOrBlank() && actionStepsJson != "[]") {
            try {
                val legacySteps = AgentTaskStep.deserializeList(actionStepsJson)
                if (legacySteps.isNotEmpty()) {
                    return legacySteps.map { step ->
                        AgentStepTrace(
                            stepIndex = step.stepIndex,
                            toolName = step.actionType,
                            inputQuery = step.title,
                            status = when (step.status) {
                                com.textmorph.app.agent.model.AgentStepStatus.COMPLETED -> "success"
                                com.textmorph.app.agent.model.AgentStepStatus.FAILED -> "failed"
                                com.textmorph.app.agent.model.AgentStepStatus.INTERRUPTED -> "cancelled"
                                com.textmorph.app.agent.model.AgentStepStatus.RUNNING -> "running"
                                else -> "pending"
                            },
                            durationMs = (System.currentTimeMillis() - step.timestamp).coerceAtLeast(0),
                            outputSnippet = step.detail,
                            failureReason = if (step.status == com.textmorph.app.agent.model.AgentStepStatus.FAILED) step.detail else null
                        )
                    }
                }
            } catch (e: Exception) {
                // ignore
            }
        }
        return emptyList()
    }

    fun getSources(): List<SourceCitation> {
        if (sourcesJson.isNullOrBlank() || sourcesJson == "[]") return emptyList()
        return try {
            json.decodeFromString(sourcesJson)
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun getArtifact(): ArtifactMetadata? {
        if (artifactPayloadJson.isNullOrBlank()) return null
        return try {
            json.decodeFromString(artifactPayloadJson)
        } catch (e: Exception) {
            null
        }
    }

    fun getAttachments(): List<ChatAttachment> {
        if (attachmentsJson.isBlank() || attachmentsJson == "[]") return emptyList()
        return try {
            json.decodeFromString(attachmentsJson)
        } catch (e: Exception) {
            emptyList()
        }
    }

    companion object {
        private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }

        fun serializeAttachments(attachments: List<ChatAttachment>): String {
            if (attachments.isEmpty()) return "[]"
            return try {
                json.encodeToString(attachments)
            } catch (e: Exception) {
                "[]"
            }
        }

        fun serializeStepTraces(traces: List<AgentStepTrace>): String {
            if (traces.isEmpty()) return "[]"
            return try {
                json.encodeToString(traces)
            } catch (e: Exception) {
                "[]"
            }
        }

        fun serializeSources(sources: List<SourceCitation>): String {
            if (sources.isEmpty()) return "[]"
            return try {
                json.encodeToString(sources)
            } catch (e: Exception) {
                "[]"
            }
        }

        fun serializeArtifact(artifact: ArtifactMetadata?): String? {
            if (artifact == null) return null
            return try {
                json.encodeToString(artifact)
            } catch (e: Exception) {
                null
            }
        }
    }
}

@Serializable
data class AgentStepTrace(
    val stepIndex: Int,
    val toolName: String,
    var inputQuery: String = "",
    var status: String = "running", // "running", "success", "failed", "cancelled"
    var durationMs: Long = 0L,
    var outputSnippet: String? = null,
    var failureReason: String? = null,
    val startTimeMs: Long = System.currentTimeMillis()
) {
    val inputSummary: String get() = inputQuery
    val outputSummary: String? get() = outputSnippet
    val diagnosticReason: String? get() = failureReason
}

@Serializable
data class SourceCitation(
    val title: String,
    val url: String,
    val snippet: String = "",
    val sourceIndex: Int = 1
)

@Serializable
data class ArtifactMetadata(
    val title: String,
    val wordCount: Int = 0,
    val sectionCount: Int = 0,
    val filePath: String? = null,
    val content: String = "",
    val id: String = java.util.UUID.randomUUID().toString(),
    val type: String = "report",
    val summary: String = "",
    val createdAtMs: Long = System.currentTimeMillis()
)

@Serializable
data class ChatAttachment(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String,
    val mimeType: String,
    val contentSnippet: String? = null,
    val localUri: String? = null,
    val sizeBytes: Long = 0L
)
