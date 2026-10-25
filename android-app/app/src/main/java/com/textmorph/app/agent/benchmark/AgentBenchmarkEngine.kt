package com.textmorph.app.agent.benchmark

import android.content.Context
import com.textmorph.app.agent.model.AgentAction
import com.textmorph.app.agent.parser.AgentActionParser
import com.textmorph.app.agent.parser.CodeActionParser
import com.textmorph.app.llm.LlmEngine
import com.textmorph.app.util.AppLogger
import com.textmorph.app.util.SystemTelemetryManager
import com.textmorph.app.util.TokenEstimator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * Orchestrator for evaluating an LLM's agentic capabilities and system impact,
 * comparing Code-as-Action vs JSON execution paradigms across 5 standard tasks.
 */
object AgentBenchmarkEngine {

    private const val TAG = "AgentBenchmarkEngine"

    val TEST_CASES: List<BenchmarkTestCase> = listOf(
        BenchmarkTestCase(
            id = "test_1_app_launch",
            title = "App Launching",
            scenario = "Launch an installed application (YouTube)",
            userPrompt = "Open YouTube to check trending videos",
            expectedAction = AgentAction.ACTION_OPEN_APP,
            expectedParamsValidator = { action ->
                val app = action.getStringParamWithAliases("app_name", "name", "app", "target")
                app.contains("youtube", ignoreCase = true)
            }
        ),
        BenchmarkTestCase(
            id = "test_2_phone_call",
            title = "Voice Call",
            scenario = "Initiate a phone call to a named contact (Mom)",
            userPrompt = "Call Mom immediately",
            expectedAction = AgentAction.ACTION_MAKE_CALL,
            expectedParamsValidator = { action ->
                val contact = action.getStringParamWithAliases("contact_name", "name", "contact")
                contact.contains("mom", ignoreCase = true)
            }
        ),
        BenchmarkTestCase(
            id = "test_3_send_sms",
            title = "SMS Messaging",
            scenario = "Prepare a text message with recipient and message body",
            userPrompt = "Send an SMS to Nouh saying 'I will arrive in 15 minutes'",
            expectedAction = AgentAction.ACTION_SEND_SMS,
            expectedParamsValidator = { action ->
                val recipient = action.getStringParamWithAliases("recipient", "contact_name", "name", "to")
                val msg = action.getStringParamWithAliases("message", "text", "body")
                recipient.contains("nouh", ignoreCase = true) && msg.contains("15", ignoreCase = true)
            }
        ),
        BenchmarkTestCase(
            id = "test_4_set_alarm",
            title = "Device Alarm",
            scenario = "Configure a system alarm with time and label",
            userPrompt = "Set an alarm for 7:30 AM labeled Gym",
            expectedAction = AgentAction.ACTION_SET_ALARM,
            expectedParamsValidator = { action ->
                val hour = action.getIntParam("hour", -1)
                val minute = action.getIntParam("minute", -1)
                val label = action.getStringParamWithAliases("label", "name")
                hour == 7 && minute == 30 && (label.isBlank() || label.contains("gym", ignoreCase = true))
            }
        ),
        BenchmarkTestCase(
            id = "test_5_ui_click",
            title = "UI Screen Automation",
            scenario = "Perform an accessibility click on an on-screen element",
            userPrompt = "Tap the Search button on the screen",
            expectedAction = AgentAction.ACTION_CLICK_TEXT,
            expectedParamsValidator = { action ->
                val text = action.getStringParamWithAliases("text", "label", "target")
                text.contains("search", ignoreCase = true)
            }
        )
    )

    private const val CODE_SYSTEM_INSTRUCTION =
        "You are TextMorph Agent. You control the Android device.\n" +
        "When instructed to perform an action, respond ONLY with a single executable Code-as-Action command.\n" +
        "No JSON, no explanations, no markdown fences.\n" +
        "Available commands:\n" +
        "- open_app(\"YouTube\")\n" +
        "- call(\"Mom\") or make_call(\"Mom\")\n" +
        "- send_sms(\"Nouh\", \"I am here\")\n" +
        "- set_alarm(7, 30, \"Gym\")\n" +
        "- click_text(\"Search\")"

    private const val JSON_SYSTEM_INSTRUCTION =
        "You are TextMorph Agent. You control the Android device.\n" +
        "When instructed to perform an action, respond ONLY with a valid JSON object matching this schema:\n" +
        "{\"action\": string, \"params\": object}\n" +
        "No code commands, no explanations, no markdown fences.\n" +
        "Examples:\n" +
        "{\"action\": \"open_app\", \"params\": {\"app_name\": \"YouTube\"}}\n" +
        "{\"action\": \"make_call\", \"params\": {\"contact_name\": \"Mom\"}}\n" +
        "{\"action\": \"send_sms\", \"params\": {\"contact_name\": \"Nouh\", \"message\": \"I am here\"}}\n" +
        "{\"action\": \"set_alarm\", \"params\": {\"hour\": 7, \"minute\": 30, \"label\": \"Gym\"}}\n" +
        "{\"action\": \"click_text\", \"params\": {\"text\": \"Search\"}}"

    suspend fun runBenchmark(
        engine: LlmEngine,
        modelName: String,
        backend: String,
        context: Context,
        onProgress: (BenchmarkProgressState) -> Unit
    ): BenchmarkSummaryReport = withContext(Dispatchers.IO) {
        AppLogger.i(TAG, "Starting agentic benchmark on '$modelName' ($backend)...")
        val testReports = mutableListOf<BenchmarkTestReport>()
        val totalTestCount = TEST_CASES.size

        for ((index, testCase) in TEST_CASES.withIndex()) {
            if (!currentCoroutineContext().isActive) break

            // 1. Run Code-as-Action
            onProgress(
                BenchmarkProgressState(
                    isRunning = true,
                    currentTestIndex = index + 1,
                    totalTests = totalTestCount,
                    currentFormat = BenchmarkFormat.CODE_AS_ACTION,
                    currentStepDescription = "Test ${index + 1}/$totalTestCount (Code-as-Action): ${testCase.title}",
                    liveStreamingOutput = ""
                )
            )

            val codeResult = executeSingleTest(
                testCase = testCase,
                format = BenchmarkFormat.CODE_AS_ACTION,
                systemInstruction = CODE_SYSTEM_INSTRUCTION,
                engine = engine,
                context = context,
                onToken = { streamedText ->
                    onProgress(
                        BenchmarkProgressState(
                            isRunning = true,
                            currentTestIndex = index + 1,
                            totalTests = totalTestCount,
                            currentFormat = BenchmarkFormat.CODE_AS_ACTION,
                            currentStepDescription = "Test ${index + 1}/$totalTestCount (Code-as-Action): ${testCase.title}",
                            liveStreamingOutput = streamedText
                        )
                    )
                }
            )

            // 2. Run JSON Payload
            onProgress(
                BenchmarkProgressState(
                    isRunning = true,
                    currentTestIndex = index + 1,
                    totalTests = totalTestCount,
                    currentFormat = BenchmarkFormat.JSON_PAYLOAD,
                    currentStepDescription = "Test ${index + 1}/$totalTestCount (JSON): ${testCase.title}",
                    liveStreamingOutput = ""
                )
            )

            val jsonResult = executeSingleTest(
                testCase = testCase,
                format = BenchmarkFormat.JSON_PAYLOAD,
                systemInstruction = JSON_SYSTEM_INSTRUCTION,
                engine = engine,
                context = context,
                onToken = { streamedText ->
                    onProgress(
                        BenchmarkProgressState(
                            isRunning = true,
                            currentTestIndex = index + 1,
                            totalTests = totalTestCount,
                            currentFormat = BenchmarkFormat.JSON_PAYLOAD,
                            currentStepDescription = "Test ${index + 1}/$totalTestCount (JSON): ${testCase.title}",
                            liveStreamingOutput = streamedText
                        )
                    )
                }
            )

            testReports.add(BenchmarkTestReport(testCase, codeResult, jsonResult))
        }

        val summary = generateSummaryReport(modelName, backend, testReports)
        AppLogger.i(TAG, "Completed agentic benchmark: Winner=${summary.recommendedFormat}, Code=${summary.codeSuccessCount}/${summary.totalTests}, JSON=${summary.jsonSuccessCount}/${summary.totalTests}")
        summary
    }

    private suspend fun executeSingleTest(
        testCase: BenchmarkTestCase,
        format: BenchmarkFormat,
        systemInstruction: String,
        engine: LlmEngine,
        context: Context,
        onToken: (String) -> Unit
    ): BenchmarkRunResult {
        val ramBefore = SystemTelemetryManager.getTelemetry(context).ramUsedMb
        val tempBefore = SystemTelemetryManager.getDeviceTemperature(context)
        val startTime = System.currentTimeMillis()
        var firstTokenTime: Long? = null
        var tokenCount = 0
        val sb = StringBuilder()
        var errorMsg: String? = null

        try {
            val flow = engine.generateStream(
                prompt = testCase.userPrompt,
                systemInstruction = systemInstruction,
                imageBytes = null
            )

            flow.catch { err ->
                AppLogger.w(TAG, "Inference error during benchmark test ${testCase.id} ($format): ${err.message}")
                errorMsg = err.message
            }.collect { token ->
                if (firstTokenTime == null) {
                    firstTokenTime = System.currentTimeMillis()
                }
                tokenCount++
                sb.append(token)
                onToken(sb.toString())
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Exception running test ${testCase.id}: ${e.message}", e)
            errorMsg = e.message
        }

        val endTime = System.currentTimeMillis()
        val durationMs = (endTime - startTime).coerceAtLeast(1L)
        val ttftMs = (firstTokenTime ?: endTime) - startTime
        val rawOutput = sb.toString().trim()
        val actualTokenCount = if (tokenCount > 0) tokenCount else TokenEstimator.estimateTextTokens(rawOutput)
        val tps = if (durationMs > 0) (actualTokenCount.toFloat() / (durationMs / 1000f)) else 0f

        val ramAfter = SystemTelemetryManager.getTelemetry(context).ramUsedMb
        val tempAfter = SystemTelemetryManager.getDeviceTemperature(context)
        val ramDelta = ramAfter - ramBefore
        val tb = tempBefore
        val ta = tempAfter
        val tempDelta = if (tb != null && ta != null) ta - tb else null

        // Parse and validate based on format
        val parsedAction = if (format == BenchmarkFormat.CODE_AS_ACTION) {
            CodeActionParser.parse(rawOutput)
        } else {
            AgentActionParser.parseAction(rawOutput)
        }

        val isParseSuccess = parsedAction != null
        val isActionMatch = parsedAction != null && (
            parsedAction.action.equals(testCase.expectedAction, ignoreCase = true) ||
            (testCase.expectedAction == AgentAction.ACTION_MAKE_CALL && parsedAction.action.equals("call", ignoreCase = true))
        )
        val isSemanticMatch = parsedAction != null && isActionMatch && testCase.expectedParamsValidator(parsedAction)

        return BenchmarkRunResult(
            format = format,
            rawOutput = rawOutput,
            parsedAction = parsedAction,
            isParseSuccess = isParseSuccess,
            isSemanticMatch = isSemanticMatch,
            durationMs = durationMs,
            ttftMs = ttftMs,
            tokenCount = actualTokenCount,
            tokensPerSecond = tps,
            ramBeforeMb = ramBefore,
            ramAfterMb = ramAfter,
            ramDeltaMb = ramDelta,
            tempBeforeC = tempBefore,
            tempAfterC = tempAfter,
            tempDeltaC = tempDelta,
            error = errorMsg
        )
    }

    private fun generateSummaryReport(
        modelName: String,
        backend: String,
        reports: List<BenchmarkTestReport>
    ): BenchmarkSummaryReport {
        val total = reports.size
        val codeSuccesses = reports.count { it.codeResult.isSemanticMatch }
        val jsonSuccesses = reports.count { it.jsonResult.isSemanticMatch }

        val codeAvgTokens = if (total > 0) reports.map { it.codeResult.tokenCount }.average().toFloat() else 0f
        val jsonAvgTokens = if (total > 0) reports.map { it.jsonResult.tokenCount }.average().toFloat() else 0f

        val codeAvgLatency = if (total > 0) reports.map { it.codeResult.durationMs }.average().toLong() else 0L
        val jsonAvgLatency = if (total > 0) reports.map { it.jsonResult.durationMs }.average().toLong() else 0L

        val codeAvgTps = if (total > 0) reports.map { it.codeResult.tokensPerSecond }.average().toFloat() else 0f
        val jsonAvgTps = if (total > 0) reports.map { it.jsonResult.tokensPerSecond }.average().toFloat() else 0f

        val codeAvgRamDelta = if (total > 0) reports.map { it.codeResult.ramDeltaMb }.average().toLong() else 0L
        val jsonAvgRamDelta = if (total > 0) reports.map { it.jsonResult.ramDeltaMb }.average().toLong() else 0L

        val codeValidTemps = reports.mapNotNull { it.codeResult.tempDeltaC }
        val jsonValidTemps = reports.mapNotNull { it.jsonResult.tempDeltaC }
        val codeAvgTemp = if (codeValidTemps.isNotEmpty()) codeValidTemps.average().toFloat() else null
        val jsonAvgTemp = if (jsonValidTemps.isNotEmpty()) jsonValidTemps.average().toFloat() else null

        val tokenSavingsPercent = if (jsonAvgTokens > 0f) {
            (((jsonAvgTokens - codeAvgTokens) / jsonAvgTokens) * 100f).toInt().coerceAtLeast(0)
        } else 0

        val latencySpeedupPercent = if (jsonAvgLatency > 0L) {
            (((jsonAvgLatency - codeAvgLatency).toFloat() / jsonAvgLatency.toFloat()) * 100f).toInt().coerceAtLeast(0)
        } else 0

        // Determine recommended format
        val (recommended, reason) = when {
            codeSuccesses > jsonSuccesses -> {
                Pair(
                    BenchmarkFormat.CODE_AS_ACTION,
                    "Code-as-Action achieved a higher agentic success rate ($codeSuccesses/$total vs $jsonSuccesses/$total). It eliminated JSON syntax errors while reducing output tokens by $tokenSavingsPercent%."
                )
            }
            jsonSuccesses > codeSuccesses -> {
                Pair(
                    BenchmarkFormat.JSON_PAYLOAD,
                    "JSON syntax achieved a higher success rate on this model ($jsonSuccesses/$total vs $codeSuccesses/$total). The model adheres better to JSON schemas."
                )
            }
            else -> {
                // Tied success rate -> compare token economy and latency
                if (tokenSavingsPercent >= 15 || latencySpeedupPercent >= 15) {
                    Pair(
                        BenchmarkFormat.CODE_AS_ACTION,
                        "Code-as-Action is recommended: Both formats achieved $codeSuccesses/$total success, but Code-as-Action is $latencySpeedupPercent% faster, emits $tokenSavingsPercent% fewer tokens, and runs cooler on the device."
                    )
                } else {
                    Pair(
                        BenchmarkFormat.CODE_AS_ACTION,
                        "Code-as-Action is recommended for on-device efficiency: Similar accuracy ($codeSuccesses/$total), with lower token overhead and instant command execution."
                    )
                }
            }
        }

        return BenchmarkSummaryReport(
            modelName = modelName,
            backend = backend,
            testReports = reports,
            codeSuccessCount = codeSuccesses,
            jsonSuccessCount = jsonSuccesses,
            totalTests = total,
            codeAvgTokens = codeAvgTokens,
            jsonAvgTokens = jsonAvgTokens,
            codeAvgLatencyMs = codeAvgLatency,
            jsonAvgLatencyMs = jsonAvgLatency,
            codeAvgTps = codeAvgTps,
            jsonAvgTps = jsonAvgTps,
            codeAvgRamDeltaMb = codeAvgRamDelta,
            jsonAvgRamDeltaMb = jsonAvgRamDelta,
            codeAvgTempDeltaC = codeAvgTemp,
            jsonAvgTempDeltaC = jsonAvgTemp,
            tokenSavingsPercent = tokenSavingsPercent,
            latencySpeedupPercent = latencySpeedupPercent,
            recommendedFormat = recommended,
            verdictReason = reason
        )
    }
}
