package com.textmorph.app.data.repository

import android.content.Context
import androidx.core.util.AtomicFile
import com.textmorph.app.data.model.ActionCommandSyntax
import com.textmorph.app.data.model.AppSettings
import com.textmorph.app.data.model.EngineType
import com.textmorph.app.data.model.ModelFileType
import com.textmorph.app.data.model.ScreenPerceptionMode
import com.textmorph.app.theme.ThemeMode
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

class SettingsRepository(private val context: Context) {

    private val mutex = Mutex()
    private val TAG = "SettingsRepository"
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    private val settingsFile: File
        get() = File(context.filesDir, "app_settings_v1.json")

    private val _settingsState = MutableStateFlow(AppSettings())
    val settingsState: StateFlow<AppSettings> = _settingsState.asStateFlow()

    init {
        loadSettings()
    }

    private fun loadSettings() {
        try {
            if (settingsFile.exists()) {
                val content = settingsFile.readText()
                val parsed = json.decodeFromString<AppSettings>(content)
                _settingsState.value = parsed
                AppLogger.d(TAG, "Settings loaded successfully from disk.")
            } else {
                val defaults = AppSettings()
                _settingsState.value = defaults
                saveSettingsToDisk(defaults)
                AppLogger.d(TAG, "Created default settings file.")
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error loading settings, fallback to defaults: ${e.message}", e)
            _settingsState.value = AppSettings()
        }
    }

    suspend fun updateSettings(transform: (AppSettings) -> AppSettings) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val updated = transform(_settingsState.value)
            _settingsState.value = updated
            saveSettingsToDisk(updated)
            AppLogger.i(TAG, "Settings updated and saved to disk.")
        }
    }

    suspend fun setTheme(mode: ThemeMode) = updateSettings { it.copy(theme = mode) }

    suspend fun setEngine(engine: EngineType) = updateSettings { it.copy(selectedEngine = engine) }

    suspend fun setSelectedEngine(engine: EngineType) = setEngine(engine)

    suspend fun setActiveModel(
        name: String,
        path: String?,
        uriString: String? = null,
        overrideEngine: EngineType? = null
    ) = updateSettings {
        val sanitizedPath = if (path != null && path.startsWith("/proc/self/fd/")) null else path
        val detected = ModelFileType.detect(name)
        val autoEngine = overrideEngine ?: when {
            detected == ModelFileType.GGUF || name.endsWith(".gguf", ignoreCase = true) -> EngineType.ON_DEVICE_LLAMA_CPP
            detected == ModelFileType.LITERT_LM || name.endsWith(".litertlm", ignoreCase = true) -> EngineType.ON_DEVICE_LITERT
            else -> it.selectedEngine
        }
        it.copy(
            activeModelName = name,
            activeModelPath = sanitizedPath,
            activeModelUri = uriString,
            selectedEngine = autoEngine
        )
    }

    suspend fun setActiveMmproj(name: String?, path: String?, uriString: String? = null) = updateSettings {
        val sanitizedPath = if (path != null && path.startsWith("/proc/self/fd/")) null else path
        it.copy(
            activeMmprojName = name,
            activeMmprojPath = sanitizedPath,
            activeMmprojUri = uriString
        )
    }

    suspend fun setLocalServer(enabled: Boolean, port: Int = 8080, token: String = "") = updateSettings {
        it.copy(localServerEnabled = enabled, localServerPort = port, localServerBearerToken = token)
    }

    suspend fun setFloatingToolbarEnabled(enabled: Boolean) = updateSettings {
        it.copy(floatingToolbarEnabled = enabled)
    }

    suspend fun setAlwaysShowFloatingToolbar(alwaysShow: Boolean) = updateSettings {
        it.copy(alwaysShowFloatingToolbar = alwaysShow)
    }

    suspend fun setAutoSuggestReplies(enabled: Boolean) = updateSettings {
        it.copy(autoSuggestReplies = enabled)
    }

    suspend fun setCloudConfig(baseUrl: String, apiKey: String, modelId: String) = updateSettings {
        it.copy(cloudBaseUrl = baseUrl, cloudApiKey = apiKey, cloudModelId = modelId)
    }

    suspend fun setProminentDisclosureShown(shown: Boolean = true) = updateSettings {
        it.copy(hasShownProminentDisclosure = shown)
    }

    suspend fun setPreferredAccelerator(accelerator: String) = updateSettings {
        it.copy(preferredAccelerator = accelerator)
    }

    suspend fun setProbeTimeout(timeoutMs: Long) = updateSettings {
        it.copy(probeTimeoutMs = timeoutMs.coerceAtLeast(1000L))
    }

    suspend fun setFloatingToolbarPosition(x: Int, y: Int) = updateSettings {
        it.copy(floatingToolbarX = x, floatingToolbarY = y)
    }

    suspend fun setCompactChipsMode(compact: Boolean) = updateSettings {
        it.copy(compactChipsMode = compact)
    }

    suspend fun setAlwaysOpenBarWithKeyboard(enabled: Boolean) = updateSettings {
        it.copy(alwaysOpenBarWithKeyboard = enabled)
    }

    suspend fun setQuickFixEnabled(enabled: Boolean) = updateSettings {
        it.copy(quickFixEnabled = enabled)
    }

    suspend fun setActionCommandSyntax(syntax: ActionCommandSyntax) = updateSettings {
        it.copy(actionCommandSyntax = syntax)
    }

    suspend fun setScreenPerceptionMode(mode: ScreenPerceptionMode) = updateSettings {
        it.copy(screenPerceptionMode = mode)
    }

    suspend fun updateAiModelConfig(
        maxTokens: Int,
        temperature: Float,
        topK: Int,
        topP: Float,
        enableThinking: Boolean,
        speculativeDecoding: Boolean,
        defaultSystemPrompt: String,
        agentSystemPrompt: String = com.textmorph.app.agent.prompts.AgentPrompts.AGENT_SYSTEM_PROMPT_CODE_ACTION,
        actionCommandSyntax: ActionCommandSyntax? = null,
        screenPerceptionMode: ScreenPerceptionMode? = null,
        contextWindowTokens: Int? = null
    ) = updateSettings {
        it.copy(
            maxTokens = maxTokens,
            temperature = temperature,
            topK = topK,
            topP = topP,
            enableThinking = enableThinking,
            speculativeDecoding = speculativeDecoding,
            defaultSystemPrompt = defaultSystemPrompt,
            agentSystemPrompt = agentSystemPrompt,
            actionCommandSyntax = actionCommandSyntax ?: it.actionCommandSyntax,
            screenPerceptionMode = screenPerceptionMode ?: it.screenPerceptionMode,
            contextWindowTokens = contextWindowTokens ?: it.contextWindowTokens
        )
    }

    suspend fun updateSystemPrompts(chatPrompt: String, agentPrompt: String) = updateSettings {
        it.copy(
            defaultSystemPrompt = chatPrompt,
            agentSystemPrompt = agentPrompt
        )
    }

    suspend fun setAutoSendVoiceInput(enabled: Boolean) = updateSettings { it.copy(autoSendVoiceInput = enabled) }

    private fun saveSettingsToDisk(settings: AppSettings) {
        try {
            val atomicFile = AtomicFile(settingsFile)
            val content = json.encodeToString(settings)
            val stream = atomicFile.startWrite()
            try {
                stream.write(content.toByteArray(Charsets.UTF_8))
                atomicFile.finishWrite(stream)
            } catch (writeEx: Exception) {
                atomicFile.failWrite(stream)
                throw writeEx
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to write settings to disk: ${e.message}", e)
        }
    }

    companion object {
        @Volatile
        private var instance: SettingsRepository? = null

        fun getInstance(context: Context): SettingsRepository {
            return instance ?: synchronized(this) {
                instance ?: SettingsRepository(context.applicationContext).also { instance = it }
            }
        }
    }
}
