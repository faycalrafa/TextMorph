package com.textmorph.app.data.repository

import android.content.Context
import androidx.core.util.AtomicFile
import com.textmorph.app.data.model.PromptCategory
import com.textmorph.app.data.model.PromptEntity
import com.textmorph.app.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

class PromptRepository(private val context: Context) {

    private val mutex = Mutex()
    private val TAG = "PromptRepository"
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    private val promptsFile: File
        get() = File(context.filesDir, "custom_prompts_v1.json")

    private val _promptsState = MutableStateFlow<List<PromptEntity>>(emptyList())
    val promptsState: StateFlow<List<PromptEntity>> = _promptsState.asStateFlow()

    init {
        loadPrompts()
    }

    private fun loadPrompts() {
        try {
            if (!promptsFile.exists()) {
                val seeds = getDefaultSeedPrompts()
                _promptsState.value = seeds
                savePromptsToDisk(seeds)
            } else {
                val content = promptsFile.readText()
                val parsed = json.decodeFromString<List<PromptEntity>>(content)
                _promptsState.value = parsed.sortedBy { it.sortOrder }
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error loading prompts from disk, falling back to seeds: ${e.message}", e)
            val seeds = getDefaultSeedPrompts()
            _promptsState.value = seeds
        }
    }

    suspend fun addPrompt(prompt: PromptEntity) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val updated = _promptsState.value.toMutableList().apply {
                add(prompt)
            }.sortedBy { it.sortOrder }
            _promptsState.value = updated
            savePromptsToDisk(updated)
            AppLogger.i(TAG, "Added prompt: ${prompt.title} (${prompt.id})")
        }
    }

    suspend fun updatePrompt(prompt: PromptEntity) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val updated = _promptsState.value.map {
                if (it.id == prompt.id) prompt else it
            }.sortedBy { it.sortOrder }
            _promptsState.value = updated
            savePromptsToDisk(updated)
            AppLogger.i(TAG, "Updated prompt: ${prompt.title} (${prompt.id})")
        }
    }

    suspend fun deletePrompt(id: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val updated = _promptsState.value.filterNot { it.id == id }
            _promptsState.value = updated
            savePromptsToDisk(updated)
            AppLogger.i(TAG, "Deleted prompt: $id")
        }
    }

    suspend fun resetToDefaults() = withContext(Dispatchers.IO) {
        mutex.withLock {
            val seeds = getDefaultSeedPrompts()
            _promptsState.value = seeds
            savePromptsToDisk(seeds)
            AppLogger.i(TAG, "Reset prompts to default seed data.")
        }
    }

    private fun savePromptsToDisk(prompts: List<PromptEntity>) {
        try {
            val atomicFile = AtomicFile(promptsFile)
            val content = json.encodeToString(prompts)
            val stream = atomicFile.startWrite()
            try {
                stream.write(content.toByteArray(Charsets.UTF_8))
                atomicFile.finishWrite(stream)
            } catch (writeEx: Exception) {
                atomicFile.failWrite(stream)
                throw writeEx
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to write prompts atomically to disk: ${e.message}", e)
        }
    }

    companion object {
        fun getDefaultSeedPrompts(): List<PromptEntity> = listOf(
            // Text Prompts
            PromptEntity(
                id = "seed_fix_grammar",
                title = "Fix Grammar",
                systemInstruction = "Fix all grammar, punctuation, and spelling errors while preserving the original voice.",
                iconEmoji = "✏️",
                category = PromptCategory.TEXT_TRANSFORM,
                requiresVision = false,
                sortOrder = 0
            ),
            PromptEntity(
                id = "seed_make_concise",
                title = "Make Concise",
                systemInstruction = "Make the text concise, clear, and direct without losing key meaning.",
                iconEmoji = "✂️",
                category = PromptCategory.TEXT_TRANSFORM,
                requiresVision = false,
                sortOrder = 1
            ),
            PromptEntity(
                id = "seed_pro_tone",
                title = "Professional Tone",
                systemInstruction = "Rewrite the text in an articulate, polished, and professional tone.",
                iconEmoji = "💼",
                category = PromptCategory.TEXT_TRANSFORM,
                requiresVision = false,
                sortOrder = 2
            ),
            PromptEntity(
                id = "seed_casual_tone",
                title = "Casual Tone",
                systemInstruction = "Rewrite the text in a friendly, conversational, and natural tone.",
                iconEmoji = "💬",
                category = PromptCategory.TEXT_TRANSFORM,
                requiresVision = false,
                sortOrder = 3
            ),
            PromptEntity(
                id = "seed_expand",
                title = "Expand Text",
                systemInstruction = "Elaborate and expand on the ideas with helpful detail and clarity.",
                iconEmoji = "📖",
                category = PromptCategory.TEXT_TRANSFORM,
                requiresVision = false,
                sortOrder = 4
            ),

            // Smart Reply Prompts
            PromptEntity(
                id = "seed_reply_agree",
                title = "Enthusiastic Reply",
                systemInstruction = "Generate a polite and enthusiastic affirmative reply to the ongoing conversation.",
                iconEmoji = "👍",
                category = PromptCategory.SMART_REPLY,
                requiresVision = false,
                sortOrder = 5
            ),
            PromptEntity(
                id = "seed_reply_inquire",
                title = "Thoughtful Inquiry",
                systemInstruction = "Generate a friendly follow-up question asking for more details or clarification.",
                iconEmoji = "🤔",
                category = PromptCategory.SMART_REPLY,
                requiresVision = false,
                sortOrder = 6
            ),
            PromptEntity(
                id = "seed_reply_decline",
                title = "Polite Decline",
                systemInstruction = "Generate a kind, respectful, and brief decline.",
                iconEmoji = "✋",
                category = PromptCategory.SMART_REPLY,
                requiresVision = false,
                sortOrder = 7
            ),

            // Vision Prompts
            PromptEntity(
                id = "seed_vision_describe",
                title = "Describe Screen/Image",
                systemInstruction = "Analyze and describe what is visible in this image in detail.",
                iconEmoji = "🔍",
                category = PromptCategory.VISION_ACTION,
                requiresVision = true,
                sortOrder = 8
            ),
            PromptEntity(
                id = "seed_vision_math",
                title = "Solve Puzzle / Math",
                systemInstruction = "Read the math equation or puzzle on screen and solve it step-by-step.",
                iconEmoji = "🧩",
                category = PromptCategory.VISION_ACTION,
                requiresVision = true,
                sortOrder = 9
            ),
            PromptEntity(
                id = "seed_vision_ocr",
                title = "Extract & Format Text (OCR+)",
                systemInstruction = "Extract all readable text from this image and format it cleanly in Markdown.",
                iconEmoji = "📄",
                category = PromptCategory.VISION_ACTION,
                requiresVision = true,
                sortOrder = 10
            ),
            PromptEntity(
                id = "seed_vision_meme",
                title = "Explain Meme / Chart",
                systemInstruction = "Explain the visual humor or analyze the data trends shown in this image.",
                iconEmoji = "📊",
                category = PromptCategory.VISION_ACTION,
                requiresVision = true,
                sortOrder = 11
            )
        )
    }
}
