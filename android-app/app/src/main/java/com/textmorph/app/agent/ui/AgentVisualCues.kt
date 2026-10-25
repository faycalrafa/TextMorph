package com.textmorph.app.agent.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardReturn
import androidx.compose.material.icons.automirrored.filled.Launch
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PersonSearch
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Swipe
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.textmorph.app.agent.model.AgentAction
import com.textmorph.app.theme.DangerRed

/**
 * Visual representation (icon or emoji, theme color, contextual label) for discrete agent actions.
 */
data class ActionVisualCue(
    val icon: ImageVector? = null,
    val emoji: String? = null,
    val label: String,
    val color: Color,
    val contentDescription: String
)

object AgentVisualCues {

    fun resolve(actionType: String, isThinking: Boolean = false): ActionVisualCue {
        if (isThinking || actionType.equals("thinking", ignoreCase = true) || actionType.equals("plan", ignoreCase = true)) {
            return ActionVisualCue(
                icon = null,
                emoji = "🤔",
                label = "Analyzing & Planning...",
                color = Color(0xFFFBBF24),
                contentDescription = "Agent Thinking"
            )
        }

        return when (actionType.lowercase().trim()) {
            AgentAction.ACTION_OPEN_APP, AgentAction.ACTION_LAUNCH_PACKAGE -> ActionVisualCue(
                icon = Icons.AutoMirrored.Filled.Launch,
                label = "Opening App",
                color = Color(0xFF3B82F6),
                contentDescription = "Open App"
            )
            AgentAction.ACTION_OPEN_URL -> ActionVisualCue(
                icon = Icons.Default.Language,
                label = "Opening Webpage",
                color = Color(0xFF3B82F6),
                contentDescription = "Open URL"
            )
            AgentAction.ACTION_READ_SCREEN -> ActionVisualCue(
                icon = Icons.Default.Visibility,
                label = "Viewing Screen",
                color = Color(0xFF60A5FA),
                contentDescription = "Read Screen"
            )
            AgentAction.ACTION_CLICK_TEXT, AgentAction.ACTION_CLICK_AT -> ActionVisualCue(
                icon = Icons.Default.TouchApp,
                label = "Tapping Element",
                color = Color(0xFF3B82F6),
                contentDescription = "Tap Screen"
            )
            AgentAction.ACTION_TYPE_TEXT -> ActionVisualCue(
                icon = Icons.Default.Keyboard,
                label = "Typing Input",
                color = Color(0xFF60A5FA),
                contentDescription = "Type Text"
            )
            AgentAction.ACTION_PRESS_ENTER -> ActionVisualCue(
                icon = Icons.AutoMirrored.Filled.KeyboardReturn,
                label = "Submitting Action",
                color = Color(0xFF3B82F6),
                contentDescription = "Press Enter"
            )
            AgentAction.ACTION_SCROLL -> ActionVisualCue(
                icon = Icons.Default.SwapVert,
                label = "Scrolling",
                color = Color(0xFF9CA3AF),
                contentDescription = "Scroll"
            )
            AgentAction.ACTION_SWIPE -> ActionVisualCue(
                icon = Icons.Default.Swipe,
                label = "Swiping",
                color = Color(0xFF9CA3AF),
                contentDescription = "Swipe"
            )
            AgentAction.ACTION_PRESS_BACK -> ActionVisualCue(
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                label = "Navigating Back",
                color = Color(0xFF9CA3AF),
                contentDescription = "Press Back"
            )
            AgentAction.ACTION_PRESS_HOME -> ActionVisualCue(
                icon = Icons.Default.Home,
                label = "Going Home",
                color = Color(0xFF9CA3AF),
                contentDescription = "Press Home"
            )
            AgentAction.ACTION_OPEN_NOTIFICATIONS -> ActionVisualCue(
                icon = Icons.Default.Notifications,
                label = "Notifications",
                color = Color(0xFF3B82F6),
                contentDescription = "Open Notifications"
            )
            AgentAction.ACTION_MAKE_CALL -> ActionVisualCue(
                icon = Icons.Default.Call,
                label = "Making Phone Call",
                color = Color(0xFF10B981),
                contentDescription = "Make Call"
            )
            AgentAction.ACTION_SEND_SMS -> ActionVisualCue(
                icon = Icons.AutoMirrored.Filled.Message,
                label = "Sending Message",
                color = Color(0xFF3B82F6),
                contentDescription = "Send SMS"
            )
            AgentAction.ACTION_SEND_EMAIL -> ActionVisualCue(
                icon = Icons.Default.Email,
                label = "Preparing Email",
                color = Color(0xFF3B82F6),
                contentDescription = "Send Email"
            )
            AgentAction.ACTION_SEARCH_CONTACT -> ActionVisualCue(
                icon = Icons.Default.PersonSearch,
                label = "Searching Contacts",
                color = Color(0xFF60A5FA),
                contentDescription = "Search Contacts"
            )
            AgentAction.ACTION_SET_ALARM -> ActionVisualCue(
                icon = Icons.Default.Alarm,
                label = "Setting Alarm",
                color = Color(0xFFF59E0B),
                contentDescription = "Set Alarm"
            )
            AgentAction.ACTION_SET_TIMER -> ActionVisualCue(
                icon = Icons.Default.Timer,
                label = "Setting Timer",
                color = Color(0xFFF59E0B),
                contentDescription = "Set Timer"
            )
            AgentAction.ACTION_SET_VOLUME, AgentAction.ACTION_SET_BRIGHTNESS -> ActionVisualCue(
                icon = Icons.Default.Tune,
                label = "Device Controls",
                color = Color(0xFF6366F1),
                contentDescription = "Device Settings"
            )
            "wait" -> ActionVisualCue(
                icon = Icons.Default.HourglassTop,
                label = "Waiting for Screen",
                color = Color(0xFFFBBF24),
                contentDescription = "Wait"
            )
            AgentAction.ACTION_DONE, "success" -> ActionVisualCue(
                icon = Icons.Default.CheckCircle,
                label = "Task Completed",
                color = Color(0xFF4ADE80),
                contentDescription = "Task Complete"
            )
            "interrupted", "cancelled" -> ActionVisualCue(
                icon = Icons.Default.StopCircle,
                label = "Task Stopped",
                color = DangerRed,
                contentDescription = "Task Interrupted"
            )
            else -> ActionVisualCue(
                icon = Icons.Default.Build,
                label = "Using Tool ($actionType)",
                color = Color(0xFF3B82F6),
                contentDescription = "Device Tool"
            )
        }
    }
}
