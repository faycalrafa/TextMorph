package com.textmorph.app.agent.voice

import android.content.Context
import com.textmorph.app.data.model.ChatMessageEntity
import com.textmorph.app.data.model.EngineType
import com.textmorph.app.data.model.InteractionTriggerSource
import com.textmorph.app.data.repository.ChatSessionRepository
import com.textmorph.app.data.repository.LlmInteractionRepository
import com.textmorph.app.data.repository.SettingsRepository
import com.textmorph.app.llm.LiteRtLmEngineManager
import com.textmorph.app.llm.LiteRtLmSessionDelegate
import com.textmorph.app.llm.LlamaCppEngineManager
import com.textmorph.app.llm.LlmEngine
import com.textmorph.app.llm.OpenAiCloudEngine
import com.textmorph.app.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.withContext

/**
 * Executes screen-grounded LLM inference for live voice Screen Assist queries:
 * Gathers active engine, formats screen context (multimodal image bytes and/or accessibility hierarchy),
 * streams sanitized tokens, and logs turns to ChatSessionRepository for persistent user recall.
 */
object ScreenAssistVoiceRunner {

    private const val TAG = "ScreenAssistVoiceRunner"

    data class ScreenContext(
        val imageBytes: ByteArray?,
        val visibleText: String?,
        val capturedTimestamp: Long = System.currentTimeMillis()
    )

    fun resolveActiveEngine(context: Context): LlmEngine {
        val appContext = context.applicationContext
        val settings = runCatching {
            SettingsRepository.getInstance(appContext).settingsState.value
        }.getOrNull()

        return when (settings?.selectedEngine) {
            EngineType.CLOUD_OPENAI -> {
                val cloud = OpenAiCloudEngine()
                cloud.updateConfig(settings.cloudBaseUrl, settings.cloudApiKey, settings.cloudModelId)
                cloud
            }
            EngineType.ON_DEVICE_LLAMA_CPP -> {
                LlamaCppEngineManager.getInstance(appContext)
            }
            else -> {
                LiteRtLmEngineManager.getInstance(appContext)
            }
        }
    }

    suspend fun runScreenInferenceStream(
        context: Context,
        screenContext: ScreenContext?,
        userQuestion: String
    ): Flow<String> = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val engine = resolveActiveEngine(appContext)

        if (!engine.isLoaded()) {
            AppLogger.w(TAG, "Cannot run Screen Assist inference: no LLM engine loaded.")
            return@withContext emptyFlow()
        }

        val promptBuilder = StringBuilder()
        val visibleText = screenContext?.visibleText?.trim()
        if (!visibleText.isNullOrBlank()) {
            promptBuilder.append("Active Screen Content:\n").append(visibleText).append("\n\n")
        }
        promptBuilder.append("User Spoken Question: ").append(userQuestion.trim())

        val systemInstruction = "You are an intelligent, concise on-device voice assistant. " +
                "The user is looking at their screen and speaking to you hands-free. " +
                "Answer the user's question directly, clearly, and concisely in natural spoken phrasing. " +
                "Never output reasoning traces, think tags, or raw code actions. Give direct answers suitable for audio speech."

        val prompt = promptBuilder.toString()
        val imageBytes = screenContext?.imageBytes

        try {
            if (imageBytes != null && engine.isVisionCapable) {
                AppLogger.i(TAG, "Streaming multimodal screen assist query (${imageBytes.size} image bytes)...")
                engine.generateStream(prompt, systemInstruction, imageBytes)
            } else {
                AppLogger.i(TAG, "Streaming text-based screen assist query...")
                engine.generateStream(prompt, systemInstruction)
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed initiating screen assist stream: ${e.message}", e)
            emptyFlow()
        }
    }

    suspend fun recordVoiceScreenTurn(
        context: Context,
        userQuestion: String,
        spokenAnswer: String
    ) = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        try {
            // 1. Record in dedicated Voice Agent chat session
            val chatRepo = ChatSessionRepository.getInstance(appContext)
            val session = chatRepo.getOrCreateVoiceAgentSession()

            val userMsg = ChatMessageEntity(
                sessionId = session.id,
                role = "user",
                content = userQuestion
            )
            val assistantMsg = ChatMessageEntity(
                sessionId = session.id,
                role = "assistant",
                content = spokenAnswer
            )
            chatRepo.appendMessage(userMsg)
            chatRepo.appendMessage(assistantMsg)

            // 2. Record interaction in telemetry repository
            LlmInteractionRepository.getInstance(appContext).recordInteraction(
                source = InteractionTriggerSource.VOICE_AGENT,
                title = "Live Voice Screen Assist",
                userPrompt = userQuestion,
                systemPrompt = "Voice Screen Assist Q&A",
                rawOutput = spokenAnswer,
                cleanOutput = spokenAnswer
            )
            AppLogger.i(TAG, "Recorded voice Screen Assist turn to session ${session.id}")
        } catch (t: Throwable) {
            AppLogger.w(TAG, "Failed recording voice Screen Assist turn: ${t.message}")
        }
    }
}
