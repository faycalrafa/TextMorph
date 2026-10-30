package com.textmorph.app.llm

import com.textmorph.app.util.AppLogger
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicInteger

/**
 * GpuInferenceGate provides two-tier hardware safety for on-device SLMs.
 * While BoundedWorkerPool's Semaphore limits the number of concurrently open
 * Conversation KV-caches, GpuInferenceGate ensures that only ONE worker executes
 * native forward-passes (GPU/CPU inference) at a time, preventing Vulkan/OpenCL driver
 * crashes and VRAM collision.
 *
 * Workers can perform non-LLM tasks (Jsoup web scraping, network I/O, file operations)
 * concurrently on Dispatchers.IO while another worker holds the GPU inference lock.
 */
object GpuInferenceGate {

    private const val TAG = "GpuInferenceGate"
    private val inferenceMutex = Mutex()

    private val activeCount = AtomicInteger(0)
    private val queuedCount = AtomicInteger(0)

    val isBusy: Boolean
        get() = activeCount.get() > 0

    val waitingWorkersCount: Int
        get() = queuedCount.get()

    /**
     * Executes the given LLM inference block with exclusive access to the GPU/inference engine.
     */
    suspend fun <T> withLock(callerTag: String = "Worker", block: suspend () -> T): T {
        queuedCount.incrementAndGet()
        val waitStartTime = System.currentTimeMillis()
        AppLogger.d(TAG, "[$callerTag] Waiting for GPU inference gate. (Queued: ${queuedCount.get()})")

        return try {
            inferenceMutex.withLock {
                val waitDuration = System.currentTimeMillis() - waitStartTime
                queuedCount.decrementAndGet()
                activeCount.incrementAndGet()
                AppLogger.d(TAG, "[$callerTag] GPU inference lock acquired after ${waitDuration}ms")

                val executionStartTime = System.currentTimeMillis()
                try {
                    block()
                } finally {
                    val executionDuration = System.currentTimeMillis() - executionStartTime
                    activeCount.decrementAndGet()
                    AppLogger.d(TAG, "[$callerTag] Released GPU inference lock after ${executionDuration}ms")
                }
            }
        } catch (t: Throwable) {
            AppLogger.e(TAG, "[$callerTag] Error during guarded inference execution: ${t.message}", t)
            throw t
        }
    }
}
