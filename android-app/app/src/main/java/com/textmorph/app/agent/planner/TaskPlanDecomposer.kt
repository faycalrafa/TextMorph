package com.textmorph.app.agent.planner

import com.textmorph.app.util.AppLogger
import java.util.Locale

/**
 * Represents a single planned milestone within a decomposed multi-step user goal.
 */
data class PlanMilestone(
    val index: Int,
    val description: String,
    val targetApp: String? = null,
    val isCompleted: Boolean = false
)

/**
 * Decomposes complex, multi-intent user instructions into sequential execution milestones.
 * For example, "open youtube and search for cookies recipe and pick the first video"
 * is cleanly split into:
 *   1. Open YouTube
 *   2. Search for cookies recipe
 *   3. Pick the first video
 *
 * Employs zero-latency rule-based compound connector analysis with LLM decomposition fallback.
 */
object TaskPlanDecomposer {

    private const val TAG = "TaskPlanDecomposer"

    private val COMPOUND_CONNECTORS = listOf(
        " and then ",
        " then ",
        " after that ",
        ", then ",
        ", and ",
        " and ",
        " -> "
    )

    /**
     * Splits a high-level user goal into ordered milestones.
     */
    suspend fun decompose(
        goal: String,
        llmQuery: (suspend (prompt: String) -> String)? = null
    ): List<PlanMilestone> {
        val trimmed = goal.trim()
        if (trimmed.isBlank()) return emptyList()

        AppLogger.i(TAG, "Decomposing task goal: '$trimmed'")

        // 1. Fast zero-latency rule-based heuristic decomposition
        val ruleMilestones = decomposeByRules(trimmed)
        if (ruleMilestones.size >= 2) {
            AppLogger.i(TAG, "Decomposed into ${ruleMilestones.size} rule-based milestones: ${ruleMilestones.map { it.description }}")
            return ruleMilestones
        }

        // 2. LLM-assisted decomposition fallback if query provider is supplied
        if (llmQuery != null && isMultiIntentCandidate(trimmed)) {
            try {
                val prompt = """
                    Decompose this phone task into 2 to 4 clear, sequential sub-steps:
                    "$trimmed"
                    Respond with ONLY a numbered list of short steps (e.g., 1. Open App, 2. Search X):
                """.trimIndent()
                val llmOutput = llmQuery(prompt)
                val llmMilestones = parseNumberedMilestones(llmOutput)
                if (llmMilestones.size >= 2) {
                    AppLogger.i(TAG, "Decomposed via LLM into ${llmMilestones.size} milestones: ${llmMilestones.map { it.description }}")
                    return llmMilestones
                }
            } catch (e: Exception) {
                AppLogger.w(TAG, "LLM decomposition fallback failed, using single-step goal: ${e.message}")
            }
        }

        // 3. Fallback: single milestone representing the entire goal
        val single = listOf(PlanMilestone(index = 1, description = formatMilestoneTitle(trimmed)))
        AppLogger.d(TAG, "Task retained as single milestone: '${single.first().description}'")
        return single
    }

    /**
     * Splits by compound connectors while preserving logical ordering.
     */
    fun decomposeByRules(goal: String): List<PlanMilestone> {
        var segments = listOf(goal)

        for (connector in COMPOUND_CONNECTORS) {
            val newSegments = mutableListOf<String>()
            for (seg in segments) {
                if (seg.contains(connector, ignoreCase = true)) {
                    val parts = seg.split(Regex(Regex.escape(connector), RegexOption.IGNORE_CASE))
                    for (p in parts) {
                        val cleaned = p.trim().trim(',', '.', ';')
                        if (cleaned.isNotBlank()) {
                            newSegments.add(cleaned)
                        }
                    }
                } else {
                    newSegments.add(seg)
                }
            }
            segments = newSegments
        }

        if (segments.size <= 1) return emptyList()

        return segments.mapIndexed { idx, segment ->
            val app = detectTargetApp(segment)
            val formatted = formatMilestoneTitle(segment, app)
            PlanMilestone(index = idx + 1, description = formatted, targetApp = app)
        }
    }

    private fun parseNumberedMilestones(output: String): List<PlanMilestone> {
        val lines = output.lines()
        val result = mutableListOf<PlanMilestone>()
        var count = 1
        for (rawLine in lines) {
            val line = rawLine.trim()
            if (line.matches(Regex("^\\d+[.):-]\\s*.+"))) {
                val stepText = line.replaceFirst(Regex("^\\d+[.):-]\\s*"), "").trim()
                if (stepText.isNotBlank()) {
                    result.add(
                        PlanMilestone(
                            index = count++,
                            description = formatMilestoneTitle(stepText),
                            targetApp = detectTargetApp(stepText)
                        )
                    )
                }
            }
        }
        return result
    }

    private fun isMultiIntentCandidate(text: String): Boolean {
        val lower = text.lowercase(Locale.ROOT)
        return lower.contains(" and ") || lower.contains(" then ") || lower.contains(" after ") ||
                lower.contains("search") || lower.contains("pick") || lower.contains("find")
    }

    private fun formatMilestoneTitle(title: String, detectedApp: String? = null): String {
        val cleaned = title.trim()
        if (cleaned.isEmpty()) return ""
        val firstChar = cleaned.take(1).uppercase(Locale.ROOT)
        var formatted = firstChar + cleaned.substring(1)
        val app = detectedApp ?: detectTargetApp(title)
        if (app != null && formatted.contains(app, ignoreCase = true)) {
            formatted = formatted.replace(Regex(Regex.escape(app), RegexOption.IGNORE_CASE), app)
        }
        return formatted
    }

    private fun detectTargetApp(text: String): String? {
        val lower = text.lowercase(Locale.ROOT)
        return when {
            lower.contains("youtube") -> "YouTube"
            lower.contains("chrome") || lower.contains("browser") -> "Chrome"
            lower.contains("setting") -> "Settings"
            lower.contains("maps") -> "Maps"
            lower.contains("whatsapp") -> "WhatsApp"
            lower.contains("camera") -> "Camera"
            lower.contains("clock") || lower.contains("alarm") -> "Clock"
            else -> null
        }
    }

    /**
     * Checks if a milestone's expected state is visibly confirmed on the active screen.
     */
    fun isMilestoneConfirmed(
        milestone: PlanMilestone,
        screenDump: String,
        currentPackage: String?
    ): Boolean {
        val lowerDump = screenDump.lowercase(Locale.ROOT)
        val targetApp = milestone.targetApp?.lowercase(Locale.ROOT)

        if (targetApp != null) {
            val pkgMatch = currentPackage?.lowercase(Locale.ROOT)?.contains(targetApp) == true
            val dumpMatch = lowerDump.contains(targetApp)
            if (milestone.description.lowercase(Locale.ROOT).startsWith("open") && (pkgMatch || dumpMatch)) {
                return true
            }
        }

        val descLower = milestone.description.lowercase(Locale.ROOT)
        if (descLower.contains("search")) {
            val queryKeywords = descLower.substringAfter("search for", "").ifBlank {
                descLower.substringAfter("search", "")
            }.trim().split(" ").filter { it.length > 2 }

            if (queryKeywords.isNotEmpty() && queryKeywords.any { lowerDump.contains(it) }) {
                return true
            }
        }

        return false
    }
}
