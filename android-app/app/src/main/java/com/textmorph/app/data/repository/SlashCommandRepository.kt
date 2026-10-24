package com.textmorph.app.data.repository

import android.content.Context
import androidx.core.util.AtomicFile
import com.textmorph.app.data.model.ContextAction
import com.textmorph.app.data.model.SlashCommandEntity
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

class SlashCommandRepository(private val context: Context) {

    private val mutex = Mutex()
    private val TAG = "SlashCommandRepo"
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    private val commandsFile: File
        get() = File(context.filesDir, "custom_slash_commands_v1.json")

    private val _commandsState = MutableStateFlow<List<SlashCommandEntity>>(emptyList())
    val commandsState: StateFlow<List<SlashCommandEntity>> = _commandsState.asStateFlow()

    init {
        loadCommands()
    }

    private fun loadCommands() {
        try {
            if (!commandsFile.exists()) {
                val seeds = getDefaultSeedCommands()
                _commandsState.value = seeds
                saveCommandsToDisk(seeds)
                AppLogger.i(TAG, "Initialized default seed commands: ${seeds.size} commands.")
            } else {
                val content = commandsFile.readText()
                val parsed = json.decodeFromString<List<SlashCommandEntity>>(content)
                val migrated = parsed.map { cmd ->
                    when (cmd.id) {
                        "cmd_es" -> SlashCommandEntity(
                            id = "cmd_ar",
                            trigger = "ar",
                            title = "Translate to Arabic",
                            systemInstruction = "Translate the user's text into natural, fluent, and idiomatic Arabic. Output ONLY the translated Arabic text with zero conversational commentary.",
                            description = "Translates input text to Arabic",
                            isBuiltIn = true,
                            sortOrder = cmd.sortOrder
                        )
                        "cmd_de" -> SlashCommandEntity(
                            id = "cmd_en",
                            trigger = "en",
                            title = "Translate to English",
                            systemInstruction = "Translate the user's text into natural, fluent, and idiomatic English. Output ONLY the translated English text with zero conversational commentary.",
                            description = "Translates input text to English",
                            isBuiltIn = true,
                            sortOrder = cmd.sortOrder
                        )
                        else -> cmd
                    }
                }.toMutableList()
                if (migrated.none { it.cleanTrigger == "ar" }) {
                    migrated.add(getDefaultSeedCommands().first { it.cleanTrigger == "ar" })
                }
                if (migrated.none { it.cleanTrigger == "en" }) {
                    migrated.add(getDefaultSeedCommands().first { it.cleanTrigger == "en" })
                }
                val sorted = migrated.sortedBy { it.sortOrder }
                _commandsState.value = sorted
                saveCommandsToDisk(sorted)
                AppLogger.i(TAG, "Loaded ${sorted.size} slash commands from disk (migrated /ar & /en).")
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error loading slash commands from disk, falling back to seeds: ${e.message}", e)
            val seeds = getDefaultSeedCommands()
            _commandsState.value = seeds
        }
    }

    suspend fun addCommand(command: SlashCommandEntity) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val currentList = _commandsState.value.toMutableList()
            val maxSortOrder = currentList.maxOfOrNull { it.sortOrder } ?: -1
            val normalized = command.copy(
                trigger = command.cleanTrigger,
                sortOrder = maxSortOrder + 1
            )
            // Remove any existing command with the exact same trigger
            currentList.removeAll { it.cleanTrigger == normalized.cleanTrigger }
            currentList.add(normalized)
            val updated = currentList.sortedBy { it.sortOrder }
            _commandsState.value = updated
            saveCommandsToDisk(updated)
            AppLogger.i(TAG, "Added slash command /${normalized.trigger} (${normalized.title})")
        }
    }

    suspend fun updateCommand(command: SlashCommandEntity) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val currentList = _commandsState.value.toMutableList()
            val index = currentList.indexOfFirst { it.id == command.id }
            val normalized = command.copy(trigger = command.cleanTrigger)
            if (index != -1) {
                currentList[index] = normalized
            } else {
                currentList.add(normalized)
            }
            val updated = currentList.sortedBy { it.sortOrder }
            _commandsState.value = updated
            saveCommandsToDisk(updated)
            AppLogger.i(TAG, "Updated slash command /${normalized.trigger}")
        }
    }

    suspend fun deleteCommand(commandId: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val currentList = _commandsState.value.toMutableList()
            val removed = currentList.removeAll { it.id == commandId }
            if (removed) {
                _commandsState.value = currentList
                saveCommandsToDisk(currentList)
                AppLogger.i(TAG, "Deleted slash command: $commandId")
            }
        }
    }

    suspend fun resetToDefaults() = withContext(Dispatchers.IO) {
        mutex.withLock {
            val seeds = getDefaultSeedCommands()
            _commandsState.value = seeds
            saveCommandsToDisk(seeds)
            AppLogger.i(TAG, "Reset slash commands to default seeds.")
        }
    }

    /**
     * Parses the given text to detect any registered slash command (e.g. `/fr`).
     * If matched, removes the slash command token from the text, cleans extra spaces,
     * and returns the matched command alongside the cleaned remaining text.
     * If no registered command is matched, returns `Pair(null, text)`.
     */
    fun matchAndExtractCommand(text: String): Pair<SlashCommandEntity?, String> {
        if (text.isBlank()) return Pair(null, text)

        val commands = _commandsState.value
        // Regex looks for slash followed by alphanumeric/dash/underscore token
        val regex = Regex("""(?:^|\s)/([a-zA-Z0-9_-]+)(?:\s|$)""")
        val matches = regex.findAll(text).toList()

        for (match in matches) {
            val rawMatchedToken = match.groupValues[1].lowercase()
            val matchedCmd = commands.firstOrNull { it.cleanTrigger == rawMatchedToken }
            if (matchedCmd != null) {
                // Strip the `/token` from the input string
                val tokenPattern = Regex("""\s*/""" + Regex.escape(match.groupValues[1]) + """\b|\b/""" + Regex.escape(match.groupValues[1]) + """\s*""")
                val cleanedText = text.replace(tokenPattern, " ")
                    .replace(Regex("""\s+"""), " ")
                    .trim()

                AppLogger.i(TAG, "Matched slash command /${matchedCmd.trigger} for input: '$text' -> cleaned: '$cleanedText'")
                return Pair(matchedCmd, cleanedText)
            }
        }

        return Pair(null, text)
    }

    private fun saveCommandsToDisk(commands: List<SlashCommandEntity>) {
        try {
            val atomicFile = AtomicFile(commandsFile)
            val jsonString = json.encodeToString(commands)
            val outputStream = atomicFile.startWrite()
            try {
                outputStream.bufferedWriter().use { it.write(jsonString) }
                atomicFile.finishWrite(outputStream)
                AppLogger.d(TAG, "Saved ${commands.size} commands atomically to disk.")
            } catch (e: Exception) {
                atomicFile.failWrite(outputStream)
                throw e
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed saving slash commands to disk: ${e.message}", e)
        }
    }

    companion object {
        @Volatile
        private var instance: SlashCommandRepository? = null

        fun getInstance(context: Context): SlashCommandRepository {
            return instance ?: synchronized(this) {
                instance ?: SlashCommandRepository(context.applicationContext).also { instance = it }
            }
        }

        fun getDefaultSeedCommands(): List<SlashCommandEntity> = listOf(
            SlashCommandEntity(
                id = "cmd_fr",
                trigger = "fr",
                title = "Translate to French",
                systemInstruction = "Translate the user's text into natural, fluent, and idiomatic French. Output ONLY the translated French text with zero conversational commentary.",
                description = "Translates input text to French",
                isBuiltIn = true,
                sortOrder = 0
            ),
            SlashCommandEntity(
                id = "cmd_ar",
                trigger = "ar",
                title = "Translate to Arabic",
                systemInstruction = "Translate the user's text into natural, fluent, and idiomatic Arabic. Output ONLY the translated Arabic text with zero commentary.",
                description = "Translates input text to Arabic",
                isBuiltIn = true,
                sortOrder = 1
            ),
            SlashCommandEntity(
                id = "cmd_en",
                trigger = "en",
                title = "Translate to English",
                systemInstruction = "Translate the user's text into natural, fluent, and idiomatic English. Output ONLY the translated English text with zero commentary.",
                description = "Translates input text to English",
                isBuiltIn = true,
                sortOrder = 2
            ),
            SlashCommandEntity(
                id = "cmd_fix",
                trigger = "fix",
                title = "Fix Grammar & Spelling",
                systemInstruction = "take this text and Fix all grammar, punctuation, and spelling errors while preserving the original Meaning and return only that fixed message without any extra comments on it",
                description = "Quick grammar and typo correction",
                isBuiltIn = true,
                sortOrder = 3
            ),
            SlashCommandEntity(
                id = "cmd_short",
                trigger = "short",
                title = "Make Concise",
                systemInstruction = "Make the text concise, clear, and direct. Remove fluff and wordiness without losing core meaning. Output ONLY the concise text.",
                description = "Shortens text to its essential meaning",
                isBuiltIn = true,
                sortOrder = 4
            ),
            SlashCommandEntity(
                id = "cmd_formal",
                trigger = "formal",
                title = "Professional Tone",
                systemInstruction = "Rewrite the text in an articulate, polite, and professional business tone. Output ONLY the polished text.",
                description = "Polishes text into professional tone",
                isBuiltIn = true,
                sortOrder = 5
            ),
            SlashCommandEntity(
                id = "cmd_casual",
                trigger = "casual",
                title = "Casual Tone",
                systemInstruction = "Rewrite the text in a friendly, conversational, and natural tone. Output ONLY the casual text.",
                description = "Rewrites text in a relaxed casual voice",
                isBuiltIn = true,
                sortOrder = 6
            ),
            SlashCommandEntity(
                id = "cmd_bullets",
                trigger = "bullets",
                title = "Convert to Bullet Points",
                systemInstruction = "Convert the key ideas, tasks, or points in the text into a clean, markdown bulleted list. Output ONLY the bulleted list.",
                description = "Formats key takeaways as bullet points",
                isBuiltIn = true,
                sortOrder = 7
            ),
            SlashCommandEntity(
                id = "cmd_shot",
                trigger = "shot",
                title = "Explain Screen",
                systemInstruction = "Analyze the provided screenshot along with the user's query and provide an accurate, helpful response.",
                description = "Captures screen and answers questions about it",
                isBuiltIn = true,
                sortOrder = 8,
                contextAction = ContextAction.SCREENSHOT,
                requiresVision = true
            ),
            SlashCommandEntity(
                id = "cmd_clip",
                trigger = "clip",
                title = "Process Clipboard",
                systemInstruction = "Process and transform the provided clipboard content according to the user's instructions.",
                description = "Applies command to current clipboard text",
                isBuiltIn = true,
                sortOrder = 9,
                contextAction = ContextAction.CLIPBOARD,
                requiresVision = false
            ),
            SlashCommandEntity(
                id = "cmd_ocr",
                trigger = "ocr",
                title = "Screen OCR Text",
                systemInstruction = "Extract all text from the current screen and follow the user's instruction.",
                description = "Extracts and formats on-screen text",
                isBuiltIn = true,
                sortOrder = 10,
                contextAction = ContextAction.OCR,
                requiresVision = true
            )
        )
    }
}
