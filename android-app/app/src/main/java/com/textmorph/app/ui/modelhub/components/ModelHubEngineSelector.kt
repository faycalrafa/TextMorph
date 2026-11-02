package com.textmorph.app.ui.modelhub.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.textmorph.app.data.model.EngineType

/**
 * Modern 3-option engine selector for the top of Model Hub.
 * Allows switching between LiteRT-LM, llama.cpp (GGUF), and Cloud API.
 */
@Composable
fun ModelHubEngineSelector(
    selectedEngine: EngineType,
    onSelectEngine: (EngineType) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Color(0xFF111827),
        border = BorderStroke(1.dp, Color(0xFF1F2937)),
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            EngineSelectorTab(
                title = "LiteRT-LM",
                subtitle = "Google Mobile",
                isSelected = selectedEngine == EngineType.ON_DEVICE_LITERT,
                onClick = { onSelectEngine(EngineType.ON_DEVICE_LITERT) },
                modifier = Modifier.weight(1f)
            )

            EngineSelectorTab(
                title = "llama.cpp",
                subtitle = "GGUF Native",
                isSelected = selectedEngine == EngineType.ON_DEVICE_LLAMA_CPP,
                onClick = { onSelectEngine(EngineType.ON_DEVICE_LLAMA_CPP) },
                modifier = Modifier.weight(1f)
            )

            EngineSelectorTab(
                title = "Cloud API",
                subtitle = "OpenAI / Custom",
                isSelected = selectedEngine == EngineType.CLOUD_OPENAI,
                onClick = { onSelectEngine(EngineType.CLOUD_OPENAI) },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun EngineSelectorTab(
    title: String,
    subtitle: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(9.dp),
        color = if (isSelected) Color(0xFF2563EB) else Color.Transparent,
        modifier = modifier.clickable(onClick = onClick)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp)
        ) {
            Text(
                text = title,
                fontSize = 13.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                color = if (isSelected) Color.White else Color(0xFFE5E7EB)
            )
            Text(
                text = subtitle,
                fontSize = 10.sp,
                color = if (isSelected) Color(0xFFBFDBFE) else Color(0xFF9CA3AF)
            )
        }
    }
}
