package com.textmorph.app.agent.benchmark

import com.textmorph.app.agent.guardrails.ExecutionGuardrail
import com.textmorph.app.agent.guardrails.ToolValidator
import com.textmorph.app.agent.model.AgentAction
import com.textmorph.app.agent.orchestration.DeepPlanEngine
import com.textmorph.app.agent.parser.CodeActionParser
import com.textmorph.app.agent.tools.registry.AgentToolCategory
import com.textmorph.app.agent.tools.registry.AgentToolDefinition
import com.textmorph.app.agent.tools.registry.AgentToolParameter
import com.textmorph.app.llm.GpuInferenceGate
import com.textmorph.app.llm.LlmEngine
import java.util.Locale

/**
 * Isolated scenario evaluators for the Agentic Readiness Suite.
 * Extracted to ensure strict adherence to the project < 500 lines/file modularity contract.
 */
object AgenticReadinessScenarios {

    val BENCHMARK_TOOLS = listOf(
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

    fun formatToolSignatures(): String {
        return BENCHMARK_TOOLS.joinToString("\n") { "- ${it.codeSignature} : ${it.description}" }
    }

    /**
     * Scenario 1: Tool Picking (15 pts) & Irrelevance Detection (10 pts)
     */
    suspend fun runScenario1(
        engine: LlmEngine,
        onSubTestStart: (title: String, prompt: String) -> Unit
    ): ScenarioResult {
        val toolsDesc = formatToolSignatures()
        val sysPrompt = "You are an autonomous mobile agent. Use Code-as-Action when an action is required. Output ONLY the tool command without conversational preamble or apologies.\n$toolsDesc"
        val subTests = mutableListOf<SubTestResult>()

        // Sub-test 1.1: Actionable query
        val query1 = "Find the latest quarterly earnings report for Apple."
        val title1 = "Sub-Test 1.1: Tool Picking (Actionable Query)"
        onSubTestStart(title1, query1)
        val t0_1 = System.currentTimeMillis()
        val out1 = generateGuarded(engine, query1, sysPrompt)
        val d1 = System.currentTimeMillis() - t0_1
        val action1 = CodeActionParser.parse(out1)
        val pickingPassed = action1 != null && action1.action == AgentAction.ACTION_SEARCH_WEB &&
            (action1.params["query"]?.toString()?.lowercase(Locale.ROOT)?.contains("apple") == true)
        val score1 = if (pickingPassed) 15 else 0

        subTests.add(
            SubTestResult(
                id = "1.1",
                title = title1,
                prompt = query1,
                rawResponse = out1,
                passed = pickingPassed,
                pointsAwarded = score1,
                maxPoints = 15,
                explanation = if (pickingPassed) {
                    "Correctly invoked search_web with 'apple' in query parameter."
                } else {
                    "Failed to invoke search_web(query=...) with target keyword 'apple'."
                },
                durationMs = d1
            )
        )

        // Sub-test 1.2: Casual greeting ("hammer syndrome" check)
        val query2 = "Hello! How are you doing today?"
        val title2 = "Sub-Test 1.2: Irrelevance Detection (Greeting)"
        onSubTestStart(title2, query2)
        val t0_2 = System.currentTimeMillis()
        val out2 = generateGuarded(engine, query2, sysPrompt)
        val d2 = System.currentTimeMillis() - t0_2
        val action2 = CodeActionParser.parse(out2)
        val irrelevancePassed = action2 == null // Must NOT call any tool on casual greetings
        val score2 = if (irrelevancePassed) 10 else 0

        subTests.add(
            SubTestResult(
                id = "1.2",
                title = title2,
                prompt = query2,
                rawResponse = out2,
                passed = irrelevancePassed,
                pointsAwarded = score2,
                maxPoints = 10,
                explanation = if (irrelevancePassed) {
                    "Successfully avoided unnecessary tool calls on casual greeting."
                } else {
                    "Triggered tool call (${action2.action}) on greeting (Hammer Syndrome)."
                },
                durationMs = d2
            )
        )

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
            details = details,
            subTests = subTests
        )
    }

    /**
     * Scenario 2: Schema Strictness (15 pts) & Error Self-Correction (10 pts)
     */
    suspend fun runScenario2(
        engine: LlmEngine,
        onSubTestStart: (title: String, prompt: String) -> Unit
    ): ScenarioResult {
        val toolsDesc = formatToolSignatures()
        val sysPrompt = "You are a mobile agent. Invoke actions with exact signatures. Output ONLY the tool command without conversational preamble or apologies.\n$toolsDesc"
        val subTests = mutableListOf<SubTestResult>()

        // Pass 2.1: Strict valid syntax
        val prompt1 = "Search for latest Android 15 features using Code-as-Action."
        val title1 = "Sub-Test 2.1: Schema Strictness (Tool Syntax)"
        onSubTestStart(title1, prompt1)
        val t0_1 = System.currentTimeMillis()
        val out1 = generateGuarded(engine, prompt1, sysPrompt)
        val d1 = System.currentTimeMillis() - t0_1
        val action1 = CodeActionParser.parse(out1)
        val validation1 = if (action1 != null) ToolValidator.validate(action1, BENCHMARK_TOOLS) else null
        val pass1Success = validation1 != null && validation1.isValid
        val score1 = if (pass1Success) 15 else if (action1 != null) 5 else 0

        subTests.add(
            SubTestResult(
                id = "2.1",
                title = title1,
                prompt = prompt1,
                rawResponse = out1,
                passed = pass1Success,
                pointsAwarded = score1,
                maxPoints = 15,
                explanation = if (pass1Success) {
                    "Tool call syntax strictly valid against benchmark tool schema."
                } else if (action1 != null) {
                    "Action recognized but failed schema validation: ${validation1?.error}."
                } else {
                    "No valid Code-as-Action call recognized in model response."
                },
                durationMs = d1
            )
        )

        // Pass 2.2: Injected validation error self-correction
        val brokenAction = AgentAction(action = AgentAction.ACTION_SEARCH_WEB, params = emptyMap())
        val correctionPrompt = ToolValidator.synthesizeCorrectionPrompt(brokenAction, "Missing required parameter 'query'")
        val selfCorrectionPrompt = "Your prior call failed: $correctionPrompt\nPlease invoke search_web with the required parameter."
        val title2 = "Sub-Test 2.2: Error Self-Correction Recovery"
        onSubTestStart(title2, selfCorrectionPrompt)
        val t0_2 = System.currentTimeMillis()
        val out2 = generateGuarded(engine, selfCorrectionPrompt, sysPrompt)
        val d2 = System.currentTimeMillis() - t0_2
        val action2 = CodeActionParser.parse(out2)
        val validation2 = if (action2 != null) ToolValidator.validate(action2, BENCHMARK_TOOLS) else null
        val pass2Success = validation2 != null && validation2.isValid && !action2!!.params["query"]?.toString().isNullOrBlank()
        val score2 = if (pass2Success) 10 else 0

        subTests.add(
            SubTestResult(
                id = "2.2",
                title = title2,
                prompt = selfCorrectionPrompt,
                rawResponse = out2,
                passed = pass2Success,
                pointsAwarded = score2,
                maxPoints = 10,
                explanation = if (pass2Success) {
                    "Successfully self-corrected missing parameter 'query' in subsequent turn."
                } else {
                    "Failed to recover valid parameters after injected error prompt."
                },
                durationMs = d2
            )
        )

        val totalScore = score1 + score2
        val details = "Syntax Validation: ${if (pass1Success) "Valid (+15)" else "Failed (-15)"}. Error Self-Correction: ${if (pass2Success) "Self-Corrected (+10)" else "Failed (-10)"}."

        return ScenarioResult(
            scenarioId = "scenario_2",
            name = "Schema Strictness & Self-Correction",
            score = totalScore,
            maxScore = 25,
            passed = totalScore >= 20,
            details = details,
            subTests = subTests
        )
    }

    /**
     * Scenario 3: Context Grounding (10 pts) & Loop Resistance (10 pts)
     */
    suspend fun runScenario3(
        engine: LlmEngine,
        onSubTestStart: (title: String, prompt: String) -> Unit
    ): ScenarioResult {
        val prompt = """
            Context Information:
            [Observation from search_web]:
            - Distractor: Curiosity rover landed in Gale Crater in 2012.
            - TARGET FACT: The Perseverance rover successfully landed on Mars on February 18, 2021.
            - Distractor: Opportunity rover concluded operations in 2018.

            Question: What exact date did the Perseverance rover land on Mars? Provide only the date or done(summary="...").
        """.trimIndent()
        val subTests = mutableListOf<SubTestResult>()

        val title1 = "Sub-Test 3.1: Context Grounding & Fact Extraction"
        onSubTestStart(title1, prompt)
        val t0 = System.currentTimeMillis()
        val out = generateGuarded(engine, prompt, "Extract exact grounded facts from the observation without looping.")
        val d = System.currentTimeMillis() - t0
        val lower = out.lowercase(Locale.ROOT)
        val grounded = (lower.contains("february 18, 2021") || lower.contains("february 18") || lower.contains("2021")) &&
            !lower.contains("2012")

        val guardrail = ExecutionGuardrail(maxHops = 3)
        val action = CodeActionParser.parse(out)
        val loopDecision = if (action != null) guardrail.evaluate(action.action, action.params) else null
        val loopResistancePassed = loopDecision == null || loopDecision.allowed

        val score1 = if (grounded) 10 else 0
        val score2 = if (loopResistancePassed) 10 else 0

        subTests.add(
            SubTestResult(
                id = "3.1",
                title = title1,
                prompt = prompt,
                rawResponse = out,
                passed = grounded,
                pointsAwarded = score1,
                maxPoints = 10,
                explanation = if (grounded) {
                    "Extracted February 18, 2021 accurately while ignoring 2012 distractor."
                } else {
                    "Failed to extract grounded date or included distractor (2012)."
                },
                durationMs = d
            )
        )

        subTests.add(
            SubTestResult(
                id = "3.2",
                title = "Sub-Test 3.2: Loop Resistance",
                prompt = "Evaluated action state against ExecutionGuardrail history.",
                rawResponse = action?.toString() ?: "No repetitive actions emitted",
                passed = loopResistancePassed,
                pointsAwarded = score2,
                maxPoints = 10,
                explanation = if (loopResistancePassed) "Clean execution without repetitive action looping." else "Detected repetitive action loop.",
                durationMs = 0
            )
        )

        val totalScore = score1 + score2
        val details = "Grounded Fact: ${if (grounded) "Accurate (+10)" else "Hallucinated (-10)"}. Loop Resistance: ${if (loopResistancePassed) "Clean (+10)" else "Looping (-10)"}."

        return ScenarioResult(
            scenarioId = "scenario_3",
            name = "Context Grounding & Loop Resistance",
            score = totalScore,
            maxScore = 20,
            passed = totalScore >= 15,
            details = details,
            subTests = subTests
        )
    }

    /**
     * Scenario 4: /plan Decomposition Quality (15 pts)
     */
    suspend fun runScenario4(
        engine: LlmEngine,
        onSubTestStart: (title: String, prompt: String) -> Unit
    ): ScenarioResult {
        val planPrompt = """
            Decompose this research topic into 3 to 5 clear, sequential sub-task sections:
            Topic: "Quantum Supremacy and Error Correction"

            Output ONLY a numbered list of 3 to 5 sections, one per line, formatted as:
            1. Section Title: Brief description of the section focus
            2. Section Title: Brief description of the section focus
            3. Section Title: Brief description of the section focus
        """.trimIndent()
        val subTests = mutableListOf<SubTestResult>()

        val title = "Sub-Test 4.1: /plan Multi-Step Decomposition"
        onSubTestStart(title, planPrompt)
        val t0 = System.currentTimeMillis()
        val out = generateGuarded(engine, planPrompt, "You are a master research planner. Output clean numbered sections.")
        val d = System.currentTimeMillis() - t0
        val planEngine = DeepPlanEngine(context = null, engine = engine)
        val sections = planEngine.parsePlanSections(out, "Quantum Supremacy")

        val isPlaceholderEcho = sections.any {
            it.title.contains("[Section Title]") || it.title.equals("Section Title", ignoreCase = true)
        }

        val score = when {
            !isPlaceholderEcho && sections.size in 3..6 && sections.all { it.title.length >= 4 } -> 15
            !isPlaceholderEcho && sections.size == 2 -> 8
            else -> 0
        }

        subTests.add(
            SubTestResult(
                id = "4.1",
                title = title,
                prompt = planPrompt,
                rawResponse = out,
                passed = score >= 12,
                pointsAwarded = score,
                maxPoints = 15,
                explanation = if (isPlaceholderEcho) {
                    "Disqualified: Output echoed literal placeholder template '[Section Title]'."
                } else {
                    "Parsed ${sections.size} distinct plan sections: ${sections.map { it.title }}."
                },
                durationMs = d
            )
        )

        return ScenarioResult(
            scenarioId = "scenario_4",
            name = "/plan Decomposition Quality",
            score = score,
            maxScore = 15,
            passed = score >= 12,
            details = if (isPlaceholderEcho) {
                "Failed: Echoed template placeholders instead of generating distinct sections."
            } else {
                "Produced ${sections.size} structured sub-task sections with titles: ${sections.map { it.title }}."
            },
            subTests = subTests
        )
    }

    /**
     * Scenario 5: Hardware & Runtime Telemetry (15 pts)
     */
    suspend fun runScenario5(
        engine: LlmEngine,
        backend: String,
        onSubTestStart: (title: String, prompt: String) -> Unit
    ): ScenarioResult {
        val runtime = Runtime.getRuntime()
        val memBefore = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)

        val startTime = System.currentTimeMillis()
        var firstTokenTime: Long? = null
        var tokenCount = 0

        val prompt = "Write a concise 50-word overview of how transformer attention mechanisms calculate token embeddings."
        val title = "Sub-Test 5.1: Streaming Latency & Runtime Telemetry"
        onSubTestStart(title, prompt)

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

        val isCloud = backend.contains("Cloud", ignoreCase = true) || backend.contains("OpenAI", ignoreCase = true)
        val score = if (isCloud) {
            when {
                ttft <= 1500 && tokPerSec >= 10f -> 15
                ttft <= 3000 -> 10
                ttft <= 5000 -> 5
                else -> 0
            }
        } else {
            when {
                ttft <= 1000 && tokPerSec >= 12f -> 15
                ttft <= 2000 && tokPerSec >= 6f -> 10
                ttft <= 3500 -> 5
                else -> 0
            }
        }

        val details = if (isCloud) {
            "Cloud Latency (RTT+TTFT): ${ttft}ms | Speed: ${"%.1f".format(tokPerSec)} tok/s | JVM Δ: ${memDelta}MB"
        } else {
            "TTFT: ${ttft}ms | Speed: ${"%.1f".format(tokPerSec)} tok/s | RAM Delta: ${memDelta}MB"
        }

        val subTest = SubTestResult(
            id = "5.1",
            title = title,
            prompt = prompt,
            rawResponse = sb.toString(),
            passed = score >= 10,
            pointsAwarded = score,
            maxPoints = 15,
            explanation = "$details (${if (isCloud) "Remote API evaluation" else "On-device hardware evaluation"}).",
            durationMs = totalDuration
        )

        return ScenarioResult(
            scenarioId = "scenario_5",
            name = "Hardware & Runtime Telemetry",
            score = score,
            maxScore = 15,
            passed = score >= 10,
            details = details,
            telemetry = telemetry,
            subTests = listOf(subTest)
        )
    }

    suspend fun generateGuarded(engine: LlmEngine, prompt: String, instruction: String): String {
        return GpuInferenceGate.withLock("Benchmark") {
            engine.generate(prompt, instruction, imageBytes = null, temperature = 0.0f)
        }
    }
}
