package com.textmorph.app.data.model

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
enum class AutomationActionType(val displayName: String) {
    SCREEN_ASSIST("Screen Assist & Clipboard"),
    PHONE_CALL("Direct Phone Call"),
    SEND_SMS("Send SMS"),
    OPEN_APP("Launch App"),
    DEVICE_CONTROL("Device Setting"),
    CODE_SCRIPT("Multi-step Script"),
    CUSTOM_TOAST("Notification Toast")
}

/**
 * Entity representing a direct voice trigger or automated task.
 * Allows executing instant actions (e.g. ScreenAssist, calling contacts, launching apps, scripts)
 * without incurring the latency of a full autonomous agent planning loop.
 */
@Serializable
data class AutomationEntity(
    val id: String = UUID.randomUUID().toString(),
    val trigger: String, // e.g. "assist me", "call my brother"
    val title: String,
    val description: String = "",
    val actionType: AutomationActionType,
    val targetNumber: String? = null, // e.g. "0612345678" or contact name
    val targetApp: String? = null, // e.g. "YouTube"
    val scriptCode: String? = null, // Code-as-Action lines
    val promptInstruction: String? = null, // LLM instruction for ScreenAssist
    val toastMessage: String? = null, // e.g. "called out nouh !"
    val copyToClipboard: Boolean = false,
    val isBuiltIn: Boolean = false,
    val isEnabled: Boolean = true,
    val sortOrder: Int = 0
) {
    /**
     * Normalized trigger in lowercase and stripped of punctuation.
     */
    val cleanTrigger: String
        get() = trigger.trim().lowercase().replace(Regex("[^a-z0-9 ]"), "")

    /**
     * Human-readable summary of the action payload.
     */
    val displayActionSummary: String
        get() = when (actionType) {
            AutomationActionType.SCREEN_ASSIST -> "Captures screen, analyzes with AI & copies result to clipboard"
            AutomationActionType.PHONE_CALL -> if (!targetNumber.isNullOrBlank()) "Calls $targetNumber directly" else "Opens phone dialer"
            AutomationActionType.SEND_SMS -> "Prepares SMS message to $targetNumber"
            AutomationActionType.OPEN_APP -> "Launches $targetApp"
            AutomationActionType.DEVICE_CONTROL -> "Adjusts device volume, brightness or alarms"
            AutomationActionType.CODE_SCRIPT -> "Runs ${scriptCode?.lines()?.count { it.isNotBlank() } ?: 0} action steps"
            AutomationActionType.CUSTOM_TOAST -> "Shows toast: \"$toastMessage\""
        }
}
