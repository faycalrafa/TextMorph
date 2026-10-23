package com.textmorph.app.data.model

import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * Entity representing an inline slash command (e.g. `/fr`, `/fix`, `/shot`, `/clip`).
 * Allows users to trigger custom prompts with optional multimodal context (screenshot, camera, OCR, clipboard).
 */
@Serializable
data class SlashCommandEntity(
    val id: String = UUID.randomUUID().toString(),
    val trigger: String, // e.g. "fr" or "/fr" (normalized without leading slash)
    val title: String,
    val systemInstruction: String,
    val description: String = "",
    val isBuiltIn: Boolean = false,
    val sortOrder: Int = 0,
    val contextAction: ContextAction = ContextAction.NONE,
    val requiresVision: Boolean = false
) {
    /**
     * Normalized trigger without leading slash and in lowercase.
     */
    val cleanTrigger: String
        get() = trigger.trim().removePrefix("/").lowercase()

    /**
     * Display label formatted with leading slash (e.g. "/fr").
     */
    val displayTrigger: String
        get() = "/$cleanTrigger"
}
