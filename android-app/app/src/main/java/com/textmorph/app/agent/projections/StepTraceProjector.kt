package com.textmorph.app.agent.projections

import com.textmorph.app.agent.events.AgentEventRecord
import com.textmorph.app.data.model.AgentStepTrace
import com.textmorph.app.util.AppLogger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Projects real-time step traces from events for the floating companion pill and UI accordion.
 */
object StepTraceProjector {

    private const val TAG = "StepTraceProjector"
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun projectStepTraces(events: List<AgentEventRecord>): List<AgentStepTrace> {
        val traces = mutableListOf<AgentStepTrace>()
        for (event in events) {
            try {
                when (event.eventType) {
                    "TOOL_REQUESTED" -> {
                        val payload = json.parseToJsonElement(event.payloadJson) as? JsonObject
                        val toolName = payload?.get("toolName")?.jsonPrimitive?.content.orEmpty()
                        val stepIndex = payload?.get("stepIndex")?.jsonPrimitive?.content?.toIntOrNull() ?: 1
                        traces.add(
                            AgentStepTrace(
                                stepIndex = stepIndex,
                                toolName = toolName,
                                inputQuery = toolName,
                                status = "running"
                            )
                        )
                    }

                    "TOOL_COMPLETED" -> {
                        val payload = json.parseToJsonElement(event.payloadJson) as? JsonObject
                        val toolName = payload?.get("toolName")?.jsonPrimitive?.content.orEmpty()
                        val outputSnippet = payload?.get("outputSnippet")?.jsonPrimitive?.content.orEmpty()
                        val stepIndex = payload?.get("stepIndex")?.jsonPrimitive?.content?.toIntOrNull() ?: 1
                        val duration = payload?.get("durationMs")?.jsonPrimitive?.content?.toLongOrNull() ?: 0L

                        val existingIdx = traces.indexOfLast { it.stepIndex == stepIndex }
                        if (existingIdx != -1) {
                            traces[existingIdx] = traces[existingIdx].copy(
                                status = "success",
                                outputSnippet = outputSnippet,
                                durationMs = duration
                            )
                        } else {
                            traces.add(
                                AgentStepTrace(
                                    stepIndex = stepIndex,
                                    toolName = toolName,
                                    inputQuery = toolName,
                                    status = "success",
                                    outputSnippet = outputSnippet,
                                    durationMs = duration
                                )
                            )
                        }
                    }

                    "TOOL_FAILED" -> {
                        val payload = json.parseToJsonElement(event.payloadJson) as? JsonObject
                        val errorMsg = payload?.get("errorMessage")?.jsonPrimitive?.content.orEmpty()
                        val stepIndex = payload?.get("stepIndex")?.jsonPrimitive?.content?.toIntOrNull() ?: 1

                        val existingIdx = traces.indexOfLast { it.stepIndex == stepIndex }
                        if (existingIdx != -1) {
                            traces[existingIdx] = traces[existingIdx].copy(
                                status = "failed",
                                failureReason = errorMsg
                            )
                        }
                    }
                }
            } catch (t: Throwable) {
                AppLogger.w(TAG, "Error projecting step trace event ${event.eventId}: ${t.message}")
            }
        }
        return traces
    }
}
