package com.textmorph.app.data.model

import kotlinx.serialization.Serializable

enum class PromptCategory(val displayName: String) {
    TEXT_TRANSFORM("Text Transform"),
    SMART_REPLY("Smart Reply"),
    VISION_ACTION("Vision Action")
}

@Serializable
data class PromptEntity(
    val id: String = java.util.UUID.randomUUID().toString(),
    val title: String,
    val systemInstruction: String,
    val iconEmoji: String = "✨",
    val category: PromptCategory = PromptCategory.TEXT_TRANSFORM,
    val requiresVision: Boolean = false,
    val sortOrder: Int = 0
)
