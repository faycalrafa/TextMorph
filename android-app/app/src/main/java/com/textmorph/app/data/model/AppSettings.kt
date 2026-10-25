package com.textmorph.app.data.model

import com.textmorph.app.theme.ThemeMode
import kotlinx.serialization.Serializable

enum class EngineType(val displayName: String) {
    ON_DEVICE_LITERT("On-Device (LiteRT-LM)"),
    ON_DEVICE_LLAMA_CPP("On-Device (llama.cpp GGUF)"),
    CLOUD_OPENAI("Cloud OpenAI Compatible")
}

@Serializable
enum class ActionCommandSyntax(val displayName: String) {
    CODE_AS_ACTION("Code-as-Action"),
    JSON_BASED("JSON-Based")
}

@Serializable
enum class ScreenPerceptionMode(val displayName: String) {
    ACCESSIBILITY_TREE("Accessibility Tree"),
    SCREEN_CAPTURE("Screen Capture (Multimodal)"),
    PRIVATE_AGENT("Private Agent Way")
}

@Serializable
data class AppSettings(
    val theme: ThemeMode = ThemeMode.DARK_SLATE,
    val selectedEngine: EngineType = EngineType.ON_DEVICE_LITERT,
    val actionCommandSyntax: ActionCommandSyntax = ActionCommandSyntax.CODE_AS_ACTION,
    val screenPerceptionMode: ScreenPerceptionMode = ScreenPerceptionMode.ACCESSIBILITY_TREE,
    val activeModelPath: String? = null,
    val activeModelUri: String? = null,
    val activeModelName: String = "gemma-4-E2B-it.litertlm",
    val activeMmprojPath: String? = null,
    val activeMmprojUri: String? = null,
    val activeMmprojName: String? = null,
    val localServerEnabled: Boolean = false,
    val localServerPort: Int = 8080,
    val localServerBearerToken: String = "",
    val cloudBaseUrl: String = "https://api.openai.com/v1",
    val cloudApiKey: String = "",
    val cloudModelId: String = "gpt-4o-mini",
    val floatingToolbarEnabled: Boolean = true,
    val alwaysShowFloatingToolbar: Boolean = false,
    val autoSuggestReplies: Boolean = true,
    val preferredAccelerator: String = "AUTO",
    val probeTimeoutMs: Long = 30000L,
    val floatingToolbarX: Int? = null,
    val floatingToolbarY: Int? = null,
    val hasShownProminentDisclosure: Boolean = false,
    val compactChipsMode: Boolean = false,
    val alwaysOpenBarWithKeyboard: Boolean = true,
    val quickFixEnabled: Boolean = false,
    @Deprecated("Feature removed")
    val guessNextWordEnabled: Boolean = false,
    val autoSendVoiceInput: Boolean = true,
    val maxTokens: Int = 1024,
    val temperature: Float = 0.7f,
    val topK: Int = 40,
    val topP: Float = 0.9f,
    val enableThinking: Boolean = false,
    val speculativeDecoding: Boolean = false,
    val defaultSystemPrompt: String = "You are a helpful, concise AI writing assistant. Transform or complete text according to user instructions. Return ONLY the final output without preamble or explanations.",
    val agentSystemPrompt: String = com.textmorph.app.agent.prompts.AgentPrompts.AGENT_SYSTEM_PROMPT_CODE_ACTION
)
