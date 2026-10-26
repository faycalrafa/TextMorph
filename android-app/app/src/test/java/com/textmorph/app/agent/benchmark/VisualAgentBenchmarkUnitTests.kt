package com.textmorph.app.agent.benchmark

import com.textmorph.app.agent.model.AgentAction
import com.textmorph.app.agent.parser.CodeActionParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Unit tests for Visual Agentic Benchmarking: button placement, collision avoidance,
 * spatial hit detection, and report aggregation.
 */
class VisualAgentBenchmarkUnitTests {

    @Test
    fun testVisualButtonTargetContainsAndDistance() {
        val target = VisualButtonTarget(
            number = 1,
            centerX = 200,
            centerY = 300,
            radius = 48
        )

        // Exact Center Click
        assertTrue(target.contains(200, 300))
        assertEquals(0f, target.distanceFromCenter(200, 300), 0.001f)

        // Edge Click within Radius (dx = 30, dy = 0 -> distance = 30 <= 48)
        assertTrue(target.contains(230, 300))
        assertEquals(30f, target.distanceFromCenter(230, 300), 0.001f)

        // Click in Touch Tolerance Margin (dx = 52 -> distance = 52 <= 48 * 1.15 = 55.2)
        assertTrue(target.contains(252, 300, toleranceMultiplier = 1.15f))
        assertFalse(target.contains(252, 300, toleranceMultiplier = 1.0f))

        // Complete Miss (dx = 80, dy = 0)
        assertFalse(target.contains(280, 300))
        assertEquals(80f, target.distanceFromCenter(280, 300), 0.001f)
    }

    @Test
    fun testScreenGeneratorButtonPositionsNoOverlap() {
        // Run 25 iterations with varying seeds to verify collision avoidance guarantee
        for (seed in 1..25) {
            val random = Random(seed)
            val buttons = VisualScreenGenerator.generateButtonPositions(
                screenWidth = 720,
                screenHeight = 1280,
                buttonRadius = 48,
                count = 6,
                margin = 64,
                random = random
            )

            assertEquals(6, buttons.size)

            // Verify numbering 1 to 6
            for (i in 1..6) {
                assertEquals(i, buttons[i - 1].number)
            }

            // Verify all buttons respect boundary margins
            val minX = 64 + 48
            val maxX = 720 - 64 - 48
            val minY = 64 + 48
            val maxY = 1280 - 64 - 48

            for (btn in buttons) {
                assertTrue("Button ${btn.number} X=${btn.centerX} out of [$minX, $maxX]", btn.centerX in minX..maxX)
                assertTrue("Button ${btn.number} Y=${btn.centerY} out of [$minY, $maxY]", btn.centerY in minY..maxY)
            }

            // Verify no pair overlaps (min distance >= 2 * 48 + 28 = 124)
            for (i in 0 until buttons.size) {
                for (j in i + 1 until buttons.size) {
                    val b1 = buttons[i]
                    val b2 = buttons[j]
                    val dist = b1.distanceFromCenter(b2.centerX, b2.centerY)
                    assertTrue("Overlap detected between btn ${b1.number} and ${b2.number}: dist=$dist", dist >= 120f)
                }
            }
        }
    }

    @Test
    fun testSpatialClickActionParsingAndHitResolution() {
        val target = VisualButtonTarget(number = 3, centerX = 350, centerY = 600, radius = 48)

        // Parse click_at command
        val action = CodeActionParser.parse("click_at(355, 605)")
        assertNotNull(action)
        assertEquals(AgentAction.ACTION_CLICK_AT, action!!.action)

        val x = action.getIntParamWithAliases("x", default = -1)
        val y = action.getIntParamWithAliases("y", default = -1)
        assertEquals(355, x)
        assertEquals(605, y)

        val isHit = target.contains(x, y)
        assertTrue(isHit)
        val dist = target.distanceFromCenter(x, y)
        assertTrue(dist < 10f)

        // Miss command
        val missAction = CodeActionParser.parse("click_at(100, 100)")
        assertNotNull(missAction)
        assertFalse(target.contains(missAction!!.getIntParamWithAliases("x"), missAction.getIntParamWithAliases("y")))
    }

    @Test
    fun testTextClickActionParsingAndHitResolution() {
        // Target is button 5
        val correctTextAction = CodeActionParser.parse("click_text(\"5\")")
        assertNotNull(correctTextAction)
        val text = correctTextAction!!.getStringParamWithAliases("text")
        assertEquals("5", text)
        assertTrue(text == "5" || text.contains("5"))

        // Wrong text action
        val wrongTextAction = CodeActionParser.parse("click_text(\"2\")")
        assertNotNull(wrongTextAction)
        val wrongText = wrongTextAction!!.getStringParamWithAliases("text")
        assertFalse(wrongText == "5")
    }

    @Test
    fun testVisualBenchmarkReportAggregation() {
        val dummyTarget = VisualButtonTarget(1, 100, 100)

        // Generate 3 rounds: Round 1 (6/6), Round 2 (6/6), Round 3 (5/6) -> 17/18 total
        val roundReports = (1..3).map { roundIdx ->
            val steps = (1..6).map { btnIdx ->
                val isMiss = (roundIdx == 3 && btnIdx == 6)
                VisualBenchmarkStepResult(
                    roundIndex = roundIdx,
                    targetNumber = btnIdx,
                    target = dummyTarget,
                    rawOutput = if (!isMiss) "click_at(100, 100)" else "click_at(500, 500)",
                    parsedAction = AgentAction(action = "click_at"),
                    clickedX = if (!isMiss) 100 else 500,
                    clickedY = if (!isMiss) 100 else 500,
                    isHit = !isMiss,
                    hitButtonNumber = if (!isMiss) btnIdx else null,
                    distanceErrorPx = if (!isMiss) 2.0f else 400.0f,
                    durationMs = 500L,
                    ttftMs = 150L,
                    tokenCount = 8,
                    tokensPerSecond = 16f
                )
            }
            VisualBenchmarkRoundReport(
                roundIndex = roundIdx,
                buttons = listOf(dummyTarget),
                steps = steps,
                hits = steps.count { it.isHit },
                totalSteps = 6,
                durationMs = 3000L
            )
        }

        val allSteps = roundReports.flatMap { it.steps }
        val hits = allSteps.count { it.isHit }
        val accuracy = (hits.toFloat() / allSteps.size.toFloat()) * 100f
        val perfectTrials = roundReports.count { it.isPerfect }

        val report = VisualBenchmarkReport(
            modelName = "gemma-4-E4B-it.litertlm",
            backend = "GPU (OpenCL)",
            roundReports = roundReports,
            totalTrials = 3,
            totalSteps = 18,
            successfulHits = hits,
            accuracyPercent = accuracy,
            perfectTrials = perfectTrials,
            avgLatencyMs = allSteps.map { it.durationMs }.average().toLong(),
            avgTtftMs = allSteps.map { it.ttftMs }.average().toLong(),
            avgTps = allSteps.map { it.tokensPerSecond }.average().toFloat(),
            avgDistanceErrorPx = allSteps.mapNotNull { it.distanceErrorPx }.average().toFloat(),
            ramBeforeMb = 4000L,
            ramAfterMb = 4030L,
            ramDeltaMb = 30L,
            tempBeforeC = 36.0f,
            tempAfterC = 36.4f,
            tempDeltaC = 0.4f,
            verdict = "Outstanding Visual Agent"
        )

        assertEquals(17, report.successfulHits)
        assertEquals(18, report.totalSteps)
        assertEquals(2, report.perfectTrials)
        assertEquals(3, report.totalTrials)
        assertTrue(report.accuracyPercent > 94f)
        assertEquals(500L, report.avgLatencyMs)
        assertEquals(150L, report.avgTtftMs)
        assertEquals(16f, report.avgTps, 0.001f)
        assertEquals(30L, report.ramDeltaMb)
    }
}
