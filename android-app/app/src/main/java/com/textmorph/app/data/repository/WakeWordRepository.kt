package com.textmorph.app.data.repository

import android.content.Context
import androidx.core.util.AtomicFile
import com.textmorph.app.data.model.WakeWordEntity
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

/**
 * Thread-safe repository managing offline wake-word triggers, calibration state,
 * and the strict "max 2 active triggers" rule.
 */
class WakeWordRepository(private val context: Context) {

    private val mutex = Mutex()
    private val TAG = "WakeWordRepository"
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    private val wakeWordsFile: File
        get() = File(context.filesDir, "wake_words_v1.json")

    private val _wakeWordsState = MutableStateFlow<List<WakeWordEntity>>(emptyList())
    val wakeWordsState: StateFlow<List<WakeWordEntity>> = _wakeWordsState.asStateFlow()

    // Cached set of valid tokens loaded from kws/tokens.txt
    private var validTokensCache: Set<String>? = null

    init {
        loadWakeWords()
    }

    private fun loadWakeWords() {
        try {
            if (!wakeWordsFile.exists()) {
                val seeds = getDefaultSeedWakeWords()
                _wakeWordsState.value = seeds
                saveWakeWordsToDisk(seeds)
                AppLogger.i(TAG, "Initialized default seed wake-words: ${seeds.size} items.")
            } else {
                val content = wakeWordsFile.readText()
                val parsed = json.decodeFromString<List<WakeWordEntity>>(content)
                _wakeWordsState.value = parsed
                AppLogger.i(TAG, "Loaded ${parsed.size} wake-words from disk.")
            }
        } catch (t: Throwable) {
            AppLogger.e(TAG, "Failed loading wake-words from disk: ${t.message}", t)
            val fallback = getDefaultSeedWakeWords()
            _wakeWordsState.value = fallback
        }
    }

    private fun saveWakeWordsToDisk(items: List<WakeWordEntity>) {
        try {
            val atomicFile = AtomicFile(wakeWordsFile)
            val bytes = json.encodeToString(items).toByteArray(Charsets.UTF_8)
            val fos = atomicFile.startWrite()
            try {
                fos.write(bytes)
                atomicFile.finishWrite(fos)
                AppLogger.d(TAG, "Saved ${items.size} wake-words atomically to disk.")
            } catch (t: Throwable) {
                atomicFile.failWrite(fos)
                AppLogger.e(TAG, "Failed atomic write for wake-words: ${t.message}", t)
            }
        } catch (t: Throwable) {
            AppLogger.e(TAG, "Error saving wake-words to disk: ${t.message}", t)
        }
    }

    suspend fun toggleWakeWord(id: String): Result<Boolean> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val currentList = _wakeWordsState.value
            val item = currentList.find { it.id == id }
                ?: return@withContext Result.failure(IllegalArgumentException("Wake-word with id '$id' not found."))

            if (!item.isEnabled) {
                // Must be calibrated first
                if (!item.isCalibrated) {
                    return@withContext Result.failure(
                        IllegalStateException("Trigger '${item.displayName}' must be calibrated with 3 successful tests before activation.")
                    )
                }

                // Strict constraint: cannot activate more than 2
                val currentActiveCount = currentList.count { it.isEnabled }
                if (currentActiveCount >= 2) {
                    return@withContext Result.failure(
                        IllegalStateException("Cannot activate more than two wake-words simultaneously. Please deactivate one first.")
                    )
                }
            }

            val updatedList = currentList.map {
                if (it.id == id) it.copy(isEnabled = !it.isEnabled) else it
            }
            _wakeWordsState.value = updatedList
            saveWakeWordsToDisk(updatedList)
            val newState = updatedList.first { it.id == id }.isEnabled
            AppLogger.i(TAG, "Toggled wake-word '${item.displayName}' (id=$id): isEnabled=$newState")
            Result.success(newState)
        }
    }

    suspend fun recordCalibrationSuccess(id: String): Result<Boolean> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val currentList = _wakeWordsState.value
            val item = currentList.find { it.id == id }
                ?: return@withContext Result.failure(IllegalArgumentException("Wake-word with id '$id' not found."))

            val currentActiveCount = currentList.count { it.isEnabled }
            val autoEnable = !item.isEnabled && currentActiveCount < 2

            val updatedList = currentList.map {
                if (it.id == id) {
                    it.copy(
                        isCalibrated = true,
                        testPassCount = 3,
                        isEnabled = if (autoEnable) true else it.isEnabled
                    )
                } else it
            }
            _wakeWordsState.value = updatedList
            saveWakeWordsToDisk(updatedList)
            AppLogger.i(TAG, "Recorded calibration success for '${item.displayName}'. Auto-enabled=$autoEnable")
            Result.success(autoEnable)
        }
    }

    suspend fun resetCalibration(id: String): Result<Unit> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val currentList = _wakeWordsState.value
            val updatedList = currentList.map {
                if (it.id == id) it.copy(isCalibrated = false, testPassCount = 0, isEnabled = false) else it
            }
            _wakeWordsState.value = updatedList
            saveWakeWordsToDisk(updatedList)
            AppLogger.d(TAG, "Reset calibration for wake-word id=$id")
            Result.success(Unit)
        }
    }

    suspend fun saveCustomWakeWord(entity: WakeWordEntity): Result<Unit> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val missingTokens = validatePhonemes(entity.phonemes)
            if (missingTokens.isNotEmpty()) {
                return@withContext Result.failure(
                    IllegalArgumentException("Invalid phonemes. Unrecognized tokens: ${missingTokens.joinToString()}")
                )
            }

            val currentList = _wakeWordsState.value.toMutableList()
            val existingIndex = currentList.indexOfFirst { it.id == entity.id }
            if (existingIndex >= 0) {
                currentList[existingIndex] = entity
            } else {
                currentList.add(entity)
            }
            _wakeWordsState.value = currentList
            saveWakeWordsToDisk(currentList)
            AppLogger.i(TAG, "Saved custom wake-word: '${entity.displayName}' (id=${entity.id})")
            Result.success(Unit)
        }
    }

    suspend fun deleteWakeWord(id: String): Result<Unit> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val currentList = _wakeWordsState.value
            val item = currentList.find { it.id == id }
                ?: return@withContext Result.failure(IllegalArgumentException("Wake-word not found"))
            if (item.isBuiltIn) {
                return@withContext Result.failure(IllegalStateException("Built-in wake-words cannot be deleted."))
            }

            val updatedList = currentList.filterNot { it.id == id }
            _wakeWordsState.value = updatedList
            saveWakeWordsToDisk(updatedList)
            AppLogger.i(TAG, "Deleted custom wake-word '${item.displayName}' (id=$id)")
            Result.success(Unit)
        }
    }

    fun buildActiveKeywordsString(): String {
        val activeItems = _wakeWordsState.value.filter { it.isEnabled }
        if (activeItems.isEmpty()) {
            return DEFAULT_KEYWORDS_FALLBACK
        }

        val lines = mutableListOf<String>()
        for (item in activeItems.take(2)) {
            val tag = if (item.aliasTag.startsWith("@")) item.aliasTag else "@${item.aliasTag}"
            item.phonemes.lineSequence().forEach { line ->
                val trimmed = line.trim()
                if (trimmed.isNotEmpty()) {
                    val tokensOnly = trimmed.substringBefore("@").trim()
                    lines.add("$tokensOnly $tag")
                }
            }
        }
        return lines.joinToString("\n")
    }

    fun getValidTokens(): Set<String> {
        validTokensCache?.let { return it }
        return try {
            val tokensStream = context.assets.open("kws/tokens.txt")
            val tokens = tokensStream.bufferedReader().useLines { lines ->
                lines.mapNotNull { line ->
                    val parts = line.trim().split("\\s+".toRegex())
                    parts.firstOrNull()?.takeIf { it.isNotBlank() }
                }.toSet()
            }
            validTokensCache = tokens
            tokens
        } catch (t: Throwable) {
            AppLogger.e(TAG, "Error loading tokens.txt from assets: ${t.message}", t)
            emptySet()
        }
    }

    fun validatePhonemes(phonemes: String): List<String> {
        val validTokens = getValidTokens()
        if (validTokens.isEmpty()) return emptyList()

        val missing = mutableListOf<String>()
        phonemes.lineSequence().forEach { rawLine ->
            val cleanLine = rawLine.substringBefore("@").trim()
            val tokens = cleanLine.split("\\s+".toRegex()).filter { it.isNotBlank() }
            for (token in tokens) {
                if (!validTokens.contains(token)) {
                    missing.add(token)
                }
            }
        }
        return missing.distinct()
    }

    companion object {
        const val DEFAULT_KEYWORDS_FALLBACK =
            "K AH0 M P Y UW1 T ER0 @computer\n" +
            "K AH1 M P Y UW1 T ER0 @computer\n" +
            "HH EY1 T AA1 M IH0 M @hey_tamim\n" +
            "HH EY1 T AA1 M IY0 M @hey_tamim"

        @Volatile
        private var instance: WakeWordRepository? = null

        fun getInstance(context: Context): WakeWordRepository {
            return instance ?: synchronized(this) {
                instance ?: WakeWordRepository(context.applicationContext).also { instance = it }
            }
        }

        fun getDefaultSeedWakeWords(): List<WakeWordEntity> = listOf(
            WakeWordEntity(
                id = "computer",
                displayName = "Computer",
                phonemes = "K AH0 M P Y UW1 T ER0\nK AH1 M P Y UW1 T ER0",
                aliasTag = "@computer",
                isBuiltIn = true,
                isCalibrated = true,
                isEnabled = true,
                testPassCount = 3
            ),
            WakeWordEntity(
                id = "hey_tamim",
                displayName = "Hey Tamim",
                phonemes = "HH EY1 T AA1 M IH0 M\nHH EY1 T AA1 M IY0 M",
                aliasTag = "@hey_tamim",
                isBuiltIn = true,
                isCalibrated = true,
                isEnabled = true,
                testPassCount = 3
            ),
            WakeWordEntity(
                id = "jarvis",
                displayName = "Jarvis",
                phonemes = "JH AA1 R V IH0 S\nJH AA1 R V IY0 S",
                aliasTag = "@jarvis",
                isBuiltIn = true,
                isCalibrated = false,
                isEnabled = false,
                testPassCount = 0
            ),
            WakeWordEntity(
                id = "hey_textmorph",
                displayName = "Hey TextMorph",
                phonemes = "HH EY1 T EH1 K S T M AO1 R F",
                aliasTag = "@hey_textmorph",
                isBuiltIn = true,
                isCalibrated = false,
                isEnabled = false,
                testPassCount = 0
            ),
            WakeWordEntity(
                id = "hey_assistant",
                displayName = "Hey Assistant",
                phonemes = "HH EY1 AH0 S IH1 S T AH0 N T",
                aliasTag = "@hey_assistant",
                isBuiltIn = true,
                isCalibrated = false,
                isEnabled = false,
                testPassCount = 0
            ),
            WakeWordEntity(
                id = "open_sesame",
                displayName = "Open Sesame",
                phonemes = "OW1 P AH0 N S EH1 S AH0 M IY0",
                aliasTag = "@open_sesame",
                isBuiltIn = true,
                isCalibrated = false,
                isEnabled = false,
                testPassCount = 0
            )
        )
    }
}
