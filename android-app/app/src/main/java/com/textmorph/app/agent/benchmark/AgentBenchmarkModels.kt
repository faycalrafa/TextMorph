package com.textmorph.app.agent.benchmark

import com.textmorph.app.agent.model.AgentAction

/**
 * Execution syntax paradigms tested in the benchmark.
 */
enum class BenchmarkFormat(val displayName: String, val badgeLabel: String) {
    CODE_AS_ACTION("Code-as-Action (Function Calls)", "Code-as-Action"),
    JSON_PAYLOAD("Structured JSON Payload", "JSON")
}

/**
 * Defines a standardized agentic capability test scenario.
 */
data class BenchmarkTestCase(
    val id: String,
    val title: String,
    val scenario: String,
    val userPrompt: String,
    val expectedAction: String,
    val expectedParamsValidator: (AgentAction) -> Boolean
)

/**
 * Result metrics for a single test run on a specific format.
 */
data class BenchmarkRunResult(
    val format: BenchmarkFormat,
    val rawOutput: String,
    val parsedAction: AgentAction?,
    val isParseSuccess: Boolean,
    val isSemanticMatch: Boolean,
    val durationMs: Long,
    val ttftMs: Long,
    val tokenCount: Int,
    val tokensPerSecond: Float,
    val ramBeforeMb: Long,
    val ramAfterMb: Long,
    val ramDeltaMb: Long,
    val tempBeforeC: Float?,
    val tempAfterC: Float?,
    val tempDeltaC: Float?,
    val error: String? = null
)

/**
 * Side-by-side comparison for a single test case across Code and JSON.
 */
data class BenchmarkTestReport(
    val testCase: BenchmarkTestCase,
    val codeResult: BenchmarkRunResult,
    val jsonResult: BenchmarkRunResult
)

/**
 * Aggregated summary report comparing Code-as-Action vs JSON on the tested LLM.
 */
data class BenchmarkSummaryReport(
    val modelName: String,
    val backend: String,
    val testReports: List<BenchmarkTestReport>,
    val codeSuccessCount: Int,
    val jsonSuccessCount: Int,
    val totalTests: Int,
    val codeAvgTokens: Float,
    val jsonAvgTokens: Float,
    val codeAvgLatencyMs: Long,
    val jsonAvgLatencyMs: Long,
    val codeAvgTps: Float,
    val jsonAvgTps: Float,
    val codeAvgRamDeltaMb: Long,
    val jsonAvgRamDeltaMb: Long,
    val codeAvgTempDeltaC: Float?,
    val jsonAvgTempDeltaC: Float?,
    val tokenSavingsPercent: Int,
    val latencySpeedupPercent: Int,
    val recommendedFormat: BenchmarkFormat,
    val verdictReason: String
)

/**
 * Real-time reactive progress state during active benchmarking.
 */
data class BenchmarkProgressState(
    val isRunning: Boolean = false,
    val currentTestIndex: Int = 0,
    val totalTests: Int = 5,
    val currentFormat: BenchmarkFormat? = null,
    val currentStepDescription: String = "",
    val liveStreamingOutput: String = "",
    val currentReport: BenchmarkSummaryReport? = null,
    val error: String? = null
)
