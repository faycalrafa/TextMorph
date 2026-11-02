package com.textmorph.app.ui.modelhub.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.textmorph.app.data.repository.ModelImportProgress

/**
 * Animated progress indicator card displayed when external models are being streamed/copied
 * into private internal app storage.
 */
@Composable
fun ModelImportProgressCard(
    importProgress: ModelImportProgress,
    modifier: Modifier = Modifier
) {
    if (!importProgress.isImporting) return

    fun formatBytes(bytes: Long): String {
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb >= 1000.0) String.format("%.2f GB", mb / 1024.0) else String.format("%.0f MB", mb)
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
        border = BorderStroke(1.dp, Color(0xFF3B82F6))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = Color(0xFF3B82F6)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Copying to Internal Storage...",
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFF9FAFB)
                    )
                }

                val pct = (importProgress.progressFraction * 100).toInt()
                Text(
                    text = "$pct%",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF60A5FA)
                )
            }

            Text(
                text = importProgress.fileName,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = Color(0xFFE2E8F0)
            )

            LinearProgressIndicator(
                progress = { importProgress.progressFraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp),
                color = Color(0xFF3B82F6),
                trackColor = Color(0xFF334155)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "${formatBytes(importProgress.bytesCopied)} / ${formatBytes(importProgress.totalBytes)}",
                    fontSize = 11.sp,
                    color = Color(0xFF94A3B8)
                )
                Text(
                    text = "Zero-permission internal storage",
                    fontSize = 10.5.sp,
                    color = Color(0xFF64748B)
                )
            }
        }
    }
}
