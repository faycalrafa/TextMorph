package com.textmorph.app.ui.chat.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.textmorph.app.data.model.ArtifactMetadata
import com.textmorph.app.util.AppLogger
import kotlinx.coroutines.launch

/**
 * Dedicated full-screen reader for generated artifacts, execution plans, and research reports.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArtifactReaderScreen(
    artifact: ArtifactMetadata,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isExportingPdf by remember { mutableStateOf(false) }
    val wordCount = remember(artifact.content) {
        artifact.content.split(Regex("\\s+")).filter { it.isNotBlank() }.size
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = artifact.title,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFF9FAFB),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "${artifact.type.uppercase()} • $wordCount words",
                            fontSize = 11.5.sp,
                            color = Color(0xFF9CA3AF)
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = Color(0xFFE5E7EB)
                        )
                    }
                },
                actions = {
                    // Export PDF Button
                    if (isExportingPdf) {
                        CircularProgressIndicator(
                            color = Color(0xFF60A5FA),
                            strokeWidth = 2.dp,
                            modifier = Modifier
                                .padding(horizontal = 12.dp)
                                .size(20.dp)
                        )
                    } else {
                        IconButton(
                            onClick = {
                                isExportingPdf = true
                                scope.launch {
                                    try {
                                        val result = PdfExportHelper.exportArtifactToPdf(context, artifact)
                                        result.onSuccess { pdfFile ->
                                            Toast.makeText(context, "Saved PDF to Downloads/TextMorph", Toast.LENGTH_LONG).show()
                                            try {
                                                val uri = FileProvider.getUriForFile(
                                                    context,
                                                    "${context.packageName}.fileprovider",
                                                    pdfFile
                                                )
                                                val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                                                    setDataAndType(uri, "application/pdf")
                                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                                                }
                                                context.startActivity(viewIntent)
                                            } catch (openEx: Exception) {
                                                AppLogger.w("ArtifactReaderScreen", "PDF saved but viewer failed: ${openEx.message}")
                                            }
                                        }.onFailure { err ->
                                            Toast.makeText(context, "Failed exporting PDF: ${err.message}", Toast.LENGTH_SHORT).show()
                                        }
                                    } finally {
                                        isExportingPdf = false
                                    }
                                }
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.PictureAsPdf,
                                contentDescription = "Export PDF",
                                tint = Color(0xFF60A5FA)
                            )
                        }
                    }

                    // Copy Markdown Button
                    IconButton(
                        onClick = {
                            try {
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                cm?.setPrimaryClip(ClipData.newPlainText("Artifact", artifact.content))
                                Toast.makeText(context, "Copied Markdown", Toast.LENGTH_SHORT).show()
                            } catch (_: Exception) {}
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = "Copy markdown",
                            tint = Color(0xFF9CA3AF)
                        )
                    }

                    // Share Button
                    IconButton(
                        onClick = {
                            try {
                                val sendIntent = Intent().apply {
                                    action = Intent.ACTION_SEND
                                    putExtra(Intent.EXTRA_TEXT, artifact.content)
                                    type = "text/plain"
                                }
                                context.startActivity(Intent.createChooser(sendIntent, "Share Document"))
                            } catch (_: Exception) {}
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = "Share",
                            tint = Color(0xFF9CA3AF)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF111827)
                )
            )
        },
        containerColor = Color(0xFF0B1020),
        modifier = modifier.fillMaxSize()
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(Color(0xFF0B1020))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 16.dp)
            ) {
                MarkdownContentView(
                    markdown = artifact.content,
                    fontSize = 15.sp,
                    color = Color(0xFFF3F4F6)
                )
                Spacer(modifier = Modifier.height(48.dp))
            }
        }
    }
}
