package com.apps.naviai.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val NaviDarkColorScheme = darkColorScheme(
    primary = NaviPrimary,
    onPrimary = NaviOnPrimary,
    background = NaviBackground,
    onBackground = NaviOnSurface,
    surface = NaviSurface,
    onSurface = NaviOnSurface,
    surfaceVariant = NaviSurfaceVariant,
    onSurfaceVariant = NaviOnSurfaceMuted,
    outline = NaviOutline,
    error = RiskCritical
)

private val NaviLightColorScheme = lightColorScheme(
    primary = NaviPrimary,
    error = RiskCritical
)

/**
 * Defaults to a dedicated dark, high-contrast palette rather than system
 * dynamic color: this app is built for blind/low-vision users first, so a
 * consistent, deliberately high-contrast look takes priority over matching
 * the device wallpaper theme.
 */
@Composable
fun NaviAITheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) NaviDarkColorScheme else NaviLightColorScheme
    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
