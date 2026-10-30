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

            override fun generateStream(prompt: String, systemInstruction: String?, imageBytes: ByteArray?) =
                listOf("Attention ", "mechanisms ", "compute ", "contextualized ", "token ", "embeddings.").asFlow()

            override suspend fun generate(prompt: String, systemInstruction: String?, imageBytes: ByteArray?): String {
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

            override fun generateStream(prompt: String, systemInstruction: String?, imageBytes: ByteArray?) =
                listOf("Token").asFlow()

            override suspend fun generate(prompt: String, systemInstruction: String?, imageBytes: ByteArray?): String {
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
}
