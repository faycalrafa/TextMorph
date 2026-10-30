package com.textmorph.app

import com.textmorph.app.agent.artifacts.ArtifactAssemblyStore
import com.textmorph.app.agent.artifacts.PlanSection
import com.textmorph.app.agent.artifacts.SectionStatus
import com.textmorph.app.agent.orchestration.BoundedWorkerPool
import com.textmorph.app.agent.orchestration.DeepPlanEngine
import com.textmorph.app.llm.GpuInferenceGate
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ChatHarnessPhaseBUnitTests {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testGpuInferenceGateSerialization() = runBlocking {
        var runningInferences = 0
        var maxObservedConcurrent = 0

        val job1 = async {
            GpuInferenceGate.withLock("Job1") {
                runningInferences++
                if (runningInferences > maxObservedConcurrent) maxObservedConcurrent = runningInferences
                delay(50)
                runningInferences--
            }
        }

        val job2 = async {
            GpuInferenceGate.withLock("Job2") {
                runningInferences++
                if (runningInferences > maxObservedConcurrent) maxObservedConcurrent = runningInferences
                delay(50)
                runningInferences--
            }
        }

        job1.await()
        job2.await()

        assertEquals(1, maxObservedConcurrent)
        assertFalse(GpuInferenceGate.isBusy)
    }

    @Test
    fun testArtifactAssemblyStoreStagingAndQualityGate() = runBlocking {
        val cacheDir = tempFolder.newFolder("cache")
        val sessionId = "test-session-123"
        val store = ArtifactAssemblyStore(cacheDir, sessionId)

        val initialSections = listOf(
            PlanSection(index = 1, title = "Overview", queryOrGoal = "Background info"),
            PlanSection(index = 2, title = "Deep Analysis", queryOrGoal = "Technical facts")
        )

        store.initSession("Quantum Computing Advancements", initialSections)

        val retrieved = store.getAllSections()
        assertEquals(2, retrieved.size)
        assertEquals(SectionStatus.PENDING, retrieved[0].status)

        // Write short stub to section 1
        store.writeSection(1, "TODO: add quantum computing info")
        val stubCheck = store.validateSectionQuality(1)
        assertFalse(stubCheck.isValid)

        // Write quality content
        val solidContent = """
            Quantum computing leverages principles of quantum mechanics including superposition and entanglement.
            Recent advances in trapped-ion and superconducting qubits have demonstrated significant coherence time
            improvements across commercial hardware platforms, pushing fault-tolerant quantum error correction forward.
            These developments represent critical milestones in scaling logical qubit architectures for chemistry simulation.
        """.trimIndent()

        store.writeSection(1, solidContent)
        val solidCheck = store.validateSectionQuality(1)
        assertTrue(solidCheck.isValid)

        // Test append
        store.appendSection(1, "Furthermore, neutral atom architectures show immense promise.")
        val sec1 = store.getSection(1)
        assertNotNull(sec1)
        assertTrue(sec1!!.content.contains("Furthermore, neutral atom architectures"))

        // Complete sections and compile final artifact
        store.updateStatus(1, SectionStatus.DONE)
        store.writeSection(2, "Superconducting qubits from leading vendors operate at near-zero Kelvin temperatures with high fidelity gates.")
        store.updateStatus(2, SectionStatus.DONE)

        assertTrue(store.areAllSectionsCompleted())

        val compiled = store.compileFinalArtifact()
        assertTrue(compiled.contains("# Quantum Computing Advancements"))
        assertTrue(compiled.contains("Table of Contents"))
        assertTrue(compiled.contains("## 1. Overview"))
        assertTrue(compiled.contains("## 2. Deep Analysis"))
    }

    @Test
    fun testDeepPlanEngineParsing() {
        // Mock engine is not needed for pure parsing tests
        val engine = object : com.textmorph.app.llm.LlmEngine {
            override suspend fun initialize(modelPath: String?) = true
            override fun generateStream(prompt: String, systemInstruction: String?, imageBytes: ByteArray?) = kotlinx.coroutines.flow.emptyFlow<String>()
            override suspend fun generate(prompt: String, systemInstruction: String?, imageBytes: ByteArray?) = ""
            override fun isLoaded() = true
            override suspend fun unload() {}
        }

        val planEngine = DeepPlanEngine(
            context = null,
            engine = engine
        )

        val rawLlmPlan = """
            Here is the requested plan:
            1. Core Architecture - Overview of system blocks and components
            2. Memory Constraints - Detailed analysis of RAM usage and KV cache limits
            3. Thread Serialization - Examination of Mutex and Semaphore interaction
            4. Practical Benchmarks - Comparative metrics and speed evaluations
        """.trimIndent()

        val parsed = planEngine.parsePlanSections(rawLlmPlan, "On-device Agent Harness")
        assertEquals(4, parsed.size)
        assertEquals("Core Architecture", parsed[0].title)
        assertEquals("Memory Constraints", parsed[1].title)
        assertEquals("Thread Serialization", parsed[2].title)
        assertEquals("Practical Benchmarks", parsed[3].title)

        // Test default fallback
        val fallback = planEngine.generateDefaultSections("Mobile AI Engine")
        assertEquals(4, fallback.size)
        assertEquals(1, fallback[0].index)
        assertEquals(4, fallback[3].index)
    }

    @Test
    fun testBoundedWorkerPoolClamping() {
        val poolNegative = BoundedWorkerPool(0)
        assertEquals(1, poolNegative.maxConcurrentWorkers)

        val poolNormal = BoundedWorkerPool(2)
        assertEquals(2, poolNormal.maxConcurrentWorkers)

        val poolLarge = BoundedWorkerPool(10)
        assertEquals(4, poolLarge.maxConcurrentWorkers)
    }
}
