package com.textmorph.app.agent.router

import com.textmorph.app.agent.model.AgentAction
import com.textmorph.app.agent.tools.js.JsToolRepository
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
        val workersOverride: Int? = null,
        val isExecute: Boolean = false,
        val isEdit: Boolean = false,
        val editFeedback: String? = null
    ) : CommandRouteResult()

    data class DeepSearchCommand(
        val query: String,
        val workersOverride: Int? = null,
        val isExecute: Boolean = false,
        val isEdit: Boolean = false,
        val editFeedback: String? = null
    ) : CommandRouteResult()

    data class GoalCommand(
        val goal: String
    ) : CommandRouteResult()

    data class SetWorkersCommand(
        val workerCount: Int,
        val message: String
    ) : CommandRouteResult()

    object BenchmarkCommand : CommandRouteResult()

    data class StandardTurn(
        val cleanedPrompt: String,
        val phase: AgentPhase,
        val forcedTool: String? = null
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

            val isQuestionAboutCommand = remainder.endsWith("?") ||
                remainder.startsWith("how ", ignoreCase = true) ||
                remainder.startsWith("what ", ignoreCase = true) ||
                remainder.startsWith("explain ", ignoreCase = true) ||
                remainder.startsWith("why ", ignoreCase = true) ||
                remainder.startsWith("can you ", ignoreCase = true)

            if (!isQuestionAboutCommand) {
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
                        val flags = extractPlanCommandFlags(remainder)
                        AppLogger.i(TAG, "Routed /plan: goal='${flags.goal}', workers=${flags.workers}, isExec=${flags.isExecute}, isEdit=${flags.isEdit}")
                        return CommandRouteResult.PlanCommand(
                            goal = flags.goal,
                            workersOverride = flags.workers,
                            isExecute = flags.isExecute,
                            isEdit = flags.isEdit,
                            editFeedback = flags.editFeedback
                        )
                    }

                    "/deepsearch" -> {
                        val flags = extractPlanCommandFlags(remainder)
                        AppLogger.i(TAG, "Routed /deepsearch: query='${flags.goal}', workers=${flags.workers}, isExec=${flags.isExecute}, isEdit=${flags.isEdit}")
                        return CommandRouteResult.DeepSearchCommand(
                            query = flags.goal,
                            workersOverride = flags.workers,
                            isExecute = flags.isExecute,
                            isEdit = flags.isEdit,
                            editFeedback = flags.editFeedback
                        )
                    }

                    "/goal" -> {
                        AppLogger.i(TAG, "Routed /goal: goal='$remainder'")
                        return CommandRouteResult.GoalCommand(remainder)
                    }
                }
            }
        }

        // 2. Tool mention detection (@tool ...)
        if (trimmed.startsWith("@")) {
            val parts = trimmed.split(Regex("\\s+"), limit = 2)
            val mentionToken = parts[0].removePrefix("@").lowercase(Locale.ROOT)
            val remainder = if (parts.size > 1) parts[1].trim() else ""

            val resolvedTool = resolveToolMention(mentionToken)
            if (resolvedTool != null) {
                val phase = resolvePhaseForForcedTool(resolvedTool)
                val cleanPrompt = remainder.ifBlank { "Execute $resolvedTool" }
                AppLogger.i(TAG, "Routed tool mention @$mentionToken -> forcedTool='$resolvedTool', phase=$phase, prompt='$cleanPrompt'")
                return CommandRouteResult.StandardTurn(
                    cleanedPrompt = cleanPrompt,
                    phase = phase,
                    forcedTool = resolvedTool
                )
            }
        }

        // 3. Casual chit-chat fast exit (0 tools injected)
        val normalized = trimmed.lowercase(Locale.ROOT).trimEnd('.', '!', '?', ',')
        val isCasual = CASUAL_GREETINGS.contains(normalized) ||
            ((normalized.startsWith("hello") || normalized.startsWith("hi ") || normalized.startsWith("hey ")) &&
             !RESEARCH_KEYWORDS.any { normalized.contains(it) } &&
             !AUTOMATION_KEYWORDS.any { normalized.contains(it) })

        if (isCasual) {
            AppLogger.d(TAG, "Casual greeting detected: '$normalized' -> PHASE_CHAT (0 tools)")
            return CommandRouteResult.StandardTurn(trimmed, AgentPhase.PHASE_CHAT)
        }

        // 4. Phase classification for standard prompt
        val phase = classifyPhase(trimmed)
        AppLogger.d(TAG, "Classified turn as $phase for prompt: '${trimmed.take(40)}…'")
        return CommandRouteResult.StandardTurn(trimmed, phase)
    }

    private fun extractWorkerFlags(text: String): Pair<Int?, String> {
        var clean = text
        var workers: Int? = null

        // Match --workers=N or --workers N or -w N or -w=N
        val longRegex = Regex("""--workers\s*=?\s*(\d+)""")
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

    private data class PlanCommandFlags(
        val goal: String,
        val workers: Int?,
        val isExecute: Boolean,
        val isEdit: Boolean,
        val editFeedback: String?
    )

    private fun extractPlanCommandFlags(text: String): PlanCommandFlags {
        var clean = text.trim()
        var isExecute = false
        var isEdit = false
        var editFeedback: String? = null

        // Check for --execute, -x, or leading execute
        if (clean.contains("--execute") || clean.startsWith("-x ") || clean == "-x" || clean.startsWith("execute ") || clean == "execute") {
            isExecute = true
            clean = clean.replace("--execute", "").replace("-x", "").replaceFirst(Regex("^(?i)execute\\s*"), "").trim()
        }

        // Check for --edit <feedback> or -e <feedback>
        val editRegex = Regex("""(?:--edit|-e)\s*[:=]?\s*(.*)$""", RegexOption.IGNORE_CASE)
        val editMatch = editRegex.find(clean)
        if (editMatch != null) {
            isEdit = true
            editFeedback = editMatch.groupValues[1].trim()
            clean = clean.substring(0, editMatch.range.first).trim()
        }

        val (workers, remainingGoal) = extractWorkerFlags(clean)
        return PlanCommandFlags(remainingGoal, workers, isExecute, isEdit, editFeedback)
    }

    fun classifyPhase(text: String): AgentPhase {
        // Direct Code-as-Action syntax check
        val action = com.textmorph.app.agent.parser.CodeActionParser.parse(text)
        if (action != null) {
            when (action.action) {
                AgentAction.ACTION_SEARCH_WEB,
                AgentAction.ACTION_SEARCH_IMAGES,
                AgentAction.ACTION_FETCH_PAGE,
                AgentAction.ACTION_CREATE_JS_TOOL -> return AgentPhase.PHASE_RESEARCH

                AgentAction.ACTION_EXECUTE_TASK -> return AgentPhase.PHASE_PLANNING

                AgentAction.ACTION_OPEN_APP,
                AgentAction.ACTION_CLICK_TEXT,
                AgentAction.ACTION_CLICK_AT,
                AgentAction.ACTION_CLICK_ELEMENT,
                AgentAction.ACTION_TYPE_TEXT,
                AgentAction.ACTION_PRESS_ENTER,
                AgentAction.ACTION_SCROLL,
                AgentAction.ACTION_SWIPE,
                AgentAction.ACTION_PRESS_BACK,
                AgentAction.ACTION_PRESS_HOME,
                AgentAction.ACTION_LAUNCH_PACKAGE,
                AgentAction.ACTION_MAKE_CALL,
                AgentAction.ACTION_SEND_SMS,
                AgentAction.ACTION_SET_ALARM,
                AgentAction.ACTION_SET_TIMER,
                AgentAction.ACTION_SET_VOLUME,
                AgentAction.ACTION_SET_BRIGHTNESS,
                AgentAction.ACTION_DONE -> return AgentPhase.PHASE_AUTOMATION
            }
        }

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

    /**
     * Maps an @tool token or alias (e.g. "weather", "web", "fetch", "get_weather")
     * to the canonical tool identifier recognized by the executor.
     */
    fun resolveToolMention(token: String): String? {
        val clean = token.lowercase(Locale.ROOT).trim()
        if (clean.isBlank()) return null

        when (clean) {
            "weather" -> return "get_weather"
            "web", "search" -> return "search_web"
            "images", "image" -> return "search_images"
            "fetch", "page" -> return "fetch_page"
            "app" -> return "open_app"
            "call" -> return "make_call"
            "sms", "message" -> return "send_sms"
            "email", "mail" -> return "send_email"
        }

        val builtInNames = setOf(
            AgentAction.ACTION_SEARCH_WEB,
            AgentAction.ACTION_FETCH_PAGE,
            AgentAction.ACTION_SEARCH_IMAGES,
            AgentAction.ACTION_CREATE_JS_TOOL,
            AgentAction.ACTION_OPEN_APP,
            AgentAction.ACTION_MAKE_CALL,
            AgentAction.ACTION_SEND_SMS,
            AgentAction.ACTION_SET_ALARM,
            AgentAction.ACTION_SET_TIMER,
            AgentAction.ACTION_SET_VOLUME,
            AgentAction.ACTION_SET_BRIGHTNESS,
            AgentAction.ACTION_CLICK_TEXT,
            AgentAction.ACTION_CLICK_AT,
            AgentAction.ACTION_TYPE_TEXT,
            AgentAction.ACTION_PRESS_ENTER,
            AgentAction.ACTION_SCROLL,
            AgentAction.ACTION_SWIPE,
            AgentAction.ACTION_PRESS_BACK,
            AgentAction.ACTION_PRESS_HOME,
            AgentAction.ACTION_DONE,
            AgentAction.ACTION_EXECUTE_TASK,
            AgentAction.ACTION_REQUEST_USER_APPROVAL,
            "send_email"
        )
        if (clean in builtInNames) return clean

        if (JsToolRepository.getShowcaseTools().any { it.id.equals(clean, ignoreCase = true) }) {
            return clean
        }

        if (Regex("^[a-z0-9_]+$").matches(clean)) {
            return clean
        }

        return null
    }

    /**
     * Resolves the appropriate AgentPhase for a forced tool to ensure that the
     * required tool schema is curated into the prompt without blowing up context window.
     */
    fun resolvePhaseForForcedTool(toolId: String): AgentPhase {
        val automationTools = setOf(
            AgentAction.ACTION_OPEN_APP,
            AgentAction.ACTION_MAKE_CALL,
            AgentAction.ACTION_SEND_SMS,
            AgentAction.ACTION_SET_ALARM,
            AgentAction.ACTION_SET_TIMER,
            AgentAction.ACTION_SET_VOLUME,
            AgentAction.ACTION_SET_BRIGHTNESS,
            AgentAction.ACTION_CLICK_TEXT,
            AgentAction.ACTION_CLICK_AT,
            AgentAction.ACTION_CLICK_ELEMENT,
            AgentAction.ACTION_TYPE_TEXT,
            AgentAction.ACTION_PRESS_ENTER,
            AgentAction.ACTION_SCROLL,
            AgentAction.ACTION_SWIPE,
            AgentAction.ACTION_PRESS_BACK,
            AgentAction.ACTION_PRESS_HOME,
            AgentAction.ACTION_LAUNCH_PACKAGE,
            AgentAction.ACTION_DONE,
            "send_email"
        )
        if (toolId in automationTools) return AgentPhase.PHASE_AUTOMATION

        if (toolId == AgentAction.ACTION_EXECUTE_TASK || toolId == AgentAction.ACTION_REQUEST_USER_APPROVAL) {
            return AgentPhase.PHASE_PLANNING
        }

        return AgentPhase.PHASE_RESEARCH
    }
}
