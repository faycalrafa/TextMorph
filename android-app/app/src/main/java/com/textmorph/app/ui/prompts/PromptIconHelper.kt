package com.textmorph.app.ui.prompts

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ShortText
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Screenshot
import androidx.compose.material.icons.filled.Summarize
import androidx.compose.ui.graphics.vector.ImageVector
import com.textmorph.app.data.model.ContextAction
import com.textmorph.app.data.model.PromptEntity

/**
 * Resolves a crisp Material Vector Icon for a given [PromptEntity].
 * Prefers explicit [PromptEntity.iconName], then known seed IDs, then [ContextAction].
 */
fun PromptEntity.getVectorIcon(): ImageVector? {
    val name = iconName ?: when (id) {
        "seed_creative_polish" -> "auto_awesome"
        "seed_clipboard_summary" -> "summarize"
        "seed_screen_assist" -> "screenshot"
        else -> null
    }

    if (name != null) {
        when (name.lowercase()) {
            "screenshot", "screen", "vision" -> return Icons.Default.Screenshot
            "summarize", "clipboard", "paste" -> return Icons.Default.Summarize
            "auto_awesome", "creative", "sparkle", "sparkles", "magic" -> return Icons.Default.AutoAwesome
            "auto_fix", "fix", "spellcheck" -> return Icons.Default.AutoFixHigh
            "camera" -> return Icons.Default.CameraAlt
            "ocr", "scanner" -> return Icons.Default.DocumentScanner
            "short_text", "next", "word" -> return Icons.AutoMirrored.Filled.ShortText
            "lightbulb", "idea" -> return Icons.Default.Lightbulb
        }
    }

    return when (contextAction) {
        ContextAction.SCREENSHOT -> Icons.Default.Screenshot
        ContextAction.CAMERA -> Icons.Default.CameraAlt
        ContextAction.OCR -> Icons.Default.DocumentScanner
        ContextAction.CLIPBOARD -> Icons.Default.Summarize
        else -> null
    }
}
