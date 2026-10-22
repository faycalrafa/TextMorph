package com.textmorph.app.data.model

import kotlinx.serialization.Serializable

enum class PromptCategory(val displayName: String) {
    TEXT_TRANSFORM("Text Transform"),
    SMART_REPLY("Smart Reply"),
    VISION_ACTION("Vision Action")
}

@Serializable
enum class ContextAction(val displayName: String, val description: String) {
    NONE("None", "Text only, transforms field content directly"),
    SCREENSHOT("Add Screenshot", "Captures active screen and sends image to multimodal AI"),
    CAMERA("Open Camera", "Opens rear camera feed to capture a real-world photo"),
    OCR("Screen OCR", "Extracts readable text from the screen and appends to prompt"),
    ACCESSIBILITY_TEXT("Accessibility Screen Text", "Extracts visible screen text and conversation chat bubbles"),
    CLIPBOARD("Add Clipboard", "Adds clipboard contents to the request")
}

@Serializable
data class PromptEntity(
    val id: String = java.util.UUID.randomUUID().toString(),
    val title: String,
    val systemInstruction: String,
    val iconEmoji: String = "✨",
    val category: PromptCategory = PromptCategory.TEXT_TRANSFORM,
    val requiresVision: Boolean = false,
    val sortOrder: Int = 0,
    val contextAction: ContextAction = ContextAction.NONE
)
