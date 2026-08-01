package com.clarifi.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/** How the app picks between the dark and light palettes. */
enum class ThemeMode {
    SYSTEM,
    DARK,
    LIGHT;

    companion object {
        fun from(value: String?): ThemeMode =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: SYSTEM
    }
}

@Composable
fun ClariFiTheme(
    mode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit,
) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
    }

    val colorScheme = if (dark) ClariFiDarkColors else ClariFiLightColors
    val palette = if (dark) DarkPalette else LightPalette

    // The bars are transparent (edge-to-edge), so only the icon tint has to follow
    // the theme - otherwise light mode gets white-on-white status icons.
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }

    CompositionLocalProvider(LocalClariFiPalette provides palette) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = ClariFiTypography,
            shapes = ClariFiShapes,
            content = content,
        )
    }
}

/** Shorthand for the tokens Material 3 has no slot for. */
val clarifiPalette: ClariFiPalette
    @Composable get() = LocalClariFiPalette.current
