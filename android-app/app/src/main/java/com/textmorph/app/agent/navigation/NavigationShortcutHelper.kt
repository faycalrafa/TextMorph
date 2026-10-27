package com.textmorph.app.agent.navigation

import com.textmorph.app.agent.memory.ActionStep

/**
 * Provides predefined navigation sequences to accelerate cold starts for common Android tasks.
 * 1-to-1 port of PrivateAgent's navigation shortcut resolver.
 */
object NavigationShortcutHelper {

    private val APP_PATTERNS = mapOf(
        "Settings" to listOf("settings", "brightness", "display", "notification"),
        "Play Store" to listOf("play store", "playstore", "download", "install app", "google play"),
        "YouTube" to listOf("youtube"),
        "WhatsApp" to listOf("whatsapp"),
        "Chrome" to listOf("chrome", "browse", "search google"),
        "Camera" to listOf("camera", "take a photo", "take photo", "take a picture"),
        "Gallery" to listOf("gallery", "photos"),
        "Messages" to listOf("message", "sms", "text to"),
        "Phone" to listOf("call", "dial"),
        "Gmail" to listOf("gmail", "email"),
        "Maps" to listOf("maps", "navigate to", "directions"),
        "Clock" to listOf("alarm", "timer", "stopwatch"),
        "Calculator" to listOf("calculator", "calculate", "calc")
    )

    private val GENERIC_OPEN_REGEX = Regex("""^open\s+([a-zA-Z0-9]+)""")

    fun getNavigationShortcut(goal: String): List<ActionStep>? {
        val lower = goal.lowercase().trim()

        if (lower.contains("dark mode") || lower.contains("dark theme")) {
            return listOf(
                ActionStep("open_app", mapOf("app_name" to "Settings")),
                ActionStep("click_text", mapOf("text" to "Display"))
            )
        }

        if (lower.contains("wifi") || lower.contains("wi-fi")) {
            return listOf(
                ActionStep("open_app", mapOf("app_name" to "Settings")),
                ActionStep("click_text", mapOf("text" to "Network & internet"))
            )
        }

        if (lower.contains("bluetooth")) {
            return listOf(
                ActionStep("open_app", mapOf("app_name" to "Settings")),
                ActionStep("click_text", mapOf("text" to "Connected devices"))
            )
        }

        for ((appName, keywords) in APP_PATTERNS) {
            for (keyword in keywords) {
                if (lower.contains(keyword)) {
                    return listOf(
                        ActionStep("open_app", mapOf("app_name" to appName))
                    )
                }
            }
        }

        val openMatch = GENERIC_OPEN_REGEX.find(lower)
        if (openMatch != null) {
            val rawApp = openMatch.groupValues[1]
            val capitalizedApp = rawApp.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
            return listOf(
                ActionStep("open_app", mapOf("app_name" to capitalizedApp))
            )
        }

        return null
    }
}
