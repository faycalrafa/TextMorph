package com.textmorph.app.ui.modelhub.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.work.WorkInfo
import com.textmorph.app.data.model.ModelFileType
import com.textmorph.app.data.model.ModelInfo
import com.textmorph.app.data.repository.ModelStorageRepository
import com.textmorph.app.worker.DownloadWorker

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ModelItemCard(
    model: ModelInfo,
    isCurrentActive: Boolean,
    isLoadingEngine: Boolean,
    onActivate: () -> Unit,
    onAttachMmproj: () -> Unit = {},
    onDetachMmproj: () -> Unit = {},
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onDelete: () -> Unit,
    onImportToPrivateStorage: () -> Unit,
    onNavigateToChat: () -> Unit,
    modelRepository: ModelStorageRepository,
    modifier: Modifier = Modifier
) {
    val workInfo by modelRepository.getDownloadWorkInfo(model.id).collectAsState(initial = null)
    val isDownloading = workInfo?.state == WorkInfo.State.RUNNING || workInfo?.state == WorkInfo.State.ENQUEUED
    val isGguf = model.modelFileType == ModelFileType.GGUF || model.name.endsWith(".gguf", ignoreCase = true)

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(
                enabled = model.isDownloaded && !isLoadingEngine && !isCurrentActive,
                onClick = onActivate
            ),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isCurrentActive) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isCurrentActive) 4.dp else 1.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = model.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        SuggestionChip(
                            onClick = {},
                            label = { Text(if (isGguf) "GGUF" else "LiteRT-LM", fontSize = 11.sp, fontWeight = FontWeight.SemiBold) }
                        )
                        if (isCurrentActive) {
                            SuggestionChip(
                                onClick = {},
                                label = { Text("Active", fontWeight = FontWeight.Bold) }
                            )
                        }
                    }
                    Text(
                        text = model.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (model.isDownloaded && model.path != null) {
                    IconButton(onClick = onDelete) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Delete Model",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            // Multimodal Vision Projector (Optional) for GGUF models
            if (isGguf && model.isDownloaded) {
                Spacer(modifier = Modifier.height(10.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Multimodal Vision Projector",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = if (!model.mmprojName.isNullOrBlank()) "Projector: ${model.mmprojName}" else "No mmproj attached (Text Only)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                        }
                        if (!model.mmprojName.isNullOrBlank()) {
                            IconButton(
                                onClick = onDetachMmproj,
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Detach Vision Projector",
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        } else {
                            OutlinedButton(
                                onClick = onAttachMmproj,
                                shape = RoundedCornerShape(6.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                modifier = Modifier.height(28.dp)
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Add mmproj", fontSize = 11.sp)
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Download Progress Bar & Speed / ETA
            if (isDownloading) {
                val progress = workInfo?.progress?.getInt(DownloadWorker.KEY_PROGRESS_PERCENT, 0) ?: 0
                val receivedBytes = workInfo?.progress?.getLong(DownloadWorker.KEY_RECEIVED_BYTES, 0L) ?: 0L
                val totalBytes = workInfo?.progress?.getLong(DownloadWorker.KEY_TOTAL_BYTES, model.sizeBytes) ?: model.sizeBytes
                val rate = workInfo?.progress?.getLong(DownloadWorker.KEY_DOWNLOAD_RATE, 0L) ?: 0L
                val remainingMs = workInfo?.progress?.getLong(DownloadWorker.KEY_REMAINING_MS, 0L) ?: 0L

                val speedMb = rate / (1024.0 * 1024.0)
                val etaSec = remainingMs / 1000

                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "$progress% • ${receivedBytes / (1024 * 1024)} / ${totalBytes / (1024 * 1024)} MB",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "%.1f MB/s • %ds left".format(speedMb, etaSec),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { progress / 100f },
                        modifier = Modifier.fillMaxWidth().height(6.dp)
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))
            }

            // Bottom action row
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = if (model.sizeBytes > 0) "${model.sizeBytes / (1024 * 1024)} MB" else "Catalog Entry",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(vertical = 4.dp)
                )

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (isDownloading) {
                        OutlinedButton(
                            onClick = onCancelDownload,
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Cancel")
                        }
                    } else if (!model.isDownloaded) {
                        if (model.downloadUrl != null) {
                            Button(
                                onClick = onDownload,
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Download")
                            }
                        }
                    } else {
                        // Model is downloaded
                        if (model.isExternalStorage) {
                            OutlinedButton(
                                onClick = onImportToPrivateStorage,
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Icon(Icons.Default.SaveAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Import", fontSize = 12.sp)
                            }
                        }

                        if (!isCurrentActive) {
                            Button(
                                onClick = onActivate,
                                enabled = !isLoadingEngine,
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(if (isLoadingEngine) "Loading..." else "Activate Model")
                            }
                        } else {
                            Button(
                                onClick = onNavigateToChat,
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                            ) {
                                Icon(Icons.Default.Speed, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Test in Playground")
                            }
                        }
                    }
                }
            }
        }
    }
}
