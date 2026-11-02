package com.textmorph.app.agent.events

import com.textmorph.app.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * High-performance token buffer that coalesces streaming tokens emitted by on-device SLMs.
 * Throttles UI/projection updates to a configured interval (default 50ms) to prevent Jetpack
 * Compose recomposition overload and maintain a fluid 60 FPS rendering pipeline.
 */
class ReactiveTokenBuffer(
    private val flushIntervalMs: Long = 50L,
    private val onFlush: suspend (accumulatedContent: String, accumulatedThought: String?) -> Unit
) {
    private val TAG = "ReactiveTokenBuffer"
    private val mutex = Mutex()
    private val contentBuilder = StringBuilder()
    private val thoughtBuilder = StringBuilder()
    private var scheduledFlushJob: Job? = null

    /**
     * Appends a newly generated token to the buffer.
     */
    fun pushToken(
        token: String,
        isThought: Boolean,
        scope: CoroutineScope
    ) {
        synchronized(this) {
            if (isThought) {
                thoughtBuilder.append(token)
            } else {
                contentBuilder.append(token)
            }

            if (scheduledFlushJob == null || scheduledFlushJob?.isCompleted == true) {
                scheduledFlushJob = scope.launch(Dispatchers.Default) {
                    delay(flushIntervalMs)
                    flushInternal()
                }
            }
        }
    }

    /**
     * Flushes any remaining buffered text immediately (e.g. upon stream completion).
     */
    suspend fun flushRemaining() {
        flushInternal()
    }

    private suspend fun flushInternal() {
        val content: String
        val thought: String?
        synchronized(this) {
            content = contentBuilder.toString()
            thought = if (thoughtBuilder.isNotEmpty()) thoughtBuilder.toString() else null
            scheduledFlushJob = null
        }
        try {
            onFlush(content, thought)
        } catch (t: Throwable) {
            AppLogger.e(TAG, "Error during token buffer flush: ${t.message}", t)
        }
    }

    /**
     * Resets the buffer for a new turn.
     */
    fun reset() {
        synchronized(this) {
            scheduledFlushJob?.cancel()
            scheduledFlushJob = null
            contentBuilder.clear()
            thoughtBuilder.clear()
        }
    }
}
