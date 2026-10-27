package com.textmorph.app.agent.memory

import kotlinx.serialization.Serializable

/**
 * Atomic action step recorded during task execution.
 */
@Serializable
data class ActionStep(
    val action: String,
    val params: Map<String, String> = emptyMap()
)

/**
 * Reusable multi-step skill learned from successful user task executions.
 * Ported 1-to-1 from PrivateAgent's skill memory system.
 */
@Serializable
data class SavedSkill(
    val id: String,
    val task: String,
    val taskKeywords: List<String>,
    var successCount: Int = 0,
    var failCount: Int = 0,
    val lastUsedTimestamp: Long = System.currentTimeMillis(),
    val steps: List<ActionStep> = emptyList()
) {
    /**
     * Skill is reliable if executed successfully at least once and has failure rate < 30%.
     */
    val isReliable: Boolean
        get() = successCount >= 1 && ((failCount.toDouble() / (successCount + failCount).coerceAtLeast(1)) < 0.3)
}
