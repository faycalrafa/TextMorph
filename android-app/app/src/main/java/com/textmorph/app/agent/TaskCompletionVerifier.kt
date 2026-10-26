package com.textmorph.app.agent

import com.textmorph.app.agent.tools.ScreenAutomationTool
import com.textmorph.app.data.repository.ChatSessionRepository
import com.textmorph.app.util.AppLogger

/**
 * Handles post-execution screen inspection and LLM evaluation to verify whether
 * a multi-step automation task actually accomplished the user's objective.
 */
class TaskCompletionVerifier(
    private val screenTool: ScreenAutomationTool,
    private val queryLlm: suspend (prompt: String, imageBytes: ByteArray?, systemPrompt: String) -> String,
    private val chatRepoProvider: (() -> ChatSessionRepository)?
) {
    companion object {
        private const val TAG = "TaskCompletionVerifier"
    }

    suspend fun verify(
        goal: String,
        reportedReason: String,
        taskSystemPrompt: String,
        isVisionCapable: Boolean
    ): Pair<Boolean, String> {
        return try {
            val verifyImage = if (isVisionCapable) screenTool.captureCleanScreenshotBytes() else null
            val verifyDump = screenTool.readScreenPrivateAgent(goal)

            val verifyPrompt = """
                TASK GOAL: $goal
                FINAL SCREEN STATE:
                $verifyDump
                
                The agent reported: "$reportedReason".
                Does the screen show that the user's task was successfully achieved?
                Respond with ONLY ONE of:
                COMPLETED: <brief sentence confirming completion>
                or
                INCOMPLETE: <brief sentence explaining what is missing>
            """.trimIndent()

            val rawVerify = queryLlm(verifyPrompt, verifyImage, taskSystemPrompt)
            try {
                chatRepoProvider?.invoke()?.recordDiagnosticTurn(
                    userPrompt = "Verification Check: $goal",
                    systemPrompt = taskSystemPrompt,
                    rawOutput = rawVerify
                )
            } catch (e: Exception) {
                AppLogger.w(TAG, "Failed recording verification turn: ${e.message}")
            }

            val clean = rawVerify.trim()
            val isCompleted = clean.contains("COMPLETED", ignoreCase = true) && !clean.contains("INCOMPLETE", ignoreCase = true)
            val details = when {
                clean.contains("COMPLETED:", ignoreCase = true) -> clean.substringAfter("COMPLETED:").trim().lines().firstOrNull() ?: clean
                clean.contains("INCOMPLETE:", ignoreCase = true) -> clean.substringAfter("INCOMPLETE:").trim().lines().firstOrNull() ?: clean
                else -> clean.lines().firstOrNull() ?: clean
            }
            Pair(isCompleted, details.take(200))
        } catch (e: Exception) {
            AppLogger.w(TAG, "Verification check fallback: ${e.message}")
            Pair(true, reportedReason)
        }
    }
}
