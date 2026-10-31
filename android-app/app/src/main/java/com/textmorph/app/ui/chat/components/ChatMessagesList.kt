package com.textmorph.app.ui.chat.components

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.textmorph.app.data.model.ArtifactMetadata
import com.textmorph.app.data.model.ChatMessageEntity
import kotlinx.coroutines.launch

/**
 * Message history list component managing lazy scrolling, empty state starters,
 * item rendering, and the jump-to-bottom FAB.
 */
@Composable
fun ChatMessagesList(
    messages: List<ChatMessageEntity>,
    listState: LazyListState,
    isGenerating: Boolean,
    activeGenerationStatus: String?,
    activeModelName: String,
    onSelectStarter: (String) -> Unit,
    onEditPrompt: (ChatMessageEntity) -> Unit,
    onRetryPrompt: (ChatMessageEntity) -> Unit,
    onInterruptAction: () -> Unit,
    onSelectOption: (String) -> Unit,
    onSelectSuggestion: (String) -> Unit,
    onViewArtifact: (ArtifactMetadata) -> Unit,
    onExecutePlan: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()

    Box(modifier = modifier) {
        if (messages.isEmpty()) {
            ChatEmptyStateStarters(
                isAgentMode = true,
                activeModelName = activeModelName,
                onSelectStarter = onSelectStarter
            )
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(messages, key = { it.id }) { msg ->
                    ChatMessageItemView(
                        message = msg,
                        isGenerating = isGenerating && msg.id == messages.lastOrNull()?.id,
                        generationStatus = activeGenerationStatus,
                        onEditPrompt = onEditPrompt,
                        onRetryPrompt = onRetryPrompt,
                        onInterruptAction = onInterruptAction,
                        onSelectOption = onSelectOption,
                        onSelectSuggestion = onSelectSuggestion,
                        onViewArtifact = onViewArtifact,
                        onExecutePlan = onExecutePlan
                    )
                }
            }

            if (listState.canScrollForward) {
                ScrollToBottomFab(
                    onClick = {
                        scope.launch {
                            if (messages.isNotEmpty()) {
                                listState.animateScrollToItem(messages.size - 1)
                            }
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(bottom = 12.dp, end = 12.dp)
                )
            }
        }
    }
}
