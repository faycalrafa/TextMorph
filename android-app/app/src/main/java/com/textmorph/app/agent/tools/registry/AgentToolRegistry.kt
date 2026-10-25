package com.textmorph.app.agent.tools.registry

import com.textmorph.app.agent.model.AgentAction

/**
 * Central catalog of all built-in agent actions and extensible script tools.
 */
object AgentToolRegistry {

    private val builtInTools = listOf(
        AgentToolDefinition(
            id = AgentAction.ACTION_OPEN_APP,
            name = "Open App",
            category = AgentToolCategory.APPS_AND_WEB,
            description = "Launches an installed Android application by its display name.",
            codeSignature = "open_app(\"YouTube\")",
            parameters = listOf(
                AgentToolParameter("app_name", "App Name", defaultValue = "YouTube", description = "Name of the app (e.g. YouTube, Settings, WhatsApp, Chrome)")
            )
        ),
        AgentToolDefinition(
            id = AgentAction.ACTION_OPEN_URL,
            name = "Open Web URL",
            category = AgentToolCategory.APPS_AND_WEB,
            description = "Opens a web link or deep link directly in the default browser.",
            codeSignature = "open_url(\"https://www.google.com\")",
            parameters = listOf(
                AgentToolParameter("url", "Web URL", defaultValue = "https://www.google.com", description = "Full HTTP/HTTPS or intent URL")
            )
        ),
        AgentToolDefinition(
            id = AgentAction.ACTION_MAKE_CALL,
            name = "Make Phone Call",
            category = AgentToolCategory.COMMUNICATION,
            description = "Initiates a direct phone call to a saved contact name or raw phone number.",
            codeSignature = "call(\"Mom\")",
            parameters = listOf(
                AgentToolParameter("contact_name", "Contact Name", defaultValue = "Mom", isRequired = false, description = "Saved contact name"),
                AgentToolParameter("phone_number", "Phone Number", defaultValue = "", isRequired = false, description = "Raw phone number digits")
            )
        ),
        AgentToolDefinition(
            id = AgentAction.ACTION_SEND_SMS,
            name = "Send SMS",
            category = AgentToolCategory.COMMUNICATION,
            description = "Composes and sends an SMS text message to a contact or phone number.",
            codeSignature = "send_sms(\"Mom\", \"I am on my way\")",
            parameters = listOf(
                AgentToolParameter("contact_name", "Contact / Phone", defaultValue = "Mom", description = "Contact name or phone number"),
                AgentToolParameter("message", "Message Text", defaultValue = "Hello from TextMorph!", description = "Content of the text message")
            )
        ),
        AgentToolDefinition(
            id = AgentAction.ACTION_SEND_EMAIL,
            name = "Send Email",
            category = AgentToolCategory.COMMUNICATION,
            description = "Prepares and opens the system email client with recipient, subject, and body.",
            codeSignature = "send_email(\"friend@example.com\", \"Meeting\", \"See you at 3\")",
            parameters = listOf(
                AgentToolParameter("to", "Recipient Email", defaultValue = "friend@example.com", description = "Target email address"),
                AgentToolParameter("subject", "Subject", defaultValue = "Meeting Update", isRequired = false, description = "Email subject"),
                AgentToolParameter("body", "Body Content", defaultValue = "Let's meet tomorrow.", isRequired = false, description = "Email message body")
            )
        ),
        AgentToolDefinition(
            id = AgentAction.ACTION_SEARCH_CONTACT,
            name = "Search Contacts",
            category = AgentToolCategory.COMMUNICATION,
            description = "Queries the device contact database to locate phone numbers or email addresses.",
            codeSignature = "search_contact(\"John\")",
            parameters = listOf(
                AgentToolParameter("query", "Search Query", defaultValue = "John", description = "Contact name or fragment")
            )
        ),
        AgentToolDefinition(
            id = AgentAction.ACTION_CLICK_TEXT,
            name = "Click by Text",
            category = AgentToolCategory.SCREEN_AUTOMATION,
            description = "Locates and clicks an interactive UI element by matching visible text or content description.",
            codeSignature = "click_text(\"Search\")",
            parameters = listOf(
                AgentToolParameter("text", "Element Label", defaultValue = "Search", description = "Exact or partial visible text to click")
            )
        ),
        AgentToolDefinition(
            id = AgentAction.ACTION_CLICK_AT,
            name = "Click at Coordinates",
            category = AgentToolCategory.SCREEN_AUTOMATION,
            description = "Dispatches a tap gesture at exact screen coordinates (x, y).",
            codeSignature = "click_at(540, 960)",
            parameters = listOf(
                AgentToolParameter("x", "X Coordinate", type = "number", defaultValue = "540", description = "Horizontal pixel coordinate"),
                AgentToolParameter("y", "Y Coordinate", type = "number", defaultValue = "960", description = "Vertical pixel coordinate")
            )
        ),
        AgentToolDefinition(
            id = AgentAction.ACTION_TYPE_TEXT,
            name = "Type Text",
            category = AgentToolCategory.SCREEN_AUTOMATION,
            description = "Types string into the active focused field or a field matching the given hint.",
            codeSignature = "type_text(\"cute cats\", hint=\"Search\")",
            parameters = listOf(
                AgentToolParameter("text", "Input Text", defaultValue = "TextMorph AI", description = "Text string to enter"),
                AgentToolParameter("field_hint", "Field Hint", defaultValue = "", isRequired = false, description = "Optional placeholder hint of the target field")
            )
        ),
        AgentToolDefinition(
            id = AgentAction.ACTION_PRESS_ENTER,
            name = "Press Enter",
            category = AgentToolCategory.SCREEN_AUTOMATION,
            description = "Submits the active keyboard action (Enter, Search, Go, Done).",
            codeSignature = "press_enter()"
        ),
        AgentToolDefinition(
            id = AgentAction.ACTION_SCROLL,
            name = "Scroll Screen",
            category = AgentToolCategory.SCREEN_AUTOMATION,
            description = "Performs a scroll gesture in the specified direction (down or up).",
            codeSignature = "scroll(\"down\")",
            parameters = listOf(
                AgentToolParameter("direction", "Direction", defaultValue = "down", description = "'down' or 'up'")
            )
        ),
        AgentToolDefinition(
            id = AgentAction.ACTION_SWIPE,
            name = "Swipe Gesture",
            category = AgentToolCategory.SCREEN_AUTOMATION,
            description = "Dispatches a smooth swipe gesture between two coordinate pairs.",
            codeSignature = "swipe(540, 1800, 540, 600)",
            parameters = listOf(
                AgentToolParameter("startX", "Start X", type = "number", defaultValue = "540"),
                AgentToolParameter("startY", "Start Y", type = "number", defaultValue = "1800"),
                AgentToolParameter("endX", "End X", type = "number", defaultValue = "540"),
                AgentToolParameter("endY", "End Y", type = "number", defaultValue = "600")
            )
        ),
        AgentToolDefinition(
            id = AgentAction.ACTION_PRESS_BACK,
            name = "Press Back",
            category = AgentToolCategory.SCREEN_AUTOMATION,
            description = "Triggers the system Back navigation gesture.",
            codeSignature = "press_back()"
        ),
        AgentToolDefinition(
            id = AgentAction.ACTION_PRESS_HOME,
            name = "Press Home",
            category = AgentToolCategory.SCREEN_AUTOMATION,
            description = "Triggers the system Home navigation gesture.",
            codeSignature = "press_home()"
        ),
        AgentToolDefinition(
            id = AgentAction.ACTION_OPEN_NOTIFICATIONS,
            name = "Open Notifications",
            category = AgentToolCategory.SCREEN_AUTOMATION,
            description = "Pulls down the system notification shade.",
            codeSignature = "open_notifications()"
        ),
        AgentToolDefinition(
            id = AgentAction.ACTION_READ_SCREEN,
            name = "Read Screen Hierarchy",
            category = AgentToolCategory.SCREEN_AUTOMATION,
            description = "Inspects the visible accessibility hierarchy and extracts text, bounds, and interactive controls.",
            codeSignature = "read_screen()"
        ),
        AgentToolDefinition(
            id = AgentAction.ACTION_SET_ALARM,
            name = "Set Alarm",
            category = AgentToolCategory.DEVICE_SETTINGS,
            description = "Schedules a system clock alarm for a specific hour and minute.",
            codeSignature = "set_alarm(7, 30, \"Wake up\")",
            parameters = listOf(
                AgentToolParameter("hour", "Hour (0-23)", type = "number", defaultValue = "7"),
                AgentToolParameter("minute", "Minute (0-59)", type = "number", defaultValue = "30"),
                AgentToolParameter("label", "Alarm Label", defaultValue = "Wake up", isRequired = false)
            )
        ),
        AgentToolDefinition(
            id = AgentAction.ACTION_SET_TIMER,
            name = "Set Timer",
            category = AgentToolCategory.DEVICE_SETTINGS,
            description = "Starts a countdown timer in the clock app.",
            codeSignature = "set_timer(60, \"Tea\")",
            parameters = listOf(
                AgentToolParameter("seconds", "Seconds", type = "number", defaultValue = "60"),
                AgentToolParameter("label", "Timer Label", defaultValue = "Timer", isRequired = false)
            )
        ),
        AgentToolDefinition(
            id = AgentAction.ACTION_SET_VOLUME,
            name = "Set Volume",
            category = AgentToolCategory.DEVICE_SETTINGS,
            description = "Adjusts media volume level (0 to 100).",
            codeSignature = "set_volume(50)",
            parameters = listOf(
                AgentToolParameter("level", "Volume Level (0-100)", type = "number", defaultValue = "50")
            )
        ),
        AgentToolDefinition(
            id = AgentAction.ACTION_SET_BRIGHTNESS,
            name = "Set Brightness",
            category = AgentToolCategory.DEVICE_SETTINGS,
            description = "Adjusts screen display brightness (0 to 100).",
            codeSignature = "set_brightness(60)",
            parameters = listOf(
                AgentToolParameter("level", "Brightness Level (0-100)", type = "number", defaultValue = "60")
            )
        ),
        AgentToolDefinition(
            id = AgentAction.ACTION_EXECUTE_TASK,
            name = "Execute Autonomous Task",
            category = AgentToolCategory.ORCHESTRATION,
            description = "Starts an autonomous multi-step reasoning loop to complete a goal across apps.",
            codeSignature = "execute_task(\"open YouTube and search cats\")",
            parameters = listOf(
                AgentToolParameter("goal", "Goal Description", defaultValue = "open Settings and check storage", description = "High-level goal for the agent")
            )
        ),
        AgentToolDefinition(
            id = AgentAction.ACTION_DONE,
            name = "Task Complete",
            category = AgentToolCategory.ORCHESTRATION,
            description = "Signals that an autonomous agent task has been successfully accomplished.",
            codeSignature = "done(\"Found the requested video.\")",
            parameters = listOf(
                AgentToolParameter("summary", "Completion Summary", defaultValue = "Task completed successfully.")
            )
        )
    )

    fun getAllTools(): List<AgentToolDefinition> = builtInTools

    fun getToolsByCategory(category: AgentToolCategory): List<AgentToolDefinition> {
        return builtInTools.filter { it.category == category }
    }

    fun getToolById(id: String): AgentToolDefinition? {
        return builtInTools.firstOrNull { it.id == id }
    }

    fun findTool(query: String): AgentToolDefinition? {
        val trimmed = query.trim().lowercase()
        return builtInTools.firstOrNull { tool ->
            tool.id.equals(trimmed, ignoreCase = true) ||
            tool.name.equals(trimmed, ignoreCase = true) ||
            tool.codeSignature.lowercase().startsWith("$trimmed(") ||
            tool.codeSignature.lowercase().startsWith(trimmed)
        }
    }
}
