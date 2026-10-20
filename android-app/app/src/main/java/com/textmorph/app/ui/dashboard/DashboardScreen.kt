package com.textmorph.app.ui.dashboard

import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
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
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.textmorph.app.data.model.PromptCategory
import com.textmorph.app.data.model.PromptEntity
import com.textmorph.app.data.repository.AppReleaseInfo
import com.textmorph.app.data.repository.DownloadState
import com.textmorph.app.data.repository.ModelStorageRepository
import com.textmorph.app.data.repository.PromptRepository
import com.textmorph.app.data.repository.SettingsRepository
import com.textmorph.app.data.repository.UpdateRepository
import com.textmorph.app.ui.about.AboutContent
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
    SETTINGS("Settings"),
    ABOUT("About TextMorph")
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

    // Intercept Back button when search is open or drawer is open
    BackHandler(enabled = isSearchActive || drawerState.isOpen) {
        if (isSearchActive) {
            isSearchActive = false
            searchQuery = ""
        } else if (drawerState.isOpen) {
            scope.launch { drawerState.close() }
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
                    icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                    label = { Text("Settings") },
                    selected = currentDestination == AppDestination.SETTINGS,
                    onClick = {
                        currentDestination = AppDestination.SETTINGS
                        scope.launch { drawerState.close() }
                    },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                )

                NavigationDrawerItem(
                    icon = { Icon(Icons.Default.Terminal, contentDescription = null) },
                    label = { Text("Developer Diagnostics") },
                    selected = false,
                    onClick = {
                        showDevLogs = true
                        scope.launch { drawerState.close() }
                    },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp, horizontal = 16.dp))

                NavigationDrawerItem(
                    icon = { Icon(Icons.Default.Info, contentDescription = null) },
                    label = { Text("About") },
                    selected = currentDestination == AppDestination.ABOUT,
                    onClick = {
                        currentDestination = AppDestination.ABOUT
                        scope.launch { drawerState.close() }
                    },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                )
            }
        }
    ) {
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
                            Icon(Icons.Default.Menu, contentDescription = "Menu")
                        }
                    },
                    actions = {
                        if (currentDestination == AppDestination.PROMPTS) {
                            if (isSearchActive) {
                                IconButton(onClick = {
                                    if (searchQuery.isNotEmpty()) {
                                        searchQuery = ""
                                    } else {
                                        isSearchActive = false
                                    }
                                }) {
                                    Icon(Icons.Default.Close, contentDescription = "Close search")
                                }
                            } else {
                                IconButton(onClick = { isSearchActive = true }) {
                                    Icon(Icons.Default.Search, contentDescription = "Search prompts")
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
                        Icon(Icons.Default.Add, contentDescription = "Add Custom Prompt")
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
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            item {
                                Spacer(modifier = Modifier.height(8.dp))
                                ReadinessBanner(
                                    onOpenAccessibilityDisclosure = {
                                        showProminentDisclosure = true
                                    }
                                )
                            }

                            item {
                                ScrollableTabRow(
                                    selectedTabIndex = selectedCategoryIndex,
                                    edgePadding = 0.dp,
                                    containerColor = MaterialTheme.colorScheme.background,
                                    contentColor = MaterialTheme.colorScheme.primary,
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

                            items(filteredPrompts, key = { it.id }) { prompt ->
                                PromptItemCard(
                                    prompt = prompt,
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
                            settingsRepository = settingsRepository
                        )
                    }

                    AppDestination.SETTINGS -> {
                        SettingsContent(
                            settingsRepository = settingsRepository,
                            updateRepository = updateRepository,
                            onOpenDevLogs = { showDevLogs = true },
                            onCheckUpdate = {
                                scope.launch {
                                    val result = updateRepository.checkForUpdates()
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
                            updateRepository = updateRepository,
                            onCheckUpdate = {
                                scope.launch {
                                    val result = updateRepository.checkForUpdates()
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
                }
            }
        }
    }

    // Full-Width Dialogs
    if (showPromptEditor) {
        PromptEditorDialog(
            prompt = editingPrompt,
            onDismiss = { showPromptEditor = false },
            onSave = { savedPrompt ->
                scope.launch {
                    if (editingPrompt != null) {
                        promptRepository.updatePrompt(savedPrompt)
                    } else {
                        promptRepository.addPrompt(savedPrompt)
                    }
                    showPromptEditor = false
                    Toast.makeText(context, "Prompt saved successfully.", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    if (showProminentDisclosure) {
        ProminentDisclosureDialog(
            onDismiss = { showProminentDisclosure = false },
            onAcceptAndContinue = {
                showProminentDisclosure = false
                scope.launch {
                    settingsRepository.setProminentDisclosureShown(true)
                }
                PermissionUtils.openAccessibilitySettings(context)
            }
        )
    }

    if (showDevLogs) {
        DevLogsDialog(
            onDismiss = { showDevLogs = false }
        )
    }

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
                }
            }
        )
    }
}

@Composable
private fun PromptItemCard(
    prompt: PromptEntity,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = prompt.iconEmoji,
                        fontSize = 24.sp
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = prompt.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = prompt.category.displayName + (if (prompt.requiresVision) " • Vision" else ""),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                Row {
                    IconButton(onClick = onEdit) {
                        Icon(Icons.Default.Edit, contentDescription = "Edit", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = prompt.systemInstruction,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2
            )
        }
    }
}
