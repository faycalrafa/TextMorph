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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.textmorph.app.data.model.EngineType
import com.textmorph.app.ui.navigation.components.EngineStartStopButton

/**
 * Top active engine banner in Model Hub displaying active model status, backend,
 * and quick start/stop offload controls.
 */
@Composable
fun ModelHubActiveBanner(
    engineType: EngineType,
    activeModelName: String,
    isLoaded: Boolean,
    isLoading: Boolean,
    activeBackend: String,
    onToggleEngine: () -> Unit,
    modifier: Modifier = Modifier
) {
    val engineTitle = when (engineType) {
        EngineType.ON_DEVICE_LITERT -> "LiteRT-LM"
        EngineType.ON_DEVICE_LLAMA_CPP -> "llama.cpp (GGUF)"
        EngineType.CLOUD_OPENAI -> "Cloud API"
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isLoaded) Color(0xFF1E293B) else Color(0xFF111827)
        ),
        border = BorderStroke(1.dp, if (isLoaded) Color(0xFF3B82F6).copy(alpha = 0.5f) else Color(0xFF1F2937))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f, fill = false)
            ) {
                Icon(
                    imageVector = Icons.Default.Memory,
                    contentDescription = null,
                    tint = if (isLoaded) Color(0xFF3B82F6) else Color(0xFF9CA3AF),
                    modifier = Modifier.size(24.dp)
                )

                Spacer(modifier = Modifier.width(12.dp))

                Column {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = engineTitle,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFF9FAFB)
                        )

                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = if (isLoaded) Color(0xFF16A34A).copy(alpha = 0.2f) else Color(0xFF374151)
                        ) {
                            Text(
                                text = if (isLoaded) "LOADED" else "OFFLOADED",
                                color = if (isLoaded) Color(0xFF22C55E) else Color(0xFF9CA3AF),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(2.dp))

                    Text(
                        text = if (activeModelName.isNotBlank()) activeModelName else "No model active",
                        fontSize = 12.sp,
                        color = if (activeModelName.isNotBlank()) Color(0xFFE2E8F0) else Color(0xFF64748B),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    if (isLoaded && activeBackend.isNotBlank()) {
                        Text(
                            text = "Backend: $activeBackend",
                            fontSize = 10.5.sp,
                            color = Color(0xFF94A3B8)
                        )
                    }
                }
            }

            // Start / Stop Action Button
            if (activeModelName.isNotBlank() || isLoaded) {
                EngineStartStopButton(
                    isLoaded = isLoaded,
                    isLoading = isLoading,
                    onToggle = onToggleEngine
                )
            }
        }
    }
}
