package com.textmorph.app.agent.benchmark

import com.textmorph.app.llm.LlmEngine
import com.textmorph.app.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * AgenticReadinessSuite executes a controlled, deterministic evaluation
 * of LLMs (on-device SLMs and Cloud APIs) across 5 core agentic scenarios
 * before deploying them to the harness.
 *
 * All scenarios run with Code-as-Action validation, live sub-test question/response
 * streaming, and strict pre-flight assertions that reject unloaded mock engines.
 */
object AgenticReadinessSuite {

    private const val TAG = "AgenticReadinessSuite"

    /**
     * Executes the complete 5-scenario evaluation suite.
     */
    suspend fun runReadinessSuite(
        engine: LlmEngine,
        modelName: String,
        backend: String,
        onProgress: (ReadinessProgressState) -> Unit
    ): AgenticReadinessReport = withContext(Dispatchers.IO) {
        if (!engine.isLoaded()) {
            throw IllegalStateException("Cannot run Agentic Readiness Suite: Engine is not loaded or configured.")
        }

        AppLogger.i(TAG, "Starting Agentic Readiness Suite for model: '$modelName' on $backend")
        val scenarioResults = mutableListOf<ScenarioResult>()
        val accumulatedSubTests = mutableListOf<SubTestResult>()
        var accumulatedScore = 0

        fun reportProgress(
            scenarioIndex: Int,
            scenarioName: String,
            subTestTitle: String = "",
            prompt: String = "",
            liveChunk: String = ""
        ) {
            onProgress(
                ReadinessProgressState(
                    isRunning = true,
                    currentScenarioIndex = scenarioIndex,
                    totalScenarios = 5,
                    currentScenarioName = scenarioName,
                    currentSubTestTitle = subTestTitle,
                    currentSubTestPrompt = prompt,
                    liveResponseChunk = liveChunk,
                    partialScore = accumulatedScore,
                    completedSubTests = accumulatedSubTests.toList()
                )
            )
        }

        // Scenario 1: Tool Picking & Irrelevance Detection (25 pts)
        val s1Name = "Scenario 1: Tool Picking & Irrelevance Detection"
        reportProgress(1, s1Name)
        val s1 = AgenticReadinessScenarios.runScenario1(engine, onSubTestStart = { title, prompt ->
            reportProgress(1, s1Name, title, prompt)
        })
        accumulatedSubTests.addAll(s1.subTests)
        scenarioResults.add(s1)
        accumulatedScore += s1.score
        reportProgress(1, s1Name)

        // Scenario 2: Schema Strictness & Error Self-Correction (25 pts)
        val s2Name = "Scenario 2: Schema Strictness & Self-Correction"
        reportProgress(2, s2Name)
        val s2 = AgenticReadinessScenarios.runScenario2(engine, onSubTestStart = { title, prompt ->
            reportProgress(2, s2Name, title, prompt)
        })
        accumulatedSubTests.addAll(s2.subTests)
        scenarioResults.add(s2)
        accumulatedScore += s2.score
        reportProgress(2, s2Name)

        // Scenario 3: Context Grounding & Loop Resistance (20 pts)
        val s3Name = "Scenario 3: Grounding & Loop Resistance"
        reportProgress(3, s3Name)
        val s3 = AgenticReadinessScenarios.runScenario3(engine, onSubTestStart = { title, prompt ->
            reportProgress(3, s3Name, title, prompt)
        })
        accumulatedSubTests.addAll(s3.subTests)
        scenarioResults.add(s3)
        accumulatedScore += s3.score
        reportProgress(3, s3Name)

        // Scenario 4: /plan Decomposition Quality (15 pts)
        val s4Name = "Scenario 4: /plan Decomposition Quality"
        reportProgress(4, s4Name)
        val s4 = AgenticReadinessScenarios.runScenario4(engine, onSubTestStart = { title, prompt ->
            reportProgress(4, s4Name, title, prompt)
        })
        accumulatedSubTests.addAll(s4.subTests)
        scenarioResults.add(s4)
        accumulatedScore += s4.score
        reportProgress(4, s4Name)

        // Scenario 5: Hardware & Runtime Telemetry (15 pts)
        val s5Name = "Scenario 5: Hardware & Runtime Telemetry"
        reportProgress(5, s5Name)
        val s5 = AgenticReadinessScenarios.runScenario5(engine, backend, onSubTestStart = { title, prompt ->
            reportProgress(5, s5Name, title, prompt)
        })
        accumulatedSubTests.addAll(s5.subTests)
        scenarioResults.add(s5)
        accumulatedScore += s5.score

        val totalScore = accumulatedScore.coerceIn(0, 100)
        val tier = ReadinessTier.fromScore(totalScore)
        val recommendedWorkers = calculateRecommendedWorkers(s5.telemetry, totalScore)

        val report = AgenticReadinessReport(
            modelName = modelName,
            backend = backend,
            totalScore = totalScore,
            tier = tier,
            scenarioResults = scenarioResults,
            recommendedConcurrentWorkers = recommendedWorkers
        )

        AppLogger.i(TAG, "Readiness Suite finished. Score: $totalScore/100, Tier: ${tier.displayName}, Recommended Workers: $recommendedWorkers")
        onProgress(
            ReadinessProgressState(
                isRunning = false,
                currentScenarioIndex = 5,
                totalScenarios = 5,
                currentScenarioName = "Completed",
                partialScore = totalScore,
                completedSubTests = accumulatedSubTests.toList(),
                completedReport = report
            )
        )

        return@withContext report
    }

    private fun calculateRecommendedWorkers(telemetry: ScenarioTelemetry?, totalScore: Int): Int {
        if (totalScore < 45) return 1
        val tps = telemetry?.tokensPerSecond ?: 0f
        val ttft = telemetry?.ttftMs ?: 2000L

        return when {
            tps >= 25f && ttft <= 800L -> 3
            tps >= 10f && ttft <= 1800L -> 2
            else -> 1
        }
    }
}
