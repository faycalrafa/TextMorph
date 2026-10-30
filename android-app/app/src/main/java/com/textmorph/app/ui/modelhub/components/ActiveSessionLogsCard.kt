package com.textmorph.app.ui.modelhub.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.textmorph.app.llm.ModelLoadingProgress
import com.textmorph.app.llm.ModelSessionState

/**
 * Dedicated card anchored at the very end of the Model Hub page.
 * Always expanded and visible whenever a model is loading or currently active in memory.
 */
@Composable
fun ActiveSessionLogsCard(
    isLoadingEngine: Boolean,
    loadingProgress: ModelLoadingProgress?,
    sessionState: ModelSessionState?,
    modifier: Modifier = Modifier
) {
    val isLoaded = sessionState?.isLoaded == true
    if (!isLoadingEngine && !isLoaded) return

    val backendTitle = sessionState?.backend ?: "GPU"
    val modelName = sessionState?.modelName ?: "Active Model"

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Color(0xFF1E293B).copy(alpha = 0.85f),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Terminal,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isLoadingEngine) "Loading: ${loadingProgress?.statusText ?: "Initializing..."}"
                        else "Active Session Logs ($backendTitle • $modelName)",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFE2E8F0)
                    )
                }

                if (isLoadingEngine) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF0F172A), RoundedCornerShape(8.dp))
                    .padding(10.dp)
            ) {
                if (isLoadingEngine) {
                    Text(
                        text = "› Status: ${loadingProgress?.statusText ?: "Probing OpenCL / Hexagon hardware..."}",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = Color(0xFF38BDF8)
                    )
                    loadingProgress?.logLines?.takeLast(3)?.forEach { log ->
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "› $log",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            color = Color(0xFF94A3B8)
                        )
                    }
                } else if (isLoaded) {
                    Text(
                        text = "› Backend: $backendTitle (Active in memory)",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = Color(0xFF4ADE80)
                    )
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        text = "› Model: $modelName",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = Color(0xFFCBD5E1)
                    )
                    sessionState.modelPath?.let { path ->
                        Spacer(modifier = Modifier.height(3.dp))
                        Text(
                            text = "› Path: $path",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            color = Color(0xFF94A3B8)
                        )
                    }
                }
            }
        }
    }
}
