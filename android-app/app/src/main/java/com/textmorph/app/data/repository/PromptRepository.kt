package com.textmorph.app.data.repository

import android.content.Context
import androidx.core.util.AtomicFile
import com.textmorph.app.data.model.ContextAction
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
                val migrated = parsed.map { prompt ->
                    var p = prompt
                    if (p.category == PromptCategory.SMART_REPLY) {
                        p = p.copy(
                            category = PromptCategory.TEXT_TRANSFORM,
                            contextAction = if (p.contextAction == ContextAction.ACCESSIBILITY_TEXT) ContextAction.NONE else p.contextAction
                        )
                    }
                    if (p.requiresVision && p.contextAction == ContextAction.NONE) {
                        p = p.copy(contextAction = ContextAction.SCREENSHOT)
                    }
                    if (p.id == "seed_fix_grammar" && p.systemInstruction.startsWith("Fix all grammar, punctuation")) {
                        p = p.copy(systemInstruction = "take this text and Fix all grammar, punctuation, and spelling errors while preserving the original Meaning and return only that fixed message without any extra comments on it")
                    } else if (p.id == "seed_make_concise" && p.systemInstruction.startsWith("Make the text concise")) {
                        p = p.copy(systemInstruction = "take this text and Make the text concise, clear, and direct without losing key meaning and return only that transformed message without any extra comments on it")
                    } else if (p.id == "seed_pro_tone" && p.systemInstruction.startsWith("Rewrite the text in an articulate")) {
                        p = p.copy(systemInstruction = "take this text and Rewrite the text in an articulate, polished, and professional tone and return only that transformed message without any extra comments on it")
                    } else if (p.id == "seed_casual_tone" && p.systemInstruction.startsWith("Rewrite the text in a friendly")) {
                        p = p.copy(systemInstruction = "take this text and Rewrite the text in a friendly, conversational, and natural tone and return only that transformed message without any extra comments on it")
                    } else if (p.id == "seed_expand" && p.systemInstruction.startsWith("Elaborate and expand")) {
                        p = p.copy(systemInstruction = "take this text and Elaborate and expand on the ideas with helpful detail and clarity and return only that transformed message without any extra comments on it")
                    } else if (p.id == "seed_clipboard_context" && p.systemInstruction.startsWith("Analyze or polish")) {
                        p = p.copy(systemInstruction = "take this text and Analyze or polish the clipboard content provided and return only that result without any extra comments on it")
                    }
                    p
                }.sortedBy { it.sortOrder }
                _promptsState.value = migrated
                savePromptsToDisk(migrated)
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error loading prompts from disk, falling back to seeds: ${e.message}", e)
            val seeds = getDefaultSeedPrompts()
            _promptsState.value = seeds
        }
    }

    suspend fun addPrompt(prompt: PromptEntity) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val currentList = _promptsState.value.toMutableList()
            val maxSortOrder = currentList.maxOfOrNull { it.sortOrder } ?: -1
            val normalizedPrompt = prompt.copy(sortOrder = maxSortOrder + 1)
            currentList.add(normalizedPrompt)
            val updated = currentList.sortedBy { it.sortOrder }
            _promptsState.value = updated
            savePromptsToDisk(updated)
            AppLogger.i(TAG, "Added prompt: ${prompt.title} (${prompt.id}) at sortOrder=${normalizedPrompt.sortOrder}")
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
                .mapIndexed { index, prompt -> prompt.copy(sortOrder = index) }
            _promptsState.value = updated
            savePromptsToDisk(updated)
            AppLogger.i(TAG, "Deleted prompt: $id")
        }
    }

    suspend fun movePrompt(promptId: String, direction: Int) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val list = _promptsState.value.sortedBy { it.sortOrder }.toMutableList()
            val currentIndex = list.indexOfFirst { it.id == promptId }
            if (currentIndex == -1) return@withLock
            val targetIndex = currentIndex + direction
            if (targetIndex in list.indices) {
                val currentItem = list[currentIndex]
                val targetItem = list[targetIndex]
                list[currentIndex] = targetItem
                list[targetIndex] = currentItem
                // Re-index all sortOrders cleanly
                val reordered = list.mapIndexed { index, item -> item.copy(sortOrder = index) }
                _promptsState.value = reordered
                savePromptsToDisk(reordered)
                AppLogger.i(TAG, "Moved prompt '${currentItem.title}' ($promptId) from $currentIndex to $targetIndex")
            }
        }
    }

    suspend fun reorderPrompts(newOrderedList: List<PromptEntity>) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val indexed = newOrderedList.mapIndexed { index, item -> item.copy(sortOrder = index) }
            _promptsState.value = indexed
            savePromptsToDisk(indexed)
            AppLogger.i(TAG, "Reordered ${indexed.size} prompts in repository.")
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
        @Volatile
        private var instance: PromptRepository? = null

        fun getInstance(context: Context): PromptRepository {
            return instance ?: synchronized(this) {
                instance ?: PromptRepository(context.applicationContext).also { instance = it }
            }
        }

        fun getDefaultSeedPrompts(): List<PromptEntity> = listOf(
            // Text Prompts
            PromptEntity(
                id = "seed_fix_grammar",
                title = "Fix Grammar",
                systemInstruction = "take this text and Fix all grammar, punctuation, and spelling errors while preserving the original Meaning and return only that fixed message without any extra comments on it",
                iconEmoji = "✏️",
                category = PromptCategory.TEXT_TRANSFORM,
                requiresVision = false,
                sortOrder = 0,
                contextAction = ContextAction.NONE
            ),
            PromptEntity(
                id = "seed_make_concise",
                title = "Make Concise",
                systemInstruction = "take this text and Make the text concise, clear, and direct without losing key meaning and return only that transformed message without any extra comments on it",
                iconEmoji = "✂️",
                category = PromptCategory.TEXT_TRANSFORM,
                requiresVision = false,
                sortOrder = 1,
                contextAction = ContextAction.NONE
            ),
            PromptEntity(
                id = "seed_pro_tone",
                title = "Professional Tone",
                systemInstruction = "take this text and Rewrite the text in an articulate, polished, and professional tone and return only that transformed message without any extra comments on it",
                iconEmoji = "💼",
                category = PromptCategory.TEXT_TRANSFORM,
                requiresVision = false,
                sortOrder = 2,
                contextAction = ContextAction.NONE
            ),
            PromptEntity(
                id = "seed_casual_tone",
                title = "Casual Tone",
                systemInstruction = "take this text and Rewrite the text in a friendly, conversational, and natural tone and return only that transformed message without any extra comments on it",
                iconEmoji = "💬",
                category = PromptCategory.TEXT_TRANSFORM,
                requiresVision = false,
                sortOrder = 3,
                contextAction = ContextAction.NONE
            ),
            PromptEntity(
                id = "seed_expand",
                title = "Expand Text",
                systemInstruction = "take this text and Elaborate and expand on the ideas with helpful detail and clarity and return only that transformed message without any extra comments on it",
                iconEmoji = "📖",
                category = PromptCategory.TEXT_TRANSFORM,
                requiresVision = false,
                sortOrder = 4,
                contextAction = ContextAction.NONE
            ),
            PromptEntity(
                id = "seed_clipboard_context",
                title = "Process Clipboard",
                systemInstruction = "take this text and Analyze or polish the clipboard content provided and return only that result without any extra comments on it",
                iconEmoji = "📋",
                category = PromptCategory.TEXT_TRANSFORM,
                requiresVision = false,
                sortOrder = 5,
                contextAction = ContextAction.CLIPBOARD
            ),

            // Vision Prompts
            PromptEntity(
                id = "seed_vision_describe",
                title = "Describe Screen/Image",
                systemInstruction = "Analyze and describe what is visible in this image in detail.",
                iconEmoji = "🔍",
                category = PromptCategory.VISION_ACTION,
                requiresVision = true,
                sortOrder = 8,
                contextAction = ContextAction.SCREENSHOT
            ),
            PromptEntity(
                id = "seed_vision_math",
                title = "Solve Puzzle / Math",
                systemInstruction = "Read the math equation or puzzle on screen and solve it step-by-step.",
                iconEmoji = "🧩",
                category = PromptCategory.VISION_ACTION,
                requiresVision = true,
                sortOrder = 9,
                contextAction = ContextAction.SCREENSHOT
            ),
            PromptEntity(
                id = "seed_vision_ocr",
                title = "Extract & Format Text (OCR+)",
                systemInstruction = "Extract all readable text from this image and format it cleanly in Markdown.",
                iconEmoji = "📄",
                category = PromptCategory.VISION_ACTION,
                requiresVision = true,
                sortOrder = 10,
                contextAction = ContextAction.OCR
            ),
            PromptEntity(
                id = "seed_vision_meme",
                title = "Explain Meme / Chart",
                systemInstruction = "Explain the visual humor or analyze the data trends shown in this image.",
                iconEmoji = "📊",
                category = PromptCategory.VISION_ACTION,
                requiresVision = true,
                sortOrder = 11,
                contextAction = ContextAction.SCREENSHOT
            )
        )
    }
}
