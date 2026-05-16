package id.infinia.porta.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// ── Theme Mode ──

enum class ThemeMode { SYSTEM, LIGHT, DARK }

// ── Brand Colors (fixed, theme-independent) ──
val PortaPrimary = Color(0xFF6366F1)     // Indigo-500
val PortaSecondary = Color(0xFF8B5CF6)   // Violet-500
val PortaTertiary = Color(0xFF06B6D4)    // Cyan-500
val PortaSuccess = Color(0xFF10B981)     // Emerald-500
val PortaWarning = Color(0xFFF59E0B)     // Amber-500
val PortaError = Color(0xFFEF4444)       // Red-500

// ── Adaptive Colors (change based on light/dark) ──

data class PortaColors(
    val surface: Color,
    val surfaceVariant: Color,
    val onSurface: Color,
    val cardBackground: Color,
    val userBubble: Color,
    val userBubbleText: Color,
    val assistantBubble: Color,
    val assistantBubbleText: Color,
    val isDark: Boolean,
)

private val DarkPortaColors = PortaColors(
    surface = Color(0xFF0F172A),           // Slate-900
    surfaceVariant = Color(0xFF1E293B),    // Slate-800
    onSurface = Color(0xFFF1F5F9),        // Slate-100
    cardBackground = Color(0xFF1E293B),
    userBubble = Color(0xFF6366F1),        // Indigo
    userBubbleText = Color.White,
    assistantBubble = Color(0xFF1E293B),   // Slate-800
    assistantBubbleText = Color(0xFFF1F5F9),
    isDark = true,
)

private val LightPortaColors = PortaColors(
    surface = Color(0xFFF8FAFC),           // Slate-50
    surfaceVariant = Color(0xFFE2E8F0),    // Slate-200
    onSurface = Color(0xFF0F172A),         // Slate-900
    cardBackground = Color.White,
    userBubble = Color(0xFF6366F1),        // Indigo
    userBubbleText = Color.White,
    assistantBubble = Color(0xFFE2E8F0),   // Slate-200
    assistantBubbleText = Color(0xFF1E293B),
    isDark = false,
)

val LocalPortaColors = staticCompositionLocalOf { DarkPortaColors }

// ── Legacy accessors (backwards compatible — read from composition local) ──
// These are used by existing screens. They are NOT @Composable, so they
// default to dark values. New code should use PortaTheme.colors instead.
val PortaSurface get() = DarkPortaColors.surface
val PortaSurfaceVariant get() = DarkPortaColors.surfaceVariant
val PortaOnSurface get() = DarkPortaColors.onSurface

// ── Material Color Schemes ──

private val DarkColorScheme = darkColorScheme(
    primary = PortaPrimary,
    secondary = PortaSecondary,
    tertiary = PortaTertiary,
    background = DarkPortaColors.surface,
    surface = DarkPortaColors.surface,
    surfaceVariant = DarkPortaColors.surfaceVariant,
    onPrimary = Color.White,
    onSecondary = Color.White,
    onTertiary = Color.White,
    onBackground = DarkPortaColors.onSurface,
    onSurface = DarkPortaColors.onSurface,
    error = PortaError,
    onError = Color.White,
)

private val LightColorScheme = lightColorScheme(
    primary = PortaPrimary,
    secondary = PortaSecondary,
    tertiary = PortaTertiary,
    background = LightPortaColors.surface,
    surface = LightPortaColors.surface,
    surfaceVariant = LightPortaColors.surfaceVariant,
    onPrimary = Color.White,
    onSecondary = Color.White,
    onTertiary = Color.White,
    onBackground = LightPortaColors.onSurface,
    onSurface = LightPortaColors.onSurface,
    error = PortaError,
    onError = Color.White,
)

// ── Theme ──

object PortaTheme {
    val colors: PortaColors
        @Composable
        get() = LocalPortaColors.current
}

@Composable
fun PortaRokidTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
    }

    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    val portaColors = if (darkTheme) DarkPortaColors else LightPortaColors

    CompositionLocalProvider(LocalPortaColors provides portaColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography(),
            content = content
        )
    }
}
