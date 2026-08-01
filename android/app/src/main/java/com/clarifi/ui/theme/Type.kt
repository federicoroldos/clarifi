package com.clarifi.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp

/**
 * The desktop asks for Google Sans and falls back to the system UI font. Google
 * Sans cannot be redistributed in an APK, so the system font (Roboto, or whatever
 * the device ships) is used directly - the sizes, weights and the tight tracking
 * on large text are what actually carry ClariFi's typographic feel, and those are
 * matched here.
 */
private val Family = FontFamily.Default

private val Tight = LineHeightStyle(
    alignment = LineHeightStyle.Alignment.Center,
    trim = LineHeightStyle.Trim.None,
)

val ClariFiTypography = Typography(
    // Balances and headline figures: large, heavy, tightly tracked.
    displaySmall = TextStyle(
        fontFamily = Family,
        fontWeight = FontWeight.Bold,
        fontSize = 34.sp,
        lineHeight = 40.sp,
        letterSpacing = (-0.8).sp,
        lineHeightStyle = Tight,
    ),
    headlineMedium = TextStyle(
        fontFamily = Family,
        fontWeight = FontWeight.Bold,
        fontSize = 26.sp,
        lineHeight = 32.sp,
        letterSpacing = (-0.5).sp,
        lineHeightStyle = Tight,
    ),
    headlineSmall = TextStyle(
        fontFamily = Family,
        fontWeight = FontWeight.SemiBold,
        fontSize = 21.sp,
        lineHeight = 28.sp,
        letterSpacing = (-0.4).sp,
        lineHeightStyle = Tight,
    ),
    // Card and section titles.
    titleLarge = TextStyle(
        fontFamily = Family,
        fontWeight = FontWeight.SemiBold,
        fontSize = 19.sp,
        lineHeight = 25.sp,
        letterSpacing = (-0.3).sp,
        lineHeightStyle = Tight,
    ),
    titleMedium = TextStyle(
        fontFamily = Family,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
        letterSpacing = (-0.1).sp,
        lineHeightStyle = Tight,
    ),
    titleSmall = TextStyle(
        fontFamily = Family,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.sp,
        lineHeightStyle = Tight,
    ),
    bodyLarge = TextStyle(
        fontFamily = Family,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 23.sp,
        letterSpacing = 0.15.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = Family,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.15.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = Family,
        fontWeight = FontWeight.Normal,
        fontSize = 12.5.sp,
        lineHeight = 17.sp,
        letterSpacing = 0.2.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = Family,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 19.sp,
        letterSpacing = 0.1.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = Family,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.4.sp,
    ),
    // Section eyebrows: small, spaced out, uppercase at the call site.
    labelSmall = TextStyle(
        fontFamily = Family,
        fontWeight = FontWeight.SemiBold,
        fontSize = 11.sp,
        lineHeight = 15.sp,
        letterSpacing = 0.9.sp,
    ),
)
