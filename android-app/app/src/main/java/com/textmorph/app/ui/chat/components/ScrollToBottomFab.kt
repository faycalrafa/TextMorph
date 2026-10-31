package com.textmorph.app.ui.chat.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
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
 * Floating button that appears when user has scrolled up,
 * allowing instant smooth scrolling back to the latest message.
 */
@Composable
fun ScrollToBottomFab(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = Color(0xFF1F2937),
        border = BorderStroke(1.dp, Color(0xFF3B82F6)),
        shadowElevation = 6.dp,
        modifier = modifier.clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.ArrowDownward,
                contentDescription = "Scroll to bottom",
                tint = Color(0xFF60A5FA),
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = "Latest",
                fontSize = 12.sp,
                color = Color(0xFFF9FAFB),
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}
