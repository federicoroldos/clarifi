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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The ClariFi mark: three concentric emerald rings around a solid core, the same
 * shape as the desktop's favicon and the launcher icon.
 *
 * Drawn rather than shipped as a drawable so it picks up the accent colour from
 * whichever theme is active.
 */
@Composable
fun ClariFiLogo(
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
) {
    val accent = MaterialTheme.colorScheme.primary
    val core = MaterialTheme.colorScheme.background

    Canvas(modifier = modifier.size(size)) {
        val center = Offset(this.size.width / 2f, this.size.height / 2f)
        val unit = this.size.minDimension / 64f
        val strokeWidth = 2.5f * unit

        drawCircle(accent, radius = 20f * unit, center = center, alpha = 0.32f, style = Stroke(strokeWidth))
        drawCircle(accent, radius = 14f * unit, center = center, alpha = 0.60f, style = Stroke(strokeWidth))
        drawCircle(accent, radius = 7f * unit, center = center)
        drawCircle(core, radius = 2.4f * unit, center = center)
    }
}

/** Mark plus wordmark, as it appears at the top of the desktop sidebar. */
@Composable
fun ClariFiWordmark(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ClariFiLogo(size = 38.dp)
        Text(
            text = "ClariFi",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
