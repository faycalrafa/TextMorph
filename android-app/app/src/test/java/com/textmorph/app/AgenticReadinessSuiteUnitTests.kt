package com.textmorph.app

import com.textmorph.app.agent.benchmark.AgenticReadinessSuite
import com.textmorph.app.agent.benchmark.ReadinessTier
import com.textmorph.app.llm.LlmEngine
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgenticReadinessSuiteUnitTests {

    @Test
    fun testReadinessTierScoringBands() {
        assertEquals(ReadinessTier.AUTONOMOUS_READY, ReadinessTier.fromScore(100))
        assertEquals(ReadinessTier.AUTONOMOUS_READY, ReadinessTier.fromScore(85))
        assertEquals(ReadinessTier.ASSISTED_AGENT, ReadinessTier.fromScore(84))
        assertEquals(ReadinessTier.ASSISTED_AGENT, ReadinessTier.fromScore(65))
        assertEquals(ReadinessTier.EXPERIMENTAL, ReadinessTier.fromScore(64))
        assertEquals(ReadinessTier.EXPERIMENTAL, ReadinessTier.fromScore(45))
        assertEquals(ReadinessTier.CHAT_ONLY, ReadinessTier.fromScore(44))
        assertEquals(ReadinessTier.CHAT_ONLY, ReadinessTier.fromScore(0))
    }

    @Test
    fun testAutonomousReadyModelExecution() = runBlocking {
        val mockAutonomousEngine = object : LlmEngine {
            override suspend fun initialize(modelPath: String?) = true
            override fun isLoaded() = true
            override suspend fun unload() {}

            override fun generateStream(prompt: String, systemInstruction: String?, imageBytes: ByteArray?, temperature: Float?) =
                listOf("Attention ", "mechanisms ", "compute ", "contextualized ", "token ", "embeddings.").asFlow()

            override suspend fun generate(prompt: String, systemInstruction: String?, imageBytes: ByteArray?, temperature: Float?): String {
                return when {
                    // Scenario 1: Actionable query
                    prompt.contains("quarterly earnings") -> """
                        ```
                        search_web(query="Apple quarterly earnings report")
                        ```
                    """.trimIndent()

                    // Scenario 1: Casual greeting
                    prompt.contains("Hello!") -> "Hello! I am ready to assist you today without unprompted tools."

                    // Scenario 2: Strict syntax pass 1
                    prompt.contains("Android 15 features") -> """
                        ```
                        search_web(query="Android 15 features")
                        ```
                    """.trimIndent()

                    // Scenario 2: Self-correction pass 2
                    prompt.contains("synthesizeCorrectionPrompt") || prompt.contains("failed") || prompt.contains("required parameter") -> """
                        ```
                        search_web(query="Kotlin 2.1 compiler features")
                        ```
                    """.trimIndent()

                    // Scenario 3: Grounded observation
                    prompt.contains("Perseverance rover land") -> """
                        The Perseverance rover landed on Mars on February 18, 2021.
                        ```
                        done(summary="Perseverance landed February 18, 2021")
                        ```
                    """.trimIndent()

                    // Scenario 4: Plan decomposition
                    prompt.contains("Quantum Supremacy") -> """
                        1. Theoretical Overview - Quantum mechanics and superposition principles
                        2. Hardware Implementations - Superconducting and neutral atom architectures
                        3. Error Correction Benchmarks - Fault tolerance and surface code thresholds
                        4. Strategic Outlook - Near-term cryptographic and scientific applications
                    """.trimIndent()

                    else -> "Concise mock response"
                }
            }
        }

        val report = AgenticReadinessSuite.runReadinessSuite(
            engine = mockAutonomousEngine,
            modelName = "gemma-4-E2B-it.litertlm",
            backend = "GPU (Vulkan)",
            onProgress = {}
        )

        assertEquals(5, report.scenarioResults.size)
        assertTrue(report.scenarioResults.all { it.passed })
        assertTrue("Total score should be >= 85, was: ${report.totalScore}", report.totalScore >= 85)
        assertEquals(ReadinessTier.AUTONOMOUS_READY, report.tier)
        assertTrue(report.recommendedConcurrentWorkers in 1..4)
    }

    @Test
    fun testHammerSyndromeDetectionOnGreeting() = runBlocking {
        val mockHammerEngine = object : LlmEngine {
            override suspend fun initialize(modelPath: String?) = true
            override fun isLoaded() = true
            override suspend fun unload() {}

            override fun generateStream(prompt: String, systemInstruction: String?, imageBytes: ByteArray?, temperature: Float?) =
                listOf("Token").asFlow()

            override suspend fun generate(prompt: String, systemInstruction: String?, imageBytes: ByteArray?, temperature: Float?): String {
                // Returns search_web on EVERY prompt, including greetings!
                return """
                    ```
                    search_web(query="unnecessary tool call")
                    ```
                """.trimIndent()
            }
        }

        val report = AgenticReadinessSuite.runReadinessSuite(
            engine = mockHammerEngine,
            modelName = "hallucinatory-model.litertlm",
            backend = "CPU",
            onProgress = {}
        )

        val s1 = report.scenarioResults.first { it.scenarioId == "scenario_1" }
        // Tool picking (+15) but hammer syndrome on greeting (-10) -> score = 15/25, does not pass threshold of 20
        assertFalse(s1.passed)
        assertTrue(s1.details.contains("hammer syndrome"))
    }

    @Test
    fun testSubTestResultsAndLiveProgressCaptured() = runBlocking {
        val recordedSubTestTitles = mutableListOf<String>()
        val mockEngine = object : LlmEngine {
            override suspend fun initialize(modelPath: String?) = true
            override fun isLoaded() = true
            override suspend fun unload() {}
            override fun generateStream(prompt: String, systemInstruction: String?, imageBytes: ByteArray?, temperature: Float?) =
                listOf("Telemetry ", "tokens ").asFlow()
            override suspend fun generate(prompt: String, systemInstruction: String?, imageBytes: ByteArray?, temperature: Float?): String {
                return when {
                    prompt.contains("quarterly earnings") -> "search_web(query=\"Apple\")"
                    prompt.contains("Hello!") -> "Hello there!"
                    prompt.contains("Android 15 features") -> "search_web(query=\"Android 15\")"
                    prompt.contains("prior call failed") -> "search_web(query=\"Android 15 features\")"
                    prompt.contains("Perseverance") -> "February 18, 2021"
                    prompt.contains("Quantum Supremacy") -> "1. Overview: Foundations\n2. Hardware: Qubits\n3. Future: Outlook"
                    else -> "Output"
                }
            }
        }

        val report = AgenticReadinessSuite.runReadinessSuite(
            engine = mockEngine,
            modelName = "test-model",
            backend = "CPU",
            onProgress = { state ->
                if (state.currentSubTestTitle.isNotBlank() && !recordedSubTestTitles.contains(state.currentSubTestTitle)) {
                    recordedSubTestTitles.add(state.currentSubTestTitle)
                }
            }
        )

        // Verify sub-tests were recorded in scenario results
        assertTrue(report.scenarioResults.all { it.subTests.isNotEmpty() })
        val s1 = report.scenarioResults.first { it.scenarioId == "scenario_1" }
        assertEquals(2, s1.subTests.size)
        assertEquals("Sub-Test 1.1: Tool Picking (Actionable Query)", s1.subTests[0].title)
        assertTrue(s1.subTests[0].rawResponse.contains("search_web"))

        // Verify live progress callback reported sub-tests
        assertTrue("Expected live sub-test titles, got: $recordedSubTestTitles", recordedSubTestTitles.isNotEmpty())
    }

    @Test
    fun testEchoedPlaceholdersDisqualifiedInScenario4() = runBlocking {
        val mockEchoEngine = object : LlmEngine {
            override suspend fun initialize(modelPath: String?) = true
            override fun isLoaded() = true
            override suspend fun unload() {}
            override fun generateStream(prompt: String, systemInstruction: String?, imageBytes: ByteArray?, temperature: Float?) =
                listOf("Token").asFlow()
            override suspend fun generate(prompt: String, systemInstruction: String?, imageBytes: ByteArray?, temperature: Float?): String {
                // Echoes literal placeholder template
                return """
                    1. [Section Title] - [Focus]
                    2. [Section Title] - [Focus]
                    3. [Section Title] - [Focus]
                """.trimIndent()
            }
        }

        val report = AgenticReadinessSuite.runReadinessSuite(
            engine = mockEchoEngine,
            modelName = "echo-model",
            backend = "CPU",
            onProgress = {}
        )

        val s4 = report.scenarioResults.first { it.scenarioId == "scenario_4" }
        assertEquals(0, s4.score)
        assertFalse(s4.passed)
        assertTrue(s4.details.contains("template placeholders") || s4.details.contains("Echoed"))
    }

    @Test(expected = IllegalStateException::class)
    fun testUnloadedEngineRejectedWithException() = runBlocking {
        val unloadedEngine = object : LlmEngine {
            override suspend fun initialize(modelPath: String?) = false
            override fun isLoaded() = false
            override suspend fun unload() {}
            override fun generateStream(prompt: String, systemInstruction: String?, imageBytes: ByteArray?, temperature: Float?) = listOf<String>().asFlow()
            override suspend fun generate(prompt: String, systemInstruction: String?, imageBytes: ByteArray?, temperature: Float?) = ""
        }

        AgenticReadinessSuite.runReadinessSuite(
            engine = unloadedEngine,
            modelName = "unloaded-model",
            backend = "None",
            onProgress = {}
        )
        Unit
    }

    @Test
    fun testGemma4NativeToolCallingAchievesAutonomousReady() = runBlocking {
        val gemmaEngine = object : LlmEngine {
            override suspend fun initialize(modelPath: String?) = true
            override fun isLoaded() = true
            override suspend fun unload() {}

            override fun generateStream(prompt: String, systemInstruction: String?, imageBytes: ByteArray?, temperature: Float?) =
                listOf("Attention ", "mechanisms ", "compute ", "contextualized ", "token ", "embeddings.").asFlow()

            override suspend fun generate(prompt: String, systemInstruction: String?, imageBytes: ByteArray?, temperature: Float?): String {
                return when {
                    // Scenario 1: Actionable query (Gemma raw control token)
                    prompt.contains("quarterly earnings") ->
                        "<|tool_call>call:search_web(query=\"latest quarterly earnings report for Apple\")"

                    // Scenario 1: Casual greeting
                    prompt.contains("Hello!") ->
                        "I am doing well, thank you for asking! I am ready to assist you with any tasks you have. How can I help you today?"

                    // Scenario 2: Strict syntax pass 1 (Gemma raw control token)
                    prompt.contains("Android 15 features") ->
                        "<|tool_call>call:search_web(query=\"latest Android 15 features Code-as-Action\")"

                    // Scenario 2: Self-correction recovery
                    prompt.contains("prior call failed") || prompt.contains("Validation Error") || prompt.contains("Missing required parameter") ->
                        "<|tool_call>call:search_web(query=\"latest Android 15 features\")"

                    // Scenario 3: Grounded observation
                    prompt.contains("Perseverance") ->
                        "February 18, 2021"

                    // Scenario 4: Plan decomposition
                    prompt.contains("Quantum Supremacy") -> """
                        1. Literature Review: Establish a comprehensive understanding of quantum supremacy demonstrations.
                        2. Benchmarking: Analyze superconducting and neutral atom architectures.
                        3. Error Correction: Model surface code thresholds and fault tolerance.
                        4. Future Outlook: Assess strategic impact on cryptography.
                    """.trimIndent()

                    else -> "Concise output"
                }
            }
        }

        val report = AgenticReadinessSuite.runReadinessSuite(
            engine = gemmaEngine,
            modelName = "Cloud: gemma-4-E2B-it.litertlm",
            backend = "OpenAI-Compatible Cloud",
            onProgress = {}
        )

        assertEquals(5, report.scenarioResults.size)
        // Verify all 5 scenarios passed
        assertTrue("Expected score >= 85 for Gemma-4 with multi-dialect support, got: ${report.totalScore}", report.totalScore >= 85)
        assertEquals(ReadinessTier.AUTONOMOUS_READY, report.tier)
        assertTrue(report.scenarioResults.first { it.scenarioId == "scenario_1" }.passed)
        assertTrue(report.scenarioResults.first { it.scenarioId == "scenario_2" }.passed)
    }
}
