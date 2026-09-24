package com.gekkotron.oikos.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

enum class ThemeChoice { SYSTEM, NIGHT_GLASS, WARM_MINIMAL }

private val LocalOikosColors = staticCompositionLocalOf { NightGlassColors }
private val LocalOikosType = staticCompositionLocalOf { oikosType(dark = true) }

private val NightScheme = darkColorScheme(
    primary = Color(0xFFFFC457), onPrimary = Color(0xFF0B1020), secondary = Color(0xFF6FF0BF),
    background = Color(0xFF0B1020), onBackground = Color(0xFFE8ECFF),
    surface = Color(0xFF151B33), onSurface = Color(0xFFE8ECFF), onSurfaceVariant = Color(0xFFB9BFD6),
    surfaceContainerLow = Color(0xFF151B33), surfaceContainer = Color(0xFF151B33), surfaceContainerHigh = Color(0xFF1B2240),
    secondaryContainer = Color(0xFF2A3358), onSecondaryContainer = Color(0xFFE8ECFF),
    inverseSurface = Color(0xFFE8ECFF), inverseOnSurface = Color(0xFF0B1020), outline = Color(0x33FFFFFF),
)

private val WarmScheme = lightColorScheme(
    primary = Color(0xFF1F1B16), onPrimary = Color(0xFFF5F1EA), secondary = Color(0xFFD98B3A),
    background = Color(0xFFF5F1EA), onBackground = Color(0xFF1F1B16),
    surface = Color(0xFFFFFDF9), onSurface = Color(0xFF1F1B16), onSurfaceVariant = Color(0xFF5E574E),
    surfaceContainerLow = Color(0xFFFFFDF9), surfaceContainer = Color(0xFFFFFDF9), surfaceContainerHigh = Color(0xFFF8F3EB),
    secondaryContainer = Color(0xFFECE5DA), onSecondaryContainer = Color(0xFF1F1B16),
    inverseSurface = Color(0xFF1F1B16), inverseOnSurface = Color(0xFFF5F1EA), outline = Color(0xFFD9D0C2),
)

/** Follows the system unless the user picked a theme (spec §1.1). */
@Composable
fun OikosTheme(choice: ThemeChoice = ThemeChoice.SYSTEM, content: @Composable () -> Unit) {
    val dark = when (choice) {
        ThemeChoice.SYSTEM -> isSystemInDarkTheme()
        ThemeChoice.NIGHT_GLASS -> true
        ThemeChoice.WARM_MINIMAL -> false
    }
    val colors = if (dark) NightGlassColors else WarmMinimalColors
    CompositionLocalProvider(LocalOikosColors provides colors, LocalOikosType provides oikosType(dark)) {
        MaterialTheme(colorScheme = if (dark) NightScheme else WarmScheme, typography = materialTypography(), content = content)
    }
}

object OikosTheme {
    val colors: OikosColors
        @Composable @ReadOnlyComposable get() = LocalOikosColors.current
    val type: OikosType
        @Composable @ReadOnlyComposable get() = LocalOikosType.current
}

/** Night Glass: radial gradient from the top-left; Warm Minimal: flat paper. */
@Composable
fun OikosBackground(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val c = OikosTheme.colors
    Box(
        modifier
            .fillMaxSize()
            .drawBehind {
                drawRect(c.backgroundBottom)
                if (c.backgroundTop != c.backgroundBottom) {
                    drawRect(
                        Brush.radialGradient(
                            listOf(c.backgroundTop, c.backgroundBottom),
                            center = Offset(size.width * 0.2f, 0f),
                            radius = size.maxDimension * 0.9f,
                        ),
                    )
                }
            },
    ) {
        CompositionLocalProvider(LocalContentColor provides c.content) { content() }
    }
}
