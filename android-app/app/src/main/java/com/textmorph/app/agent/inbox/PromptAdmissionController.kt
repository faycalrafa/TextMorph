package com.textmorph.app.agent.inbox

import android.content.Context
import com.textmorph.app.agent.events.AgentEventStore
import com.textmorph.app.agent.events.PromptAdmittedEvent
import com.textmorph.app.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * High-level controller responsible for prompt ingestion, steering validation,
 * and decoupling user submissions from asynchronous execution loops.
 */
class PromptAdmissionController private constructor(private val context: Context) {

    private val TAG = "PromptAdmissionCtrl"
    private val inboxRepo = SessionInboxRepository.getInstance(context)
    private val eventStore = AgentEventStore.getInstance(context)
    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        @Volatile
        private var instance: PromptAdmissionController? = null

        fun getInstance(context: Context): PromptAdmissionController {
            return instance ?: synchronized(this) {
                instance ?: PromptAdmissionController(context).also { instance = it }
            }
        }
    }

    /**
     * Submits a prompt or steering directive into the agentic engine.
     */
    suspend fun submitPrompt(
        sessionId: String,
        prompt: String,
        mode: AdmissionMode = AdmissionMode.QUEUE,
        attachmentsJson: String = "[]"
    ): InboxItem? = withContext(Dispatchers.IO) {
        try {
            AppLogger.i(TAG, "Admitting prompt for session $sessionId [Mode=$mode]: '${prompt.take(40)}...'")

            val item = inboxRepo.enqueue(
                sessionId = sessionId,
                prompt = prompt,
                mode = mode,
                attachmentsJson = attachmentsJson
            ) ?: return@withContext null

            // Record PromptAdmittedEvent in append-only event store
            val admittedEvent = PromptAdmittedEvent(
                promptId = item.inboxId.toString(),
                sessionId = sessionId,
                prompt = prompt,
                admissionMode = mode.name,
                attachmentsJson = attachmentsJson
            )
            eventStore.appendEvent(
                sessionId = sessionId,
                eventType = admittedEvent.eventType,
                payloadJson = json.encodeToString(admittedEvent)
            )

            item
        } catch (t: Throwable) {
            AppLogger.e(TAG, "Error admitting prompt for session $sessionId: ${t.message}", t)
            null
        }
    }

    /**
     * Inspects whether an active steering instruction has arrived for a running step.
     */
    suspend fun peekSteerDirective(sessionId: String): InboxItem? {
        return inboxRepo.peekActiveSteer(sessionId)
    }

    /**
     * Fetches the next pending item (STEER first, then QUEUE).
     */
    suspend fun peekNextPending(sessionId: String): InboxItem? {
        return inboxRepo.peekNextPending(sessionId)
    }

    /**
     * Marks an inbox item as consumed once processed.
     */
    suspend fun markConsumed(inboxId: Long): Boolean {
        return inboxRepo.markConsumed(inboxId)
    }

    /**
     * Cancels any pending prompts for a session.
     */
    suspend fun cancelPending(sessionId: String): Int {
        return inboxRepo.cancelPending(sessionId)
    }
}
