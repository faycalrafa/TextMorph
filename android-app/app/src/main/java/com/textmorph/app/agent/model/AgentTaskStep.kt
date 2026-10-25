package com.textmorph.app.agent.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Status lifecycle states for discrete agent execution steps.
 */
@Serializable
enum class AgentStepStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED,
    INTERRUPTED
}

/**
 * Represents a single discrete step in an autonomous multi-step agent routine.
 */
@Serializable
data class AgentTaskStep(
    val stepIndex: Int,
    val actionType: String,
    val title: String,
    val detail: String = "",
    val status: AgentStepStatus = AgentStepStatus.RUNNING,
    val timestamp: Long = System.currentTimeMillis()
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }

        fun serializeList(steps: List<AgentTaskStep>): String {
            return try {
                json.encodeToString(steps)
            } catch (e: Exception) {
                "[]"
            }
        }

        fun deserializeList(raw: String?): List<AgentTaskStep> {
            if (raw.isNullOrBlank()) return emptyList()
            return try {
                json.decodeFromString(raw)
            } catch (e: Exception) {
                emptyList()
            }
        }
    }
}
