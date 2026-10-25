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
                val defaultSeeds = getDefaultSeedPrompts()
                val hasLegacySeeds = parsed.any { it.id in LEGACY_SEED_IDS }
                val hasNewSeeds = parsed.any { it.id == "seed_creative_polish" || it.id == "seed_clipboard_summary" || it.id == "seed_screen_assist" }

                val migrated = if (hasLegacySeeds || !hasNewSeeds) {
                    AppLogger.i(TAG, "Migrating prompts from legacy seeds to the 3 modernized seeds.")
                    val customPrompts = parsed.filterNot { it.id in LEGACY_SEED_IDS || it.id.startsWith("seed_") }
                    (defaultSeeds + customPrompts).mapIndexed { index, prompt ->
                        prompt.copy(sortOrder = index)
                    }
                } else {
                    parsed.map { prompt ->
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
                        p
                    }.sortedBy { it.sortOrder }
                }

                val withCorrect = if (migrated.none { it.id == "seed_correct" }) {
                    val correctPrompt = defaultSeeds.first { it.id == "seed_correct" }
                    listOf(correctPrompt) + migrated
                } else {
                    migrated
                }.mapIndexed { index, prompt -> prompt.copy(sortOrder = index) }

                _promptsState.value = withCorrect
                savePromptsToDisk(withCorrect)
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

        val LEGACY_SEED_IDS = setOf(
            "seed_fix_grammar",
            "seed_make_concise",
            "seed_pro_tone",
            "seed_casual_tone",
            "seed_expand",
            "seed_clipboard_context",
            "seed_vision_describe",
            "seed_vision_math",
            "seed_vision_ocr",
            "seed_vision_meme"
        )

        fun getInstance(context: Context): PromptRepository {
            return instance ?: synchronized(this) {
                instance ?: PromptRepository(context.applicationContext).also { instance = it }
            }
        }

        fun getDefaultSeedPrompts(): List<PromptEntity> = listOf(
            PromptEntity(
                id = "seed_correct",
                title = "Correct",
                systemInstruction = "Fix all grammar, punctuation, and spelling errors in this text while preserving the original meaning, tone, and formatting. Return ONLY the corrected text directly—no introductions, quotation marks, explanations, or commentary.",
                iconEmoji = "✍️",
                iconName = "spellcheck",
                category = PromptCategory.TEXT_TRANSFORM,
                requiresVision = false,
                sortOrder = 0,
                contextAction = ContextAction.NONE
            ),
            PromptEntity(
                id = "seed_creative_polish",
                title = "Creative Polish",
                systemInstruction = "take this text and creatively enhance and elevate it: make it vibrant, articulate, and compelling while keeping the exact original intent and core facts. Return ONLY the polished text directly—no introductions, quotation marks, explanations, or commentary.",
                iconEmoji = "✨",
                iconName = "auto_awesome",
                category = PromptCategory.TEXT_TRANSFORM,
                requiresVision = false,
                sortOrder = 1,
                contextAction = ContextAction.NONE
            ),
            PromptEntity(
                id = "seed_clipboard_summary",
                title = "Summarize Clipboard",
                systemInstruction = "Summarize the clipboard content into a clear, concise summary capturing all key points. Return ONLY the summary directly—no explanations, greetings, quotes, or extra commentary.",
                iconEmoji = "📋",
                iconName = "summarize",
                category = PromptCategory.TEXT_TRANSFORM,
                requiresVision = false,
                sortOrder = 2,
                contextAction = ContextAction.CLIPBOARD
            ),
            PromptEntity(
                id = "seed_screen_assist",
                title = "Screen Assist",
                systemInstruction = "Analyze the image and follow these instructions:\nIf it is a chat or conversation: Write a brief, helpful reply directly addressing the latest incoming message. Output only the reply text—no explanations, quotation marks, or extra commentary.\nIf it is not a chat: Provide a single short paragraph that explains the meme, solves the puzzle, or summarizes what is shown on screen.",
                iconEmoji = "📱",
                iconName = "screenshot",
                category = PromptCategory.VISION_ACTION,
                requiresVision = true,
                sortOrder = 3,
                contextAction = ContextAction.SCREENSHOT
            )
        )
    }
}
