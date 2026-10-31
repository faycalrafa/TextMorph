package com.textmorph.app.ui.chat.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.textmorph.app.data.model.ArtifactMetadata
import com.textmorph.app.util.AppLogger

/**
 * First-class summary card presented in the chat message stream
 * when an extensive plan, deep-research report, or code artifact is assembled.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ArtifactSummaryCard(
    artifact: ArtifactMetadata,
    onViewArtifact: (ArtifactMetadata) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val wordCount = artifact.content.split(Regex("\\s+")).filter { it.isNotBlank() }.size

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Color(0xFF111827), // Midnight Slate
        border = BorderStroke(1.dp, Color(0xFF374151)),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header Row: Type Badge + Word Count
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = Color(0xFF1F2937),
                    border = BorderStroke(1.dp, Color(0xFF3B82F6))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Article,
                            contentDescription = null,
                            tint = Color(0xFF60A5FA),
                            modifier = Modifier.size(13.dp)
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = artifact.type.uppercase(),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF93C5FD)
                        )
                    }
                }

                Text(
                    text = "$wordCount words",
                    fontSize = 11.5.sp,
                    color = Color(0xFF9CA3AF)
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Artifact Title
            Text(
                text = artifact.title,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFF9FAFB)
            )

            if (artifact.summary.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = artifact.summary,
                    fontSize = 12.5.sp,
                    color = Color(0xFF9CA3AF),
                    lineHeight = 17.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Action Buttons
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { onViewArtifact(artifact) },
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF2563EB),
                        contentColor = Color.White
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.OpenInFull,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("View Document", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }

                OutlinedButton(
                    onClick = {
                        try {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            cm?.setPrimaryClip(ClipData.newPlainText("Artifact", artifact.content))
                            Toast.makeText(context, "Markdown copied to clipboard", Toast.LENGTH_SHORT).show()
                        } catch (t: Throwable) {
                            AppLogger.w("ArtifactSummaryCard", "Failed copying markdown: ${t.message}")
                        }
                    },
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, Color(0xFF374151)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFE5E7EB))
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = null,
                        tint = Color(0xFF9CA3AF),
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Copy Markdown", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}
