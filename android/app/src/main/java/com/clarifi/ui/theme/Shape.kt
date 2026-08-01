package com.clarifi.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Corner radii from the desktop's `--r-card` (28), `--r-sub` (20) and `--r-pill`
 * tokens. The generously rounded cards are a big part of ClariFi's look, so they
 * are kept rather than falling back to Material's smaller defaults.
 */
val ClariFiShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** `--r-pill`: fully rounded, for chips, filters and segmented controls. */
val PillShape = RoundedCornerShape(percent = 50)
