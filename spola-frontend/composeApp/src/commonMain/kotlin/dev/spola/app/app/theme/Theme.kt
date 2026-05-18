package dev.spola.app.app.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf

private val GolemDarkColorScheme = darkColorScheme(
    primary = GolemColors.Dark.accent,
    onPrimary = GolemColors.Dark.userBubbleText,
    secondary = GolemColors.Dark.accentLight,
    background = GolemColors.Dark.bg,
    surface = GolemColors.Dark.bgSurface,
    surfaceVariant = GolemColors.Dark.bgElevated,
    onBackground = GolemColors.Dark.textPrimary,
    onSurface = GolemColors.Dark.textPrimary,
    onSurfaceVariant = GolemColors.Dark.textSecondary,
    error = GolemColors.Dark.error,
    onError = GolemColors.Dark.userBubbleText,
    outline = GolemColors.Dark.textMuted,
)

private val GolemLightColorScheme = lightColorScheme(
    primary = GolemColors.Light.accent,
    onPrimary = GolemColors.Light.userBubbleText,
    secondary = GolemColors.Light.accentLight,
    background = GolemColors.Light.bg,
    surface = GolemColors.Light.bgSurface,
    surfaceVariant = GolemColors.Light.bgElevated,
    onBackground = GolemColors.Light.textPrimary,
    onSurface = GolemColors.Light.textPrimary,
    onSurfaceVariant = GolemColors.Light.textSecondary,
    error = GolemColors.Light.error,
    onError = GolemColors.Light.userBubbleText,
    outline = GolemColors.Light.textMuted,
)

/**
 * CompositionLocal to allow deep theme toggling.
 */
val LocalIsDarkMode = staticCompositionLocalOf { true }

@Composable
fun GolemTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val effectiveDark = darkTheme
    GolemColors.isDarkMode = effectiveDark

    CompositionLocalProvider(LocalIsDarkMode provides effectiveDark) {
        MaterialTheme(
            colorScheme = if (effectiveDark) GolemDarkColorScheme else GolemLightColorScheme,
            typography = GolemTypography,
            shapes = GolemShapes,
            content = content,
        )
    }
}
