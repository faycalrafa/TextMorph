package com.textmorph.app.ui.chat.components

import android.content.Context
import com.textmorph.app.agent.model.AgentAction
import com.textmorph.app.agent.parser.CodeActionParser
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

        // Parse Code-as-Action
        val parsed = CodeActionParser.parseAction(trimmed)
        if (parsed != null) {
            when (parsed.action) {
                AgentAction.ACTION_SEARCH_WEB,
                AgentAction.ACTION_FETCH_PAGE,
                AgentAction.ACTION_SEARCH_IMAGES,
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
        context: Context? = null
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
                    var url = action.getStringParamWithAliases("url", "link", "target", "uri", "page", "website", "site", "href", "address", "query")
                    if (url.isBlank()) {
                        url = action.params.values.firstOrNull { elem ->
                            val s = elem.toString().removeSurrounding("\"").trim()
                            s.startsWith("http://") || s.startsWith("https://") || s.startsWith("//") || s.contains(".com") || s.contains(".org")
                        }?.toString()?.removeSurrounding("\"")?.trim().orEmpty()
                    }
                    val limit = action.getIntParamWithAliases("char_limit", "limit", default = 3000)
                    if (url.isBlank()) {
                        return@withContext Pair(false, "Webpage URL is empty.")
                    }
                    val pageMarkdown = WebTools.fetchPage(url, charLimit = limit)
                    Pair(!pageMarkdown.startsWith("Error:"), pageMarkdown)
                }

                AgentAction.ACTION_SEARCH_IMAGES -> {
                    val query = action.getStringParamWithAliases("query", "q", "search")
                    if (query.isBlank()) {
                        return@withContext Pair(false, "Image search query is empty.")
                    }
                    val results = com.textmorph.app.agent.tools.web.ImageSearchTool.searchImages(query, maxResults = 4)
                    val formatted = com.textmorph.app.agent.concurrency.ImageAssetRegistry.registerAndFormatStub(results)
                    Pair(results.isNotEmpty(), formatted)
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

                    if (context == null) {
                        return@withContext Pair(false, "Tool requires application context.")
                    }
                    val ok = JsToolRepository.getInstance(context).saveTool(toolEntity)
                    if (ok) {
                        Pair(true, "JavaScript tool '${toolEntity.name}' (${toolEntity.codeSignature}) was created and is now active!")
                    } else {
                        Pair(false, "Failed to save the new JavaScript tool.")
                    }
                }

                else -> {
                    if (context == null) {
                        return@withContext Pair(false, "Tool '${action.action}' requires application context.")
                    }
                    val jsRepo = JsToolRepository.getInstance(context)
                    val jsTool = jsRepo.getToolById(action.action)
                    if (jsTool != null && jsTool.isEnabled) {
                        val runner = JsToolRunner.getInstance(context)
                        val (ok, res) = runner.execute(jsTool, action.params)
                        val cappedRes = if (res.length > 3000) res.take(3000) + "\n...[truncated to 3000 chars]" else res
                        Pair(ok, "Output of ${jsTool.name}:\n$cappedRes")
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

            INSTRUCTION: Review the tool output above. If further information is needed, call the next required tool via code block. Otherwise, provide your comprehensive final response to the user.
            Remember to offer helpful follow-up suggestions using suggest_next_actions(...) if there are natural next steps.
        """.trimIndent()
    }

    /**
     * Checks if the action is an information tool that should loop observations back to the LLM.
     */
    fun isInformationTool(action: AgentAction, context: Context?): Boolean {
        when (action.action) {
            AgentAction.ACTION_SEARCH_WEB,
            AgentAction.ACTION_FETCH_PAGE,
            AgentAction.ACTION_SEARCH_IMAGES,
            AgentAction.ACTION_CREATE_JS_TOOL -> return true
        }
        if (context != null) {
            val jsTool = JsToolRepository.getInstance(context).getToolById(action.action)
            if (jsTool != null && jsTool.isEnabled) return true
        }
        return false
    }

    /**
     * Returns a human-friendly display name for the tool.
     */
    fun getToolDisplayName(action: AgentAction, context: Context? = null): String {
        return when (action.action) {
            AgentAction.ACTION_SEARCH_WEB -> "Web Search"
            AgentAction.ACTION_FETCH_PAGE -> "Webpage Reader"
            AgentAction.ACTION_SEARCH_IMAGES -> "Image Search"
            AgentAction.ACTION_CREATE_JS_TOOL -> "Custom JS Tool Creator"
            "get_weather" -> "Live Weather"
            "unit_converter" -> "Unit Converter"
            else -> {
                if (context != null) {
                    val jsTool = JsToolRepository.getInstance(context).getToolById(action.action)
                    jsTool?.name ?: action.action
                } else action.action
            }
        }
    }

    /**
     * Provides a user-friendly status message while the tool is executing.
     */
    fun formatToolStatus(action: AgentAction, context: Context? = null): String {
        return when (action.action) {
            AgentAction.ACTION_SEARCH_WEB -> {
                val q = action.getStringParamWithAliases("query", "q", "search")
                if (q.isNotBlank()) "Searching DuckDuckGo for \"$q\"…" else "Searching web via DuckDuckGo…"
            }
            AgentAction.ACTION_FETCH_PAGE -> {
                val u = action.getStringParamWithAliases("url", "link", "target")
                if (u.isNotBlank()) "Fetching $u…" else "Fetching webpage content…"
            }
            AgentAction.ACTION_SEARCH_IMAGES -> {
                val q = action.getStringParamWithAliases("query", "q", "search")
                if (q.isNotBlank()) "Searching images for \"$q\"…" else "Searching for visual assets…"
            }
            AgentAction.ACTION_CREATE_JS_TOOL -> "Creating custom JavaScript tool…"
            "get_weather" -> {
                val loc = action.getStringParamWithAliases("location", "city", "place", default = "Paris")
                "Checking live weather for $loc…"
            }
            "unit_converter" -> {
                val v = action.getStringParamWithAliases("value", default = "")
                val from = action.getStringParamWithAliases("from_unit", default = "")
                val to = action.getStringParamWithAliases("to_unit", default = "")
                if (v.isNotBlank()) "Converting $v $from to $to…" else "Converting units…"
            }
            else -> {
                val name = getToolDisplayName(action, context)
                "Executing $name…"
            }
        }
    }
}
