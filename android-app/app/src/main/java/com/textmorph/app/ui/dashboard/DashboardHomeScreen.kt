package com.textmorph.app.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SecondaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.textmorph.app.data.model.PromptEntity
import com.textmorph.app.ui.dashboard.components.PromptItemCard
import com.textmorph.app.ui.dashboard.components.ReadinessBanner

@Composable
fun DashboardHomeScreen(
    filteredPrompts: List<PromptEntity>,
    selectedCategoryIndex: Int,
    onCategorySelected: (Int) -> Unit,
    categories: List<String>,
    searchQuery: String,
    onMovePrompt: (id: String, direction: Int) -> Unit,
    onEditPrompt: (PromptEntity) -> Unit,
    onDeletePrompt: (PromptEntity) -> Unit,
    onOpenAccessibilityDisclosure: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // System Readiness Checklist (only displayed if a permission is missing)
        item {
            ReadinessBanner(
                onOpenAccessibilityDisclosure = onOpenAccessibilityDisclosure,
                modifier = Modifier.padding(top = 8.dp)
            )
        }

        item {
            SecondaryScrollableTabRow(
                selectedTabIndex = selectedCategoryIndex,
                edgePadding = 0.dp,
                containerColor = Color.Transparent,
                divider = {}
            ) {
                categories.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedCategoryIndex == index,
                        onClick = { onCategorySelected(index) },
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
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 6.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(text = "🌟", fontSize = 20.sp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "Direct Companion Actions (Top 5)",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Text(
                                text = "Prompts #1 to #5 are directly accessible in the floating companion bar. Drag ⠿ handle to reorder.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                            )
                        }
                    }
                }
            } else if (index == 5 && selectedCategoryIndex == 0 && searchQuery.isBlank()) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp, bottom = 6.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(text = "📋", fontSize = 20.sp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "Extended Prompts (Companion Bar Overflow)",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                            Text(
                                text = "Prompts #6+ are accessible in the expanded companion bar.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f)
                            )
                        }
                    }
                }
            }

            val isTop5 = index < 5 && selectedCategoryIndex == 0 && searchQuery.isBlank()
            val slotLabel = if (isTop5) "⚡ Companion Slot #${index + 1}" else if (selectedCategoryIndex == 0 && searchQuery.isBlank()) "📂 Extended #${index - 4}" else null

            PromptItemCard(
                prompt = prompt,
                slotLabel = slotLabel,
                isTop5 = isTop5,
                canMoveUp = index > 0,
                canMoveDown = index < filteredPrompts.lastIndex,
                onMoveUp = { onMovePrompt(prompt.id, -1) },
                onMoveDown = { onMovePrompt(prompt.id, 1) },
                onEdit = { onEditPrompt(prompt) },
                onDelete = { onDeletePrompt(prompt) }
            )
        }

        item {
            Spacer(modifier = Modifier.height(80.dp)) // Space for FAB
        }
    }
}
