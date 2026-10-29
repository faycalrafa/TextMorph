package com.textmorph.app.agent.tools.js

import com.textmorph.app.agent.tools.registry.AgentToolCategory
import com.textmorph.app.agent.tools.registry.AgentToolDefinition
import com.textmorph.app.agent.tools.registry.AgentToolParameter
import com.textmorph.app.agent.tools.registry.ToolSource
import kotlinx.serialization.Serializable

/**
 * Entity representing an extensible JavaScript tool plugin with embedded metadata.
 */
@Serializable
data class JsToolEntity(
    val id: String,
    val name: String,
    val description: String,
    val codeSignature: String,
    val parameters: List<AgentToolParameter> = emptyList(),
    val script: String,
    val isEnabled: Boolean = true,
    val isPreinstalled: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
) {
    /**
     * Converts entity to an AgentToolDefinition for registry publication.
     */
    fun toAgentToolDefinition(): AgentToolDefinition {
        return AgentToolDefinition(
            id = id,
            name = name,
            category = AgentToolCategory.APPS_AND_WEB,
            description = description,
            codeSignature = codeSignature,
            parameters = parameters,
            source = ToolSource.IMPORTED_JS
        )
    }

    companion object {
        /**
         * Parses metadata header comments directly from a JavaScript script string.
         * Supports JSDoc style tags: @tool, @name, @description, @signature, @param.
         */
        fun parseFromScript(scriptContent: String, fallbackId: String = "custom_js_tool"): JsToolEntity {
            var toolId = fallbackId
            var name = "Custom Script Tool"
            var description = "Custom JavaScript tool plugin."
            var signature = "$fallbackId()"
            val params = mutableListOf<AgentToolParameter>()

            val lines = scriptContent.lines()
            for (line in lines) {
                val trimmed = line.trim().removePrefix("*").trim()
                when {
                    trimmed.startsWith("@tool") -> {
                        toolId = trimmed.removePrefix("@tool").trim().lowercase()
                            .replace(Regex("[^a-z0-9_]"), "_")
                    }
                    trimmed.startsWith("@name") -> {
                        name = trimmed.removePrefix("@name").trim()
                    }
                    trimmed.startsWith("@description") -> {
                        description = trimmed.removePrefix("@description").trim()
                    }
                    trimmed.startsWith("@signature") -> {
                        signature = trimmed.removePrefix("@signature").trim()
                    }
                    trimmed.startsWith("@param") -> {
                        val paramSpec = trimmed.removePrefix("@param").trim()
                        val parts = paramSpec.split(Regex("\\s+"), limit = 3)
                        if (parts.isNotEmpty()) {
                            val (paramType, paramName, paramDesc) = if (parts[0].startsWith("{") && parts[0].endsWith("}")) {
                                val type = parts[0].removeSurrounding("{", "}").lowercase()
                                val name = if (parts.size > 1) parts[1] else ""
                                val desc = if (parts.size > 2) parts[2] else ""
                                Triple(type, name, desc)
                            } else {
                                val name = parts[0]
                                val desc = if (parts.size > 1) parts.drop(1).joinToString(" ") else ""
                                Triple("string", name, desc)
                            }

                            if (paramName.isNotBlank()) {
                                params.add(
                                    AgentToolParameter(
                                        name = paramName,
                                        label = paramName.replace("_", " ").replaceFirstChar { it.uppercase() },
                                        type = paramType,
                                        description = paramDesc
                                    )
                                )
                            }
                        }
                    }
                }
            }

            if (signature.isBlank() || signature == "()") {
                signature = "$toolId()"
            }

            return JsToolEntity(
                id = toolId,
                name = name,
                description = description,
                codeSignature = signature,
                parameters = params,
                script = scriptContent
            )
        }
    }
}
