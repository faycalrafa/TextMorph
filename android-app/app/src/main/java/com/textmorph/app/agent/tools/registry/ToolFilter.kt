package com.textmorph.app.agent.tools.registry

import android.content.Context
import com.textmorph.app.agent.model.AgentAction
import com.textmorph.app.agent.router.AgentPhase
import com.textmorph.app.util.AppLogger

/**
 * Phase-aware dynamic tool filtering engine.
 * Ensures the 1B–2B on-device LLM only sees the 3–5 tools required for its current phase,
 * drastically reducing prompt token footprint and preventing hallucinated tool picking.
 */
object ToolFilter {

    private const val TAG = "ToolFilter"

    /**
     * Resolves the active tools for the given phase.
     */
    fun getToolsForPhase(phase: AgentPhase, context: Context? = null): List<AgentToolDefinition> {
        val allBuiltIns = AgentToolRegistry.getInAppTools()
        val jsTools = context?.let { AgentToolRegistry.getJsTools(it) } ?: emptyList()

        return when (phase) {
            AgentPhase.PHASE_CHAT -> {
                // Pure conversation: zero tools injected
                emptyList()
            }

            AgentPhase.PHASE_RESEARCH -> {
                // Live web search, page reading, image search & custom JS tools
                val researchIds = setOf(
                    AgentAction.ACTION_SEARCH_WEB,
                    AgentAction.ACTION_FETCH_PAGE,
                    AgentAction.ACTION_SEARCH_IMAGES
                )
                allBuiltIns.filter { it.id in researchIds } + jsTools
            }

            AgentPhase.PHASE_PLANNING -> {
                // Decomposition & user clarification
                allBuiltIns.filter {
                    it.id == AgentAction.ACTION_REQUEST_USER_APPROVAL ||
                    it.id == AgentAction.ACTION_EXECUTE_TASK
                }
            }

            AgentPhase.PHASE_AUTOMATION -> {
                // Screen automation, OS actions & custom JS tools
                val autoIds = setOf(
                    AgentAction.ACTION_CLICK_TEXT,
                    AgentAction.ACTION_CLICK_AT,
                    AgentAction.ACTION_TYPE_TEXT,
                    AgentAction.ACTION_PRESS_ENTER,
                    AgentAction.ACTION_SCROLL,
                    AgentAction.ACTION_OPEN_APP,
                    AgentAction.ACTION_PRESS_BACK,
                    AgentAction.ACTION_PRESS_HOME,
                    AgentAction.ACTION_DONE
                )
                allBuiltIns.filter { it.id in autoIds } + jsTools
            }

            AgentPhase.PHASE_SYNTHESIS -> {
                // Artifact generation & section completion
                listOf(
                    AgentToolDefinition(
                        id = "write_section",
                        name = "Write Section",
                        category = AgentToolCategory.ORCHESTRATION,
                        description = "Writes markdown content for a planned section index.",
                        codeSignature = "write_section(index=1, markdown=\"...\")",
                        parameters = listOf(
                            AgentToolParameter("index", "Section Index", type = "number", defaultValue = "1"),
                            AgentToolParameter("markdown", "Section Markdown", defaultValue = "")
                        ),
                        isReadOnly = false
                    ),
                    AgentToolDefinition(
                        id = "append_to_section",
                        name = "Append to Section",
                        category = AgentToolCategory.ORCHESTRATION,
                        description = "Appends additional markdown content to an existing section index.",
                        codeSignature = "append_to_section(index=1, markdown=\"...\")",
                        parameters = listOf(
                            AgentToolParameter("index", "Section Index", type = "number", defaultValue = "1"),
                            AgentToolParameter("markdown", "Markdown to Append", defaultValue = "")
                        ),
                        isReadOnly = false
                    ),
                    AgentToolDefinition(
                        id = AgentAction.ACTION_DONE,
                        name = "Complete Task",
                        category = AgentToolCategory.ORCHESTRATION,
                        description = "Marks all sections complete and returns the final response.",
                        codeSignature = "done(summary=\"...\")",
                        parameters = listOf(
                            AgentToolParameter("summary", "Final Summary", defaultValue = "Task complete.")
                        ),
                        isReadOnly = true
                    )
                )
            }
        }
    }

    /**
     * Formats tools into compact, low-token Code-as-Action signatures.
     */
    fun formatPhaseToolsForPrompt(phase: AgentPhase, context: Context? = null): String {
        val tools = getToolsForPhase(phase, context)
        if (tools.isEmpty()) return ""

        val sb = StringBuilder()
        sb.append("AVAILABLE ACTIONS (Code-as-Action only):\n")
        sb.append("To take an action, invoke a single command on its own line:\n")
        for (tool in tools) {
            sb.append("- ").append(tool.codeSignature).append(" : ").append(tool.description).append("\n")
        }
        sb.append("No JSON, no conversational preamble, no markdown fences around text.\n")
        sb.append("When you receive tool observations, synthesize your answer directly. Do NOT repeat the same tool call.\n")
        return sb.toString().trim()
    }

    fun formatCompactToolSignatures(tools: List<AgentToolDefinition>): String {
        if (tools.isEmpty()) return ""
        return tools.joinToString("\n") { "- ${it.codeSignature} : ${it.description}" }
    }
}
