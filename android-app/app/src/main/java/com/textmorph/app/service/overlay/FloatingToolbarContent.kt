package com.textmorph.app.service.overlay

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.textmorph.app.data.model.PromptEntity
import com.textmorph.app.data.repository.SlashCommandRepository
import com.textmorph.app.llm.LlmEngine
import com.textmorph.app.service.overlay.components.CompanionSquareIconButton
import com.textmorph.app.service.overlay.components.FloatingEdgePill
import com.textmorph.app.service.overlay.components.FloatingInlinePromptEditor
import com.textmorph.app.service.overlay.components.ToolbarActionHandler
import com.textmorph.app.service.overlay.components.buildToolbarChips
import com.textmorph.app.util.AppLogger
import kotlinx.coroutines.Job

/**
 * Visual operational states for the floating companion overlay.
 */
enum class FloatingButtonState {
    IDLE,
    GENERATING,
    REVERT_WINDOW
}

/**
 * Modern TextMorph Companion:
 * - Collapsed: Minimalist 36dp magnetic Edge Pill with Charcoal Slate styling.
 * - Expanded: Ultra-low footprint 44dp horizontal Companion Ribbon with neat square icon buttons.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FloatingToolbarContent(
    getLiveInputText: () -> String,
    getConversationTranscript: () -> String = { "" },
    prompts: List<PromptEntity>,
    isVisionCapable: () -> Boolean,
    onInsertText: (text: String, isIntermediate: Boolean) -> Unit,
    onSnapshotOriginalText: (original: String) -> Unit,
    onRevertText: () -> Unit,
    hasUndo: () -> Boolean,
    getRecentClipboardText: () -> String? = { null },
    onQuickPaste: () -> Unit = {},
    isPlacedOnRight: () -> Boolean,
    isDragging: Boolean = false,
    onRegisterSingleTapHandler: (((() -> Unit)) -> Unit)? = null,
    onRegisterCollapseHandler: (((() -> Unit)) -> Unit)? = null,
    onRegisterUserTypedHandler: (((String) -> Unit) -> Unit)? = null,
    onRegisterUserNavigatedBackHandler: (((() -> Unit)) -> Unit)? = null,
    onExpandedStateChanged: (isExpanded: Boolean) -> Unit,
    onBusyStateChanged: (isBusy: Boolean) -> Unit,
    onTakeScreenshotContext: (((ByteArray) -> Unit) -> Unit)? = null,
    isConversationContext: () -> Boolean = { false },
    getScreenVisibleText: () -> String = { "" },
    onQuickEditSave: (PromptEntity) -> Unit = {},
    onQuickEditDelete: (String) -> Unit = {},
    onEditorFocusMode: (Boolean) -> Unit = {},
    compactChipsMode: Boolean = false,
    onToggleCompactMode: (Boolean) -> Unit = {},
    quickFixEnabled: Boolean = false,
    onToggleQuickFix: (Boolean) -> Unit = {},
    slashCommandRepository: SlashCommandRepository = SlashCommandRepository.getInstance(LocalContext.current),
    onDismiss: () -> Unit,
    onOpenFloatingChat: () -> Unit = {},
    engineProvider: () -> LlmEngine
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val TAG = "FloatingCompanion"

    var isExpanded by remember { mutableStateOf(false) }
    var state by remember { mutableStateOf(FloatingButtonState.IDLE) }
    var activeJob by remember { mutableStateOf<Job?>(null) }

    // Quick Prompt Inline Editor state & Compact Chips mode
    var editingPromptInline by remember { mutableStateOf<PromptEntity?>(null) }
    val isCompactMode = compactChipsMode

    // Reactive Quick Fix (Auto Fix) state
    var localQuickFixEnabled by remember(quickFixEnabled) { mutableStateOf(quickFixEnabled) }
    var isFixingInProgress by remember { mutableStateOf(false) }
    var pendingFixUndo by remember { mutableStateOf<String?>(null) }

    val actionHandler = remember(context, scope, engineProvider) {
        ToolbarActionHandler(
            context = context,
            scope = scope,
            engineProvider = engineProvider,
            getLiveInputText = getLiveInputText,
            isVisionCapable = isVisionCapable,
            onInsertText = onInsertText,
            onSnapshotOriginalText = onSnapshotOriginalText,
            onRevertText = onRevertText,
            onTakeScreenshotContext = onTakeScreenshotContext,
            onStateChanged = { state = it },
            onActiveJobChanged = { activeJob = it }
        )
    }

    LaunchedEffect(state) {
        val isBusy = state == FloatingButtonState.GENERATING
        onBusyStateChanged(isBusy)
    }

    fun setExpanded(expanded: Boolean) {
        isExpanded = expanded
        if (!expanded) {
            if (editingPromptInline != null) {
                editingPromptInline = null
                onEditorFocusMode(false)
            }
            if (state == FloatingButtonState.REVERT_WINDOW) {
                state = FloatingButtonState.IDLE
            }
            actionHandler.cancelQuickFix()
            isFixingInProgress = false
        }
        onExpandedStateChanged(expanded)
    }

    // Connect handlers from WindowManager synchronously on every recomposition commit
    SideEffect {
        onRegisterSingleTapHandler?.invoke {
            AppLogger.d(TAG, "singleTapHandler executed in Compose. state=$state, isExpanded=$isExpanded")
            when (state) {
                FloatingButtonState.IDLE -> {
                    setExpanded(!isExpanded)
                }
                FloatingButtonState.GENERATING -> {
                    actionHandler.cancelActiveJob()
                    Toast.makeText(context, "Generation stopped.", Toast.LENGTH_SHORT).show()
                }
                FloatingButtonState.REVERT_WINDOW -> {
                    onRevertText()
                    state = FloatingButtonState.IDLE
                    Toast.makeText(context, "Text reverted.", Toast.LENGTH_SHORT).show()
                }
            }
        }

        onRegisterCollapseHandler?.invoke {
            AppLogger.d(TAG, "collapseHandler executed in Compose. isExpanded=$isExpanded, editing=${editingPromptInline != null}")
            if (editingPromptInline != null) {
                return@invoke
            }
            if (isExpanded) {
                setExpanded(false)
            }
        }

        onRegisterUserTypedHandler?.invoke { newText ->
            actionHandler.handleUserTyped(
                newText = newText,
                currentState = state,
                quickFixEnabled = localQuickFixEnabled,
                onFixingInProgressChanged = { isFixingInProgress = it },
                onPendingFixUndoChanged = { pendingFixUndo = it }
            )
        }

        onRegisterUserNavigatedBackHandler?.invoke {
            if (state == FloatingButtonState.REVERT_WINDOW) {
                state = FloatingButtonState.IDLE
            }
            actionHandler.cancelQuickFix()
            isFixingInProgress = false
            pendingFixUndo = null
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // UI RENDERING: COLLAPSED EDGE PILL vs EXPANDED COMPANION RIBBON
    // ─────────────────────────────────────────────────────────────────────────────

    if (!isExpanded) {
        FloatingEdgePill(
            state = state,
            isDragging = isDragging,
            onClick = {
                AppLogger.d(TAG, "Collapsed companion pill clicked directly in Compose: state=$state")
                when (state) {
                    FloatingButtonState.IDLE -> setExpanded(!isExpanded)
                    FloatingButtonState.GENERATING -> {
                        actionHandler.cancelActiveJob()
                        Toast.makeText(context, "Generation stopped.", Toast.LENGTH_SHORT).show()
                    }
                    FloatingButtonState.REVERT_WINDOW -> {
                        onRevertText()
                        state = FloatingButtonState.IDLE
                        Toast.makeText(context, "Text reverted.", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        )
    } else {
        if (editingPromptInline != null) {
            FloatingInlinePromptEditor(
                prompt = editingPromptInline!!,
                onSave = { updated ->
                    onQuickEditSave(updated)
                    editingPromptInline = null
                    onEditorFocusMode(false)
                },
                onDelete = { id ->
                    onQuickEditDelete(id)
                    editingPromptInline = null
                    onEditorFocusMode(false)
                },
                onClose = {
                    editingPromptInline = null
                    onEditorFocusMode(false)
                }
            )
        } else {
            // EXPANDED COMPANION RIBBON (44dp single-row dock, Charcoal Slate styled)
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = Color(0xF2111827),
                shadowElevation = 10.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .border(1.5.dp, Color(0x663B82F6), RoundedCornerShape(12.dp))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Left Status / Leading Action Square Icon Button
                    CompanionSquareIconButton(
                        icon = when (state) {
                            FloatingButtonState.GENERATING -> Icons.Default.Stop
                            FloatingButtonState.REVERT_WINDOW -> Icons.AutoMirrored.Filled.Undo
                            FloatingButtonState.IDLE -> Icons.Default.AutoAwesome
                        },
                        contentDescription = "Companion Action",
                        tint = when (state) {
                            FloatingButtonState.GENERATING -> Color(0xFFEF4444)
                            FloatingButtonState.REVERT_WINDOW -> Color(0xFFFBBF24)
                            FloatingButtonState.IDLE -> Color(0xFF60A5FA)
                        },
                        backgroundColor = Color(0x331F2937),
                        borderColor = Color(0x55374151),
                        onClick = {
                            if (state == FloatingButtonState.GENERATING) {
                                actionHandler.cancelActiveJob()
                                Toast.makeText(context, "Generation stopped.", Toast.LENGTH_SHORT).show()
                            } else if (state == FloatingButtonState.REVERT_WINDOW) {
                                onRevertText()
                                state = FloatingButtonState.IDLE
                                Toast.makeText(context, "Text reverted.", Toast.LENGTH_SHORT).show()
                            } else {
                                setExpanded(false)
                            }
                        }
                    )

                    Spacer(modifier = Modifier.width(6.dp))

                    // Center Scrollable Action Chips
                    LazyRow(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        buildToolbarChips(
                            context = context,
                            state = state,
                            isCompactMode = isCompactMode,
                            isFixingInProgress = isFixingInProgress,
                            pendingFixUndo = pendingFixUndo,
                            onClearFixUndo = { pendingFixUndo = null },
                            onInsertText = onInsertText,
                            recentClipboardText = getRecentClipboardText(),
                            onQuickPaste = onQuickPaste,
                            prompts = prompts,
                            onExecutePrompt = { actionHandler.executePrompt(it) },
                            onEditPromptInline = {
                                editingPromptInline = it
                                onEditorFocusMode(true)
                            },
                            localQuickFixEnabled = localQuickFixEnabled,
                            onToggleQuickFix = {
                                localQuickFixEnabled = it
                                onToggleQuickFix(it)
                            },
                            onCancelActiveJob = {
                                actionHandler.cancelActiveJob()
                                Toast.makeText(context, "Generation stopped.", Toast.LENGTH_SHORT).show()
                            }
                        )
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    // Right End Action Controls (Open Chat, Quick Execute, Collapse)
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 1. Dedicated Open Chat Button (replaces static undo; auto-fix undo preserved in ribbon)
                        CompanionSquareIconButton(
                            icon = Icons.Default.ChatBubbleOutline,
                            contentDescription = "Open Chat",
                            tint = Color(0xFFDCD7C9),
                            backgroundColor = Color(0xFF2C3930),
                            borderColor = Color(0xFFA27B5C),
                            onClick = {
                                onOpenFloatingChat()
                            }
                        )

                        // 2. Quick Execute Button (Checks for /command in text or runs direct execution)
                        CompanionSquareIconButton(
                            icon = Icons.Default.Bolt,
                            contentDescription = "Quick Execute",
                            tint = Color(0xFFFBBF24),
                            backgroundColor = Color(0x333B82F6),
                            borderColor = Color(0xFF3B82F6),
                            onClick = {
                                val liveText = getLiveInputText()
                                if (liveText.isBlank()) {
                                    Toast.makeText(context, "Please type or place cursor in a text field first.", Toast.LENGTH_SHORT).show()
                                } else {
                                    val (matchedCommand, cleanedText) = slashCommandRepository.matchAndExtractCommand(liveText)
                                    if (matchedCommand != null) {
                                        actionHandler.executeSlashCommand(matchedCommand, cleanedText)
                                    } else {
                                        actionHandler.startGeneration("", directExecute = true)
                                    }
                                }
                            }
                        )

                        // 3. Collapse ('x') Button
                        CompanionSquareIconButton(
                            icon = Icons.Default.Close,
                            contentDescription = "Collapse",
                            tint = Color(0xFF9CA3AF),
                            backgroundColor = Color(0x331F2937),
                            borderColor = Color(0x33374151),
                            onClick = {
                                if (editingPromptInline != null) {
                                    editingPromptInline = null
                                    onEditorFocusMode(false)
                                }
                                setExpanded(false)
                            }
                        )
                    }
                }
            }
        }
    }
}
