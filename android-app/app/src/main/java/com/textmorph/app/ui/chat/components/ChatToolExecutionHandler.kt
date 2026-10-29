package com.textmorph.app.ui.chat.components

import android.content.Context
import com.textmorph.app.agent.model.AgentAction
import com.textmorph.app.agent.parser.AgentActionParser
import com.textmorph.app.agent.tools.js.JsToolEntity
import com.textmorph.app.agent.tools.js.JsToolRepository
import com.textmorph.app.agent.tools.js.JsToolRunner
import com.textmorph.app.agent.tools.web.WebTools
import com.textmorph.app.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Handles detection, background execution, and observation formatting of tools
 * invoked during normal and agent chat inference turns (e.g. search_web, fetch_page, JS tools).
 */
object ChatToolExecutionHandler {

    private const val TAG = "ChatToolExecution"

    /**
     * Determines if the assistant's generated output contains a tool invocation
     * that should be executed and looped back to the model.
     */
    fun detectToolInvocation(content: String, context: Context?): AgentAction? {
        val trimmed = content.trim()
        if (trimmed.isBlank()) return null

        // Parse Code-as-Action or JSON
        val parsed = AgentActionParser.parseAction(trimmed)
        if (parsed != null) {
            when (parsed.action) {
                AgentAction.ACTION_SEARCH_WEB,
                AgentAction.ACTION_FETCH_PAGE,
                AgentAction.ACTION_CREATE_JS_TOOL -> return parsed
            }

            if (context != null) {
                val jsTool = JsToolRepository.getInstance(context).getToolById(parsed.action)
                if (jsTool != null && jsTool.isEnabled) {
                    return parsed
                }
            }
        }

        return null
    }

    /**
     * Executes the detected tool on Dispatchers.IO and returns (success, formattedObservation).
     */
    suspend fun executeTool(
        action: AgentAction,
        context: Context
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        AppLogger.i(TAG, "Executing chat tool: ${action.action}")
        try {
            when (action.action) {
                AgentAction.ACTION_SEARCH_WEB -> {
                    val query = action.getStringParamWithAliases("query", "q", "search")
                    if (query.isBlank()) {
                        return@withContext Pair(false, "Search query is empty.")
                    }
                    val results = WebTools.searchWeb(query, maxResults = 3)
                    val formatted = WebTools.formatSearchResults(query, results)
                    Pair(results.isNotEmpty(), formatted)
                }

                AgentAction.ACTION_FETCH_PAGE -> {
                    val url = action.getStringParamWithAliases("url", "link", "target")
                    val limit = action.getIntParamWithAliases("char_limit", "limit", default = 3000)
                    if (url.isBlank()) {
                        return@withContext Pair(false, "Webpage URL is empty.")
                    }
                    val pageMarkdown = WebTools.fetchPage(url, charLimit = limit)
                    Pair(!pageMarkdown.startsWith("Error:"), pageMarkdown)
                }

                AgentAction.ACTION_CREATE_JS_TOOL -> {
                    val id = action.getStringParamWithAliases("id", "tool_id")
                        .ifEmpty { "custom_tool_${System.currentTimeMillis() % 10000}" }
                        .lowercase().replace(Regex("[^a-z0-9_]"), "_")
                    val name = action.getStringParamWithAliases("name", "tool_name", default = id)
                    val desc = action.getStringParamWithAliases("description", "desc", default = "Custom tool created by AI")
                    val sig = action.getStringParamWithAliases("signature", "code_signature", default = "$id()")
                    val script = action.getStringParamWithAliases("code", "script", default = "function run(p) { return 'Success'; }")

                    val toolEntity = JsToolEntity(
                        id = id,
                        name = name,
                        description = desc,
                        codeSignature = sig,
                        script = script,
                        isEnabled = true,
                        isPreinstalled = false
                    )

                    val ok = JsToolRepository.getInstance(context).saveTool(toolEntity)
                    if (ok) {
                        Pair(true, "JavaScript tool '${toolEntity.name}' (${toolEntity.codeSignature}) was created and is now active!")
                    } else {
                        Pair(false, "Failed to save the new JavaScript tool.")
                    }
                }

                else -> {
                    val jsRepo = JsToolRepository.getInstance(context)
                    val jsTool = jsRepo.getToolById(action.action)
                    if (jsTool != null && jsTool.isEnabled) {
                        val runner = JsToolRunner(context)
                        val (ok, res) = runner.execute(jsTool, action.params)
                        Pair(ok, "Output of ${jsTool.name}:\n$res")
                    } else {
                        Pair(false, "Tool '${action.action}' is not supported or is disabled.")
                    }
                }
            }
        } catch (t: Throwable) {
            AppLogger.e(TAG, "Unhandled error during chat tool execution: ${t.message}", t)
            Pair(false, "Tool execution failed: ${t.message}")
        }
    }

    /**
     * Formats the tool execution result into an observation prompt to resume LLM generation.
     */
    fun formatObservationPrompt(action: AgentAction, toolOutput: String): String {
        return """
            [Tool Result for ${action.action}]:
            $toolOutput

            INSTRUCTION: Now review the tool output above and provide your comprehensive, final response to the user.
            Remember to offer helpful follow-up suggestions using suggest_next_actions(...) if there are natural next steps.
        """.trimIndent()
    }
}
