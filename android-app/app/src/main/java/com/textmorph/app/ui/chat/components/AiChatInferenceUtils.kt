package com.textmorph.app.ui.chat.components

import com.textmorph.app.llm.LiteRtLmEngine
import com.textmorph.app.llm.LlamaCppEngine
import com.textmorph.app.llm.LlmEngine
import com.textmorph.app.llm.OpenAiCloudEngine
import kotlinx.coroutines.flow.Flow

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
        onMetrics: ((com.textmorph.app.llm.InferenceMetrics) -> Unit)? = null
    ): Flow<String> = when (engine) {
        is LiteRtLmEngine -> engine.generateStreamWithMetrics(prompt = prompt, imageBytes = imageBytes, onMetricsUpdated = { onMetrics?.invoke(it) })
        is LlamaCppEngine -> engine.generateStreamWithMetrics(prompt = prompt, imageBytes = imageBytes, onMetricsUpdated = { onMetrics?.invoke(it) })
        is OpenAiCloudEngine -> engine.generateStreamWithMetrics(prompt = prompt, imageBytes = imageBytes, onMetricsUpdated = { onMetrics?.invoke(it) })
        else -> engine.generateStream(prompt = prompt, imageBytes = imageBytes)
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

    fun createStepTrace(stepIndex: Int, action: com.textmorph.app.agent.model.AgentAction): com.textmorph.app.data.model.AgentStepTrace {
        val summary = action.params.entries.joinToString(", ") { "${it.key}=${it.value.toString().removeSurrounding("\"").take(40)}" }
        return com.textmorph.app.data.model.AgentStepTrace(
            stepIndex = stepIndex,
            toolName = action.action,
            inputQuery = summary,
            status = "running",
            startTimeMs = System.currentTimeMillis()
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
}
