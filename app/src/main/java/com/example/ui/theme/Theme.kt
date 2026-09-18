package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = CrimsonPrimary,
    onPrimary = Color.White,
    primaryContainer = CrimsonContainer,
    onPrimaryContainer = CrimsonOnContainer,
    secondary = CyanAccent,
    onSecondary = Color.Black,
    secondaryContainer = SlateSurfaceVariant,
    onSecondaryContainer = TextPrimary,
    tertiary = AmberWarning,
    background = SlateBlack,
    onBackground = TextPrimary,
    surface = SlateDark,
    onSurface = TextPrimary,
    surfaceVariant = SlateSurface,
    onSurfaceVariant = TextSecondary,
    outline = SlateBorder,
    outlineVariant = SlateBorderSubtle
)

private val LightColorScheme = lightColorScheme(
    primary = CrimsonPrimary,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFE4E8),
    onPrimaryContainer = Color(0xFF4C0519),
    secondary = Color(0xFF0891B2),
    onSecondary = Color.White,
    secondaryContainer = LightSurfaceVariant,
    onSecondaryContainer = LightTextPrimary,
    tertiary = AmberWarning,
    background = LightBackground,
    onBackground = LightTextPrimary,
    surface = LightSurface,
    onSurface = LightTextPrimary,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightTextSecondary,
    outline = LightBorder,
    outlineVariant = Color(0xFFCBD5E1)
)

enum class ThemeMode {
    LIGHT,
    DARK,
    SYSTEM
}

@Composable
fun MyApplicationTheme(
    // v1.8: app default is light mode (was dark).
    darkTheme: Boolean = false,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
