package com.textmorph.app.llm

import kotlinx.coroutines.flow.Flow

interface LlmEngine {
    /**
     * Initializes the engine with the specified model file path or config.
     * Returns true if successfully loaded and ready for inference.
     */
    suspend fun initialize(modelPath: String? = null): Boolean

    /**
     * Streams inference tokens token-by-token.
     * Guaranteed not to include <think> reasoning tokens.
     */
    fun generateStream(
        prompt: String,
        systemInstruction: String? = null,
        imageBytes: ByteArray? = null
    ): Flow<String>

    /**
     * Generates the complete inference result as a single string.
     */
    suspend fun generate(
        prompt: String,
        systemInstruction: String? = null,
        imageBytes: ByteArray? = null
    ): String

    /**
     * Checks if the model or client session is currently active and ready.
     */
    fun isLoaded(): Boolean

    /**
     * Releases any GPU or native model resources.
     */
    suspend fun unload()
}
