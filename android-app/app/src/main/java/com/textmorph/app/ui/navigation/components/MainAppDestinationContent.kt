package com.textmorph.app.ui.navigation.components

import android.content.Context
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.textmorph.app.data.model.PromptCategory
import com.textmorph.app.data.model.PromptEntity
import com.textmorph.app.data.repository.AppReleaseInfo
import com.textmorph.app.data.repository.ChatSessionRepository
import com.textmorph.app.data.repository.ModelStorageRepository
import com.textmorph.app.data.repository.PromptRepository
import com.textmorph.app.data.repository.SettingsRepository
import com.textmorph.app.data.repository.SlashCommandRepository
import com.textmorph.app.data.repository.UpdateRepository
import com.textmorph.app.llm.LiteRtLmEngineManager
import com.textmorph.app.llm.LlamaCppEngineManager
import com.textmorph.app.ui.about.AboutContent
import com.textmorph.app.ui.automations.AutomationsScreen
import com.textmorph.app.ui.chat.AiChatContent
import com.textmorph.app.ui.commands.CustomCommandsContent
import com.textmorph.app.ui.dashboard.DashboardHomeScreen
import com.textmorph.app.ui.modelhub.ModelHubContent
import com.textmorph.app.ui.navigation.AppDestination
import com.textmorph.app.ui.settings.SettingsContent
import com.textmorph.app.ui.setup.SetupPermissionsScreen
import com.textmorph.app.ui.tools.AgentToolsScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Modular content router for primary destinations inside MainAppScaffold.
 * Extracted to ensure MainAppScaffold remains strictly under 500 lines.
 */
@Composable
fun MainAppDestinationContent(
    currentDestination: AppDestination,
    context: Context,
    scope: CoroutineScope,
    filteredPrompts: List<PromptEntity>,
    selectedCategoryIndex: Int,
    onCategorySelected: (Int) -> Unit,
    categories: List<String>,
    searchQuery: String,
    promptRepository: PromptRepository,
    slashCommandRepository: SlashCommandRepository,
    modelRepository: ModelStorageRepository,
    settingsRepository: SettingsRepository,
    liteRtEngine: com.textmorph.app.llm.LiteRtLmEngine,
    llamaCppEngine: com.textmorph.app.llm.LlamaCppEngine,
    chatRepository: ChatSessionRepository,
    updateRepository: UpdateRepository,
    isCheckingUpdate: Boolean,
    activeReleaseInfo: AppReleaseInfo?,
    onOpenAccessibilityDisclosure: () -> Unit,
    onEditPrompt: (PromptEntity) -> Unit,
    onTriggerUpdateCheck: () -> Unit,
    onShowUpdateDialog: () -> Unit,
    onSelectDestination: (AppDestination) -> Unit,
    onOpenDrawer: () -> Unit,
    modifier: Modifier = Modifier
) {
    when (currentDestination) {
        AppDestination.PROMPTS -> {
            DashboardHomeScreen(
                filteredPrompts = filteredPrompts,
                selectedCategoryIndex = selectedCategoryIndex,
                onCategorySelected = onCategorySelected,
                categories = categories,
                searchQuery = searchQuery,
                onMovePrompt = { id, direction ->
                    scope.launch { promptRepository.movePrompt(id, direction) }
                },
                onEditPrompt = onEditPrompt,
                onDeletePrompt = { prompt ->
                    scope.launch {
                        promptRepository.deletePrompt(prompt.id)
                        Toast.makeText(context, "Deleted: ${prompt.title}", Toast.LENGTH_SHORT).show()
                    }
                },
                onOpenAccessibilityDisclosure = onOpenAccessibilityDisclosure
            )
        }

        AppDestination.CUSTOM_COMMANDS -> {
            CustomCommandsContent(slashCommandRepository = slashCommandRepository)
        }

        AppDestination.AUTOMATIONS -> {
            AutomationsScreen(
                onOpenDrawer = onOpenDrawer,
                onNavigateToPermissions = { onSelectDestination(AppDestination.PERMISSIONS_GUIDE) }
            )
        }

        AppDestination.PERMISSIONS_GUIDE -> {
            SetupPermissionsScreen(
                onOpenDrawer = onOpenDrawer,
                onOpenAccessibilityDisclosure = onOpenAccessibilityDisclosure
            )
        }

        AppDestination.MODEL_HUB -> {
            ModelHubContent(
                modelRepository = modelRepository,
                settingsRepository = settingsRepository,
                liteRtEngine = liteRtEngine,
                llamaCppEngine = llamaCppEngine,
                onNavigateToChat = { onSelectDestination(AppDestination.CHAT) }
            )
        }

        AppDestination.CHAT -> {
            AiChatContent(
                liteRtEngine = liteRtEngine,
                settingsRepository = settingsRepository,
                modelRepository = modelRepository,
                llamaCppEngine = llamaCppEngine,
                chatRepository = chatRepository,
                onNavigateToModelHub = { onSelectDestination(AppDestination.MODEL_HUB) },
                onOpenDrawer = onOpenDrawer,
                onOpenAccessibilityDisclosure = onOpenAccessibilityDisclosure
            )
        }

        AppDestination.SETTINGS -> {
            SettingsContent(
                settingsRepository = settingsRepository,
                updateRepository = updateRepository,
                onOpenDevLogs = { onSelectDestination(AppDestination.DEV_LOGS) },
                onCheckUpdate = onTriggerUpdateCheck,
                onOpenAccessibilityDisclosure = onOpenAccessibilityDisclosure
            )
        }

        AppDestination.ABOUT -> {
            AboutContent(
                installedVersionName = updateRepository.getCurrentVersionName(),
                isCheckingUpdate = isCheckingUpdate,
                downloadedApk = updateRepository.getDownloadedApkFile(),
                onCheckForUpdates = onTriggerUpdateCheck,
                onInstallDownloadedApk = { updateRepository.getDownloadedApkFile()?.let { updateRepository.installApk(it) } },
                onSaveApkToPath = { if (activeReleaseInfo != null) onShowUpdateDialog() else onTriggerUpdateCheck() },
                onReplayOnboarding = onOpenAccessibilityDisclosure,
                onNavigateToDevLogs = { onSelectDestination(AppDestination.DEV_LOGS) },
                onNavigateToVersionHistory = { onSelectDestination(AppDestination.VERSION_HISTORY) }
            )
        }

        AppDestination.TOOLS -> AgentToolsScreen(onOpenDrawer = onOpenDrawer)

        AppDestination.VERSION_HISTORY,
        AppDestination.DEV_LOGS,
        AppDestination.RAW_LLM_DUMP,
        AppDestination.BENCHMARK -> {
            // Handled in root Scaffold branch
        }
    }
}
