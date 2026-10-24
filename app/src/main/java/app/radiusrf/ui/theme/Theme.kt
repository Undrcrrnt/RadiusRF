package app.radiusrf.ui.theme

import android.app.Activity
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

private fun scheme(p: AppPalette) = if (p.light) {
    lightColorScheme(
        primary = p.accent,
        onPrimary = p.onAccent,
        primaryContainer = p.accentBg,
        onPrimaryContainer = p.accent,
        secondary = p.info,
        onSecondary = Color.White,
        secondaryContainer = p.infoBg,
        onSecondaryContainer = p.info,
        tertiary = p.medium,
        onTertiary = p.bg,
        tertiaryContainer = p.mediumBg,
        onTertiaryContainer = p.medium,
        error = p.critical,
        onError = p.onCritical,
        errorContainer = p.criticalBg,
        onErrorContainer = p.critical,
        background = p.bg,
        onBackground = p.text,
        surface = p.surface,
        onSurface = p.text,
        surfaceVariant = p.surface2,
        onSurfaceVariant = p.text2,
        surfaceContainerLowest = p.bg,
        surfaceContainerLow = p.surface,
        surfaceContainer = p.surface,
        surfaceContainerHigh = p.surface2,
        surfaceContainerHighest = p.surface3,
        outline = p.borderStrong,
        outlineVariant = p.border,
        scrim = p.scrim
    )
} else {
    darkColorScheme(
        primary = p.accent,
        onPrimary = p.onAccent,
        primaryContainer = p.accentBg,
        onPrimaryContainer = p.accent,
        secondary = p.info,
        onSecondary = p.bg,
        secondaryContainer = p.infoBg,
        onSecondaryContainer = p.info,
        tertiary = p.medium,
        onTertiary = p.bg,
        tertiaryContainer = p.mediumBg,
        onTertiaryContainer = p.medium,
        error = p.critical,
        onError = p.onCritical,
        errorContainer = p.criticalBg,
        onErrorContainer = p.critical,
        background = p.bg,
        onBackground = p.text,
        surface = p.surface,
        onSurface = p.text,
        surfaceVariant = p.surface2,
        onSurfaceVariant = p.text2,
        surfaceContainerLowest = p.bg,
        surfaceContainerLow = p.surface,
        surfaceContainer = p.surface,
        surfaceContainerHigh = p.surface2,
        surfaceContainerHighest = p.surface3,
        outline = p.borderStrong,
        outlineVariant = p.border,
        scrim = p.scrim
    )
}

private val RadiusRfShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(14.dp),
    extraLarge = RoundedCornerShape(22.dp)
)

@Composable
fun RadiusRfTheme(content: @Composable () -> Unit) {
    val palette = AppThemes.selected.value
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            @Suppress("DEPRECATION")
            window.statusBarColor = palette.bg.toArgb()
            @Suppress("DEPRECATION")
            window.navigationBarColor = palette.bg.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = palette.light
                isAppearanceLightNavigationBars = palette.light
            }
        }
    }

    MaterialTheme(
        colorScheme = scheme(palette),
        typography = Typography,
        shapes = RadiusRfShapes,
        content = content
    )
}
