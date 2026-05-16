package com.porta.rokid.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// ── Brand Colors ──
val PortaPrimary = Color(0xFF6366F1)     // Indigo-500
val PortaSecondary = Color(0xFF8B5CF6)   // Violet-500
val PortaTertiary = Color(0xFF06B6D4)    // Cyan-500
val PortaSurface = Color(0xFF0F172A)     // Slate-900
val PortaSurfaceVariant = Color(0xFF1E293B) // Slate-800
val PortaOnSurface = Color(0xFFF1F5F9)   // Slate-100
val PortaSuccess = Color(0xFF10B981)     // Emerald-500
val PortaWarning = Color(0xFFF59E0B)     // Amber-500
val PortaError = Color(0xFFEF4444)       // Red-500

private val DarkColorScheme = darkColorScheme(
    primary = PortaPrimary,
    secondary = PortaSecondary,
    tertiary = PortaTertiary,
    background = PortaSurface,
    surface = PortaSurface,
    surfaceVariant = PortaSurfaceVariant,
    onPrimary = Color.White,
    onSecondary = Color.White,
    onTertiary = Color.White,
    onBackground = PortaOnSurface,
    onSurface = PortaOnSurface,
    error = PortaError,
    onError = Color.White,
)

private val LightColorScheme = lightColorScheme(
    primary = PortaPrimary,
    secondary = PortaSecondary,
    tertiary = PortaTertiary,
)

@Composable
fun PortaRokidTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
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
        typography = Typography(),
        content = content
    )
}
