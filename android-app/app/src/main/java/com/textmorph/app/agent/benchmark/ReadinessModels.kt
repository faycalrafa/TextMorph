package com.textmorph.app.agent.benchmark

import kotlinx.serialization.Serializable

/**
 * Performance and hardware telemetry collected during scenario execution.
 */
@Serializable
data class ScenarioTelemetry(
    val ttftMs: Long = 0,
    val tokensPerSecond: Float = 0f,
    val memoryDeltaMb: Long = 0,
    val totalDurationMs: Long = 0,
    val outputTokenCount: Int = 0
)

/**
 * Result of evaluating an individual scenario in the Agentic Readiness Suite.
 */
@Serializable
data class ScenarioResult(
    val scenarioId: String,
    val name: String,
    val score: Int,
    val maxScore: Int,
    val passed: Boolean,
    val details: String,
    val telemetry: ScenarioTelemetry? = null
)

/**
 * Categorical operational readiness tier for an LLM on mobile devices.
 */
@Serializable
enum class ReadinessTier(
    val displayName: String,
    val description: String,
    val minScore: Int
) {
    AUTONOMOUS_READY(
        displayName = "Autonomous Ready",
        description = "Model is highly competent at multi-turn Code-as-Action and /plan decomposition.",
        minScore = 85
    ),
    ASSISTED_AGENT(
        displayName = "Assisted Agent",
        description = "Reliable on single-turn actions and bounded queries; may require retries on complex loops.",
        minScore = 65
    ),
    EXPERIMENTAL(
        displayName = "Experimental",
        description = "Prone to syntax errors or parameter omissions. Recommended for supervised single-worker tasks only.",
        minScore = 45
    ),
    CHAT_ONLY(
        displayName = "Chat Only",
        description = "Model lacks reliable action grounding or suffers from severe tool hallucination.",
        minScore = 0
    );

    companion object {
        fun fromScore(score: Int): ReadinessTier {
            return when {
                score >= AUTONOMOUS_READY.minScore -> AUTONOMOUS_READY
                score >= ASSISTED_AGENT.minScore -> ASSISTED_AGENT
                score >= EXPERIMENTAL.minScore -> EXPERIMENTAL
                else -> CHAT_ONLY
            }
        }
    }
}

/**
 * Complete summary report of an Agentic Readiness Suite evaluation run.
 */
@Serializable
data class AgenticReadinessReport(
    val modelName: String,
    val backend: String,
    val totalScore: Int,
    val tier: ReadinessTier,
    val scenarioResults: List<ScenarioResult>,
    val recommendedConcurrentWorkers: Int,
    val testedAt: Long = System.currentTimeMillis()
)

/**
 * Real-time progress state observed during test execution.
 */
data class ReadinessProgressState(
    val isRunning: Boolean = false,
    val currentScenarioIndex: Int = 0,
    val totalScenarios: Int = 5,
    val currentScenarioName: String = "",
    val partialScore: Int = 0,
    val completedReport: AgenticReadinessReport? = null,
    val errorMessage: String? = null
)
