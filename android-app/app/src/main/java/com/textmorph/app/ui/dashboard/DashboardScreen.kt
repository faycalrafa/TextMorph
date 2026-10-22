package com.textmorph.app.ui.dashboard

import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.textmorph.app.data.model.ContextAction
import com.textmorph.app.data.model.PromptCategory
import com.textmorph.app.data.model.PromptEntity
import com.textmorph.app.data.repository.AppReleaseInfo
import com.textmorph.app.data.repository.DownloadState
import com.textmorph.app.data.repository.ModelStorageRepository
import com.textmorph.app.data.repository.PromptRepository
import com.textmorph.app.data.repository.SettingsRepository
import com.textmorph.app.data.repository.UpdateRepository
import com.textmorph.app.ui.about.AboutContent
import com.textmorph.app.ui.about.DevLogsScreen
import com.textmorph.app.ui.about.VersionHistoryScreen
import com.textmorph.app.ui.common.DevLogsDialog
import com.textmorph.app.ui.common.ProminentDisclosureDialog
import com.textmorph.app.ui.common.UpdateDialog
import com.textmorph.app.ui.dashboard.components.ReadinessBanner
import com.textmorph.app.ui.modelhub.ModelHubContent
import com.textmorph.app.ui.prompts.PromptEditorDialog
import com.textmorph.app.ui.settings.SettingsContent
import com.textmorph.app.util.AppLogger
import com.textmorph.app.util.PermissionUtils
import kotlinx.coroutines.launch

enum class AppDestination(val title: String) {
    PROMPTS("Custom Prompts"),
    MODEL_HUB("Model Hub"),
    CHAT("AI Playground"),
    SETTINGS("Settings"),
    ABOUT("About TextMorph"),
    VERSION_HISTORY("Version History"),
    DEV_LOGS("Developer Diagnostics")
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DashboardScreen(
    promptRepository: PromptRepository,
    settingsRepository: SettingsRepository,
    modelRepository: ModelStorageRepository,
    updateRepository: UpdateRepository
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val liteRtEngine = remember { com.textmorph.app.llm.LiteRtLmEngineManager.getInstance(context) }

    var currentDestination by remember { mutableStateOf(AppDestination.PROMPTS) }

    // Search Top Bar State
    var isSearchActive by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    // Dialogs State
    var showPromptEditor by remember { mutableStateOf(false) }
    var editingPrompt by remember { mutableStateOf<PromptEntity?>(null) }
    var showProminentDisclosure by remember { mutableStateOf(false) }
    var showDevLogs by remember { mutableStateOf(false) }
    var showUpdateDialog by remember { mutableStateOf(false) }
    var isCheckingUpdate by remember { mutableStateOf(false) }
    var activeReleaseInfo by remember { mutableStateOf<AppReleaseInfo?>(null) }
    var downloadState by remember { mutableStateOf<DownloadState?>(null) }

    // Prompts & Category filtering
    val allPrompts by promptRepository.promptsState.collectAsState()
    var selectedCategoryIndex by remember { mutableIntStateOf(0) }
    val categories = remember { listOf("All") + PromptCategory.entries.map { it.displayName } }

    val filteredPrompts = remember(allPrompts, selectedCategoryIndex, searchQuery) {
        allPrompts.filter { prompt ->
            val matchesCategory = when (selectedCategoryIndex) {
                0 -> true
                1 -> prompt.category == PromptCategory.TEXT_TRANSFORM
                2 -> prompt.category == PromptCategory.SMART_REPLY
                3 -> prompt.category == PromptCategory.VISION_ACTION
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
    BackHandler(enabled = isSearchActive || drawerState.isOpen || currentDestination != AppDestination.PROMPTS) {
        if (isSearchActive) {
            isSearchActive = false
            searchQuery = ""
        } else if (drawerState.isOpen) {
            scope.launch { drawerState.close() }
        } else if (currentDestination != AppDestination.PROMPTS) {
            currentDestination = AppDestination.PROMPTS
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = MaterialTheme.colorScheme.surface,
                modifier = Modifier.width(300.dp)
            ) {
                // Drawer Header
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .padding(24.dp)
                ) {
                    Text(
                        text = "TextMorph",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Text(
                        text = "On-Device Gemma 4 AI Studio",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Scrollable main navigation items (pinned above footer)
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                ) {
                    NavigationDrawerItem(
                        icon = { Icon(Icons.Default.Widgets, contentDescription = null) },
                        label = { Text("Custom Prompts") },
                        selected = currentDestination == AppDestination.PROMPTS,
                        onClick = {
                            currentDestination = AppDestination.PROMPTS
                            scope.launch { drawerState.close() }
                        },
                        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                    )

                    NavigationDrawerItem(
                        icon = { Icon(Icons.Default.Memory, contentDescription = null) },
                        label = { Text("Model Hub") },
                        selected = currentDestination == AppDestination.MODEL_HUB,
                        onClick = {
                            currentDestination = AppDestination.MODEL_HUB
                            scope.launch { drawerState.close() }
                        },
                        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                    )

                    NavigationDrawerItem(
                        icon = { Icon(Icons.Default.AutoAwesome, contentDescription = null) },
                        label = { Text("AI Playground") },
                        selected = currentDestination == AppDestination.CHAT,
                        onClick = {
                            currentDestination = AppDestination.CHAT
                            scope.launch { drawerState.close() }
                        },
                        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                    )
                }

                HorizontalDivider()

                // Drawer Bottom Footer: Settings (Pinned to bottom)
                Surface(
                    onClick = {
                        currentDestination = AppDestination.SETTINGS
                        scope.launch { drawerState.close() }
                    },
                    color = if (currentDestination == AppDestination.SETTINGS) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f) else MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = "Settings",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Settings",
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Theme, floating button & localhost API",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                    fontSize = 11.sp
                                )
                            )
                        }
                    }
                }

                HorizontalDivider()

                // Drawer Bottom Footer: About TextMorph with embedded BugReport action (Pinned to bottom)
                Surface(
                    onClick = {
                        currentDestination = AppDestination.ABOUT
                        scope.launch { drawerState.close() }
                    },
                    color = if (currentDestination == AppDestination.ABOUT) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f) else MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = "About App",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "About TextMorph",
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "v${updateRepository.getCurrentVersionName()}",
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        IconButton(
                            onClick = {
                                currentDestination = AppDestination.DEV_LOGS
                                scope.launch { drawerState.close() }
                            },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                Icons.Default.BugReport,
                                contentDescription = "Developer Logs",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    ) {
        if (currentDestination == AppDestination.DEV_LOGS) {
            DevLogsScreen(
                installedVersionName = updateRepository.getCurrentVersionName(),
                onNavigateBack = { currentDestination = AppDestination.PROMPTS },
                onOpenDrawer = { scope.launch { drawerState.open() } }
            )
        } else if (currentDestination == AppDestination.VERSION_HISTORY) {
            VersionHistoryScreen(
                currentVersionName = updateRepository.getCurrentVersionName(),
                onNavigateBack = { currentDestination = AppDestination.ABOUT }
            )
        } else {
            Scaffold(
                topBar = {
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
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        )
                    )
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
                    when (currentDestination) {
                        AppDestination.PROMPTS -> {
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 16.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                item {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    ReadinessBanner(
                                        onOpenAccessibilityDisclosure = { showProminentDisclosure = true }
                                    )
                                }

                                item {
                                    ScrollableTabRow(
                                        selectedTabIndex = selectedCategoryIndex,
                                        edgePadding = 0.dp,
                                        containerColor = Color.Transparent,
                                        divider = {}
                                    ) {
                                        categories.forEachIndexed { index, title ->
                                            Tab(
                                                selected = selectedCategoryIndex == index,
                                                onClick = { selectedCategoryIndex = index },
                                                text = {
                                                    Text(
                                                        text = title,
                                                        fontWeight = if (selectedCategoryIndex == index) FontWeight.Bold else FontWeight.Normal
                                                    )
                                                }
                                            )
                                        }
                                    }
                                }

                                if (filteredPrompts.isEmpty()) {
                                    item {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(vertical = 40.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = if (searchQuery.isNotEmpty()) "No matching prompts found." else "No prompts in this category.",
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }

                                itemsIndexed(filteredPrompts, key = { _, prompt -> prompt.id }) { index, prompt ->
                                    if (index == 0 && selectedCategoryIndex == 0 && searchQuery.isBlank()) {
                                        Card(
                                            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                                            shape = RoundedCornerShape(12.dp),
                                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f))
                                        ) {
                                            Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                                Text(text = "🌟", fontSize = 20.sp)
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Column {
                                                    Text(
                                                        text = "Direct Radial Quick-Actions (Top 5)",
                                                        style = MaterialTheme.typography.titleSmall,
                                                        fontWeight = FontWeight.Bold,
                                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                                    )
                                                    Text(
                                                        text = "Prompts #1 to #5 are directly accessible in the floating radial menu. Drag ⠿ handle to reorder.",
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                                                    )
                                                }
                                            }
                                        }
                                    } else if (index == 5 && selectedCategoryIndex == 0 && searchQuery.isBlank()) {
                                        Card(
                                            modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 6.dp),
                                            shape = RoundedCornerShape(12.dp),
                                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f))
                                        ) {
                                            Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                                Text(text = "📋", fontSize = 20.sp)
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Column {
                                                    Text(
                                                        text = "Extended Prompts (Folder 📂 in Radial Menu)",
                                                        style = MaterialTheme.typography.titleSmall,
                                                        fontWeight = FontWeight.Bold,
                                                        color = MaterialTheme.colorScheme.onSecondaryContainer
                                                    )
                                                    Text(
                                                        text = "Prompts #6+ open in the 'More Prompts' floating dialog from the radial menu.",
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f)
                                                    )
                                                }
                                            }
                                        }
                                    }

                                    val isTop5 = index < 5 && selectedCategoryIndex == 0 && searchQuery.isBlank()
                                    val slotLabel = if (isTop5) "⚡ Radial Slot #${index + 1}" else if (selectedCategoryIndex == 0 && searchQuery.isBlank()) "📂 Extended #${index - 4}" else null

                                    PromptItemCard(
                                        prompt = prompt,
                                        slotLabel = slotLabel,
                                        isTop5 = isTop5,
                                        canMoveUp = index > 0,
                                        canMoveDown = index < filteredPrompts.lastIndex,
                                        onMoveUp = {
                                            scope.launch {
                                                promptRepository.movePrompt(prompt.id, -1)
                                            }
                                        },
                                        onMoveDown = {
                                            scope.launch {
                                                promptRepository.movePrompt(prompt.id, 1)
                                            }
                                        },
                                        onEdit = {
                                            editingPrompt = prompt
                                            showPromptEditor = true
                                        },
                                        onDelete = {
                                            scope.launch {
                                                promptRepository.deletePrompt(prompt.id)
                                                Toast.makeText(context, "Deleted: ${prompt.title}", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    )
                                }

                                item {
                                    Spacer(modifier = Modifier.height(80.dp)) // Space for FAB
                                }
                            }
                        }

                        AppDestination.MODEL_HUB -> {
                            ModelHubContent(
                                modelRepository = modelRepository,
                                settingsRepository = settingsRepository,
                                liteRtEngine = liteRtEngine,
                                onNavigateToChat = { currentDestination = AppDestination.CHAT }
                            )
                        }

                        AppDestination.CHAT -> {
                            com.textmorph.app.ui.chat.AiChatContent(
                                liteRtEngine = liteRtEngine,
                                settingsRepository = settingsRepository,
                                modelRepository = modelRepository,
                                onNavigateToModelHub = { currentDestination = AppDestination.MODEL_HUB }
                            )
                        }

                        AppDestination.SETTINGS -> {
                            SettingsContent(
                                settingsRepository = settingsRepository,
                                updateRepository = updateRepository,
                                onOpenDevLogs = { currentDestination = AppDestination.DEV_LOGS },
                                onCheckUpdate = {
                                    scope.launch {
                                        isCheckingUpdate = true
                                        val result = updateRepository.checkForUpdates()
                                        isCheckingUpdate = false
                                        result.onSuccess { info ->
                                            if (info != null && info.isUpdateAvailable) {
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
                            )
                        }

                        AppDestination.ABOUT -> {
                            AboutContent(
                                installedVersionName = updateRepository.getCurrentVersionName(),
                                isCheckingUpdate = isCheckingUpdate,
                                downloadedApk = updateRepository.getDownloadedApkFile(),
                                onCheckForUpdates = {
                                    scope.launch {
                                        isCheckingUpdate = true
                                        val result = updateRepository.checkForUpdates()
                                        isCheckingUpdate = false
                                        result.onSuccess { info ->
                                            if (info != null && info.isUpdateAvailable) {
                                                activeReleaseInfo = info
                                                showUpdateDialog = true
                                            } else {
                                                Toast.makeText(context, "App is up to date!", Toast.LENGTH_SHORT).show()
                                            }
                                        }.onFailure { err ->
                                            Toast.makeText(context, "Update check failed: ${err.message}", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                },
                                onInstallDownloadedApk = {
                                    updateRepository.getDownloadedApkFile()?.let { file ->
                                        updateRepository.installApk(file)
                                    }
                                },
                                onSaveApkToPath = {
                                    activeReleaseInfo?.let { release ->
                                        showUpdateDialog = true
                                    } ?: run {
                                        scope.launch {
                                            isCheckingUpdate = true
                                            val result = updateRepository.checkForUpdates()
                                            isCheckingUpdate = false
                                            result.onSuccess { info ->
                                                if (info != null) {
                                                    activeReleaseInfo = info
                                                    showUpdateDialog = true
                                                }
                                            }
                                        }
                                    }
                                },
                                onReplayOnboarding = {
                                    showProminentDisclosure = true
                                },
                                onNavigateToDevLogs = {
                                    currentDestination = AppDestination.DEV_LOGS
                                },
                                onNavigateToVersionHistory = {
                                    currentDestination = AppDestination.VERSION_HISTORY
                                }
                            )
                        }

                        AppDestination.VERSION_HISTORY,
                        AppDestination.DEV_LOGS -> {
                            // Handled in root Scaffold branch
                        }
                    }
                }
            }
        }

        // Prompt Editor Dialog
        if (showPromptEditor) {
            PromptEditorDialog(
                prompt = editingPrompt,
                onDismiss = {
                    showPromptEditor = false
                    editingPrompt = null
                },
                onSave = { updated ->
                    scope.launch {
                        if (editingPrompt == null) {
                            promptRepository.addPrompt(updated)
                        } else {
                            promptRepository.updatePrompt(updated)
                        }
                        showPromptEditor = false
                        editingPrompt = null
                        Toast.makeText(context, "Prompt saved successfully.", Toast.LENGTH_SHORT).show()
                    }
                }
            )
        }

        // Accessibility Prominent Disclosure Dialog
        if (showProminentDisclosure) {
            ProminentDisclosureDialog(
                onDismiss = { showProminentDisclosure = false },
                onAcceptAndContinue = {
                    showProminentDisclosure = false
                    scope.launch { settingsRepository.setProminentDisclosureShown(true) }
                    PermissionUtils.openAccessibilitySettings(context)
                }
            )
        }

        // In-App Update Dialog
        if (showUpdateDialog && activeReleaseInfo != null) {
            UpdateDialog(
                releaseInfo = activeReleaseInfo!!,
                downloadState = downloadState,
                onDismiss = {
                    showUpdateDialog = false
                    downloadState = null
                },
                onStartDownload = {
                    scope.launch {
                        updateRepository.downloadApk(activeReleaseInfo!!.downloadUrl).collect { state ->
                            downloadState = state
                        }
                    }
                },
                onInstall = {
                    val state = downloadState
                    if (state is DownloadState.Completed) {
                        updateRepository.installApk(state.file)
                    } else {
                        updateRepository.getDownloadedApkFile()?.let { file ->
                            updateRepository.installApk(file)
                        }
                    }
                }
            )
        }
    }
}

@Composable
private fun PromptItemCard(
    prompt: PromptEntity,
    slotLabel: String?,
    isTop5: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val dragThresholdPx = with(density) { 44.dp.toPx() }
    var accumulatedDragY by remember { mutableFloatStateOf(0f) }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isTop5) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isTop5) 2.dp else 1.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 6-dots drag handle: grabs and moves prompt up or down intuitively
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .pointerInput(prompt.id, canMoveUp, canMoveDown) {
                            detectVerticalDragGestures(
                                onDragStart = { accumulatedDragY = 0f },
                                onDragEnd = { accumulatedDragY = 0f },
                                onDragCancel = { accumulatedDragY = 0f },
                                onVerticalDrag = { change, dragAmount ->
                                    change.consume()
                                    accumulatedDragY += dragAmount
                                    if (accumulatedDragY < -dragThresholdPx && canMoveUp) {
                                        accumulatedDragY = 0f
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        onMoveUp()
                                    } else if (accumulatedDragY > dragThresholdPx && canMoveDown) {
                                        accumulatedDragY = 0f
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        onMoveDown()
                                    }
                                }
                            )
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.DragHandle,
                        contentDescription = "Drag ⠿ to reorder",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        modifier = Modifier.size(24.dp)
                    )
                }

                Spacer(modifier = Modifier.width(6.dp))
                Text(text = prompt.iconEmoji, fontSize = 22.sp)
                Spacer(modifier = Modifier.width(8.dp))

                Column(modifier = Modifier.weight(1f)) {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = prompt.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (slotLabel != null) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = if (isTop5) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer
                            ) {
                                Text(
                                    text = slotLabel,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isTop5) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    fontSize = 10.sp
                                )
                            }
                        }
                    }

                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = prompt.category.displayName,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        if (prompt.contextAction != ContextAction.NONE) {
                            Text(
                                text = "• ${prompt.contextAction.displayName}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.secondary,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onEdit, modifier = Modifier.size(32.dp)) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = "Edit Prompt",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Delete Prompt",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = prompt.systemInstruction,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
