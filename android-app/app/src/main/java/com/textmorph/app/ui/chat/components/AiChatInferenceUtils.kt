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
}
