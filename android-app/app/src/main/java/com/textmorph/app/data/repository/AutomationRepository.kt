package com.textmorph.app.data.repository

import android.content.Context
import androidx.core.util.AtomicFile
import com.textmorph.app.data.model.AutomationActionType
import com.textmorph.app.data.model.AutomationEntity
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
import java.io.FileOutputStream

/**
 * Thread-safe repository managing built-in and user-defined automations and hot voice keys.
 * Persists data to internal storage using AtomicFile on Dispatchers.IO.
 */
class AutomationRepository private constructor(private val context: Context) {

    private val mutex = Mutex()
    private val TAG = "AutomationRepo"
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    private val automationsFile: File
        get() = File(context.filesDir, "custom_automations_v1.json")

    private val _automationsState = MutableStateFlow<List<AutomationEntity>>(emptyList())
    val automationsState: StateFlow<List<AutomationEntity>> = _automationsState.asStateFlow()

    init {
        loadAutomations()
    }

    private fun loadAutomations() {
        try {
            if (!automationsFile.exists()) {
                val seeds = getDefaultSeedAutomations()
                _automationsState.value = seeds
                saveAutomationsToDisk(seeds)
                AppLogger.i(TAG, "Initialized default seed automations (${seeds.size} items).")
            } else {
                val content = automationsFile.readText()
                val parsed = json.decodeFromString<List<AutomationEntity>>(content)
                val seeds = getDefaultSeedAutomations()
                val merged = parsed.toMutableList()

                // Ensure all default built-ins exist
                for (seed in seeds) {
                    if (merged.none { it.id == seed.id || (it.cleanTrigger == seed.cleanTrigger && it.isBuiltIn) }) {
                        merged.add(seed)
                    }
                }
                val sorted = merged.sortedBy { it.sortOrder }
                _automationsState.value = sorted
                saveAutomationsToDisk(sorted)
                AppLogger.i(TAG, "Loaded ${sorted.size} automations from disk.")
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed loading automations from disk, falling back to seeds: ${e.message}", e)
            val seeds = getDefaultSeedAutomations()
            _automationsState.value = seeds
        }
    }

    private fun saveAutomationsToDisk(list: List<AutomationEntity>) {
        val atomicFile = AtomicFile(automationsFile)
        var fos: FileOutputStream? = null
        try {
            fos = atomicFile.startWrite()
            val content = json.encodeToString(list)
            fos.write(content.toByteArray(Charsets.UTF_8))
            atomicFile.finishWrite(fos)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed writing automations to atomic file: ${e.message}", e)
            if (fos != null) {
                atomicFile.failWrite(fos)
            }
        }
    }

    suspend fun saveAutomation(automation: AutomationEntity) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val current = _automationsState.value.toMutableList()
            val existingIndex = current.indexOfFirst { it.id == automation.id }
            if (existingIndex >= 0) {
                current[existingIndex] = automation
            } else {
                current.add(automation)
            }
            val sorted = current.sortedBy { it.sortOrder }
            _automationsState.value = sorted
            saveAutomationsToDisk(sorted)
            AppLogger.i(TAG, "Saved automation: '${automation.title}' (trigger='${automation.trigger}')")
        }
    }

    suspend fun deleteAutomation(id: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val current = _automationsState.value.filterNot { it.id == id }
            _automationsState.value = current
            saveAutomationsToDisk(current)
            AppLogger.i(TAG, "Deleted automation with id: $id")
        }
    }

    suspend fun toggleAutomationEnabled(id: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val current = _automationsState.value.map {
                if (it.id == id) it.copy(isEnabled = !it.isEnabled) else it
            }
            _automationsState.value = current
            saveAutomationsToDisk(current)
            AppLogger.i(TAG, "Toggled enabled for automation id: $id")
        }
    }

    /**
     * Matches a spoken voice utterance or trigger input against active automations.
     * Uses normalized whitespace and punctuation stripping.
     */
    fun findMatchingAutomation(utterance: String): AutomationEntity? {
        val cleanInput = utterance.trim().lowercase().replace(Regex("[^a-z0-9 ]"), "")
        if (cleanInput.isBlank()) return null

        val active = _automationsState.value.filter { it.isEnabled }

        // 1. Exact match on clean trigger
        val exact = active.firstOrNull { it.cleanTrigger == cleanInput }
        if (exact != null) return exact

        // 2. Utterance starts with trigger phrase (e.g. "call my brother now" -> "call my brother")
        val prefixMatch = active.filter { cleanInput.startsWith(it.cleanTrigger) }
            .maxByOrNull { it.cleanTrigger.length }
        if (prefixMatch != null) return prefixMatch

        // 3. Utterance contains trigger phrase as a distinct word boundary
        val containsMatch = active.firstOrNull { cleanInput.contains(Regex("\\b${Regex.escape(it.cleanTrigger)}\\b")) }
        if (containsMatch != null) return containsMatch

        return null
    }

    companion object {
        @Volatile
        private var instance: AutomationRepository? = null

        fun getInstance(context: Context): AutomationRepository {
            return instance ?: synchronized(this) {
                instance ?: AutomationRepository(context.applicationContext).also { instance = it }
            }
        }

        fun getDefaultSeedAutomations(): List<AutomationEntity> = listOf(
            AutomationEntity(
                id = "seed_assist_me",
                trigger = "assist me",
                title = "Screen Assist & Clipboard",
                description = "Captures active screen context, runs AI assistance, and dumps results to clipboard.",
                actionType = AutomationActionType.SCREEN_ASSIST,
                promptInstruction = "You are an on-device screen assistant. Analyze the user's active screen and provide a direct, helpful, actionable solution or summary. Strict rule: Output ONLY the clean assistance text with zero conversational filler.",
                toastMessage = "📋 Screen Assist copied to clipboard!",
                copyToClipboard = true,
                isBuiltIn = true,
                sortOrder = 1
            ),
            AutomationEntity(
                id = "seed_screen_assist",
                trigger = "screen assist",
                title = "Screen Assist (Alias)",
                description = "Direct alias for 'assist me' trigger.",
                actionType = AutomationActionType.SCREEN_ASSIST,
                promptInstruction = "Analyze this screen and provide immediate actionable help.",
                toastMessage = "📋 Screen Assist copied to clipboard!",
                copyToClipboard = true,
                isBuiltIn = true,
                sortOrder = 2
            ),
            AutomationEntity(
                id = "seed_summarize_screen",
                trigger = "summarize screen",
                title = "Summarize Screen",
                description = "Summarizes all readable text on screen into 3 concise bullet points.",
                actionType = AutomationActionType.SCREEN_ASSIST,
                promptInstruction = "Summarize the visible screen into 3 concise bullet points. Output only the bullet points.",
                toastMessage = "📋 Screen summary copied to clipboard!",
                copyToClipboard = true,
                isBuiltIn = true,
                sortOrder = 3
            ),
            AutomationEntity(
                id = "seed_copy_screen_text",
                trigger = "copy screen text",
                title = "Extract Screen Text",
                description = "Transcribes all visible screen text directly to the system clipboard.",
                actionType = AutomationActionType.SCREEN_ASSIST,
                promptInstruction = "Transcribe and extract all visible text from this screen clearly.",
                toastMessage = "📋 Screen text copied to clipboard!",
                copyToClipboard = true,
                isBuiltIn = true,
                sortOrder = 4
            ),
            AutomationEntity(
                id = "seed_call_brother",
                trigger = "call my brother",
                title = "Call Brother (Sample)",
                description = "Initiates a direct phone call to your brother and displays a toast.",
                actionType = AutomationActionType.PHONE_CALL,
                targetNumber = "",
                toastMessage = "called out nouh !",
                isBuiltIn = false,
                sortOrder = 5
            ),
            AutomationEntity(
                id = "seed_open_youtube",
                trigger = "open youtube",
                title = "Launch YouTube",
                description = "Instantly opens the YouTube application.",
                actionType = AutomationActionType.OPEN_APP,
                targetApp = "YouTube",
                toastMessage = "Opening YouTube…",
                isBuiltIn = true,
                sortOrder = 6
            ),
            AutomationEntity(
                id = "seed_open_settings",
                trigger = "open settings",
                title = "Launch Settings",
                description = "Instantly opens system Android Settings.",
                actionType = AutomationActionType.OPEN_APP,
                targetApp = "Settings",
                toastMessage = "Opening Settings…",
                isBuiltIn = true,
                sortOrder = 7
            ),
            AutomationEntity(
                id = "seed_mute_volume",
                trigger = "mute volume",
                title = "Mute Volume",
                description = "Adjusts device media volume to zero percent.",
                actionType = AutomationActionType.DEVICE_CONTROL,
                scriptCode = "set_volume(0)",
                toastMessage = "Volume muted",
                isBuiltIn = true,
                sortOrder = 8
            )
        )
    }
}
