package com.textmorph.app.llm.context

import com.textmorph.app.data.model.ChatMessageEntity
import com.textmorph.app.llm.LlmEngine
import com.textmorph.app.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext

/**
 * Manages sliding-window conversation pruning, rolling summarization,
 * and semantic session state extraction to maximize context window efficiency.
 */
object ChatCompactor {

    private const val TAG = "ChatCompactor"
    const val DEFAULT_SLIDING_WINDOW_TURNS = 4 // Last 4 turns (approx 8 messages)
    const val SUMMARY_TRIGGER_MESSAGE_COUNT = 10

    val DEFAULT_SYSTEM_PROMPT = com.textmorph.app.agent.prompts.AgentPrompts.CHAT_SYSTEM_PROMPT
    val AGENT_SYSTEM_PROMPT = com.textmorph.app.agent.prompts.AgentPrompts.AGENT_SYSTEM_PROMPT

    /**
     * Splits a list of messages into older messages (eligible for summarization)
     * and recent window messages (retained verbatim with pruned payloads).
     */
    fun partitionWindow(
        messages: List<ChatMessageEntity>,
        recentTurnCount: Int = DEFAULT_SLIDING_WINDOW_TURNS
    ): Pair<List<ChatMessageEntity>, List<ChatMessageEntity>> {
        val nonSystemMessages = messages.filter { it.role != "system" }
        val recentMessageThreshold = recentTurnCount * 2

        if (nonSystemMessages.size <= recentMessageThreshold) {
            return emptyList<ChatMessageEntity>() to nonSystemMessages
        }

        val older = nonSystemMessages.dropLast(recentMessageThreshold)
        val recent = nonSystemMessages.takeLast(recentMessageThreshold)
        return older to recent
    }

    /**
     * Assembles the complete conversational prompt for the LLM inference engine,
     * seamlessly integrating session state, compressed history, and recent sliding window.
     */
    fun assembleInferencePrompt(
        systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
        sessionSummary: String? = null,
        sessionState: String? = null,
        historyMessages: List<ChatMessageEntity>,
        currentPrompt: String,
        documentText: String? = null,
        recentTurnCount: Int = DEFAULT_SLIDING_WINDOW_TURNS
    ): String {
        val sb = StringBuilder()

        // 1. System Prompt & Active Session State
        sb.append("<|im_start|>system\n")
        sb.append(systemPrompt.trim())
        if (!sessionState.isNullOrBlank()) {
            sb.append("\n\n[Active Session Memory & Constraints]:\n").append(sessionState.trim())
        }
        sb.append("\n<|im_end|>\n")

        // 2. Rolling Compressed History Summary (if available)
        if (!sessionSummary.isNullOrBlank()) {
            sb.append("<|im_start|>system\n")
            sb.append("[Summary of Previous Conversation]:\n")
            sb.append(sessionSummary.trim())
            sb.append("\n<|im_end|>\n")
        }

        // 3. Sliding Window of Recent Messages (Payloads Pruned)
        val (_, recent) = partitionWindow(historyMessages, recentTurnCount)
        for (msg in recent) {
            val role = if (msg.role == "action") "assistant" else msg.role
            val prunedContent = ContextPruner.pruneForInferenceContext(msg)
            if (prunedContent.isNotBlank()) {
                sb.append("<|im_start|>").append(role).append("\n")
                sb.append(prunedContent)
                sb.append("\n<|im_end|>\n")
            }
        }

        // 4. Current User Turn (with Document Context if attached)
        sb.append("<|im_start|>user\n")
        if (!documentText.isNullOrBlank()) {
            sb.append("[Attached Document Context]:\n")
            sb.append(documentText.trim())
            sb.append("\n\n")
        }
        sb.append(currentPrompt.trim())
        sb.append("\n<|im_end|>\n")

        // 5. Assistant Generation Trigger
        sb.append("<|im_start|>assistant\n")

        return sb.toString()
    }

    /**
     * Summarizes older conversational turns into a concise bullet-point summary.
     */
    suspend fun summarizeTurns(
        engine: LlmEngine,
        existingSummary: String?,
        olderMessages: List<ChatMessageEntity>
    ): String = withContext(Dispatchers.IO) {
        if (olderMessages.isEmpty()) return@withContext existingSummary ?: ""

        try {
            val transcript = olderMessages.joinToString("\n") { msg ->
                val pruned = ContextPruner.pruneForInferenceContext(msg)
                "${msg.role.uppercase()}: $pruned"
            }

            val summaryPrompt = buildString {
                append("Summarize the following conversation history into 3 to 5 concise bullet points capturing key facts, user requests, and assistant outcomes.\n")
                if (!existingSummary.isNullOrBlank()) {
                    append("Existing Prior Summary:\n").append(existingSummary).append("\n\n")
                }
                append("Conversation to Summarize:\n")
                append(transcript)
                append("\n\nConcise Bullet Summary:")
            }

            val tokens = engine.generateStream(summaryPrompt).toList()
            val newSummary = tokens.joinToString("").trim()
            if (newSummary.isNotBlank()) {
                AppLogger.i(TAG, "Generated compacted history summary (${newSummary.length} chars)")
                return@withContext newSummary
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed LLM compaction summary: ${e.message}", e)
        }

        // Deterministic fallback summary if LLM summarization fails or is busy
        buildFallbackSummary(existingSummary, olderMessages)
    }

    /**
     * Extracts active task constraints or user facts for the scratchpad memory block.
     */
    suspend fun extractSessionFacts(
        engine: LlmEngine,
        existingFacts: String?,
        recentMessages: List<ChatMessageEntity>
    ): String? = withContext(Dispatchers.IO) {
        if (recentMessages.isEmpty()) return@withContext existingFacts

        try {
            val lastUserTurn = recentMessages.lastOrNull { it.role == "user" }?.content ?: return@withContext existingFacts
            val prompt = "Extract any specific user preferences, persistent instructions, or ongoing task constraints from this request in 1-2 short lines, or reply NONE:\n\"$lastUserTurn\""

            val response = engine.generateStream(prompt).toList().joinToString("").trim()
            if (response.isNotBlank() && !response.equals("NONE", ignoreCase = true) && !response.contains("None", ignoreCase = true)) {
                val combined = if (!existingFacts.isNullOrBlank()) {
                    "$existingFacts\n- $response"
                } else {
                    "- $response"
                }
                AppLogger.d(TAG, "Updated session scratchpad facts: $combined")
                return@withContext combined
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error extracting session facts: ${e.message}", e)
        }
        return@withContext existingFacts
    }

    private fun buildFallbackSummary(
        existingSummary: String?,
        messages: List<ChatMessageEntity>
    ): String {
        val sb = StringBuilder()
        if (!existingSummary.isNullOrBlank()) {
            sb.append(existingSummary.trim()).append("\n")
        }
        val userRequests = messages.filter { it.role == "user" }.map { it.content.trim().take(60) }
        for (req in userRequests) {
            sb.append("• User inquired about: ").append(req).append("\n")
        }
        return sb.toString().trim()
    }
}
