package com.textmorph.app.ui.chat.components

import android.content.Context
import com.textmorph.app.data.model.InteractionTriggerSource
import com.textmorph.app.data.repository.ChatSessionRepository
import com.textmorph.app.data.repository.LlmInteractionRepository
import com.textmorph.app.llm.LiteRtLmEngine
import com.textmorph.app.llm.LlamaCppEngine
import com.textmorph.app.llm.LlmEngine
import com.textmorph.app.llm.OpenAiCloudEngine
import com.textmorph.app.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * Utility functions for thought extraction, engine streaming delegation,
 * and multi-step intent detection for chat & agent inference.
 */
object AiChatInferenceUtils {

    fun isMultiStepIntent(userPrompt: String, modelResponse: String = ""): Boolean {
        val lowerPrompt = userPrompt.lowercase()
        val lowerResponse = modelResponse.lowercase()
        val conjunctions = listOf(" and ", " then ", " to ", " so that ", " after ")
        val actionVerbs = listOf(
            "search", "find", "look for", "play", "send", "type", "check",
            "see ", "turn on", "turn off", "toggle", "browse", "select", "click", "watch", "listen"
        )
        val hasConjunction = conjunctions.any { lowerPrompt.contains(it) }
        val hasActionVerb = actionVerbs.any { lowerPrompt.contains(it) }
        val responseHasIntent = listOf(
            "while i search", "while i find", "while i check", "searching for",
            "will search", "will find", "will check", "looking for"
        ).any { lowerResponse.contains(it) }
        return (hasConjunction && hasActionVerb) || responseHasIntent
    }

    fun extractThought(rawText: String): Pair<String?, String> {
        val (openTag, closeTag) = when {
            rawText.contains("<think>") -> "<think>" to "</think>"
            rawText.contains("<thought>") -> "<thought>" to "</thought>"
            else -> return null to rawText
        }
        val start = rawText.indexOf(openTag)
        val end = rawText.indexOf(closeTag)
        return if (end != -1) {
            val thought = rawText.substring(start + openTag.length, end).trim()
            val pre = rawText.substring(0, start).trim()
            val post = rawText.substring(end + closeTag.length).trim()
            thought to listOf(pre, post).filter { it.isNotEmpty() }.joinToString("\n\n")
        } else {
            rawText.substring(start + openTag.length).trim() to rawText.substring(0, start).trim()
        }
    }

    fun streamFromEngine(
        engine: LlmEngine,
        prompt: String,
        imageBytes: ByteArray? = null,
        temperature: Float? = null,
        onMetrics: ((com.textmorph.app.llm.InferenceMetrics) -> Unit)? = null
    ): Flow<String> = when (engine) {
        is LiteRtLmEngine -> engine.generateStreamWithMetrics(prompt = prompt, imageBytes = imageBytes, temperature = temperature, onMetricsUpdated = { onMetrics?.invoke(it) })
        is LlamaCppEngine -> engine.generateStreamWithMetrics(prompt = prompt, imageBytes = imageBytes, temperature = temperature, onMetricsUpdated = { onMetrics?.invoke(it) })
        is OpenAiCloudEngine -> engine.generateStreamWithMetrics(prompt = prompt, imageBytes = imageBytes, temperature = temperature, onMetricsUpdated = { onMetrics?.invoke(it) })
        else -> engine.generateStream(prompt = prompt, imageBytes = imageBytes, temperature = temperature)
    }

    fun extractSourcesFromToolOutput(
        action: com.textmorph.app.agent.model.AgentAction,
        toolOutput: String,
        targetList: MutableList<com.textmorph.app.data.model.SourceCitation>
    ) {
        val linkRegex = Regex("""\[(.*?)\]\((https?://[^\s)]+)\)""")
        val urlRegex = Regex("""https?://[^\s)\]]+""")

        for (m in linkRegex.findAll(toolOutput)) {
            val title = m.groupValues[1].trim()
            val url = m.groupValues[2].trim()
            if (targetList.none { it.url == url }) {
                targetList.add(
                    com.textmorph.app.data.model.SourceCitation(
                        title = title.ifBlank { url },
                        url = url,
                        snippet = toolOutput.take(120),
                        sourceIndex = targetList.size + 1
                    )
                )
            }
        }

        if (targetList.isEmpty()) {
            for (m in urlRegex.findAll(toolOutput)) {
                val url = m.value.trim()
                if (targetList.none { it.url == url }) {
                    targetList.add(
                        com.textmorph.app.data.model.SourceCitation(
                            title = url,
                            url = url,
                            snippet = toolOutput.take(120),
                            sourceIndex = targetList.size + 1
                        )
                    )
                }
            }
        }
    }

    fun createStepTrace(
        stepIndex: Int,
        action: com.textmorph.app.agent.model.AgentAction,
        stepResponse: String? = null,
        thought: String? = null
    ): com.textmorph.app.data.model.AgentStepTrace {
        val summary = action.params.entries.joinToString(", ") { "${it.key}=${it.value.toString().removeSurrounding("\"").take(40)}" }
        val effectiveResponse = stepResponse?.takeIf { it.isNotBlank() }
            ?: action.reasoning?.takeIf { it.isNotBlank() }
            ?: action.response.takeIf { it.isNotBlank() }

        return com.textmorph.app.data.model.AgentStepTrace(
            stepIndex = stepIndex,
            toolName = action.action,
            inputQuery = summary,
            status = "running",
            startTimeMs = System.currentTimeMillis(),
            stepResponse = effectiveResponse,
            thought = thought
        )
    }

    fun completeStepTrace(trace: com.textmorph.app.data.model.AgentStepTrace, output: String) {
        trace.status = "success"
        trace.outputSnippet = output.take(150)
        trace.durationMs = System.currentTimeMillis() - trace.startTimeMs
    }

    fun failStepTrace(trace: com.textmorph.app.data.model.AgentStepTrace, reason: String) {
        trace.status = "failed"
        trace.failureReason = reason
        trace.durationMs = System.currentTimeMillis() - trace.startTimeMs
    }

    fun interruptStepTraces(traces: List<com.textmorph.app.data.model.AgentStepTrace>) {
        for (trace in traces) {
            if (trace.status == "running") {
                trace.status = "cancelled"
                trace.failureReason = "Stopped by user"
                trace.durationMs = System.currentTimeMillis() - trace.startTimeMs
            }
        }
    }

    /**
     * Determines if a parsed action is a presentation request or embedded inside follow-up
     * suggestions, meaning it should not be executed automatically in the inference loop.
     */
    fun isPresentationOrFollowUpAction(content: String, action: com.textmorph.app.agent.model.AgentAction): Boolean {
        if (action.action == com.textmorph.app.agent.model.AgentAction.ACTION_SUGGEST_NEXT_ACTIONS ||
            action.action == com.textmorph.app.agent.model.AgentAction.ACTION_REQUEST_USER_APPROVAL ||
            action.action == com.textmorph.app.agent.model.AgentAction.ACTION_PRESENT_INTERACTIVE_VIEW) {
            return true
        }

        val followUps = ChatToolCallExtractor.extractFollowUps(content)
        if (followUps != null) {
            val remainingAction = com.textmorph.app.agent.parser.CodeActionParser.parse(followUps.remainingContent)
            if (remainingAction == null ||
                remainingAction.action == com.textmorph.app.agent.model.AgentAction.ACTION_SUGGEST_NEXT_ACTIONS ||
                remainingAction.action == com.textmorph.app.agent.model.AgentAction.ACTION_REQUEST_USER_APPROVAL ||
                remainingAction.action == com.textmorph.app.agent.model.AgentAction.ACTION_PRESENT_INTERACTIVE_VIEW) {
                return true
            }
        }

        return false
    }

    fun assembleNextHopPrompt(
        systemPrompt: String,
        activeSession: com.textmorph.app.data.model.ChatSessionEntity?,
        priorMessages: List<com.textmorph.app.data.model.ChatMessageEntity>,
        turnHistory: MutableList<com.textmorph.app.data.model.ChatMessageEntity>,
        sessionId: String,
        effectiveContent: String,
        nextPrompt: String,
        maxContextTokens: Int
    ): String {
        turnHistory.add(com.textmorph.app.data.model.ChatMessageEntity(sessionId = sessionId, role = "assistant", content = effectiveContent))
        return com.textmorph.app.llm.context.ChatCompactor.assembleInferencePrompt(
            systemPrompt = systemPrompt,
            sessionSummary = activeSession?.summary,
            sessionState = activeSession?.sessionState,
            historyMessages = priorMessages + turnHistory,
            currentPrompt = nextPrompt,
            documentText = null,
            maxContextTokens = maxContextTokens
        )
    }

    fun calculateTps(tokens: Int, durationMs: Long): Float {
        return if (tokens > 0 && durationMs > 0) (tokens / (durationMs / 1000f)).coerceAtLeast(0.1f) else 0f
    }

    fun recordInteractionAndCompact(
        context: Context?,
        prompt: String,
        systemPrompt: String,
        finalOutput: String,
        effectiveFinalContent: String,
        thought: String?,
        finalTps: Float,
        finalTokens: Int,
        finalDuration: Long,
        chatRepository: ChatSessionRepository,
        sessionId: String,
        engine: LlmEngine,
        activeSession: com.textmorph.app.data.model.ChatSessionEntity?
    ) {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            if (context != null) {
                try {
                    LlmInteractionRepository.getInstance(context).recordInteraction(
                        source = InteractionTriggerSource.CHAT_CONVERSATION,
                        title = "Chat Turn",
                        userPrompt = prompt,
                        systemPrompt = systemPrompt,
                        rawOutput = finalOutput,
                        cleanOutput = effectiveFinalContent,
                        thoughtContent = thought,
                        tokensPerSecond = finalTps,
                        totalTokens = finalTokens,
                        durationMs = finalDuration
                    )
                } catch (logEx: Exception) {
                    AppLogger.w("AiChatInferenceUtils", "Failed logging chat turn interaction: ${logEx.message}")
                }
            }

            // Rolling summarization
            val allMessages = chatRepository.currentMessages.value
            if (allMessages.size >= com.textmorph.app.llm.context.ChatCompactor.SUMMARY_TRIGGER_MESSAGE_COUNT) {
                try {
                    val (older, _) = com.textmorph.app.llm.context.ChatCompactor.partitionWindow(allMessages)
                    if (older.isNotEmpty()) {
                        val newSummary = com.textmorph.app.llm.context.ChatCompactor.summarizeTurns(engine, activeSession?.summary, older)
                        val updatedFacts = com.textmorph.app.llm.context.ChatCompactor.extractSessionFacts(engine, activeSession?.sessionState, allMessages)
                        chatRepository.updateSessionCompactionState(sessionId, newSummary, updatedFacts)
                    }
                } catch (t: Throwable) {
                    AppLogger.e("AiChatInferenceUtils", "Background compaction failed: ${t.message}", t)
                }
            }
        }
    }
}
