package com.textmorph.app.agent.router

import com.textmorph.app.util.AppLogger
import java.util.Locale

/**
 * Execution phases for phase-aware context & tool schema curation.
 */
enum class AgentPhase {
    PHASE_CHAT,       // Pure conversation (0 tools injected)
    PHASE_RESEARCH,   // Web search & page fetch
    PHASE_PLANNING,   // Task decomposition & approval
    PHASE_AUTOMATION, // Device control & screen automation
    PHASE_SYNTHESIS   // Artifact generation & section writing
}

/**
 * Result of routing a user message through the pre-inference command parser.
 */
sealed class CommandRouteResult {
    data class PlanCommand(
        val goal: String,
        val workersOverride: Int? = null
    ) : CommandRouteResult()

    data class DeepSearchCommand(
        val query: String,
        val workersOverride: Int? = null
    ) : CommandRouteResult()

    data class SetWorkersCommand(
        val workerCount: Int,
        val message: String
    ) : CommandRouteResult()

    object BenchmarkCommand : CommandRouteResult()

    data class StandardTurn(
        val cleanedPrompt: String,
        val phase: AgentPhase
    ) : CommandRouteResult()
}

/**
 * Deterministic command router and pre-inference intent classifier.
 * Intercepts slash commands (/plan, /deepsearch, /workers, /benchmark) in Kotlin
 * and provides a fast-exit for casual chat to eliminate tool schema context bloat on 1B–2B models.
 */
object ChatCommandRouter {

    private const val TAG = "ChatCommandRouter"

    private val CASUAL_GREETINGS = setOf(
        "hi", "hello", "hey", "howdy", "greetings", "good morning",
        "good afternoon", "good evening", "good night", "thanks",
        "thank you", "thx", "bye", "goodbye", "who are you",
        "what can you do", "help", "what's up", "whats up", "sup"
    )

    private val RESEARCH_KEYWORDS = listOf(
        "search", "find", "google", "look up", "browse", "website",
        "latest", "news", "current", "weather", "documentation",
        "price of", "who won", "article", "read page"
    )

    private val AUTOMATION_KEYWORDS = listOf(
        "open ", "launch ", "click ", "tap ", "type ", "scroll ",
        "call ", "send sms", "send message", "set alarm", "set timer",
        "volume to", "brightness to", "go home", "press back"
    )

    /**
     * Parses the incoming raw user text and determines routing before LLM invocation.
     */
    fun route(rawText: String): CommandRouteResult {
        val trimmed = rawText.trim()
        if (trimmed.isBlank()) {
            return CommandRouteResult.StandardTurn("", AgentPhase.PHASE_CHAT)
        }

        // 1. Slash command detection
        if (trimmed.startsWith("/")) {
            val parts = trimmed.split(Regex("\\s+"), limit = 2)
            val commandToken = parts[0].lowercase(Locale.ROOT)
            val remainder = if (parts.size > 1) parts[1].trim() else ""

            when (commandToken) {
                "/benchmark" -> {
                    AppLogger.i(TAG, "Routed to /benchmark")
                    return CommandRouteResult.BenchmarkCommand
                }

                "/workers" -> {
                    val count = remainder.toIntOrNull() ?: 2
                    val clamped = count.coerceIn(1, 4)
                    val warn = if (count > 2) " (Note: Setting workers > 2 may cause thermal throttling on mobile devices)" else ""
                    val msg = "Max concurrent workers set to $clamped.$warn"
                    AppLogger.i(TAG, "Routed /workers: $msg")
                    return CommandRouteResult.SetWorkersCommand(clamped, msg)
                }

                "/plan" -> {
                    val (workers, cleanTask) = extractWorkerFlags(remainder)
                    AppLogger.i(TAG, "Routed /plan: task='$cleanTask', workers=$workers")
                    return CommandRouteResult.PlanCommand(cleanTask, workers)
                }

                "/deepsearch" -> {
                    val (workers, cleanQuery) = extractWorkerFlags(remainder)
                    AppLogger.i(TAG, "Routed /deepsearch: query='$cleanQuery', workers=$workers")
                    return CommandRouteResult.DeepSearchCommand(cleanQuery, workers)
                }
            }
        }

        // 2. Casual chit-chat fast exit (0 tools injected)
        val normalized = trimmed.lowercase(Locale.ROOT).trimEnd('.', '!', '?', ',')
        val isCasual = CASUAL_GREETINGS.contains(normalized) ||
            ((normalized.startsWith("hello") || normalized.startsWith("hi ") || normalized.startsWith("hey ")) &&
             !RESEARCH_KEYWORDS.any { normalized.contains(it) } &&
             !AUTOMATION_KEYWORDS.any { normalized.contains(it) })

        if (isCasual) {
            AppLogger.d(TAG, "Casual greeting detected: '$normalized' -> PHASE_CHAT (0 tools)")
            return CommandRouteResult.StandardTurn(trimmed, AgentPhase.PHASE_CHAT)
        }

        // 3. Phase classification for standard prompt
        val phase = classifyPhase(trimmed)
        AppLogger.d(TAG, "Classified turn as $phase for prompt: '${trimmed.take(40)}…'")
        return CommandRouteResult.StandardTurn(trimmed, phase)
    }

    private fun extractWorkerFlags(text: String): Pair<Int?, String> {
        var clean = text
        var workers: Int? = null

        // Match --workers=N or -w N or -w=N
        val longRegex = Regex("""--workers=(\d+)""")
        val shortRegex = Regex("""-w\s*=?\s*(\d+)""")

        longRegex.find(clean)?.let { match ->
            workers = match.groupValues[1].toIntOrNull()?.coerceIn(1, 4)
            clean = clean.replace(match.value, "").trim()
        }

        if (workers == null) {
            shortRegex.find(clean)?.let { match ->
                workers = match.groupValues[1].toIntOrNull()?.coerceIn(1, 4)
                clean = clean.replace(match.value, "").trim()
            }
        }

        return Pair(workers, clean.replace(Regex("""\s+"""), " ").trim())
    }

    fun classifyPhase(text: String): AgentPhase {
        val lower = text.lowercase(Locale.ROOT)

        for (kw in AUTOMATION_KEYWORDS) {
            if (lower.startsWith(kw) || lower.contains(" $kw")) {
                return AgentPhase.PHASE_AUTOMATION
            }
        }

        for (kw in RESEARCH_KEYWORDS) {
            if (lower.contains(kw)) {
                return AgentPhase.PHASE_RESEARCH
            }
        }

        if (lower.contains("plan ") || lower.contains("steps to ") || lower.contains("how should i ")) {
            return AgentPhase.PHASE_PLANNING
        }

        return AgentPhase.PHASE_RESEARCH // Default to research capabilities if not pure chat
    }
}
