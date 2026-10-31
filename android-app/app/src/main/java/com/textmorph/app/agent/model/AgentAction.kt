package com.textmorph.app.agent.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Represents a discrete device or screen action requested by the AI agent.
 */
@Serializable
data class AgentAction(
    val action: String,
    val params: Map<String, JsonElement> = emptyMap(),
    val response: String = "",
    val reasoning: String? = null,
    val isComplete: Boolean = false
) {
    fun getStringParam(key: String, default: String = ""): String {
        return params[key]?.toString()?.removeSurrounding("\"")?.trim() ?: default
    }

    fun getStringParamWithAliases(vararg keys: String, default: String = ""): String {
        for (k in keys) {
            val v = getStringParam(k, "")
            if (v.isNotBlank()) return v
        }
        return default
    }

    fun getIntParam(key: String, default: Int = 0): Int {
        val raw = getStringParam(key, "")
        return raw.toIntOrNull() ?: default
    }

    fun getIntParamWithAliases(vararg keys: String, default: Int = 0): Int {
        for (k in keys) {
            val v = getStringParam(k, "").toIntOrNull()
            if (v != null) return v
        }
        return default
    }

    fun getFloatParam(key: String, default: Float = 0f): Float {
        val raw = getStringParam(key, "")
        return raw.toFloatOrNull() ?: default
    }

    companion object {
        const val ACTION_OPEN_APP = "open_app"
        const val ACTION_LAUNCH_PACKAGE = "launch_package"
        const val ACTION_OPEN_URL = "open_url"
        const val ACTION_MAKE_CALL = "make_call"
        const val ACTION_SEND_SMS = "send_sms"
        const val ACTION_SEND_EMAIL = "send_email"
        const val ACTION_SEARCH_CONTACT = "search_contact"
        const val ACTION_SET_ALARM = "set_alarm"
        const val ACTION_SET_TIMER = "set_timer"
        const val ACTION_SET_VOLUME = "set_volume"
        const val ACTION_SET_BRIGHTNESS = "set_brightness"
        const val ACTION_READ_SCREEN = "read_screen"
        const val ACTION_CLICK_TEXT = "click_text"
        const val ACTION_CLICK_AT = "click_at"
        const val ACTION_CLICK_ELEMENT = "click_element"
        const val ACTION_TYPE_TEXT = "type_text"
        const val ACTION_PRESS_ENTER = "press_enter"
        const val ACTION_SCROLL = "scroll"
        const val ACTION_SWIPE = "swipe"
        const val ACTION_PRESS_BACK = "press_back"
        const val ACTION_PRESS_HOME = "press_home"
        const val ACTION_OPEN_NOTIFICATIONS = "open_notifications"
        const val ACTION_EXECUTE_TASK = "execute_task"
        const val ACTION_DONE = "done"
        const val ACTION_WAIT = "wait"
        const val ACTION_GENERAL_QUERY = "general_query"
        const val ACTION_SEARCH_WEB = "search_web"
        const val ACTION_FETCH_PAGE = "fetch_page"
        const val ACTION_SEARCH_IMAGES = "search_images"
        const val ACTION_REQUEST_USER_APPROVAL = "request_user_approval"
        const val ACTION_SUGGEST_NEXT_ACTIONS = "suggest_next_actions"
        const val ACTION_PRESENT_INTERACTIVE_VIEW = "present_interactive_view"
        const val ACTION_CREATE_JS_TOOL = "create_js_tool"
    }
}

/**
 * Result of an agent action execution.
 */
@Serializable
data class AgentActionResult(
    val actionType: String,
    val success: Boolean,
    val details: String,
    val data: String? = null
)
