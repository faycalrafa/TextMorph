package com.textmorph.app.ui.chat.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.launch

/**
 * Charcoal Slate styled card for rendering Markdown images with loading state,
 * caption display, tap-to-view lightbox, and tap-to-download action.
 */
@Composable
fun MarkdownImageCard(
    caption: String,
    imageUrl: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isLightboxOpen by remember { mutableStateOf(false) }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF111827)), // Midnight slate
        border = BorderStroke(1.dp, Color(0xFF1F2937))
    ) {
        Column {
            // Interactive Image Thumbnail
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 160.dp, max = 260.dp)
                    .background(Color(0xFF0B1020))
                    .clickable { isLightboxOpen = true }
            ) {
                SubcomposeAsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(imageUrl)
                        .crossfade(true)
                        .build(),
                    contentDescription = caption,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                    loading = {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp,
                                color = Color(0xFF3B82F6)
                            )
                        }
                    },
                    error = {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    imageVector = Icons.Default.Image,
                                    contentDescription = null,
                                    tint = Color(0xFF6B7280),
                                    modifier = Modifier.size(32.dp)
                                )
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    text = "Preview unavailable",
                                    color = Color(0xFF9CA3AF),
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }
                )
            }

            // Caption & Action Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = caption.ifBlank { "Visual Asset" },
                    color = Color(0xFF9CA3AF),
                    fontSize = 12.sp,
                    fontStyle = FontStyle.Italic,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = { isLightboxOpen = true },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.OpenInFull,
                            contentDescription = "Expand photo",
                            tint = Color(0xFF9CA3AF),
                            modifier = Modifier.size(15.dp)
                        )
                    }

                    Spacer(Modifier.width(4.dp))

                    IconButton(
                        onClick = {
                            scope.launch {
                                ImageDownloadHelper.downloadAndSaveImage(context, imageUrl, caption)
                            }
                        },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Download,
                            contentDescription = "Download photo",
                            tint = Color(0xFF60A5FA),
                            modifier = Modifier.size(17.dp)
                        )
                    }
                }
            }
        }
    }

    if (isLightboxOpen) {
        ImageViewerLightboxDialog(
            imageUrl = imageUrl,
            caption = caption,
            onDismiss = { isLightboxOpen = false },
            onDownload = {
                scope.launch {
                    ImageDownloadHelper.downloadAndSaveImage(context, imageUrl, caption)
                }
            }
        )
    }
}

/**
 * Full-screen zoomable lightbox viewer with dark Charcoal Slate backdrop.
 */
@Composable
fun ImageViewerLightboxDialog(
    imageUrl: String,
    caption: String,
    onDismiss: () -> Unit,
    onDownload: () -> Unit
) {
    val context = LocalContext.current

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color(0xFF0B1020).copy(alpha = 0.96f)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                // Header Bar
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = caption.ifBlank { "Visual Asset" },
                        color = Color(0xFFF9FAFB),
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onDownload) {
                            Icon(Icons.Default.Download, contentDescription = "Download", tint = Color(0xFF60A5FA))
                        }
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = "Close", tint = Color(0xFFF9FAFB))
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                // Full-size Image
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    SubcomposeAsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(imageUrl)
                            .crossfade(true)
                            .build(),
                        contentDescription = caption,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                        loading = {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(color = Color(0xFF3B82F6))
                            }
                        }
                    )
                }
            }
        }
    }
}
