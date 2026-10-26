package com.textmorph.app.agent.benchmark

import com.textmorph.app.agent.model.AgentAction
import kotlin.math.sqrt

/**
 * Geometric definition of a numbered button target rendered on the visual canvas.
 */
data class VisualButtonTarget(
    val number: Int,
    val centerX: Int,
    val centerY: Int,
    val radius: Int = 48
) {
    /**
     * Checks if coordinates fall within the button's boundary, with optional touch tolerance.
     */
    fun contains(x: Int, y: Int, toleranceMultiplier: Float = 1.15f): Boolean {
        val effectiveRadius = radius * toleranceMultiplier
        val dx = (x - centerX).toDouble()
        val dy = (y - centerY).toDouble()
        return (dx * dx + dy * dy) <= (effectiveRadius * effectiveRadius)
    }

    /**
     * Calculates Euclidean distance in pixels from the button center.
     */
    fun distanceFromCenter(x: Int, y: Int): Float {
        val dx = (x - centerX).toDouble()
        val dy = (y - centerY).toDouble()
        return sqrt(dx * dx + dy * dy).toFloat()
    }
}

/**
 * Execution metrics for a single button-press action in a visual benchmark trial.
 */
data class VisualBenchmarkStepResult(
    val roundIndex: Int,
    val targetNumber: Int,
    val target: VisualButtonTarget,
    val rawOutput: String,
    val parsedAction: AgentAction?,
    val clickedX: Int?,
    val clickedY: Int?,
    val isHit: Boolean,
    val hitButtonNumber: Int?,
    val distanceErrorPx: Float?,
    val durationMs: Long,
    val ttftMs: Long,
    val tokenCount: Int,
    val tokensPerSecond: Float,
    val error: String? = null
)

/**
 * Evaluated report for a complete 6-button trial in ascending order (1 -> 6).
 */
data class VisualBenchmarkRoundReport(
    val roundIndex: Int,
    val buttons: List<VisualButtonTarget>,
    val steps: List<VisualBenchmarkStepResult>,
    val hits: Int,
    val totalSteps: Int = 6,
    val durationMs: Long
) {
    val isPerfect: Boolean
        get() = hits == totalSteps
}

/**
 * Aggregated visual agentic report across 3 complete randomized trials (18 total steps).
 */
data class VisualBenchmarkReport(
    val modelName: String,
    val backend: String,
    val roundReports: List<VisualBenchmarkRoundReport>,
    val totalTrials: Int = 3,
    val totalSteps: Int = 18,
    val successfulHits: Int,
    val accuracyPercent: Float,
    val perfectTrials: Int,
    val avgLatencyMs: Long,
    val avgTtftMs: Long,
    val avgTps: Float,
    val avgDistanceErrorPx: Float?,
    val ramBeforeMb: Long,
    val ramAfterMb: Long,
    val ramDeltaMb: Long,
    val tempBeforeC: Float?,
    val tempAfterC: Float?,
    val tempDeltaC: Float?,
    val verdict: String
)

/**
 * Real-time reactive progress state during active visual agentic benchmarking.
 */
data class VisualBenchmarkProgressState(
    val isRunning: Boolean = false,
    val currentRound: Int = 0,
    val totalRounds: Int = 3,
    val currentTargetNumber: Int = 0,
    val totalStepsPerRound: Int = 6,
    val overallStepIndex: Int = 0,
    val overallTotalSteps: Int = 18,
    val currentStatus: String = "",
    val liveStreamingOutput: String = "",
    val currentCanvasPreviewBytes: ByteArray? = null,
    val currentReport: VisualBenchmarkReport? = null,
    val error: String? = null
)
