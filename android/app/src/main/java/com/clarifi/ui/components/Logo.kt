package com.clarifi.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The ClariFi mark: three concentric emerald rings around a solid core, on the
 * rounded plate and accent halo the desktop's `.logo-mark` wears.
 *
 * Drawn rather than shipped as a drawable so it picks up the accent colour from
 * whichever theme is active. The halo is a radial gradient because Compose's
 * elevation shadow is black only, and the web's `box-shadow` is tinted.
 */
@Composable
fun ClariFiLogo(
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
) {
    val accent = MaterialTheme.colorScheme.primary
    val plate = MaterialTheme.colorScheme.surface
    val core = MaterialTheme.colorScheme.background

    // The halo needs room outside the plate, so the composable reserves it rather
    // than drawing past its bounds, where a clipping ancestor would cut it off.
    Canvas(modifier = modifier.size(size * HALO_SCALE)) {
        val plateSide = this.size.minDimension / HALO_SCALE
        val center = Offset(this.size.width / 2f, this.size.height / 2f)
        val unit = plateSide / 64f

        // `box-shadow: 0 0 28px 4px var(--accent-glow)`: a glow that hugs the plate
        // and fades out fast. Spread wide and it stops reading as a shadow and
        // starts reading as a disc, swallowing the rounded square it should frame.
        val haloRadius = plateSide * 0.86f
        drawCircle(
            brush = Brush.radialGradient(
                0.62f to accent.copy(alpha = 0.20f),
                0.78f to accent.copy(alpha = 0.09f),
                1.0f to Color.Transparent,
                center = center,
                radius = haloRadius,
            ),
            radius = haloRadius,
            center = center,
        )

        // The favicon's `<rect rx='18'>`, at the CSS radius of 14 on 40.
        drawRoundRect(
            color = plate,
            topLeft = Offset(center.x - plateSide / 2f, center.y - plateSide / 2f),
            size = Size(plateSide, plateSide),
            cornerRadius = CornerRadius(plateSide * 0.35f),
        )

        // Ring geometry is the favicon's, scaled from its 64px canvas, and drawn
        // crisp. Only the halo behind is soft: feathering the rings themselves
        // reads as an out-of-focus logo rather than a glowing one.
        val stroke = 2.5f * unit
        drawCircle(accent, radius = 20f * unit, center = center, alpha = 0.32f, style = Stroke(stroke))
        drawCircle(accent, radius = 14f * unit, center = center, alpha = 0.60f, style = Stroke(stroke))
        drawCircle(accent, radius = 7f * unit, center = center)
        drawCircle(core, radius = 2.4f * unit, center = center)
    }
}

/** How much wider than the plate the composable is, to leave room for the halo. */
private const val HALO_SCALE = 1.38f

/** Mark plus wordmark, as it appears at the top of the desktop sidebar. */
@Composable
fun ClariFiWordmark(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ClariFiLogo(size = 38.dp)
        Text(
            text = "ClariFi",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
