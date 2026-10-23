package com.textmorph.app.ui.dashboard

import androidx.compose.runtime.Composable
import com.textmorph.app.data.repository.ModelStorageRepository
import com.textmorph.app.data.repository.PromptRepository
import com.textmorph.app.data.repository.SettingsRepository
import com.textmorph.app.data.repository.SlashCommandRepository
import com.textmorph.app.data.repository.UpdateRepository
import com.textmorph.app.ui.navigation.AppDestination
import com.textmorph.app.ui.navigation.MainAppScaffold

// Re-export AppDestination for backward compatibility
typealias AppDestination = com.textmorph.app.ui.navigation.AppDestination

@Composable
fun DashboardScreen(
    promptRepository: PromptRepository,
    settingsRepository: SettingsRepository,
    modelRepository: ModelStorageRepository,
    updateRepository: UpdateRepository,
    slashCommandRepository: SlashCommandRepository
) {
    MainAppScaffold(
        promptRepository = promptRepository,
        settingsRepository = settingsRepository,
        modelRepository = modelRepository,
        updateRepository = updateRepository,
        slashCommandRepository = slashCommandRepository
    )
}
