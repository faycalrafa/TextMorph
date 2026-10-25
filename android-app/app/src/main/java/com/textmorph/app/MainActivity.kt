package com.textmorph.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import android.Manifest
import android.os.Build
import androidx.activity.result.contract.ActivityResultContracts
import com.textmorph.app.data.repository.ModelStorageRepository
import com.textmorph.app.data.repository.PromptRepository
import com.textmorph.app.data.repository.SettingsRepository
import com.textmorph.app.data.repository.SlashCommandRepository
import com.textmorph.app.data.repository.UpdateRepository
import com.textmorph.app.service.LlmForegroundService
import com.textmorph.app.theme.TextMorphTheme
import com.textmorph.app.ui.dashboard.DashboardScreen
import com.textmorph.app.util.AppLogger
import com.textmorph.app.util.PermissionUtils

class MainActivity : ComponentActivity() {

    private val TAG = "MainActivity"
    private lateinit var promptRepository: PromptRepository
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var modelRepository: ModelStorageRepository
    private lateinit var updateRepository: UpdateRepository
    private lateinit var slashCommandRepository: SlashCommandRepository

    private val startupPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        results.forEach { (permission, granted) ->
            AppLogger.i(TAG, "Startup permission result for $permission: granted=$granted")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        AppLogger.i(TAG, "MainActivity onCreate starting.")

        // Initialize Repositories
        promptRepository = PromptRepository.getInstance(applicationContext)
        settingsRepository = SettingsRepository.getInstance(applicationContext)
        modelRepository = ModelStorageRepository.getInstance(applicationContext)
        updateRepository = UpdateRepository(applicationContext)
        slashCommandRepository = SlashCommandRepository.getInstance(applicationContext)

        // Request startup permissions (Mic, Camera, and Notifications) if not already granted
        requestStartupPermissions()

        // Ensure LLM Foreground Service is running
        try {
            LlmForegroundService.startService(applicationContext)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to start LlmForegroundService: ${e.message}", e)
        }

        setContent {
            val settings by settingsRepository.settingsState.collectAsState()

            TextMorphTheme(themeMode = settings.theme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    DashboardScreen(
                        promptRepository = promptRepository,
                        settingsRepository = settingsRepository,
                        modelRepository = modelRepository,
                        updateRepository = updateRepository,
                        slashCommandRepository = slashCommandRepository
                    )
                }
            }
        }
    }

    private fun requestStartupPermissions() {
        val needed = mutableListOf<String>()
        if (!PermissionUtils.hasRecordAudioPermission(this)) {
            needed.add(Manifest.permission.RECORD_AUDIO)
        }
        if (!PermissionUtils.hasCameraPermission(this)) {
            needed.add(Manifest.permission.CAMERA)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !PermissionUtils.hasNotificationPermission(this)) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        if (needed.isNotEmpty()) {
            AppLogger.i(TAG, "Requesting startup permissions: ${needed.joinToString()}")
            startupPermissionsLauncher.launch(needed.toTypedArray())
        } else {
            AppLogger.i(TAG, "All startup permissions already granted.")
        }
    }
}
