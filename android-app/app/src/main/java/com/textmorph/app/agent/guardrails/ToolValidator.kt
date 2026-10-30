package com.textmorph.app.agent.guardrails

import android.content.Context
import com.textmorph.app.agent.model.AgentAction
import com.textmorph.app.agent.router.AgentPhase
import com.textmorph.app.agent.tools.registry.AgentToolRegistry
import com.textmorph.app.agent.tools.registry.ToolFilter
import com.textmorph.app.util.AppLogger

sealed class ValidationResult {
    object Valid : ValidationResult()
    data class Invalid(val reason: String, val correctionPrompt: String) : ValidationResult()

    val isValid: Boolean get() = this is Valid
    val error: String get() = (this as? Invalid)?.reason ?: ""
    val prompt: String get() = (this as? Invalid)?.correctionPrompt ?: ""
}

/**
 * Pre-flight schema validator for Code-as-Action calls.
 * Traps missing arguments, blank queries, and invalid parameters before execution,
 * synthesizing concise error prompts for self-correction instead of crashing to the UI.
 */
object ToolValidator {

    private const val TAG = "ToolValidator"

    fun synthesizeCorrectionPrompt(action: AgentAction, error: String): String {
        return "[Validation Error for ${action.action}]: $error\nPlease fix the tool call parameters and emit only a corrected command."
    }

    fun validate(
        action: AgentAction,
        allowedTools: List<com.textmorph.app.agent.tools.registry.AgentToolDefinition>
    ): ValidationResult {
        val toolName = action.action.trim().lowercase()
        val match = allowedTools.firstOrNull { it.id.equals(toolName, ignoreCase = true) }
        if (match == null) {
            val names = allowedTools.joinToString(", ") { it.id }
            val errorMsg = "Action '$toolName' is not in allowed tools for this phase. Allowed: [$names]."
            return ValidationResult.Invalid(errorMsg, synthesizeCorrectionPrompt(action, errorMsg))
        }

        // Check required parameters
        for (param in match.parameters) {
            if (param.required) {
                val hasParam = action.params.containsKey(param.name) ||
                    (param.name == "query" && (action.params.containsKey("q") || action.params.containsKey("search"))) ||
                    (param.name == "url" && (action.params.containsKey("link") || action.params.containsKey("target")))
                if (!hasParam) {
                    val errorMsg = "Missing required parameter: ${param.name}"
                    return ValidationResult.Invalid(errorMsg, synthesizeCorrectionPrompt(action, errorMsg))
                }
            }
        }

        return validate(action, AgentPhase.PHASE_RESEARCH, null)
    }

    fun validate(
        action: AgentAction,
        phase: AgentPhase = AgentPhase.PHASE_RESEARCH,
        context: Context? = null
    ): ValidationResult {
        val toolName = action.action.trim().lowercase()
        AppLogger.d(TAG, "Validating action: $toolName for phase: $phase")

        // 1. Check if tool is allowed or known
        val knownBuiltIns = AgentToolRegistry.getInAppTools().map { it.id.lowercase() }.toSet()
        val synthesisTools = setOf("write_section", "append_to_section", AgentAction.ACTION_DONE)
        val validTools = knownBuiltIns + synthesisTools

        if (toolName !in validTools) {
            val jsTool = context?.let { com.textmorph.app.agent.tools.js.JsToolRepository.getInstance(it).getToolById(toolName) }
            if (jsTool == null || !jsTool.isEnabled) {
                val available = ToolFilter.getToolsForPhase(phase, context).joinToString(", ") { it.id }
                val errorMsg = "Action '$toolName' is not recognized. Available actions in this phase: [$available]."
                return ValidationResult.Invalid(
                    reason = errorMsg,
                    correctionPrompt = "[Validation Error]: $errorMsg Please respond with only a valid action command."
                )
            }
        }

        // 2. Specific parameter validations
        when (toolName) {
            AgentAction.ACTION_SEARCH_WEB -> {
                val query = action.getStringParamWithAliases("query", "q", "search").trim()
                if (query.isBlank()) {
                    return ValidationResult.Invalid(
                        reason = "Parameter 'query' cannot be empty",
                        correctionPrompt = "[Validation Error]: search_web requires a non-empty 'query' parameter. Example: search_web(\"query text\"). Please correct your command."
                    )
                }
            }

            AgentAction.ACTION_FETCH_PAGE -> {
                val url = action.getStringParamWithAliases("url", "link", "target", "uri", "page", "website", "site", "href", "address", "query").trim()
                if (url.isBlank() || url == "https://" || url == "http://") {
                    return ValidationResult.Invalid(
                        reason = "Parameter 'url' cannot be empty or invalid",
                        correctionPrompt = "[Validation Error]: fetch_page requires a valid 'url' parameter. Example: fetch_page(\"https://example.com\"). Please correct your command."
                    )
                }
            }

            AgentAction.ACTION_CLICK_TEXT -> {
                val text = action.getStringParamWithAliases("text", "label").trim()
                if (text.isBlank()) {
                    return ValidationResult.Invalid(
                        reason = "Parameter 'text' cannot be empty",
                        correctionPrompt = "[Validation Error]: click_text requires a visible element 'text' to click. Example: click_text(\"Submit\"). Please correct your command."
                    )
                }
            }

            AgentAction.ACTION_CLICK_AT -> {
                val x = action.getFloatParam("x", -1f)
                val y = action.getFloatParam("y", -1f)
                if (x < 0f || y < 0f) {
                    return ValidationResult.Invalid(
                        reason = "Coordinates (x, y) must be positive pixel values",
                        correctionPrompt = "[Validation Error]: click_at requires valid x and y coordinates. Example: click_at(540, 960). Please correct your command."
                    )
                }
            }

            AgentAction.ACTION_TYPE_TEXT -> {
                val text = action.getStringParamWithAliases("text", "content").trim()
                if (text.isBlank()) {
                    return ValidationResult.Invalid(
                        reason = "Parameter 'text' cannot be empty",
                        correctionPrompt = "[Validation Error]: type_text requires text to type. Example: type_text(\"sample text\"). Please correct your command."
                    )
                }
            }

            AgentAction.ACTION_OPEN_APP -> {
                val app = action.getStringParamWithAliases("app_name", "name", "app", "target").trim()
                if (app.isBlank()) {
                    return ValidationResult.Invalid(
                        reason = "Parameter 'app_name' cannot be empty",
                        correctionPrompt = "[Validation Error]: open_app requires an app name. Example: open_app(\"YouTube\"). Please correct your command."
                    )
                }
            }

            "write_section", "append_to_section" -> {
                val index = action.getIntParamWithAliases("index", "section", default = -1)
                val md = action.getStringParamWithAliases("markdown", "content", "text").trim()
                if (index < 0 || md.isBlank()) {
                    return ValidationResult.Invalid(
                        reason = "Parameters 'index' (>= 0) and 'markdown' (non-empty) are required",
                        correctionPrompt = "[Validation Error]: $toolName requires 'index' (number) and 'markdown' (string). Example: $toolName(index=1, markdown=\"## Heading\\n...\"). Please correct your command."
                    )
                }
            }
        }

        return ValidationResult.Valid
    }
}
