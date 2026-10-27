package com.textmorph.app

import com.textmorph.app.agent.model.AgentAction
import com.textmorph.app.agent.model.AgentStepStatus
import com.textmorph.app.agent.model.AgentTaskStep
import com.textmorph.app.agent.planner.PlanMilestone
import com.textmorph.app.agent.planner.TaskPlanDecomposer
import com.textmorph.app.data.model.ChatMessageEntity
import com.textmorph.app.data.model.InteractionTriggerSource
import com.textmorph.app.data.model.LlmInteractionRecord
import com.textmorph.app.ui.modelhub.components.ConversationDumpTurn
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for Cloud Engine SSE null token prevention, Private Agent milestone decomposition,
 * and Voice Agent real-time step history mirroring and raw output preservation.
 */
class VoiceAgentAndCloudEngineUpgradeUnitTests {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // ---------------------------------------------------------------------------------------------
    // 1. Cloud Engine SSE Chunk Parsing & Null Token Prevention
    // ---------------------------------------------------------------------------------------------

    @Test
    fun testCloudEngineSseChunkParsing_filtersOutJsonNullAndLiteralNull() = runBlocking {
        // Simulates typical OpenAI / vLLM SSE stream chunk sequence:
        // Chunk 1: Role setup (content is null)
        // Chunk 2: Reasoning metadata (content is null)
        // Chunk 3: Real content token ("Hello")
        // Chunk 4: Real content token (" world")
        // Chunk 5: Empty finish chunk (content is null)
        val sseLines = listOf(
            """data: {"choices":[{"delta":{"role":"assistant","content":null}}]}""",
            """data: {"choices":[{"delta":{"content":null,"refusal":null}}]}""",
            """data: {"choices":[{"delta":{"content":"Hello"}}]}""",
            """data: {"choices":[{"delta":{"content":" world"}}]}""",
            """data: {"choices":[{"delta":{}}]}""",
            """data: [DONE]"""
        )

        val emittedTokens = flow {
            for (line in sseLines) {
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith(":")) continue
                if (trimmed == "data: [DONE]") break

                if (trimmed.startsWith("data:")) {
                    val dataJson = trimmed.substring(5).trim()
                    try {
                        val parsed = json.parseToJsonElement(dataJson).jsonObject
                        val choices = parsed["choices"]?.jsonArray
                        val delta = choices?.firstOrNull()?.jsonObject?.get("delta")?.jsonObject
                        val contentElem = delta?.get("content")
                        if (contentElem != null && contentElem !is kotlinx.serialization.json.JsonNull) {
                            val content = contentElem.jsonPrimitive.contentOrNull
                            if (!content.isNullOrEmpty() && content != "null") {
                                emit(content)
                            }
                        }
                    } catch (_: Exception) {
                    }
                }
            }
        }.toList()

        // Verify that NO "null" or "nullnull" was emitted, only "Hello" and " world"
        assertEquals(listOf("Hello", " world"), emittedTokens)
        val assembledText = emittedTokens.joinToString("")
        assertEquals("Hello world", assembledText)
        assertFalse("Assembled text must not contain 'null'", assembledText.contains("null"))
    }

    @Test
    fun testCloudEngineSseChunkParsing_handlesDeepseekReasoningWithoutNullContent() = runBlocking {
        val sseLines = listOf(
            """data: {"choices":[{"delta":{"role":"assistant","reasoning_content":"Let me think","content":null}}]}""",
            """data: {"choices":[{"delta":{"content":"Answer"}}]}""",
            """data: [DONE]"""
        )

        val emittedTokens = flow {
            for (line in sseLines) {
                val trimmed = line.trim()
                if (trimmed == "data: [DONE]") break
                if (trimmed.startsWith("data:")) {
                    val dataJson = trimmed.substring(5).trim()
                    val parsed = json.parseToJsonElement(dataJson).jsonObject
                    val choices = parsed["choices"]?.jsonArray
                    val delta = choices?.firstOrNull()?.jsonObject?.get("delta")?.jsonObject
                    val contentElem = delta?.get("content")
                    if (contentElem != null && contentElem !is kotlinx.serialization.json.JsonNull) {
                        val content = contentElem.jsonPrimitive.contentOrNull
                        if (!content.isNullOrEmpty() && content != "null") {
                            emit(content)
                        }
                    }
                }
            }
        }.toList()

        assertEquals(listOf("Answer"), emittedTokens)
    }

    // ---------------------------------------------------------------------------------------------
    // 2. Private Agent Milestone Decomposition & Sequential Progression
    // ---------------------------------------------------------------------------------------------

    @Test
    fun testTaskPlanDecomposer_splitsCompoundConnectorsCorrectly() = runBlocking {
        val compoundTask = "open YouTube and search for cats"
        val milestones = TaskPlanDecomposer.decompose(compoundTask)

        assertEquals(2, milestones.size)
        assertEquals(1, milestones[0].index)
        assertEquals("Open YouTube", milestones[0].description)
        assertEquals("YouTube", milestones[0].targetApp)

        assertEquals(2, milestones[1].index)
        assertEquals("Search for cats", milestones[1].description)
    }

    @Test
    fun testTaskPlanDecomposer_splitsMultiCompoundSteps() = runBlocking {
        val multiStep = "open chrome, then go to google.com and then check news"
        val milestones = TaskPlanDecomposer.decompose(multiStep)

        assertEquals(3, milestones.size)
        assertEquals(1, milestones[0].index)
        assertTrue(milestones[0].description.contains("Chrome", ignoreCase = true))

        assertEquals(2, milestones[1].index)
        assertTrue(milestones[1].description.contains("google.com", ignoreCase = true))

        assertEquals(3, milestones[2].index)
        assertTrue(milestones[2].description.contains("check news", ignoreCase = true))
    }

    @Test
    fun testTaskPlanDecomposer_isMilestoneConfirmed() {
        val m1 = PlanMilestone(index = 1, description = "Open YouTube", targetApp = "YouTube")
        val screenWithYoutube = "Hierarchy: [1] FrameLayout package:com.google.android.youtube text:YouTube Search"
        val screenWithoutYoutube = "Hierarchy: [1] FrameLayout package:com.android.launcher"

        assertTrue(
            "Milestone 1 confirmed when YouTube package is active",
            TaskPlanDecomposer.isMilestoneConfirmed(m1, screenWithYoutube, "com.google.android.youtube")
        )
        assertFalse(
            "Milestone 1 unconfirmed when on home launcher",
            TaskPlanDecomposer.isMilestoneConfirmed(m1, screenWithoutYoutube, "com.android.launcher")
        )

        val m2 = PlanMilestone(index = 2, description = "Search for cats")
        val screenWithCats = "Hierarchy: [5] TextView text:Funny Cats Video [6] TextView text:Cats 2026"
        val screenWithoutCats = "Hierarchy: [5] TextView text:Trending Music"

        assertTrue(
            "Milestone 2 confirmed when search keywords appear on screen",
            TaskPlanDecomposer.isMilestoneConfirmed(m2, screenWithCats, "com.google.android.youtube")
        )
        assertFalse(
            "Milestone 2 unconfirmed when keywords do not appear",
            TaskPlanDecomposer.isMilestoneConfirmed(m2, screenWithoutCats, "com.google.android.youtube")
        )
    }

    // ---------------------------------------------------------------------------------------------
    // 3. Voice Agent Chat Mirroring & Timeline Serialization
    // ---------------------------------------------------------------------------------------------

    @Test
    fun testAgentTaskStep_serializationAndChatMirroring() {
        val steps = listOf(
            AgentTaskStep(
                stepIndex = 1,
                actionType = AgentAction.ACTION_OPEN_APP,
                title = "Opening YouTube",
                detail = "Launched com.google.android.youtube",
                status = AgentStepStatus.COMPLETED
            ),
            AgentTaskStep(
                stepIndex = 2,
                actionType = AgentAction.ACTION_TYPE_TEXT,
                title = "Typing 'cats'",
                detail = "Search query entered into search bar",
                status = AgentStepStatus.COMPLETED
            ),
            AgentTaskStep(
                stepIndex = 3,
                actionType = AgentAction.ACTION_PRESS_ENTER,
                title = "Submitting search",
                detail = "IME enter key pressed",
                status = AgentStepStatus.COMPLETED
            )
        )

        val serialized = AgentTaskStep.serializeList(steps)
        assertNotNull(serialized)
        assertTrue(serialized.contains("Opening YouTube"))
        assertTrue(serialized.contains("Typing 'cats'"))

        // Create ChatMessageEntity as VoiceAgentMissionCoordinator does
        val entity = ChatMessageEntity(
            sessionId = "voice-test-session",
            role = "assistant",
            content = "Found cat videos on YouTube.",
            rawNakedOutput = "done(\"Found cat videos on YouTube.\")",
            actionPayloadJson = AgentAction.ACTION_EXECUTE_TASK,
            actionStatus = "success",
            actionStepsJson = serialized,
            isAgentMode = true
        )

        // Verify entity deserialization for AgentTaskTimelineCard
        val deserializedSteps = entity.getActionSteps()
        assertEquals(3, deserializedSteps.size)
        assertEquals("Opening YouTube", deserializedSteps[0].title)
        assertEquals(AgentStepStatus.COMPLETED, deserializedSteps[0].status)
        assertEquals("Typing 'cats'", deserializedSteps[1].title)
        assertEquals("Submitting search", deserializedSteps[2].title)
    }

    // ---------------------------------------------------------------------------------------------
    // 4. Naked LLM Output Byte-for-Byte Preservation (No Fake Error Text)
    // ---------------------------------------------------------------------------------------------

    @Test
    fun testNakedLlmOutput_preservedByteForByteOnParseFailure() {
        val actualModelResponse = "open_app(\"YouTube\") and then click the search bar"

        // When recorded via LlmInteractionRecord
        val record = LlmInteractionRecord(
            source = InteractionTriggerSource.VOICE_AGENT,
            title = "Voice Agent Mission",
            userPrompt = "open YouTube and search for cats",
            rawOutput = actualModelResponse,
            cleanOutput = "Execution failed: Model returned multi-statement syntax",
            isError = true,
            errorMessage = "Model output could not be parsed: '$actualModelResponse'"
        )

        val dumpTurn = ConversationDumpTurn.fromInteractionRecord(record, index = 1)

        // Crucial verification: The Naked LLM output MUST be the actual model response byte-for-byte!
        assertEquals(actualModelResponse, dumpTurn.rawNakedOutput)
        assertFalse(
            "Must NOT contain fake 'Stopped: AI returned invalid actions repeatedly.'",
            dumpTurn.rawNakedOutput.contains("Stopped: AI returned invalid actions repeatedly.")
        )
        assertTrue(dumpTurn.isActionFailed)
    }
}
