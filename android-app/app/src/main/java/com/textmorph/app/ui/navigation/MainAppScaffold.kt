package com.textmorph.app.ui.navigation

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.textmorph.app.data.model.EngineType
import com.textmorph.app.data.model.PromptCategory
import com.textmorph.app.data.model.PromptEntity
import com.textmorph.app.data.repository.AppReleaseInfo
import com.textmorph.app.data.repository.DownloadState
import com.textmorph.app.data.repository.ModelStorageRepository
import com.textmorph.app.data.repository.PromptRepository
import com.textmorph.app.data.repository.SettingsRepository
import com.textmorph.app.data.repository.SlashCommandRepository
import com.textmorph.app.data.repository.UpdateRepository
import com.textmorph.app.llm.LiteRtLmEngineManager
import com.textmorph.app.llm.LlamaCppEngineManager
import com.textmorph.app.ui.about.AboutContent
import com.textmorph.app.ui.about.DevLogsScreen
import com.textmorph.app.ui.about.VersionHistoryScreen
import com.textmorph.app.ui.chat.AiChatContent
import com.textmorph.app.ui.commands.CustomCommandsContent
import com.textmorph.app.ui.dashboard.DashboardHomeScreen
import com.textmorph.app.ui.modelhub.ModelHubContent
import com.textmorph.app.ui.navigation.components.MainAppDialogs
import com.textmorph.app.ui.settings.SettingsContent
import com.textmorph.app.ui.tools.AgentToolsScreen
import com.textmorph.app.util.PermissionUtils
import com.textmorph.app.util.SystemTelemetryManager
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MainAppScaffold(
    promptRepository: PromptRepository,
    settingsRepository: SettingsRepository,
    modelRepository: ModelStorageRepository,
    updateRepository: UpdateRepository,
    slashCommandRepository: SlashCommandRepository = SlashCommandRepository.getInstance(LocalContext.current)
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val liteRtEngine = remember { LiteRtLmEngineManager.getInstance(context) }
    val llamaCppEngine = remember { LlamaCppEngineManager.getInstance(context) }
    val chatRepository = remember { com.textmorph.app.data.repository.ChatSessionRepository.getInstance(context) }
    val chatSessions by chatRepository.sessions.collectAsState()
    val activeChatSessionId by chatRepository.activeSessionId.collectAsState()

    var currentDestination by remember { mutableStateOf(AppDestination.CHAT) }

    // Search Top Bar State
    var isSearchActive by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    // Dialogs State
    var showPromptEditor by remember { mutableStateOf(false) }
    var editingPrompt by remember { mutableStateOf<PromptEntity?>(null) }
    var showProminentDisclosure by remember { mutableStateOf(false) }
    var showUpdateDialog by remember { mutableStateOf(false) }
    var showAiConfigDialog by remember { mutableStateOf(false) }
    var isCheckingUpdate by remember { mutableStateOf(false) }
    var activeReleaseInfo by remember { mutableStateOf<AppReleaseInfo?>(null) }
    var downloadState by remember { mutableStateOf<DownloadState?>(null) }

    fun triggerUpdateCheck() {
        scope.launch {
            isCheckingUpdate = true
            val result = updateRepository.checkForUpdates()
            isCheckingUpdate = false
            result.onSuccess { info ->
                if (info != null) {
                    activeReleaseInfo = info
                    showUpdateDialog = true
                } else {
                    Toast.makeText(context, "App is up to date!", Toast.LENGTH_SHORT).show()
                }
            }.onFailure { err ->
                Toast.makeText(context, "Update check failed: ${err.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Prompts & Category filtering
    val allPrompts by promptRepository.promptsState.collectAsState()
    var selectedCategoryIndex by remember { mutableIntStateOf(0) }
    val categories = remember {
        listOf("All", PromptCategory.TEXT_TRANSFORM.displayName, PromptCategory.VISION_ACTION.displayName)
    }

    val filteredPrompts = remember(allPrompts, selectedCategoryIndex, searchQuery) {
        allPrompts.filter { prompt ->
            val matchesCategory = when (selectedCategoryIndex) {
                0 -> true
                1 -> prompt.category == PromptCategory.TEXT_TRANSFORM
                2 -> prompt.category == PromptCategory.VISION_ACTION
                else -> true
            }
            val matchesSearch = if (searchQuery.isBlank()) true else {
                prompt.title.contains(searchQuery, ignoreCase = true) ||
                        prompt.systemInstruction.contains(searchQuery, ignoreCase = true)
            }
            matchesCategory && matchesSearch
        }
    }

    // Intercept Back button when search is open, drawer is open, or in sub-destination
    BackHandler(enabled = isSearchActive || drawerState.isOpen || currentDestination != AppDestination.CHAT) {
        if (isSearchActive) {
            isSearchActive = false
            searchQuery = ""
        } else if (drawerState.isOpen) {
            scope.launch { drawerState.close() }
        } else if (currentDestination == AppDestination.BENCHMARK || currentDestination == AppDestination.RAW_LLM_DUMP) {
            currentDestination = AppDestination.MODEL_HUB
        } else if (currentDestination != AppDestination.CHAT) {
            currentDestination = AppDestination.CHAT
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            val curSettings by settingsRepository.settingsState.collectAsState()
            com.textmorph.app.ui.navigation.components.AppNavigationDrawerSheet(
                currentDestination = currentDestination,
                installedVersionName = updateRepository.getCurrentVersionName(),
                settings = curSettings,
                isDrawerOpen = drawerState.isOpen,
                onSelectDestination = { dest ->
                    currentDestination = dest
                    scope.launch { drawerState.close() }
                },
                sessions = chatSessions,
                activeSessionId = activeChatSessionId,
                onSelectSession = { sessionId ->
                    scope.launch {
                        chatRepository.switchSession(sessionId)
                        currentDestination = AppDestination.CHAT
                        drawerState.close()
                    }
                },
                onNewChat = {
                    scope.launch {
                        chatRepository.createSession()
                        currentDestination = AppDestination.CHAT
                        drawerState.close()
                    }
                },
                onDeleteSession = { sessionId ->
                    scope.launch { chatRepository.deleteSession(sessionId) }
                }
            )
        }
    ) {
        if (currentDestination == AppDestination.DEV_LOGS) {
            DevLogsScreen(
                installedVersionName = updateRepository.getCurrentVersionName(),
                onNavigateBack = { currentDestination = AppDestination.CHAT },
                onOpenDrawer = { scope.launch { drawerState.open() } }
            )
        } else if (currentDestination == AppDestination.VERSION_HISTORY) {
            VersionHistoryScreen(
                currentVersionName = updateRepository.getCurrentVersionName(),
                onNavigateBack = { currentDestination = AppDestination.ABOUT }
            )
        } else if (currentDestination == AppDestination.RAW_LLM_DUMP) {
            com.textmorph.app.ui.modelhub.components.LlmConversationDumpScreen(
                chatRepository = chatRepository,
                onNavigateBack = { currentDestination = AppDestination.MODEL_HUB }
            )
        } else if (currentDestination == AppDestination.BENCHMARK) {
            val curSettings by settingsRepository.settingsState.collectAsState()
            val isLlamaActive = curSettings.selectedEngine == EngineType.ON_DEVICE_LLAMA_CPP
            val activeEngine = if (isLlamaActive) llamaCppEngine else liteRtEngine
            val llamaSession by llamaCppEngine.sessionState.collectAsState()
            val liteRtSession by liteRtEngine.sessionState.collectAsState()
            val activeModelName = if (isLlamaActive) llamaSession.modelName else liteRtSession.modelName
            val activeBackend = if (isLlamaActive) llamaSession.backend else liteRtSession.backend

            com.textmorph.app.ui.modelhub.AgenticReadinessScreen(
                engine = activeEngine,
                modelName = if (activeModelName.isNotBlank()) activeModelName else curSettings.activeModelName,
                backend = activeBackend,
                onNavigateBack = { currentDestination = AppDestination.MODEL_HUB }
            )
        } else {
            Scaffold(
                topBar = {
                    if (currentDestination != AppDestination.CHAT &&
                        currentDestination != AppDestination.TOOLS &&
                        currentDestination != AppDestination.AUTOMATIONS &&
                        currentDestination != AppDestination.PERMISSIONS_GUIDE) {
                        TopAppBar(
                            title = {
                                if (isSearchActive) {
                                    BasicTextField(
                                        value = searchQuery,
                                        onValueChange = { searchQuery = it },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 8.dp),
                                        textStyle = TextStyle(
                                            color = MaterialTheme.colorScheme.onSurface,
                                            fontSize = 18.sp,
                                            fontWeight = FontWeight.Medium
                                        ),
                                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                        singleLine = true,
                                        decorationBox = { innerTextField ->
                                            if (searchQuery.isEmpty()) {
                                                Text(
                                                    text = "Search prompts...",
                                                    style = MaterialTheme.typography.bodyLarge,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                                )
                                            }
                                            innerTextField()
                                        }
                                    )
                                } else {
                                    Text(
                                        text = currentDestination.title,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            },
                            navigationIcon = {
                                IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                    Icon(Icons.Default.Menu, contentDescription = "Open Drawer")
                                }
                            },
                            actions = {
                                if (currentDestination == AppDestination.PROMPTS) {
                                    if (isSearchActive) {
                                        IconButton(onClick = {
                                            isSearchActive = false
                                            searchQuery = ""
                                        }) {
                                            Icon(Icons.Default.Close, contentDescription = "Close Search")
                                        }
                                    } else {
                                        IconButton(onClick = { isSearchActive = true }) {
                                            Icon(Icons.Default.Search, contentDescription = "Search Prompts")
                                        }
                                    }
                                } else if (currentDestination == AppDestination.MODEL_HUB) {
                                    IconButton(onClick = { currentDestination = AppDestination.RAW_LLM_DUMP }) {
                                        Icon(
                                            imageVector = Icons.Default.Terminal,
                                            contentDescription = "Raw LLM Conversation Dump",
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                    IconButton(onClick = { currentDestination = AppDestination.BENCHMARK }) {
                                        Icon(
                                            imageVector = Icons.Default.Speed,
                                            contentDescription = "Agent Capability Benchmark",
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                    IconButton(onClick = { showAiConfigDialog = true }) {
                                        Icon(
                                            imageVector = Icons.Default.Settings,
                                            contentDescription = "AI Model Configurations",
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            },
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = MaterialTheme.colorScheme.surface
                            )
                        )
                    }
                },
                floatingActionButton = {
                    if (currentDestination == AppDestination.PROMPTS) {
                        FloatingActionButton(
                            onClick = {
                                editingPrompt = null
                                showPromptEditor = true
                            },
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                            shape = CircleShape
                        ) {
                            Icon(Icons.Default.Add, contentDescription = "Create Custom Prompt")
                        }
                    }
                }
            ) { paddingValues ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                ) {
                    com.textmorph.app.ui.navigation.components.MainAppDestinationContent(
                        currentDestination = currentDestination,
                        context = context,
                        scope = scope,
                        filteredPrompts = filteredPrompts,
                        selectedCategoryIndex = selectedCategoryIndex,
                        onCategorySelected = { selectedCategoryIndex = it },
                        categories = categories,
                        searchQuery = searchQuery,
                        promptRepository = promptRepository,
                        slashCommandRepository = slashCommandRepository,
                        modelRepository = modelRepository,
                        settingsRepository = settingsRepository,
                        liteRtEngine = liteRtEngine,
                        llamaCppEngine = llamaCppEngine,
                        chatRepository = chatRepository,
                        updateRepository = updateRepository,
                        isCheckingUpdate = isCheckingUpdate,
                        activeReleaseInfo = activeReleaseInfo,
                        onOpenAccessibilityDisclosure = { showProminentDisclosure = true },
                        onEditPrompt = { prompt ->
                            editingPrompt = prompt
                            showPromptEditor = true
                        },
                        onTriggerUpdateCheck = ::triggerUpdateCheck,
                        onShowUpdateDialog = { showUpdateDialog = true },
                        onSelectDestination = { dest -> currentDestination = dest },
                        onOpenDrawer = { scope.launch { drawerState.open() } }
                    )
                }
            }
        }

        // Root Modal Dialogs
        MainAppDialogs(
            context = context,
            scope = scope,
            promptRepository = promptRepository,
            settingsRepository = settingsRepository,
            updateRepository = updateRepository,
            showPromptEditor = showPromptEditor,
            editingPrompt = editingPrompt,
            onDismissPromptEditor = {
                showPromptEditor = false
                editingPrompt = null
            },
            showProminentDisclosure = showProminentDisclosure,
            onDismissProminentDisclosure = { showProminentDisclosure = false },
            showUpdateDialog = showUpdateDialog,
            activeReleaseInfo = activeReleaseInfo,
            downloadState = downloadState,
            onDismissUpdateDialog = {
                showUpdateDialog = false
                downloadState = null
            },
            onDownloadStateChanged = { downloadState = it },
            showAiConfigDialog = showAiConfigDialog,
            onDismissAiConfigDialog = { showAiConfigDialog = false }
        )
    }
}
