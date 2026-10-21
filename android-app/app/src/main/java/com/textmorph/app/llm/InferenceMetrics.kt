package com.textmorph.app.llm

/**
 * Encapsulates performance telemetry for real-time and post-generation execution.
 * Tracks time to first token (TTFT), tokens per second (tok/s), latency, and backend.
 */
data class InferenceMetrics(
    val timeToFirstTokenMs: Long = 0L,
    val totalDurationMs: Long = 0L,
    val tokenCount: Int = 0,
    val tokensPerSecond: Float = 0f,
    val activeBackend: String = "GPU (Vulkan/OpenCL)",
    val modelName: String = ""
)
