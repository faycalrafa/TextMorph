package com.textmorph.app.ui.chat.components

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.textmorph.app.data.model.SourceCitation
import com.textmorph.app.util.AppLogger

/**
 * Bottom sheet displaying all source citations and links referenced in a turn.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageSourcesBottomSheet(
    sources: List<SourceCitation>,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF111827), // Midnight Slate
        contentColor = Color(0xFFF9FAFB)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Language,
                        contentDescription = null,
                        tint = Color(0xFF60A5FA),
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Sources & References (${sources.size})",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFF9FAFB)
                    )
                }

                IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = Color(0xFF9CA3AF)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Sources List
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(sources) { source ->
                    val domain = try {
                        Uri.parse(source.url).host ?: source.url
                    } catch (_: Exception) {
                        source.url
                    }

                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = Color(0xFF1F2937),
                        border = BorderStroke(1.dp, Color(0xFF374151)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                try {
                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(source.url)).apply {
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                    context.startActivity(intent)
                                } catch (t: Throwable) {
                                    AppLogger.w("SourcesBottomSheet", "Failed opening link ${source.url}: ${t.message}")
                                }
                            }
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = Color(0xFF2563EB),
                                modifier = Modifier.size(26.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        text = "${source.sourceIndex}",
                                        color = Color.White,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.width(12.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = source.title,
                                    fontSize = 13.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color(0xFFF9FAFB),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (!source.snippet.isNullOrBlank()) {
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = source.snippet,
                                        fontSize = 11.5.sp,
                                        color = Color(0xFF9CA3AF),
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Spacer(modifier = Modifier.height(3.dp))
                                Text(
                                    text = domain,
                                    fontSize = 11.sp,
                                    color = Color(0xFF60A5FA),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            Spacer(modifier = Modifier.width(8.dp))

                            Icon(
                                imageVector = Icons.Default.OpenInBrowser,
                                contentDescription = "Open link",
                                tint = Color(0xFF9CA3AF),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        }
    }
}
