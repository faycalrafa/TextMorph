package com.textmorph.app.agent.events

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Base polymorphic event interface for the TextMorph Event-Sourced Agentic Engine.
 * All state modifications in the agent subsystem are captured as immutable events.
 */
@Serializable
sealed interface AgentEvent {
    val eventType: String
    val timestamp: Long
}

@Serializable
data class SessionCreatedEvent(
    override val eventType: String = "SESSION_CREATED",
    override val timestamp: Long = System.currentTimeMillis(),
    val sessionId: String,
    val title: String,
    val modelName: String? = null,
    val parentSessionId: String? = null
) : AgentEvent

@Serializable
data class PromptAdmittedEvent(
    override val eventType: String = "PROMPT_ADMITTED",
    override val timestamp: Long = System.currentTimeMillis(),
    val promptId: String,
    val sessionId: String,
    val prompt: String,
    val admissionMode: String, // "STEER", "QUEUE", "ABORT"
    val attachmentsJson: String = "[]"
) : AgentEvent

@Serializable
data class InferenceStartedEvent(
    override val eventType: String = "INFERENCE_STARTED",
    override val timestamp: Long = System.currentTimeMillis(),
    val sessionId: String,
    val stepIndex: Int,
    val systemPromptHash: String,
    val isVisionIncluded: Boolean = false
) : AgentEvent

@Serializable
data class TokenChunkAppendedEvent(
    override val eventType: String = "TOKEN_CHUNK",
    override val timestamp: Long = System.currentTimeMillis(),
    val sessionId: String,
    val stepIndex: Int,
    val tokenDelta: String,
    val isThought: Boolean = false
) : AgentEvent

@Serializable
data class InferenceCompletedEvent(
    override val eventType: String = "INFERENCE_COMPLETED",
    override val timestamp: Long = System.currentTimeMillis(),
    val sessionId: String,
    val stepIndex: Int,
    val rawOutput: String,
    val thoughtContent: String? = null,
    val tokensPerSecond: Float = 0f,
    val totalTokens: Int = 0,
    val durationMs: Long = 0L
) : AgentEvent

@Serializable
data class ToolExecutionRequestedEvent(
    override val eventType: String = "TOOL_REQUESTED",
    override val timestamp: Long = System.currentTimeMillis(),
    val callId: String,
    val sessionId: String,
    val stepIndex: Int,
    val toolName: String,
    val paramsJson: String = "{}"
) : AgentEvent

@Serializable
data class ToolExecutionCompletedEvent(
    override val eventType: String = "TOOL_COMPLETED",
    override val timestamp: Long = System.currentTimeMillis(),
    val callId: String,
    val sessionId: String,
    val stepIndex: Int,
    val toolName: String,
    val success: Boolean,
    val outputSnippet: String,
    val durationMs: Long
) : AgentEvent

@Serializable
data class ToolExecutionFailedEvent(
    override val eventType: String = "TOOL_FAILED",
    override val timestamp: Long = System.currentTimeMillis(),
    val callId: String,
    val sessionId: String,
    val stepIndex: Int,
    val toolName: String,
    val errorMessage: String,
    val requiresSelfCorrection: Boolean = false
) : AgentEvent

@Serializable
data class ScreenPerceivedEvent(
    override val eventType: String = "SCREEN_PERCEIVED",
    override val timestamp: Long = System.currentTimeMillis(),
    val sessionId: String,
    val stepIndex: Int,
    val activePackage: String,
    val screenHash: String,
    val elementCount: Int,
    val isDiffOnly: Boolean = false
) : AgentEvent

@Serializable
data class GestureDispatchedEvent(
    override val eventType: String = "GESTURE_DISPATCHED",
    override val timestamp: Long = System.currentTimeMillis(),
    val sessionId: String,
    val stepIndex: Int,
    val gestureType: String,
    val coordinates: String,
    val success: Boolean
) : AgentEvent

@Serializable
data class SteerEvent(
    override val eventType: String = "AGENT_STEERED",
    override val timestamp: Long = System.currentTimeMillis(),
    val sessionId: String,
    val stepIndex: Int,
    val steerInstruction: String
) : AgentEvent

@Serializable
data class EpochCompactedEvent(
    override val eventType: String = "EPOCH_COMPACTED",
    override val timestamp: Long = System.currentTimeMillis(),
    val sessionId: String,
    val summary: String,
    val compactedUntilSequence: Long
) : AgentEvent

@Serializable
data class SubagentCompletedEvent(
    override val eventType: String = "SUBAGENT_COMPLETED",
    override val timestamp: Long = System.currentTimeMillis(),
    val parentSessionId: String,
    val childSessionId: String,
    val sectionIndex: Int,
    val sectionTitle: String,
    val summary: String
) : AgentEvent

@Serializable
data class CrashRecoveryEvent(
    override val eventType: String = "CRASH_RECOVERY",
    override val timestamp: Long = System.currentTimeMillis(),
    val sessionId: String,
    val recoveredStepIndex: Int,
    val recoveryAction: String, // "RETRY_INFERENCE", "VERIFY_ACTION", "RESET_IDLE"
    val diagnosticMessage: String
) : AgentEvent
