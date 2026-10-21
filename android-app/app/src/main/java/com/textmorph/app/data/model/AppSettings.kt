package com.textmorph.app.data.model

import com.textmorph.app.theme.ThemeMode
import kotlinx.serialization.Serializable

enum class EngineType {
    ON_DEVICE_LITERT,
    CLOUD_OPENAI
}

@Serializable
data class AppSettings(
    val theme: ThemeMode = ThemeMode.FOREST_CLAY,
    val selectedEngine: EngineType = EngineType.ON_DEVICE_LITERT,
    val activeModelPath: String? = null,
    val activeModelUri: String? = null,
    val activeModelName: String = "gemma-4-E2B-it.litertlm",
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
    val hasShownProminentDisclosure: Boolean = false
)
