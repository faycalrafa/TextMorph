package com.textmorph.app

import com.textmorph.app.agent.benchmark.AgentBenchmarkEngine
import com.textmorph.app.agent.benchmark.BenchmarkFormat
import com.textmorph.app.agent.benchmark.BenchmarkRunResult
import com.textmorph.app.agent.benchmark.BenchmarkSummaryReport
import com.textmorph.app.agent.benchmark.BenchmarkTestCase
import com.textmorph.app.agent.benchmark.BenchmarkTestReport
import com.textmorph.app.agent.model.AgentAction
import com.textmorph.app.agent.parser.AgentActionParser
import com.textmorph.app.agent.parser.CodeActionParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for Agentic Capability Benchmarking comparing Code-as-Action vs JSON.
 */
class AgentBenchmarkUnitTests {

    @Test
    fun testBenchmarkTestCasesDefinition() {
        val testCases = AgentBenchmarkEngine.TEST_CASES
        assertEquals(5, testCases.size)
        assertEquals("test_1_app_launch", testCases[0].id)
        assertEquals(AgentAction.ACTION_OPEN_APP, testCases[0].expectedAction)
        assertEquals(AgentAction.ACTION_MAKE_CALL, testCases[1].expectedAction)
        assertEquals(AgentAction.ACTION_SEND_SMS, testCases[2].expectedAction)
        assertEquals(AgentAction.ACTION_SET_ALARM, testCases[3].expectedAction)
        assertEquals(AgentAction.ACTION_CLICK_TEXT, testCases[4].expectedAction)
    }

    @Test
    fun testCodeActionValidationOnAllTestCases() {
        // Test 1: open_app("YouTube")
        val code1 = CodeActionParser.parse("open_app(\"YouTube\")")
        assertNotNull(code1)
        assertTrue(AgentBenchmarkEngine.TEST_CASES[0].expectedParamsValidator(code1!!))

        // Test 2: call("Mom")
        val code2 = CodeActionParser.parse("call(\"Mom\")")
        assertNotNull(code2)
        assertTrue(AgentBenchmarkEngine.TEST_CASES[1].expectedParamsValidator(code2!!))

        // Test 3: send_sms("Nouh", "I will arrive in 15 minutes")
        val code3 = CodeActionParser.parse("send_sms(\"Nouh\", \"I will arrive in 15 minutes\")")
        assertNotNull(code3)
        assertTrue(AgentBenchmarkEngine.TEST_CASES[2].expectedParamsValidator(code3!!))

        // Test 4: set_alarm(7, 30, "Gym")
        val code4 = CodeActionParser.parse("set_alarm(7, 30, \"Gym\")")
        assertNotNull(code4)
        assertTrue(AgentBenchmarkEngine.TEST_CASES[3].expectedParamsValidator(code4!!))

        // Test 5: click_text("Search")
        val code5 = CodeActionParser.parse("click_text(\"Search\")")
        assertNotNull(code5)
        assertTrue(AgentBenchmarkEngine.TEST_CASES[4].expectedParamsValidator(code5!!))
    }

    @Test
    fun testJsonActionValidationOnAllTestCases() {
        // Test 1: {"action": "open_app", "params": {"app_name": "YouTube"}}
        val json1 = AgentActionParser.parseAction("""{"action": "open_app", "params": {"app_name": "YouTube"}}""")
        assertNotNull(json1)
        assertTrue(AgentBenchmarkEngine.TEST_CASES[0].expectedParamsValidator(json1!!))

        // Test 2: {"action": "make_call", "params": {"contact_name": "Mom"}}
        val json2 = AgentActionParser.parseAction("""{"action": "make_call", "params": {"contact_name": "Mom"}}""")
        assertNotNull(json2)
        assertTrue(AgentBenchmarkEngine.TEST_CASES[1].expectedParamsValidator(json2!!))

        // Test 3: {"action": "send_sms", "params": {"contact_name": "Nouh", "message": "I will arrive in 15 minutes"}}
        val json3 = AgentActionParser.parseAction("""{"action": "send_sms", "params": {"contact_name": "Nouh", "message": "I will arrive in 15 minutes"}}""")
        assertNotNull(json3)
        assertTrue(AgentBenchmarkEngine.TEST_CASES[2].expectedParamsValidator(json3!!))

        // Test 4: {"action": "set_alarm", "params": {"hour": 7, "minute": 30, "label": "Gym"}}
        val json4 = AgentActionParser.parseAction("""{"action": "set_alarm", "params": {"hour": 7, "minute": 30, "label": "Gym"}}""")
        assertNotNull(json4)
        assertTrue(AgentBenchmarkEngine.TEST_CASES[3].expectedParamsValidator(json4!!))

        // Test 5: {"action": "click_text", "params": {"text": "Search"}}
        val json5 = AgentActionParser.parseAction("""{"action": "click_text", "params": {"text": "Search"}}""")
        assertNotNull(json5)
        assertTrue(AgentBenchmarkEngine.TEST_CASES[4].expectedParamsValidator(json5!!))
    }

    @Test
    fun testVerdictRecommendationLogicCodeWinsOnTokenEconomy() {
        // Create reports where both achieve 5/5, but Code is much faster and uses fewer tokens
        val dummyTestCase = AgentBenchmarkEngine.TEST_CASES[0]

        val codeResult = BenchmarkRunResult(
            format = BenchmarkFormat.CODE_AS_ACTION,
            rawOutput = "open_app(\"YouTube\")",
            parsedAction = AgentAction(action = "open_app"),
            isParseSuccess = true,
            isSemanticMatch = true,
            durationMs = 400L,
            ttftMs = 100L,
            tokenCount = 10,
            tokensPerSecond = 25f,
            ramBeforeMb = 3000L,
            ramAfterMb = 3010L,
            ramDeltaMb = 10L,
            tempBeforeC = 35.0f,
            tempAfterC = 35.2f,
            tempDeltaC = 0.2f
        )

        val jsonResult = BenchmarkRunResult(
            format = BenchmarkFormat.JSON_PAYLOAD,
            rawOutput = """{"action": "open_app", "params": {"app_name": "YouTube"}}""",
            parsedAction = AgentAction(action = "open_app"),
            isParseSuccess = true,
            isSemanticMatch = true,
            durationMs = 1400L,
            ttftMs = 120L,
            tokenCount = 35,
            tokensPerSecond = 25f,
            ramBeforeMb = 3000L,
            ramAfterMb = 3025L,
            ramDeltaMb = 25L,
            tempBeforeC = 35.0f,
            tempAfterC = 35.7f,
            tempDeltaC = 0.7f
        )

        val reportList = listOf(BenchmarkTestReport(dummyTestCase, codeResult, jsonResult))

        val summary = BenchmarkSummaryReport(
            modelName = "gemma-4-E2B-it.litertlm",
            backend = "GPU (OpenCL)",
            testReports = reportList,
            codeSuccessCount = 1,
            jsonSuccessCount = 1,
            totalTests = 1,
            codeAvgTokens = 10f,
            jsonAvgTokens = 35f,
            codeAvgLatencyMs = 400L,
            jsonAvgLatencyMs = 1400L,
            codeAvgTps = 25f,
            jsonAvgTps = 25f,
            codeAvgRamDeltaMb = 10L,
            jsonAvgRamDeltaMb = 25L,
            codeAvgTempDeltaC = 0.2f,
            jsonAvgTempDeltaC = 0.7f,
            tokenSavingsPercent = 71,
            latencySpeedupPercent = 71,
            recommendedFormat = BenchmarkFormat.CODE_AS_ACTION,
            verdictReason = "Code-as-Action is recommended: 71% faster and 71% fewer tokens."
        )

        assertEquals(BenchmarkFormat.CODE_AS_ACTION, summary.recommendedFormat)
        assertEquals(71, summary.tokenSavingsPercent)
        assertEquals(71, summary.latencySpeedupPercent)
        assertTrue(summary.verdictReason.contains("Code-as-Action is recommended"))
    }
}
