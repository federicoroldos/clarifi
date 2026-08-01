package com.clarifi.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * ClariFi's palette, ported value for value from the CSS custom properties in
 * templates/index.html. The desktop and the phone are the same product, so the
 * hexes are shared rather than re-derived - Material You / dynamic colour is
 * deliberately not used.
 */

// ── dark ──────────────────────────────────────────────────────────────────────
private val DarkBg = Color(0xFF08080A)
private val DarkBg2 = Color(0xFF1C1C20)
private val DarkBg3 = Color(0xFF28282D)
private val DarkGlass = Color(0xFF2C2C31)
private val DarkGlassHover = Color(0xFF35353B)
private val DarkGlassActive = Color(0xFF3E3E45)
private val DarkBorder = Color(0xFF2E2E33)
private val DarkBorderStrong = Color(0xFF6E6E76)
private val DarkText = Color(0xFFECECEF)
private val DarkText2 = Color(0xFFC0C0C6)
private val DarkText3 = Color(0xFF8A8A92)
private val DarkAccent = Color(0xFF10B981)
private val DarkOnAccent = Color(0xFF04231A)
private val DarkAccentDim = Color(0xFF0D3D2E)
private val DarkSecondary = Color(0xFFF59E0B)
private val DarkSecondaryDim = Color(0xFF3A2A06)
private val DarkTertiary = Color(0xFFEC4899)
private val DarkTertiaryDim = Color(0xFF3B0D27)

// ── light ─────────────────────────────────────────────────────────────────────
private val LightBg = Color(0xFFECECEF)
private val LightBg2 = Color(0xFFFBFBFC)
private val LightBg3 = Color(0xFFE4E4E8)
private val LightGlass = Color(0xFFDEDEE2)
private val LightGlassHover = Color(0xFFD4D4D8)
private val LightGlassActive = Color(0xFFCACACE)
private val LightBorder = Color(0xFFD8D8DC)
private val LightBorderStrong = Color(0xFF6E6E76)
private val LightText = Color(0xFF1A1A1C)
private val LightText2 = Color(0xFF4A4A52)
private val LightText3 = Color(0xFF76767E)
private val LightAccent = Color(0xFF059669)
private val LightOnAccent = Color(0xFFFFFFFF)
private val LightAccentDim = Color(0xFFD1FAE5)
private val LightSecondary = Color(0xFFD97706)
private val LightSecondaryDim = Color(0xFFFEF3C7)
private val LightTertiary = Color(0xFFDB2777)
private val LightTertiaryDim = Color(0xFFFCE7F3)

/**
 * The tokens Material 3's [ColorScheme] has no slot for: the semantic status
 * colours, the third text tier, and the divider/glow shades the desktop uses.
 */
@Immutable
data class ClariFiPalette(
    val green: Color,
    val greenDim: Color,
    val red: Color,
    val redDim: Color,
    val orange: Color,
    val orangeDim: Color,
    val purple: Color,
    val purpleDim: Color,
    val teal: Color,
    val textMuted: Color,
    val borderStrong: Color,
    val accentGlow: Color,
    /** Elevated rows inside a card (list items, chips at rest). */
    val subtleSurface: Color,
    val subtleSurfaceHover: Color,
    val isDark: Boolean,
) {
    /** Income is green, spending is red - the same mapping as the web app. */
    fun amountColor(isIncome: Boolean): Color = if (isIncome) green else red
}

val DarkPalette = ClariFiPalette(
    green = Color(0xFF22C55E),
    greenDim = Color(0x2922C55E),
    red = Color(0xFFEF4444),
    redDim = Color(0x29EF4444),
    orange = Color(0xFFF59E0B),
    orangeDim = Color(0x29F59E0B),
    purple = Color(0xFFA855F7),
    purpleDim = Color(0x29A855F7),
    teal = Color(0xFF14B8A6),
    textMuted = DarkText3,
    borderStrong = DarkBorderStrong,
    accentGlow = Color(0x3810B981),
    subtleSurface = DarkGlass,
    subtleSurfaceHover = DarkGlassHover,
    isDark = true,
)

val LightPalette = ClariFiPalette(
    green = Color(0xFF16A34A),
    greenDim = Color(0x2216A34A),
    red = Color(0xFFDC2626),
    redDim = Color(0x22DC2626),
    orange = Color(0xFFD97706),
    orangeDim = Color(0x24D97706),
    purple = Color(0xFF9333EA),
    purpleDim = Color(0x229333EA),
    teal = Color(0xFF0D9488),
    textMuted = LightText3,
    borderStrong = LightBorderStrong,
    accentGlow = Color(0x2E059669),
    subtleSurface = LightGlass,
    subtleSurfaceHover = LightGlassHover,
    isDark = false,
)

val LocalClariFiPalette = staticCompositionLocalOf { DarkPalette }

/**
 * Material's surface tiers are mapped onto the desktop's flat greys rather than
 * Material's tonal elevation, which would tint every card with the accent.
 */
val ClariFiDarkColors: ColorScheme = darkColorScheme(
    primary = DarkAccent,
    onPrimary = DarkOnAccent,
    primaryContainer = DarkAccentDim,
    onPrimaryContainer = DarkAccent,
    secondary = DarkSecondary,
    onSecondary = Color(0xFF231A02),
    secondaryContainer = DarkSecondaryDim,
    onSecondaryContainer = DarkSecondary,
    tertiary = DarkTertiary,
    onTertiary = Color(0xFF2B0518),
    tertiaryContainer = DarkTertiaryDim,
    onTertiaryContainer = DarkTertiary,
    background = DarkBg,
    onBackground = DarkText,
    surface = DarkBg2,
    onSurface = DarkText,
    surfaceVariant = DarkGlass,
    onSurfaceVariant = DarkText2,
    surfaceContainerLowest = DarkBg,
    surfaceContainerLow = DarkBg2,
    surfaceContainer = DarkGlass,
    surfaceContainerHigh = DarkGlassHover,
    surfaceContainerHighest = DarkGlassActive,
    inverseSurface = DarkText,
    inverseOnSurface = DarkBg,
    outline = DarkBorder,
    outlineVariant = DarkBg3,
    error = Color(0xFFEF4444),
    onError = Color(0xFF2B0505),
    errorContainer = Color(0x29EF4444),
    onErrorContainer = Color(0xFFEF4444),
    scrim = Color(0xCC000000),
)

val ClariFiLightColors: ColorScheme = lightColorScheme(
    primary = LightAccent,
    onPrimary = LightOnAccent,
    primaryContainer = LightAccentDim,
    onPrimaryContainer = LightAccent,
    secondary = LightSecondary,
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = LightSecondaryDim,
    onSecondaryContainer = LightSecondary,
    tertiary = LightTertiary,
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = LightTertiaryDim,
    onTertiaryContainer = LightTertiary,
    background = LightBg,
    onBackground = LightText,
    surface = LightBg2,
    onSurface = LightText,
    surfaceVariant = LightGlass,
    onSurfaceVariant = LightText2,
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = LightBg2,
    surfaceContainer = LightGlass,
    surfaceContainerHigh = LightGlassHover,
    surfaceContainerHighest = LightGlassActive,
    inverseSurface = LightText,
    inverseOnSurface = LightBg,
    outline = LightBorder,
    outlineVariant = LightBg3,
    error = Color(0xFFDC2626),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0x22DC2626),
    onErrorContainer = Color(0xFFDC2626),
    scrim = Color(0x99000000),
)

/** Parses an account's stored `#rrggbb`, falling back to the accent if malformed. */
fun parseAccountColor(hex: String, fallback: Color): Color =
    runCatching {
        val cleaned = hex.trim().removePrefix("#")
        when (cleaned.length) {
            6 -> Color(0xFF000000 or cleaned.toLong(16))
            8 -> Color(cleaned.toLong(16))
            else -> fallback
        }
    }.getOrDefault(fallback)
