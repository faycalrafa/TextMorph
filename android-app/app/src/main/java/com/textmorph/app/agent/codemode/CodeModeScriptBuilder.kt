package com.textmorph.app.agent.codemode

import com.textmorph.app.agent.model.AgentAction
import com.textmorph.app.agent.parser.CodeActionParser

sealed interface AgentActionPlan {
    data class SingleCodeAction(val action: AgentAction) : AgentActionPlan
    data class CodeModeScript(val scriptContent: String) : AgentActionPlan
    data class ConversationalResponse(val text: String) : AgentActionPlan
}

/**
 * Parses and synthesizes hybrid CodeMode scripts and single Code-as-Action commands.
 */
object CodeModeScriptBuilder {

    private val JS_BLOCK_REGEX = Regex("```(?:javascript|js)\\s*([\\s\\S]*?)```", RegexOption.IGNORE_CASE)

    /**
     * Identifies whether the model generated a CodeMode script, a single Code-as-Action regex, or conversational text.
     */
    fun parseActionPlan(rawOutput: String): AgentActionPlan {
        val clean = rawOutput.trim()

        // 1. Detect JavaScript code block targeting TextMorph API
        val jsMatch = JS_BLOCK_REGEX.find(clean)
        if (jsMatch != null) {
            val script = jsMatch.groupValues[1].trim()
            if (script.contains("TextMorph.")) {
                return AgentActionPlan.CodeModeScript(script)
            }
        }

        // 2. Detect traditional single Code-as-Action regex
        val action = CodeActionParser.parse(clean)
        if (action != null) {
            return AgentActionPlan.SingleCodeAction(action)
        }

        // 3. Conversational response
        return AgentActionPlan.ConversationalResponse(clean)
    }

    /**
     * Formats system prompt instructions for CodeMode batching.
     */
    fun getCodeModeInstructions(): String {
        return """
            CODEMODE SCRIPTING:
            You can batch multiple actions and logic into a single turn by writing a JavaScript block:
            ```javascript
            const web = await TextMorph.tools.searchWeb("best coffee nearby");
            await TextMorph.screen.openApp("Maps");
            return "Found " + web.results.length + " places.";
            ```
            Available APIs:
            - TextMorph.screen: clickText(text), clickAt(x,y), pressHome(), pressBack(), typeText(text)
            - TextMorph.tools: searchWeb(query), fetchPage(url)
        """.trimIndent()
    }
}
