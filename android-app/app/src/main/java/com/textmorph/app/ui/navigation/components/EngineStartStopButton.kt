package com.textmorph.app.ui.navigation.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Reusable Start/Stop toggle button for local on-device engines (LiteRT & llama.cpp)
 * styled consistently for navbars and cards.
 */
@Composable
fun EngineStartStopButton(
    isLoaded: Boolean,
    isLoading: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (isLoading) {
        CircularProgressIndicator(
            modifier = modifier.size(22.dp),
            strokeWidth = 2.dp,
            color = Color(0xFF3B82F6)
        )
    } else if (isLoaded) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = Color(0xFFDC2626).copy(alpha = 0.2f),
            border = BorderStroke(1.dp, Color(0xFFDC2626)),
            modifier = modifier.clickable(onClick = onToggle)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Stop,
                    contentDescription = "Stop Engine",
                    tint = Color(0xFFEF4444),
                    modifier = Modifier.size(15.dp)
                )
                Spacer(modifier = Modifier.width(3.dp))
                Text(
                    text = "Stop",
                    color = Color(0xFFEF4444),
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    } else {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = Color(0xFF16A34A).copy(alpha = 0.2f),
            border = BorderStroke(1.dp, Color(0xFF16A34A)),
            modifier = modifier.clickable(onClick = onToggle)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = "Start Engine",
                    tint = Color(0xFF22C55E),
                    modifier = Modifier.size(15.dp)
                )
                Spacer(modifier = Modifier.width(3.dp))
                Text(
                    text = "Start",
                    color = Color(0xFF22C55E),
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
