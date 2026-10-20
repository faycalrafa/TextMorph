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
import com.textmorph.app.data.repository.ModelStorageRepository
import com.textmorph.app.data.repository.PromptRepository
import com.textmorph.app.data.repository.SettingsRepository
import com.textmorph.app.data.repository.UpdateRepository
import com.textmorph.app.service.LlmForegroundService
import com.textmorph.app.theme.TextMorphTheme
import com.textmorph.app.ui.dashboard.DashboardScreen
import com.textmorph.app.util.AppLogger

class MainActivity : ComponentActivity() {

    private val TAG = "MainActivity"
    private lateinit var promptRepository: PromptRepository
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var modelRepository: ModelStorageRepository
    private lateinit var updateRepository: UpdateRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        AppLogger.i(TAG, "MainActivity onCreate starting.")

        // Initialize Repositories
        promptRepository = PromptRepository(applicationContext)
        settingsRepository = SettingsRepository(applicationContext)
        modelRepository = ModelStorageRepository(applicationContext)
        updateRepository = UpdateRepository(applicationContext)

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
                        updateRepository = updateRepository
                    )
                }
            }
        }
    }
}
