package com.textmorph.app.agent.recovery

import com.textmorph.app.util.AppLogger

/**
 * Encapsulates an automated recovery intervention determined by the RecoveryEngine.
 */
data class RecoveryAction(
    val action: String,
    val params: Map<String, String> = emptyMap(),
    val description: String
)

/**
 * Self-healing heuristic diagnostics engine that analyzes failed actions and screen hierarchy
 * to determine the optimal corrective action (waiting on loaders, dismissing lingering keyboards,
 * scrolling to reveal hidden targets, or backing out).
 * 1-to-1 port of PrivateAgent's RecoveryEngine.
 */
class RecoveryEngine {

    private val TAG = "RecoveryEngine"

    fun diagnose(lastFailedAction: String, screenContent: String): RecoveryAction {
        val lowerScreen = screenContent.lowercase()

        // 1. Loading / spinner detected -> Wait
        if (lowerScreen.contains("loading") || lowerScreen.contains("progress") ||
            lowerScreen.contains("spinner") || lowerScreen.contains("wait") || lowerScreen.contains("buffering")
        ) {
            val action = RecoveryAction(
                action = "wait",
                description = "App seems to be loading, waiting..."
            )
            AppLogger.i(TAG, "Diagnosed recovery: ${action.description}")
            return action
        }

        // 2. Keyboard is likely covering elements -> Press back to dismiss
        if (lowerScreen.contains("gboard") || lowerScreen.contains("keyboard") || lowerScreen.contains("inputmethod")) {
            val action = RecoveryAction(
                action = "press_back",
                description = "Keyboard might be blocking the screen, dismissing it."
            )
            AppLogger.i(TAG, "Diagnosed recovery: ${action.description}")
            return action
        }

        // 3. Last action was click_text, click_at, or click_element -> Try scrolling or press back
        if (lastFailedAction == "click_text" || lastFailedAction == "click_at" || lastFailedAction == "click_element") {
            val action = if (lowerScreen.contains("scrollable") || lowerScreen.contains("scroll")) {
                RecoveryAction(
                    action = "scroll",
                    params = mapOf("direction" to "down"),
                    description = "Click failed, trying to scroll down to find the target."
                )
            } else {
                RecoveryAction(
                    action = "press_back",
                    description = "Click failed and not scrollable, pressing back to retry from previous screen."
                )
            }
            AppLogger.i(TAG, "Diagnosed recovery: ${action.description}")
            return action
        }

        // 4. Failed to open app -> Go home to try a different approach
        if (lastFailedAction == "open_app") {
            val action = RecoveryAction(
                action = "press_home",
                description = "Failed to open app, going home to try a different approach."
            )
            AppLogger.i(TAG, "Diagnosed recovery: ${action.description}")
            return action
        }

        // Generic fallback
        val action = RecoveryAction(
            action = "press_back",
            description = "Unknown failure, pressing back to recover."
        )
        AppLogger.i(TAG, "Diagnosed recovery: ${action.description}")
        return action
    }
}
