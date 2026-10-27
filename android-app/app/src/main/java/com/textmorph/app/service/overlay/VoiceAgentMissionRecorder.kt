package com.textmorph.app.service.overlay

import android.content.Context
import com.textmorph.app.data.model.ChatMessageEntity
import com.textmorph.app.data.model.InteractionTriggerSource
import com.textmorph.app.data.repository.ChatSessionRepository
import com.textmorph.app.data.repository.LlmInteractionRepository
import com.textmorph.app.util.AppLogger

/**
 * Handles starting a dedicated chat session and logging interactions for Voice Agent missions.
 * Extracted from FloatingToolbarWindowManager to maintain modularity and stay under 500 lines.
 */
object VoiceAgentMissionRecorder {

    private const val TAG = "VoiceAgentRecorder"

    suspend fun recordMissionStart(context: Context, goal: String): String? {
        return try {
            val chatRepo = ChatSessionRepository.getInstance(context)
            val session = chatRepo.createSession(title = "Voice Agent: ${goal.take(24)}")
            val userMsg = ChatMessageEntity(
                sessionId = session.id,
                role = "user",
                content = goal,
                isAgentMode = true
            )
            chatRepo.appendMessage(userMsg)
            session.id
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed recording voice agent session start: ${e.message}", e)
            null
        }
    }

    suspend fun recordMissionResult(
        context: Context,
        sessionId: String?,
        goal: String,
        resultMessage: String,
        isSuccess: Boolean = true
    ) {
        try {
            val responseText = resultMessage.ifBlank {
                if (isSuccess) "Voice agent completed task successfully." else "Voice agent task failed."
            }

            if (sessionId != null) {
                val chatRepo = ChatSessionRepository.getInstance(context)
                val assistantMsg = ChatMessageEntity(
                    sessionId = sessionId,
                    role = "assistant",
                    content = responseText,
                    actionStatus = if (isSuccess) "success" else "failed",
                    isAgentMode = true
                )
                chatRepo.appendMessage(assistantMsg)
            }

            LlmInteractionRepository.getInstance(context).recordInteraction(
                source = InteractionTriggerSource.VOICE_AGENT,
                title = "Voice Agent Mission",
                userPrompt = goal,
                rawOutput = responseText,
                cleanOutput = responseText,
                isError = !isSuccess,
                errorMessage = if (!isSuccess) responseText else null
            )
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed recording voice agent mission result: ${e.message}", e)
        }
    }
}
