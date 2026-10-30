package com.textmorph.app.agent.benchmark

import com.textmorph.app.agent.guardrails.ExecutionGuardrail
import com.textmorph.app.agent.guardrails.ToolValidator
import com.textmorph.app.agent.model.AgentAction
import com.textmorph.app.agent.orchestration.BoundedWorkerPool
import com.textmorph.app.agent.orchestration.DeepPlanEngine
import com.textmorph.app.agent.parser.CodeActionParser
import com.textmorph.app.agent.tools.registry.AgentToolCategory
import com.textmorph.app.agent.tools.registry.AgentToolDefinition
import com.textmorph.app.agent.tools.registry.AgentToolParameter
import com.textmorph.app.llm.GpuInferenceGate
import com.textmorph.app.llm.LlmEngine
import com.textmorph.app.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * AgenticReadinessSuite executes a controlled, deterministic offline evaluation
 * of on-device LLMs across 5 core agentic scenarios before deploying them to the harness.
 *
 * All scenarios run at temperature = 0.0 with isolated sessions, mocked tools,
 * and Code-as-Action validation.
 */
object AgenticReadinessSuite {

    private const val TAG = "AgenticReadinessSuite"

    private val BENCHMARK_TOOLS = listOf(
        AgentToolDefinition(
            id = AgentAction.ACTION_SEARCH_WEB,
            name = "Search Web",
            category = AgentToolCategory.APPS_AND_WEB,
            description = "Search live web sources for current information.",
            codeSignature = "search_web(query=\"...\")",
            parameters = listOf(AgentToolParameter("query", "Search Query", defaultValue = ""))
        ),
        AgentToolDefinition(
            id = AgentAction.ACTION_FETCH_PAGE,
            name = "Fetch Page",
            category = AgentToolCategory.APPS_AND_WEB,
            description = "Fetches text from a web URL.",
            codeSignature = "fetch_page(url=\"...\")",
            parameters = listOf(AgentToolParameter("url", "URL", defaultValue = ""))
        ),
        AgentToolDefinition(
            id = AgentAction.ACTION_OPEN_APP,
            name = "Open App",
            category = AgentToolCategory.APPS_AND_WEB,
            description = "Opens an application by package name or label.",
            codeSignature = "open_app(app_name=\"...\")",
            parameters = listOf(AgentToolParameter("app_name", "App Name", defaultValue = ""))
        ),
        AgentToolDefinition(
            id = AgentAction.ACTION_CLICK_TEXT,
            name = "Click Text",
            category = AgentToolCategory.SCREEN_AUTOMATION,
            description = "Clicks on a visible UI element matching text.",
            codeSignature = "click_text(text=\"...\")",
            parameters = listOf(AgentToolParameter("text", "Text to click", defaultValue = ""))
        ),
        AgentToolDefinition(
            id = AgentAction.ACTION_DONE,
            name = "Done",
            category = AgentToolCategory.ORCHESTRATION,
            description = "Completes the task and returns summary.",
            codeSignature = "done(summary=\"...\")",
            parameters = listOf(AgentToolParameter("summary", "Summary", defaultValue = "Task completed"))
        )
    )

    private fun formatToolSignatures(): String {
        return BENCHMARK_TOOLS.joinToString("\n") { "- ${it.codeSignature} : ${it.description}" }
    }

    /**
     * Executes the complete 5-scenario evaluation suite.
     */
    suspend fun runReadinessSuite(
        engine: LlmEngine,
        modelName: String,
        backend: String,
        onProgress: (ReadinessProgressState) -> Unit
    ): AgenticReadinessReport = withContext(Dispatchers.IO) {
        AppLogger.i(TAG, "Starting Agentic Readiness Suite for model: '$modelName' on $backend")
        val scenarioResults = mutableListOf<ScenarioResult>()

        var accumulatedScore = 0

        // Scenario 1: Tool Picking & Irrelevance Detection (25 pts)
        onProgress(
            ReadinessProgressState(
                isRunning = true,
                currentScenarioIndex = 1,
                currentScenarioName = "Scenario 1: Tool Picking & Irrelevance Detection",
                partialScore = accumulatedScore
            )
        )
        val s1 = runScenario1(engine)
        scenarioResults.add(s1)
        accumulatedScore += s1.score

        // Scenario 2: Schema Strictness & Error Self-Correction (25 pts)
        onProgress(
            ReadinessProgressState(
                isRunning = true,
                currentScenarioIndex = 2,
                currentScenarioName = "Scenario 2: Schema Strictness & Self-Correction",
                partialScore = accumulatedScore
            )
        )
        val s2 = runScenario2(engine)
        scenarioResults.add(s2)
        accumulatedScore += s2.score

        // Scenario 3: Context Grounding & Loop Resistance (20 pts)
        onProgress(
            ReadinessProgressState(
                isRunning = true,
                currentScenarioIndex = 3,
                currentScenarioName = "Scenario 3: Grounding & Loop Resistance",
                partialScore = accumulatedScore
            )
        )
        val s3 = runScenario3(engine)
        scenarioResults.add(s3)
        accumulatedScore += s3.score

        // Scenario 4: /plan Decomposition Quality (15 pts)
        onProgress(
            ReadinessProgressState(
                isRunning = true,
                currentScenarioIndex = 4,
                currentScenarioName = "Scenario 4: /plan Decomposition Quality",
                partialScore = accumulatedScore
            )
        )
        val s4 = runScenario4(engine)
        scenarioResults.add(s4)
        accumulatedScore += s4.score

        // Scenario 5: Hardware & Runtime Telemetry (15 pts)
        onProgress(
            ReadinessProgressState(
                isRunning = true,
                currentScenarioIndex = 5,
                currentScenarioName = "Scenario 5: Hardware & Runtime Telemetry",
                partialScore = accumulatedScore
            )
        )
        val s5 = runScenario5(engine)
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
                currentScenarioName = "Completed",
                partialScore = totalScore,
                completedReport = report
            )
        )

        return@withContext report
    }

    /**
     * Scenario 1: Tool Picking (15 pts) & Irrelevance Detection (10 pts)
     */
    private suspend fun runScenario1(engine: LlmEngine): ScenarioResult {
        val toolsDesc = formatToolSignatures()
        val sysPrompt = "You are an autonomous mobile agent. Use Code-as-Action when an action is required.\n$toolsDesc"

        // Sub-test 1: Actionable query
        val query1 = "Find the latest quarterly earnings report for Apple."
        val out1 = generateGuarded(engine, query1, sysPrompt)
        val action1 = CodeActionParser.parse(out1)
        val pickingPassed = action1 != null && action1.action == AgentAction.ACTION_SEARCH_WEB &&
            (action1.params["query"]?.toString()?.lowercase(Locale.ROOT)?.contains("apple") == true)
        val score1 = if (pickingPassed) 15 else 0

        // Sub-test 2: Casual greeting ("hammer syndrome" check)
        val query2 = "Hello! How are you doing today?"
        val out2 = generateGuarded(engine, query2, sysPrompt)
        val action2 = CodeActionParser.parse(out2)
        val irrelevancePassed = action2 == null // Must NOT call any tool on casual greetings
        val score2 = if (irrelevancePassed) 10 else 0

        val totalScore = score1 + score2
        val details = when {
            pickingPassed && irrelevancePassed -> "Perfect tool selection on actionable query; successfully avoided tool invocation on greeting."
            pickingPassed && !irrelevancePassed -> "Tool selection succeeded (+15), but failed irrelevance detection (hammer syndrome on greeting, -10)."
            !pickingPassed && irrelevancePassed -> "Failed tool selection on actionable query (-15), but avoided hammer syndrome on greeting (+10)."
            else -> "Failed tool selection on query (-15) and triggered hammer syndrome on greeting (-10)."
        }

        return ScenarioResult(
            scenarioId = "scenario_1",
            name = "Tool Picking & Irrelevance Detection",
            score = totalScore,
            maxScore = 25,
            passed = totalScore >= 20,
            details = details
        )
    }

    /**
     * Scenario 2: Schema Strictness (15 pts) & Error Self-Correction (10 pts)
     */
    private suspend fun runScenario2(engine: LlmEngine): ScenarioResult {
        val toolsDesc = formatToolSignatures()
        val sysPrompt = "You are a mobile agent. Invoke actions with exact signatures.\n$toolsDesc"

        // Pass 1: Strict valid syntax
        val prompt1 = "Search for latest Android 15 features using Code-as-Action."
        val out1 = generateGuarded(engine, prompt1, sysPrompt)
        val action1 = CodeActionParser.parse(out1)
        val validation1 = if (action1 != null) ToolValidator.validate(action1, BENCHMARK_TOOLS) else null
        val pass1Success = validation1 != null && validation1.isValid
        val score1 = if (pass1Success) 15 else if (action1 != null) 5 else 0

        // Pass 2: Injected validation error self-correction
        val brokenAction = AgentAction(action = AgentAction.ACTION_SEARCH_WEB, params = emptyMap())
        val correctionPrompt = ToolValidator.synthesizeCorrectionPrompt(brokenAction, "Missing required parameter 'query'")
        val selfCorrectionPrompt = "Your prior call failed: $correctionPrompt\nPlease invoke search_web with the required parameter."
        val out2 = generateGuarded(engine, selfCorrectionPrompt, sysPrompt)
        val action2 = CodeActionParser.parse(out2)
        val validation2 = if (action2 != null) ToolValidator.validate(action2, BENCHMARK_TOOLS) else null
        val pass2Success = validation2 != null && validation2.isValid && !action2!!.params["query"]?.toString().isNullOrBlank()
        val score2 = if (pass2Success) 10 else 0

        val totalScore = score1 + score2
        val details = "Syntax Validation: ${if (pass1Success) "Valid (+15)" else "Failed (-15)"}. Error Self-Correction: ${if (pass2Success) "Self-Corrected (+10)" else "Failed (-10)"}."

        return ScenarioResult(
            scenarioId = "scenario_2",
            name = "Schema Strictness & Self-Correction",
            score = totalScore,
            maxScore = 25,
            passed = totalScore >= 20,
            details = details
        )
    }

    /**
     * Scenario 3: Context Grounding (10 pts) & Loop Resistance (10 pts)
     */
    private suspend fun runScenario3(engine: LlmEngine): ScenarioResult {
        val prompt = """
            Context Information:
            [Observation from search_web]:
            - Distractor: Curiosity rover landed in Gale Crater in 2012.
            - TARGET FACT: The Perseverance rover successfully landed on Mars on February 18, 2021.
            - Distractor: Opportunity rover concluded operations in 2018.

            Question: What exact date did the Perseverance rover land on Mars? Provide only the date or done(summary="...").
        """.trimIndent()

        val out = generateGuarded(engine, prompt, "Extract exact grounded facts from the observation without looping.")
        val lower = out.lowercase(Locale.ROOT)
        val grounded = lower.contains("february 18, 2021") || lower.contains("february 18") || lower.contains("2021")
        val notCuriosity = !lower.contains("2012")

        val guardrail = ExecutionGuardrail(maxHops = 3)
        val action = CodeActionParser.parse(out)
        val loopDecision = if (action != null) guardrail.evaluate(action.action, action.params) else null
        val loopResistancePassed = loopDecision == null || loopDecision.allowed

        var score = 0
        if (grounded && notCuriosity) score += 10
        if (loopResistancePassed) score += 10

        val details = "Grounded Fact: ${if (grounded) "Accurate (+10)" else "Hallucinated (-10)"}. Loop Resistance: ${if (loopResistancePassed) "Clean (+10)" else "Looping (-10)"}."

        return ScenarioResult(
            scenarioId = "scenario_3",
            name = "Context Grounding & Loop Resistance",
            score = score,
            maxScore = 20,
            passed = score >= 15,
            details = details
        )
    }

    /**
     * Scenario 4: /plan Decomposition Quality (15 pts)
     */
    private suspend fun runScenario4(engine: LlmEngine): ScenarioResult {
        val planPrompt = """
            Decompose this research topic into 3 to 5 clear, sequential report sections:
            Topic: "Quantum Supremacy and Error Correction"

            Respond with ONLY a numbered list in this format:
            1. [Section Title] - [Focus]
            2. [Section Title] - [Focus]
            3. [Section Title] - [Focus]
        """.trimIndent()

        val out = generateGuarded(engine, planPrompt, "You are a master research planner. Output clean numbered sections.")
        val planEngine = DeepPlanEngine(context = null, engine = engine)
        val sections = planEngine.parsePlanSections(out, "Quantum Supremacy")

        val score = when {
            sections.size in 3..6 && sections.all { it.title.length >= 4 } -> 15
            sections.size == 2 -> 8
            else -> 0
        }

        return ScenarioResult(
            scenarioId = "scenario_4",
            name = "/plan Decomposition Quality",
            score = score,
            maxScore = 15,
            passed = score >= 12,
            details = "Produced ${sections.size} structured sub-task sections with titles: ${sections.map { it.title }}."
        )
    }

    /**
     * Scenario 5: Hardware & Runtime Telemetry (15 pts)
     */
    private suspend fun runScenario5(engine: LlmEngine): ScenarioResult {
        val runtime = Runtime.getRuntime()
        val memBefore = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)

        val startTime = System.currentTimeMillis()
        var firstTokenTime: Long? = null
        var tokenCount = 0

        val prompt = "Write a concise 50-word overview of how transformer attention mechanisms calculate token embeddings."
        val flow = engine.generateStream(prompt, "Be concise and direct.")

        val sb = StringBuilder()
        flow.collect { token ->
            if (firstTokenTime == null && token.isNotBlank()) {
                firstTokenTime = System.currentTimeMillis()
            }
            tokenCount++
            sb.append(token)
        }

        val endTime = System.currentTimeMillis()
        val memAfter = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
        val memDelta = (memAfter - memBefore).coerceAtLeast(0)

        val ttft = (firstTokenTime ?: endTime) - startTime
        val totalDuration = (endTime - startTime).coerceAtLeast(1)
        val tokPerSec = if (totalDuration > 0) tokenCount / (totalDuration / 1000f) else 0f

        val telemetry = ScenarioTelemetry(
            ttftMs = ttft,
            tokensPerSecond = tokPerSec,
            memoryDeltaMb = memDelta,
            totalDurationMs = totalDuration,
            outputTokenCount = tokenCount
        )

        val score = when {
            ttft <= 1000 && tokPerSec >= 12f -> 15
            ttft <= 2000 && tokPerSec >= 6f -> 10
            ttft <= 3500 -> 5
            else -> 0
        }

        return ScenarioResult(
            scenarioId = "scenario_5",
            name = "Hardware & Runtime Telemetry",
            score = score,
            maxScore = 15,
            passed = score >= 10,
            details = "TTFT: ${ttft}ms | Speed: ${"%.1f".format(tokPerSec)} tok/s | RAM Delta: ${memDelta}MB",
            telemetry = telemetry
        )
    }

    private suspend fun generateGuarded(engine: LlmEngine, prompt: String, instruction: String): String {
        return GpuInferenceGate.withLock("Benchmark") {
            engine.generate(prompt, instruction)
        }
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
