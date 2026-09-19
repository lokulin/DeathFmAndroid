package com.terraeclectic.deathfm.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Matches death.fm's own --theme-color / --theme-bg for the dfm station -
// see WindowChromeHelper.cs in DeathFmTray for the desktop-app equivalent.
val DeathFmRed = Color(0xFFFF0000)
val DeathFmBackground = Color(0xFF220000)
val DeathFmSurface = Color(0xFF000000)
val DeathFmOnSurface = Color(0xFFFFFFEE)

private val DeathFmColorScheme = darkColorScheme(
    primary = DeathFmRed,
    onPrimary = DeathFmSurface,
    background = DeathFmBackground,
    onBackground = DeathFmOnSurface,
    surface = DeathFmSurface,
    onSurface = DeathFmOnSurface,
)

/** Always dark, station-red accent - this app doesn't have a light theme, same as the player page itself. */
@Composable
fun DeathFmTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DeathFmColorScheme, content = content)
}
