package com.textmorph.app.ui.chat.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Modular LazyListScope extension for rendering runtime LLM inference configuration
 * (temperature, max tokens, top-p, thinking traces) inside model switch sheets.
 */
fun LazyListScope.modelGenerationConfigSection(
    temperature: Float,
    onTemperatureChanged: (Float) -> Unit,
    maxTokens: Int,
    onMaxTokensChanged: (Int) -> Unit,
    topP: Float,
    onTopPChanged: (Float) -> Unit,
    thinkingEnabled: Boolean,
    onThinkingEnabledChanged: (Boolean) -> Unit
) {
    item {
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "GENERATION PARAMETERS",
            color = Color(0xFF60A5FA),
            fontSize = 11.5.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.sp
        )
    }

    // Temperature Slider
    item {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Temperature", color = Color(0xFFE5E7EB), fontSize = 13.sp)
                Text(String.format("%.2f", temperature), color = Color(0xFF3B82F6), fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
            Slider(
                value = temperature,
                onValueChange = onTemperatureChanged,
                valueRange = 0.0f..1.5f,
                colors = SliderDefaults.colors(
                    thumbColor = Color(0xFF3B82F6),
                    activeTrackColor = Color(0xFF3B82F6),
                    inactiveTrackColor = Color(0xFF374151)
                )
            )
        }
    }

    // Max Tokens Slider
    item {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Max Output Tokens", color = Color(0xFFE5E7EB), fontSize = 13.sp)
                Text("$maxTokens", color = Color(0xFF3B82F6), fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
            Text("Response generation limit per turn", color = Color(0xFF9CA3AF), fontSize = 11.sp)
            Slider(
                value = maxTokens.toFloat(),
                onValueChange = { onMaxTokensChanged(it.toInt()) },
                valueRange = 128f..8192f,
                steps = 63,
                colors = SliderDefaults.colors(
                    thumbColor = Color(0xFF3B82F6),
                    activeTrackColor = Color(0xFF3B82F6),
                    inactiveTrackColor = Color(0xFF374151)
                )
            )
        }
    }

    // Top-P Slider
    item {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Top-P", color = Color(0xFFE5E7EB), fontSize = 13.sp)
                Text(String.format("%.2f", topP), color = Color(0xFF3B82F6), fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
            Slider(
                value = topP,
                onValueChange = onTopPChanged,
                valueRange = 0.1f..1.0f,
                colors = SliderDefaults.colors(
                    thumbColor = Color(0xFF3B82F6),
                    activeTrackColor = Color(0xFF3B82F6),
                    inactiveTrackColor = Color(0xFF374151)
                )
            )
        }
    }

    // Thinking Mode Toggle
    item {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF1F2937))
                .border(1.dp, Color(0xFF374151), RoundedCornerShape(10.dp))
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Thinking Traces", color = Color(0xFFF9FAFB), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text("Show collapsible reasoning process before answer", color = Color(0xFF9CA3AF), fontSize = 11.sp)
            }
            Switch(
                checked = thinkingEnabled,
                onCheckedChange = onThinkingEnabledChanged,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = Color(0xFF2563EB),
                    uncheckedThumbColor = Color(0xFF9CA3AF),
                    uncheckedTrackColor = Color(0xFF374151)
                )
            )
        }
    }
}
