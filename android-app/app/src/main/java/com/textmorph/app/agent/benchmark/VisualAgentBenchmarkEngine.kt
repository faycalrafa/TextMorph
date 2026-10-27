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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * Orchestrator for evaluating multimodal LLMs on visual perception and spatial grounding.
 * Tests 2 consecutive trials of 6 randomized numbered buttons (12 total interactions)
 * in ascending numerical order (1 -> 2 -> 3 -> 4 -> 5 -> 6).
 */
object VisualAgentBenchmarkEngine {

    private const val TAG = "VisualAgentBenchmarkEngine"
    const val TOTAL_ROUNDS = 2
    const val BUTTONS_PER_ROUND = 6
    const val TOTAL_STEPS = TOTAL_ROUNDS * BUTTONS_PER_ROUND

    private const val VISUAL_SYSTEM_INSTRUCTION =
        "You are TextMorph Visual Agent. You inspect the provided Android screen image and execute requested actions.\n" +
        "When asked to press a button, analyze the screen layout and respond ONLY with a single executable command.\n" +
        "Available commands:\n" +
        "- click_at(x, y)\n" +
        "- click_text(\"number\")\n" +
        "No explanations, no markdown fences, no conversational preamble."

    /**
     * Executes the complete 3-round visual agentic benchmark.
     */
    suspend fun runVisualBenchmark(
        engine: LlmEngine,
        modelName: String,
        backend: String,
        context: Context,
        onProgress: (VisualBenchmarkProgressState) -> Unit
    ): VisualBenchmarkReport = withContext(Dispatchers.IO) {
        AppLogger.i(TAG, "Starting visual agentic benchmark on '$modelName' ($backend)...")

        val ramBefore = SystemTelemetryManager.getTelemetry(context).ramUsedMb
        val tempBefore = SystemTelemetryManager.getDeviceTemperature(context)
        val roundReports = mutableListOf<VisualBenchmarkRoundReport>()

        var overallStepIndex = 0

        for (roundIndex in 1..TOTAL_ROUNDS) {
            if (!currentCoroutineContext().isActive) break

            AppLogger.i(TAG, "Starting visual benchmark round $roundIndex/$TOTAL_ROUNDS...")
            val buttons = VisualScreenGenerator.generateButtonPositions()
            val canvasPngBytes = VisualScreenGenerator.renderCanvasToPng(buttons)
            val roundSteps = mutableListOf<VisualBenchmarkStepResult>()
            val roundStartTime = System.currentTimeMillis()

            for (targetNumber in 1..BUTTONS_PER_ROUND) {
                if (!currentCoroutineContext().isActive) break

                overallStepIndex++
                val targetButton = buttons.first { it.number == targetNumber }

                onProgress(
                    VisualBenchmarkProgressState(
                        isRunning = true,
                        currentRound = roundIndex,
                        totalRounds = TOTAL_ROUNDS,
                        currentTargetNumber = targetNumber,
                        totalStepsPerRound = BUTTONS_PER_ROUND,
                        overallStepIndex = overallStepIndex,
                        overallTotalSteps = TOTAL_STEPS,
                        currentStatus = "Round $roundIndex/$TOTAL_ROUNDS • Pressing Button $targetNumber ($overallStepIndex/$TOTAL_STEPS)",
                        liveStreamingOutput = "",
                        currentCanvasPreviewBytes = canvasPngBytes
                    )
                )

                val stepResult = executeVisualStep(
                    roundIndex = roundIndex,
                    targetNumber = targetNumber,
                    targetButton = targetButton,
                    allButtons = buttons,
                    screenPngBytes = canvasPngBytes,
                    engine = engine,
                    onToken = { streamedText ->
                        onProgress(
                            VisualBenchmarkProgressState(
                                isRunning = true,
                                currentRound = roundIndex,
                                totalRounds = TOTAL_ROUNDS,
                                currentTargetNumber = targetNumber,
                                totalStepsPerRound = BUTTONS_PER_ROUND,
                                overallStepIndex = overallStepIndex,
                                overallTotalSteps = TOTAL_STEPS,
                                currentStatus = "Round $roundIndex/$TOTAL_ROUNDS • Pressing Button $targetNumber ($overallStepIndex/$TOTAL_STEPS)",
                                liveStreamingOutput = streamedText,
                                currentCanvasPreviewBytes = canvasPngBytes
                            )
                        )
                    }
                )

                roundSteps.add(stepResult)

                // Visual feedback: Show for 2 seconds where the LLM clicked (green if hit, red if missed)
                val targetClickX = stepResult.clickedX ?: if (stepResult.isHit) targetButton.centerX else null
                val targetClickY = stepResult.clickedY ?: if (stepResult.isHit) targetButton.centerY else null

                if (targetClickX != null && targetClickY != null) {
                    val canvasWithDot = VisualScreenGenerator.renderCanvasWithClickToPng(
                        buttons = buttons,
                        clickX = targetClickX,
                        clickY = targetClickY,
                        isHit = stepResult.isHit
                    )
                    val resultStatus = if (stepResult.isHit) "HIT ✓" else "MISSED ✗ (${stepResult.distanceErrorPx?.toInt() ?: 0}px)"
                    onProgress(
                        VisualBenchmarkProgressState(
                            isRunning = true,
                            currentRound = roundIndex,
                            totalRounds = TOTAL_ROUNDS,
                            currentTargetNumber = targetNumber,
                            totalStepsPerRound = BUTTONS_PER_ROUND,
                            overallStepIndex = overallStepIndex,
                            overallTotalSteps = TOTAL_STEPS,
                            currentStatus = "Round $roundIndex/$TOTAL_ROUNDS • Button $targetNumber: $resultStatus",
                            liveStreamingOutput = stepResult.rawOutput,
                            currentCanvasPreviewBytes = canvasWithDot,
                            lastClickX = targetClickX,
                            lastClickY = targetClickY,
                            lastClickIsHit = stepResult.isHit,
                            lastDistanceErrorPx = stepResult.distanceErrorPx
                        )
                    )
                    delay(2000L)
                }
            }

            val roundEndTime = System.currentTimeMillis()
            val roundHits = roundSteps.count { it.isHit }
            val roundReport = VisualBenchmarkRoundReport(
                roundIndex = roundIndex,
                buttons = buttons,
                steps = roundSteps,
                hits = roundHits,
                totalSteps = BUTTONS_PER_ROUND,
                durationMs = (roundEndTime - roundStartTime).coerceAtLeast(1L)
            )
            roundReports.add(roundReport)
            AppLogger.i(TAG, "Completed round $roundIndex: $roundHits/$BUTTONS_PER_ROUND hits")
        }

        val ramAfter = SystemTelemetryManager.getTelemetry(context).ramUsedMb
        val tempAfter = SystemTelemetryManager.getDeviceTemperature(context)
        val ramDelta = ramAfter - ramBefore
        val tempDelta = if (tempBefore != null && tempAfter != null) tempAfter - tempBefore else null

        val summary = compileVisualReport(
            modelName = modelName,
            backend = backend,
            roundReports = roundReports,
            ramBefore = ramBefore,
            ramAfter = ramAfter,
            ramDelta = ramDelta,
            tempBefore = tempBefore,
            tempAfter = tempAfter,
            tempDelta = tempDelta
        )

        AppLogger.i(TAG, "Visual agentic benchmark finished: Hits=${summary.successfulHits}/${summary.totalSteps} (${summary.accuracyPercent}%), AvgLatency=${summary.avgLatencyMs}ms, PerfectTrials=${summary.perfectTrials}/$TOTAL_ROUNDS")
        summary
    }

    private suspend fun executeVisualStep(
        roundIndex: Int,
        targetNumber: Int,
        targetButton: VisualButtonTarget,
        allButtons: List<VisualButtonTarget>,
        screenPngBytes: ByteArray,
        engine: LlmEngine,
        onToken: (String) -> Unit
    ): VisualBenchmarkStepResult {
        val userPrompt = "Press button $targetNumber on the screen."
        val startTime = System.currentTimeMillis()
        var firstTokenTime: Long? = null
        var tokenCount = 0
        val sb = StringBuilder()
        var errorMsg: String? = null

        try {
            val flow = engine.generateStream(
                prompt = userPrompt,
                systemInstruction = VISUAL_SYSTEM_INSTRUCTION,
                imageBytes = screenPngBytes
            )

            flow.catch { err ->
                AppLogger.w(TAG, "Stream error during visual benchmark R$roundIndex-B$targetNumber: ${err.message}")
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
            AppLogger.e(TAG, "Exception during visual step R$roundIndex-B$targetNumber: ${e.message}", e)
            errorMsg = e.message
        }

        val endTime = System.currentTimeMillis()
        val durationMs = (endTime - startTime).coerceAtLeast(1L)
        val ttftMs = (firstTokenTime ?: endTime) - startTime
        val rawOutput = sb.toString().trim()
        val actualTokenCount = if (tokenCount > 0) tokenCount else TokenEstimator.estimateTextTokens(rawOutput)
        val tps = if (durationMs > 0) (actualTokenCount.toFloat() / (durationMs / 1000f)) else 0f

        // Parse Action
        val parsedAction = CodeActionParser.parse(rawOutput) ?: AgentActionParser.parseAction(rawOutput)
        var clickedX: Int? = null
        var clickedY: Int? = null
        var isHit = false
        var hitButtonNumber: Int? = null
        var distanceErrorPx: Float? = null

        if (parsedAction != null) {
            if (parsedAction.action.equals(AgentAction.ACTION_CLICK_AT, ignoreCase = true)) {
                val x = parsedAction.getIntParamWithAliases("x", "left", default = -1)
                val y = parsedAction.getIntParamWithAliases("y", "top", default = -1)
                if (x >= 0 && y >= 0) {
                    clickedX = x
                    clickedY = y
                    distanceErrorPx = targetButton.distanceFromCenter(x, y)
                    isHit = targetButton.contains(x, y)

                    // Identify which button was hit if not target
                    val hitAny = allButtons.firstOrNull { it.contains(x, y) }
                    hitButtonNumber = hitAny?.number
                }
            } else if (parsedAction.action.equals(AgentAction.ACTION_CLICK_TEXT, ignoreCase = true)) {
                val text = parsedAction.getStringParamWithAliases("text", "label", "target")
                val cleanText = text.trim()
                if (cleanText == targetNumber.toString() || cleanText.contains(targetNumber.toString())) {
                    isHit = true
                    distanceErrorPx = 0f
                    hitButtonNumber = targetNumber
                } else {
                    hitButtonNumber = cleanText.toIntOrNull()
                }
            }
        }

        return VisualBenchmarkStepResult(
            roundIndex = roundIndex,
            targetNumber = targetNumber,
            target = targetButton,
            rawOutput = rawOutput,
            parsedAction = parsedAction,
            clickedX = clickedX,
            clickedY = clickedY,
            isHit = isHit,
            hitButtonNumber = hitButtonNumber,
            distanceErrorPx = distanceErrorPx,
            durationMs = durationMs,
            ttftMs = ttftMs,
            tokenCount = actualTokenCount,
            tokensPerSecond = tps,
            error = errorMsg
        )
    }

    private fun compileVisualReport(
        modelName: String,
        backend: String,
        roundReports: List<VisualBenchmarkRoundReport>,
        ramBefore: Long,
        ramAfter: Long,
        ramDelta: Long,
        tempBefore: Float?,
        tempAfter: Float?,
        tempDelta: Float?
    ): VisualBenchmarkReport {
        val allSteps = roundReports.flatMap { it.steps }
        val totalSteps = if (allSteps.isNotEmpty()) allSteps.size else TOTAL_STEPS
        val successfulHits = allSteps.count { it.isHit }
        val accuracyPercent = if (totalSteps > 0) (successfulHits.toFloat() / totalSteps.toFloat()) * 100f else 0f
        val perfectTrials = roundReports.count { it.isPerfect }

        val avgLatencyMs = if (allSteps.isNotEmpty()) allSteps.map { it.durationMs }.average().toLong() else 0L
        val avgTtftMs = if (allSteps.isNotEmpty()) allSteps.map { it.ttftMs }.average().toLong() else 0L
        val avgTps = if (allSteps.isNotEmpty()) allSteps.map { it.tokensPerSecond }.average().toFloat() else 0f

        val validDistances = allSteps.mapNotNull { it.distanceErrorPx }
        val avgDistanceErrorPx = if (validDistances.isNotEmpty()) validDistances.average().toFloat() else null

        val verdict = when {
            accuracyPercent >= 90f ->
                "Outstanding Visual Agent ($successfulHits/$totalSteps hits, ${accuracyPercent.toInt()}% accuracy). High spatial fidelity and instant sequence execution."
            accuracyPercent >= 70f ->
                "Capable Visual Agent ($successfulHits/$totalSteps hits, ${accuracyPercent.toInt()}% accuracy). Handles most screen grounding with occasional spatial offset."
            accuracyPercent >= 40f ->
                "Moderate Visual Agent ($successfulHits/$totalSteps hits, ${accuracyPercent.toInt()}% accuracy). Struggles with precise coordinate targeting on crowded screens."
            else ->
                "Suboptimal Visual Grounding ($successfulHits/$totalSteps hits, ${accuracyPercent.toInt()}% accuracy). Model requires fine-tuning or multimodal vision adapter."
        }

        return VisualBenchmarkReport(
            modelName = modelName,
            backend = backend,
            roundReports = roundReports,
            totalTrials = roundReports.size,
            totalSteps = totalSteps,
            successfulHits = successfulHits,
            accuracyPercent = accuracyPercent,
            perfectTrials = perfectTrials,
            avgLatencyMs = avgLatencyMs,
            avgTtftMs = avgTtftMs,
            avgTps = avgTps,
            avgDistanceErrorPx = avgDistanceErrorPx,
            ramBeforeMb = ramBefore,
            ramAfterMb = ramAfter,
            ramDeltaMb = ramDelta,
            tempBeforeC = tempBefore,
            tempAfterC = tempAfter,
            tempDeltaC = tempDelta,
            verdict = verdict
        )
    }
}
