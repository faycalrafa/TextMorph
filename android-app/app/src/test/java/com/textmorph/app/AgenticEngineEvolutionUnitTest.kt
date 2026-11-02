package com.textmorph.app

import android.graphics.Rect
import com.textmorph.app.agent.codemode.AgentActionPlan
import com.textmorph.app.agent.codemode.CodeModeScriptBuilder
import com.textmorph.app.agent.core.ExecutionClaim
import com.textmorph.app.agent.events.AgentEventRecord
import com.textmorph.app.agent.inbox.AdmissionMode
import com.textmorph.app.agent.model.AgentAction
import com.textmorph.app.agent.perception.AttentionalScreenLinearizer
import com.textmorph.app.agent.perception.ScreenDiffer
import com.textmorph.app.agent.perception.ScreenHashUtils
import com.textmorph.app.agent.projections.ChatMessageProjector
import com.textmorph.app.agent.projections.StepTraceProjector
import com.textmorph.app.agent.security.NavigationBacktracker
import com.textmorph.app.agent.security.PermissionGateway
import com.textmorph.app.agent.security.PermissionLevel
import com.textmorph.app.agent.security.SpatialCircuitBreaker
import com.textmorph.app.service.overlay.UiElementNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgenticEngineEvolutionUnitTest {

    @Test
    fun testEventSourcingChatMessageProjection() {
        val sessionId = "test-session-123"
        val events = listOf(
            AgentEventRecord(
                eventId = 1L,
                sessionId = sessionId,
                sequenceNumber = 1L,
                eventType = "PROMPT_ADMITTED",
                payloadJson = """{"prompt":"Search for latest news","admissionMode":"QUEUE"}"""
            ),
            AgentEventRecord(
                eventId = 2L,
                sessionId = sessionId,
                sequenceNumber = 2L,
                eventType = "INFERENCE_STARTED",
                payloadJson = """{"stepIndex":1}"""
            ),
            AgentEventRecord(
                eventId = 3L,
                sessionId = sessionId,
                sequenceNumber = 3L,
                eventType = "TOKEN_CHUNK",
                payloadJson = """{"tokenDelta":"Thinking...","isThought":true}"""
            ),
            AgentEventRecord(
                eventId = 4L,
                sessionId = sessionId,
                sequenceNumber = 4L,
                eventType = "TOKEN_CHUNK",
                payloadJson = """{"tokenDelta":"Here are the top headlines.","isThought":false}"""
            ),
            AgentEventRecord(
                eventId = 5L,
                sessionId = sessionId,
                sequenceNumber = 5L,
                eventType = "TOOL_COMPLETED",
                payloadJson = """{"toolName":"search_web","outputSnippet":"News results","stepIndex":1,"durationMs":120}"""
            ),
            AgentEventRecord(
                eventId = 6L,
                sessionId = sessionId,
                sequenceNumber = 6L,
                eventType = "CRASH_RECOVERY",
                payloadJson = """{"diagnosticMessage":"Recovered from low-memory kill"}"""
            )
        )

        val messages = ChatMessageProjector.projectMessages(events)
        assertEquals("Should project 3 messages (user, assistant, system recovery)", 3, messages.size)

        val userMsg = messages[0]
        assertEquals("user", userMsg.role)
        assertEquals("Search for latest news", userMsg.content)

        val asstMsg = messages[1]
        assertEquals("assistant", asstMsg.role)
        assertEquals("Here are the top headlines.", asstMsg.content)
        assertEquals("Thinking...", asstMsg.thoughtContent)
        assertNotNull(asstMsg.stepTracesJson)
        assertTrue(asstMsg.stepTracesJson!!.contains("search_web"))

        val recoveryMsg = messages[2]
        assertEquals("system", recoveryMsg.role)
        assertTrue(recoveryMsg.content.contains("Recovered from low-memory kill"))
    }

    @Test
    fun testStepTraceProjection() {
        val events = listOf(
            AgentEventRecord(
                eventId = 1L,
                sessionId = "sess",
                sequenceNumber = 1L,
                eventType = "TOOL_REQUESTED",
                payloadJson = """{"toolName":"click_text","stepIndex":1}"""
            ),
            AgentEventRecord(
                eventId = 2L,
                sessionId = "sess",
                sequenceNumber = 2L,
                eventType = "TOOL_COMPLETED",
                payloadJson = """{"toolName":"click_text","outputSnippet":"Clicked Settings","stepIndex":1,"durationMs":85}"""
            )
        )

        val traces = StepTraceProjector.projectStepTraces(events)
        assertEquals(1, traces.size)
        assertEquals("success", traces[0].status)
        assertEquals("click_text", traces[0].toolName)
        assertEquals(85L, traces[0].durationMs)
    }

    @Test
    fun testAdmissionModesAndSteeringPriority() {
        assertEquals("STEER", AdmissionMode.STEER.name)
        assertEquals("QUEUE", AdmissionMode.QUEUE.name)
        assertEquals("ABORT", AdmissionMode.ABORT.name)
    }

    @Test
    fun testExecutionClaimExpiration() {
        val now = System.currentTimeMillis()
        val validClaim = ExecutionClaim(
            sessionId = "s1",
            executionId = "e1",
            currentStepIndex = 1,
            phase = ExecutionClaim.PHASE_PERCEIVING,
            heartbeatAt = now,
            leaseDurationMs = 30_000L
        )
        assertFalse("Recent claim should not be expired", validClaim.isExpired)

        val expiredClaim = ExecutionClaim(
            sessionId = "s2",
            executionId = "e2",
            currentStepIndex = 1,
            phase = ExecutionClaim.PHASE_INFERRING,
            heartbeatAt = now - 60_000L,
            leaseDurationMs = 30_000L
        )
        assertTrue("Claim older than lease duration should be expired", expiredClaim.isExpired)
    }

    @Test
    fun testScreenDiffingAndLayoutHashing() {
        val node1 = UiElementNode(
            index = 1,
            text = "Search",
            contentDescription = "",
            className = "android.widget.Button",
            bounds = Rect(0, 0, 100, 50),
            isClickable = true,
            isEditable = false,
            isScrollable = false,
            depth = 1
        )
        val node2 = UiElementNode(
            index = 2,
            text = "Settings",
            contentDescription = "",
            className = "android.widget.TextView",
            bounds = Rect(0, 60, 100, 100),
            isClickable = true,
            isEditable = false,
            isScrollable = false,
            depth = 1
        )

        val hash1 = ScreenHashUtils.computeScreenHash(listOf(node1), "com.test")
        val hash2 = ScreenHashUtils.computeScreenHash(listOf(node1), "com.test")
        assertEquals("Identical node lists must produce identical SHA-256 hashes", hash1, hash2)

        val diffIdentical = ScreenDiffer.computeDiff(listOf(node1), listOf(node1), "com.test")
        assertTrue("Diff of identical lists must report isIdentical=true", diffIdentical.isIdentical)

        val diffAdded = ScreenDiffer.computeDiff(listOf(node1), listOf(node1, node2), "com.test")
        assertFalse("Diff with added node must not be identical", diffAdded.isIdentical)
        assertEquals("Must report 1 added element", 1, diffAdded.addedElements.size)
        assertEquals("Settings", diffAdded.addedElements[0].text)

        val prompt = AttentionalScreenLinearizer.formatPerceptionPrompt(listOf(node1, node2), diffAdded, "Open Settings", "com.test")
        assertTrue(prompt.contains("SCREEN STATUS: Changed"))
        assertTrue(prompt.contains("NEW / CHANGED ELEMENTS"))
        assertTrue(prompt.contains("Settings"))
    }

    @Test
    fun testCodeModeScriptBuilderParsing() {
        // Test CodeMode JavaScript block
        val jsOutput = """
            I will search for the weather and open Maps:
            ```javascript
            const w = await TextMorph.tools.searchWeb("weather London");
            await TextMorph.screen.clickText("Maps");
            return "Done";
            ```
        """.trimIndent()
        val plan1 = CodeModeScriptBuilder.parseActionPlan(jsOutput)
        assertTrue("Should detect CodeModeScript", plan1 is AgentActionPlan.CodeModeScript)
        val script = (plan1 as AgentActionPlan.CodeModeScript).scriptContent
        assertTrue(script.contains("TextMorph.tools.searchWeb"))

        // Test single Code-as-Action regex
        val regexOutput = "click_text(\"Confirm\")"
        val plan2 = CodeModeScriptBuilder.parseActionPlan(regexOutput)
        assertTrue("Should detect SingleCodeAction", plan2 is AgentActionPlan.SingleCodeAction)
        assertEquals("click_text", (plan2 as AgentActionPlan.SingleCodeAction).action.action)

        // Test plain conversational text
        val convOutput = "Hello! How can I help you today?"
        val plan3 = CodeModeScriptBuilder.parseActionPlan(convOutput)
        assertTrue("Should detect ConversationalResponse", plan3 is AgentActionPlan.ConversationalResponse)
        assertEquals("Hello! How can I help you today?", (plan3 as AgentActionPlan.ConversationalResponse).text)
    }

    @Test
    fun testPermissionGatewayAndCircuitBreakers() {
        val safeAction = AgentAction("read_screen")
        val permSafe = PermissionGateway.evaluate("session-test", safeAction, "com.android.settings")
        assertEquals(PermissionLevel.ALLOW, permSafe)

        val sensitiveAction = AgentAction(
            action = "click_text",
            params = mapOf("text" to kotlinx.serialization.json.JsonPrimitive("Confirm Delete All"))
        )
        val permDangerous = PermissionGateway.evaluate("session-test", sensitiveAction, "com.test.app")
        assertEquals(PermissionLevel.ASK, permDangerous)

        // Test SpatialCircuitBreaker
        val breaker = SpatialCircuitBreaker(maxIdenticalScreens = 3)
        breaker.recordScreen("hash_abc")
        breaker.recordScreen("hash_abc")
        assertTrue(breaker.evaluate().allowed)
        breaker.recordScreen("hash_abc")
        val decision = breaker.evaluate()
        assertFalse("Circuit breaker must trip after 3 identical screen hashes", decision.allowed)
        assertTrue(decision.reason!!.contains("identical for 3 consecutive steps"))
    }

    @Test
    fun testNavigationBacktracker() {
        val backtracker = NavigationBacktracker()
        assertFalse(backtracker.canBacktrack)

        backtracker.pushBreadcrumb(1, "com.app.main", "h1", "Home")
        assertFalse("Single breadcrumb cannot backtrack", backtracker.canBacktrack)

        backtracker.pushBreadcrumb(2, "com.app.settings", "h2", "Settings")
        assertTrue("Multiple breadcrumbs can backtrack", backtracker.canBacktrack)

        val popped = backtracker.popBreadcrumb()
        assertNotNull(popped)
        assertEquals("com.app.settings", popped?.packageName)
        assertFalse(backtracker.canBacktrack)
    }
}
