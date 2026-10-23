package com.textmorph.app.llm

data class ModelSessionState(
    val isLoaded: Boolean = false,
    val modelName: String = "No Model Loaded",
    val modelPath: String? = null,
    val backend: String = "Standby (Simulated)",
    val isVisionCapable: Boolean = true
)

data class ModelLoadingProgress(
    val isLoading: Boolean = false,
    val progress: Float = 0f,
    val statusText: String = "Idle",
    val logLines: List<String> = emptyList(),
    val error: String? = null,
    val success: Boolean = false
)
