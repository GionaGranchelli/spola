package dev.spola.app.app.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf

private val SpolaDarkColorScheme = darkColorScheme(
    primary = SpolaColors.Dark.accent,
    onPrimary = SpolaColors.Dark.userBubbleText,
    secondary = SpolaColors.Dark.accentLight,
    background = SpolaColors.Dark.bg,
    surface = SpolaColors.Dark.bgSurface,
    surfaceVariant = SpolaColors.Dark.bgElevated,
    onBackground = SpolaColors.Dark.textPrimary,
    onSurface = SpolaColors.Dark.textPrimary,
    onSurfaceVariant = SpolaColors.Dark.textSecondary,
    error = SpolaColors.Dark.error,
    onError = SpolaColors.Dark.userBubbleText,
    outline = SpolaColors.Dark.textMuted,
)

private val SpolaLightColorScheme = lightColorScheme(
    primary = SpolaColors.Light.accent,
    onPrimary = SpolaColors.Light.userBubbleText,
    secondary = SpolaColors.Light.accentLight,
    background = SpolaColors.Light.bg,
    surface = SpolaColors.Light.bgSurface,
    surfaceVariant = SpolaColors.Light.bgElevated,
    onBackground = SpolaColors.Light.textPrimary,
    onSurface = SpolaColors.Light.textPrimary,
    onSurfaceVariant = SpolaColors.Light.textSecondary,
    error = SpolaColors.Light.error,
    onError = SpolaColors.Light.userBubbleText,
    outline = SpolaColors.Light.textMuted,
)

/**
 * CompositionLocal to allow deep theme toggling.
 */
val LocalIsDarkMode = staticCompositionLocalOf { true }

@Composable
fun SpolaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val effectiveDark = darkTheme
    SpolaColors.isDarkMode = effectiveDark

    CompositionLocalProvider(LocalIsDarkMode provides effectiveDark) {
        MaterialTheme(
            colorScheme = if (effectiveDark) SpolaDarkColorScheme else SpolaLightColorScheme,
            typography = SpolaTypography,
            shapes = SpolaShapes,
            content = content,
        )
    }
}
