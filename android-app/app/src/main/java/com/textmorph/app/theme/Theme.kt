package com.textmorph.app.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

enum class ThemeMode(val displayName: String) {
    FOREST_CLAY("Forest Clay"), // Default
    DARK_SLATE("Charcoal Slate"),
    SEPIA("Sepia Parchment"),
    LIGHT("Clean Light")
}

private val ForestClayDarkColorScheme = darkColorScheme(
    primary = ForestClayPrimary,
    secondary = ForestClaySurfaceVariant,
    tertiary = Color(0xFFC59B76),
    background = ForestClayBg,
    surface = ForestClaySurface,
    surfaceVariant = ForestClaySurfaceVariant,
    onPrimary = Color.White,
    onBackground = ForestClayOnText,
    onSurface = ForestClayOnText,
    onSurfaceVariant = ForestClayMuted,
    outline = ForestClaySurfaceVariant
)

private val DarkSlateColorScheme = darkColorScheme(
    primary = AccentBlue,
    secondary = AccentPurple,
    background = BgDark0,
    surface = BgDark1,
    surfaceVariant = BgDark2,
    onPrimary = TextDark,
    onBackground = TextDark,
    onSurface = TextDark
)

private val SepiaColorScheme = lightColorScheme(
    primary = SepiaAccent,
    background = SepiaBg,
    surface = SepiaSurface,
    surfaceVariant = SepiaSurface,
    onPrimary = SepiaBg,
    onBackground = SepiaText,
    onSurface = SepiaText
)

private val LightColorScheme = lightColorScheme(
    primary = AccentBlue,
    background = LightBg,
    surface = LightSurface,
    surfaceVariant = Color(0xFFF1F5F9),
    onPrimary = LightSurface,
    onBackground = LightText,
    onSurface = LightText
)

@Composable
fun TextMorphTheme(
    themeMode: ThemeMode = ThemeMode.FOREST_CLAY,
    content: @Composable () -> Unit
) {
    val colorScheme = when (themeMode) {
        ThemeMode.FOREST_CLAY -> ForestClayDarkColorScheme
        ThemeMode.DARK_SLATE -> DarkSlateColorScheme
        ThemeMode.SEPIA -> SepiaColorScheme
        ThemeMode.LIGHT -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
