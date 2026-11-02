package com.textmorph.app.llm

import android.content.Context
import android.net.Uri
import com.textmorph.app.data.model.CloudApiPreset
import com.textmorph.app.data.model.EngineType
import com.textmorph.app.data.model.ModelInfo
import com.textmorph.app.data.repository.SettingsRepository
import com.textmorph.app.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Centralized coordinator managing clean engine activations across the application.
 * Enforces atomic state transitions: halts any currently loading job, completely unloads
 * all active engine sessions from VRAM/RAM, validates model cleanliness or API connectivity,
 * and launches the target engine.
 */
class EngineActivationCoordinator private constructor(private val appContext: Context) {

    private val TAG = "EngineActivationCoordinator"
    private val activationMutex = Mutex()
    private val activationScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var activeLoadingJob: Job? = null

    private val settingsRepository = SettingsRepository.getInstance(appContext)

    /**
     * Halts any ongoing loading, unloads all existing engine sessions,
     * validates the target model/endpoint cleanliness, and activates the requested engine.
     */
    suspend fun activateEngine(
        targetEngine: EngineType,
        targetModel: ModelInfo? = null,
        targetCloudModelId: String? = null,
        preset: CloudApiPreset? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        activationMutex.withLock {
            try {
                AppLogger.i(TAG, "Activating engine: $targetEngine (model: ${targetModel?.name ?: targetCloudModelId})")

                // 1. Halt any in-flight background loading job
                activeLoadingJob?.cancel()
                activeLoadingJob = null

                // 2. Unload all engines from memory
                val liteRt = LiteRtLmEngineManager.getInstance(appContext)
                val llama = LlamaCppEngineManager.getInstance(appContext)
                try {
                    liteRt.unload()
                } catch (t: Throwable) {
                    AppLogger.w(TAG, "Warning while offloading LiteRT engine: ${t.message}")
                }
                try {
                    llama.unload()
                } catch (t: Throwable) {
                    AppLogger.w(TAG, "Warning while offloading llama.cpp engine: ${t.message}")
                }

                // 3. Dispatch specific engine activation
                when (targetEngine) {
                    EngineType.CLOUD_OPENAI -> {
                        val currentSettings = settingsRepository.settingsState.value
                        val effectiveUrl = preset?.baseUrl ?: currentSettings.cloudBaseUrl
                        val effectiveKey = when {
                            preset != null && preset.apiKey.isNotBlank() -> preset.apiKey
                            currentSettings.cloudApiKey.isNotBlank() -> currentSettings.cloudApiKey
                            else -> ""
                        }
                        val effectiveModel = targetCloudModelId?.takeIf { it.isNotBlank() }
                            ?: preset?.defaultModelId?.takeIf { it.isNotBlank() }
                            ?: currentSettings.cloudModelId

                        if (effectiveUrl.isBlank()) {
                            return@withContext Result.failure(IllegalArgumentException("Cloud API Base URL cannot be blank."))
                        }
                        if (effectiveModel.isBlank()) {
                            return@withContext Result.failure(IllegalArgumentException("Cloud Model ID cannot be blank."))
                        }

                        // Verify connectivity & auth
                        val cloudEngine = OpenAiCloudEngine()
                        cloudEngine.updateConfig(effectiveUrl, effectiveKey, effectiveModel)
                        val probeRes = cloudEngine.fetchAvailableModels(effectiveUrl, effectiveKey)
                        probeRes.onFailure { err ->
                            val msg = err.message ?: ""
                            if (msg.contains("401") || msg.contains("403")) {
                                return@withContext Result.failure(IllegalStateException("API Authentication failed: Invalid API Key or Unauthorized."))
                            }
                        }

                        // Persist clean cloud settings & preset token
                        if (preset != null) {
                            settingsRepository.saveApiPreset(
                                preset.copy(baseUrl = effectiveUrl, apiKey = effectiveKey, defaultModelId = effectiveModel)
                            )
                        } else {
                            settingsRepository.setCloudConfig(effectiveUrl, effectiveKey, effectiveModel)
                        }
                        settingsRepository.setEngine(EngineType.CLOUD_OPENAI)
                        settingsRepository.setActiveModel("No Model Loaded", null, null, EngineType.CLOUD_OPENAI)
                        AppLogger.i(TAG, "Cloud API activated successfully: url=$effectiveUrl, model=$effectiveModel")
                        Result.success(Unit)
                    }

                    EngineType.ON_DEVICE_LITERT -> {
                        if (targetModel == null) {
                            return@withContext Result.failure(IllegalArgumentException("No LiteRT model specified for activation."))
                        }

                        // Validate model file cleanliness
                        val fileValid = targetModel.path?.let { File(it).exists() && File(it).length() > 0 } ?: false
                        val uriValid = !targetModel.uriString.isNullOrBlank()
                        if (!fileValid && !uriValid) {
                            return@withContext Result.failure(IllegalStateException("Model file '${targetModel.name}' is missing or 0 bytes."))
                        }

                        settingsRepository.setActiveModel(targetModel.name, targetModel.path, targetModel.uriString, EngineType.ON_DEVICE_LITERT)
                        settingsRepository.setEngine(EngineType.ON_DEVICE_LITERT)

                        val settings = settingsRepository.settingsState.value
                        val uri = targetModel.uriString?.let { Uri.parse(it) }

                        activeLoadingJob = activationScope.launch {
                            try {
                                liteRt.initialize(
                                    modelPath = targetModel.path,
                                    modelName = targetModel.name,
                                    modelUri = uri,
                                    preferredAccelerator = settings.preferredAccelerator,
                                    probeTimeoutMs = settings.probeTimeoutMs
                                )
                            } catch (t: Throwable) {
                                AppLogger.e(TAG, "LiteRT engine initialization failed: ${t.message}", t)
                            }
                        }
                        Result.success(Unit)
                    }

                    EngineType.ON_DEVICE_LLAMA_CPP -> {
                        if (targetModel == null) {
                            return@withContext Result.failure(IllegalArgumentException("No GGUF model specified for activation."))
                        }

                        // Validate model file cleanliness
                        val fileValid = targetModel.path?.let { File(it).exists() && File(it).length() > 0 } ?: false
                        val uriValid = !targetModel.uriString.isNullOrBlank()
                        if (!fileValid && !uriValid) {
                            return@withContext Result.failure(IllegalStateException("Model file '${targetModel.name}' is missing or 0 bytes."))
                        }

                        settingsRepository.setActiveModel(targetModel.name, targetModel.path, targetModel.uriString, EngineType.ON_DEVICE_LLAMA_CPP)
                        settingsRepository.setEngine(EngineType.ON_DEVICE_LLAMA_CPP)
                        if (targetModel.mmprojName != null) {
                            settingsRepository.setActiveMmproj(targetModel.mmprojName, targetModel.mmprojPath, targetModel.mmprojUriString)
                        }

                        val settings = settingsRepository.settingsState.value
                        val uri = targetModel.uriString?.let { Uri.parse(it) }
                        val mmprojUri = targetModel.mmprojUriString?.let { Uri.parse(it) }

                        activeLoadingJob = activationScope.launch {
                            try {
                                llama.initialize(
                                    modelPath = targetModel.path,
                                    modelName = targetModel.name,
                                    modelUri = uri,
                                    mmprojPath = targetModel.mmprojPath,
                                    mmprojUri = mmprojUri,
                                    preferredAccelerator = settings.preferredAccelerator,
                                    probeTimeoutMs = settings.probeTimeoutMs
                                )
                            } catch (t: Throwable) {
                                AppLogger.e(TAG, "llama.cpp engine initialization failed: ${t.message}", t)
                            }
                        }
                        Result.success(Unit)
                    }
                }
            } catch (t: Throwable) {
                AppLogger.e(TAG, "Engine activation encountered an unexpected error: ${t.message}", t)
                Result.failure(t)
            }
        }
    }

    /**
     * Halts all loading and offloads all models from memory.
     */
    suspend fun offloadAll(): Result<Unit> = withContext(Dispatchers.IO) {
        activationMutex.withLock {
            try {
                activeLoadingJob?.cancel()
                activeLoadingJob = null

                LiteRtLmEngineManager.getInstance(appContext).unload()
                LlamaCppEngineManager.getInstance(appContext).unload()

                val currentEngine = settingsRepository.settingsState.value.selectedEngine
                settingsRepository.setActiveModel("No Model Loaded", null, null, currentEngine)
                AppLogger.i(TAG, "All engines offloaded and session reset.")
                Result.success(Unit)
            } catch (t: Throwable) {
                AppLogger.e(TAG, "Error while offloading all engines: ${t.message}", t)
                Result.failure(t)
            }
        }
    }

    companion object {
        @Volatile
        private var instance: EngineActivationCoordinator? = null

        fun getInstance(context: Context): EngineActivationCoordinator {
            return instance ?: synchronized(this) {
                instance ?: EngineActivationCoordinator(context.applicationContext).also { instance = it }
            }
        }
    }
}
