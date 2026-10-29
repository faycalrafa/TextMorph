package com.textmorph.app.agent.tools.registry

import kotlinx.serialization.Serializable

/**
 * Category grouping for agent tools.
 */
@Serializable
enum class AgentToolCategory(val label: String, val iconName: String) {
    SCREEN_AUTOMATION("Screen & Gestures", "TouchApp"),
    COMMUNICATION("Calls & Messaging", "Call"),
    APPS_AND_WEB("Apps & Web", "Language"),
    DEVICE_SETTINGS("Device Controls", "Tune"),
    ORCHESTRATION("Autonomous Tasks", "SmartToy"),
    INTERACTIVE("Interactive & Chat", "Forum")
}

/**
 * Origin source of the tool definition.
 */
@Serializable
enum class ToolSource {
    BUILT_IN,
    IMPORTED_JS
}

/**
 * Specification for a parameter accepted by an agent tool.
 */
@Serializable
data class AgentToolParameter(
    val name: String,
    val label: String,
    val type: String = "string", // "string", "number", "boolean"
    val defaultValue: String = "",
    val isRequired: Boolean = true,
    val description: String = ""
)

/**
 * Complete definition for a device or screen action executable by the AI agent.
 */
@Serializable
data class AgentToolDefinition(
    val id: String,
    val name: String,
    val category: AgentToolCategory,
    val description: String,
    val codeSignature: String,
    val parameters: List<AgentToolParameter> = emptyList(),
    val source: ToolSource = ToolSource.BUILT_IN
)
