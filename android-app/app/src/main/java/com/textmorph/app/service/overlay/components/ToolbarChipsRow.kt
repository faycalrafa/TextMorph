package com.textmorph.app.service.overlay.components

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Stop
import com.textmorph.app.data.model.PromptEntity
import com.textmorph.app.service.overlay.FloatingButtonState
import com.textmorph.app.ui.prompts.getVectorIcon
import kotlinx.coroutines.Job

/**
 * Builds the horizontal chip sequence for the TextMorph companion ribbon.
 * Modularized to keep FloatingToolbarContent strictly under 500 lines.
 */
fun LazyListScope.buildToolbarChips(
    context: Context,
    state: FloatingButtonState,
    isCompactMode: Boolean,
    isFixingInProgress: Boolean,
    pendingFixUndo: String?,
    onClearFixUndo: () -> Unit,
    onInsertText: (String, Boolean) -> Unit,
    recentClipboardText: String?,
    onQuickPaste: () -> Unit,
    prompts: List<PromptEntity>,
    onExecutePrompt: (PromptEntity) -> Unit,
    onEditPromptInline: (PromptEntity) -> Unit,
    localQuickFixEnabled: Boolean,
    onToggleQuickFix: (Boolean) -> Unit,
    onCancelActiveJob: () -> Unit,
    onAcceptTransformation: () -> Unit = {},
    onUndoTransformation: () -> Unit = {}
) {
    when (state) {
        FloatingButtonState.REVERT_WINDOW -> {
            item {
                CompanionActionChip(
                    icon = Icons.Default.Check,
                    label = if (isCompactMode) "Accept" else "Accept",
                    accent = true,
                    onClick = onAcceptTransformation
                )
            }
            item {
                CompanionActionChip(
                    icon = Icons.AutoMirrored.Filled.Undo,
                    label = if (isCompactMode) "Undo" else "Undo",
                    accent = false,
                    onClick = onUndoTransformation
                )
            }
        }
        FloatingButtonState.GENERATING -> {
            item {
                CompanionActionChip(
                    icon = Icons.Default.Stop,
                    label = if (isCompactMode) "Stop" else "Generating… Stop",
                    accent = true,
                    onClick = onCancelActiveJob
                )
            }
        }
        FloatingButtonState.IDLE, FloatingButtonState.LISTENING -> {
            // 1. Quick Fix in progress indicator
            if (isFixingInProgress) {
                item {
                    CompanionActionChip(
                        icon = Icons.Default.AutoFixHigh,
                        label = if (isCompactMode) null else "Fixing…",
                        accent = true,
                        onClick = {}
                    )
                }
            }

            // 2. Contextual Auto-Fixed Undo / Accept Chip (Guaranteed preserved!)
            if (pendingFixUndo != null) {
                item {
                    CompanionActionChip(
                        icon = Icons.AutoMirrored.Filled.Undo,
                        label = if (isCompactMode) "Undo" else "Undo Fix",
                        accent = true,
                        onClick = {
                            onInsertText(pendingFixUndo, false)
                            onClearFixUndo()
                            Toast.makeText(context, "Quick fix undone.", Toast.LENGTH_SHORT).show()
                        }
                    )
                }
                item {
                    CompanionActionChip(
                        icon = Icons.Default.Check,
                        label = if (isCompactMode) null else "Accept Fix",
                        accent = false,
                        onClick = onClearFixUndo
                    )
                }
            }

            // 3. Quick Paste Chip
            if (!recentClipboardText.isNullOrBlank()) {
                item {
                    val preview = if (recentClipboardText.length > 12) recentClipboardText.take(12) + "…" else recentClipboardText
                    CompanionActionChip(
                        icon = Icons.Default.ContentPaste,
                        label = if (isCompactMode) null else "Paste: \"$preview\"",
                        accent = true,
                        onClick = {
                            onQuickPaste()
                            Toast.makeText(context, "Pasted recent copy.", Toast.LENGTH_SHORT).show()
                        }
                    )
                }
            }

            // 4. Transform Prompts
            items(prompts) { prompt ->
                val vectorIcon = prompt.getVectorIcon()
                CompanionActionChip(
                    icon = vectorIcon,
                    label = if (isCompactMode) null else prompt.title,
                    emoji = if (vectorIcon == null) prompt.iconEmoji else null,
                    accent = false,
                    onClick = { onExecutePrompt(prompt) },
                    onLongClick = { onEditPromptInline(prompt) }
                )
            }

            // 5. Quick Fix Reactive Toggle Chip
            item {
                CompanionActionChip(
                    icon = Icons.Default.AutoFixHigh,
                    label = if (isCompactMode) {
                        if (localQuickFixEnabled) "Fix: ON" else "Fix: OFF"
                    } else {
                        if (localQuickFixEnabled) "Quick Fix: ON" else "Quick Fix: OFF"
                    },
                    accent = localQuickFixEnabled,
                    onClick = {
                        val newMode = !localQuickFixEnabled
                        onToggleQuickFix(newMode)
                        Toast.makeText(
                            context,
                            if (newMode) "Quick Fix: ON" else "Quick Fix: OFF",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                )
            }
        }
    }
}
