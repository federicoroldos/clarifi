package com.clarifi.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.clarifi.data.db.Account
import com.clarifi.ui.theme.parseAccountColor

/** The two-letter badges the desktop shows on each account row. */
private val REGION_CODES = mapOf(
    "uyu" to "UY",
    "usd" to "US",
    "krw" to "KR",
    "eur" to "EU",
    "ars" to "AR",
)

/**
 * An account's colour tile. The label is drawn in black or white depending on the
 * swatch's luminance, so the bright presets (yellow, cyan) stay readable instead
 * of washing out under a fixed white.
 */
@Composable
fun AccountAvatar(
    account: Account,
    modifier: Modifier = Modifier,
    size: Dp = 42.dp,
) {
    val color = parseAccountColor(account.displayColor, MaterialTheme.colorScheme.primary)
    val label = REGION_CODES[account.currency] ?: account.currency.uppercase().take(2)

    Box(
        modifier = modifier
            .size(size)
            .background(color, MaterialTheme.shapes.small),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (color.luminance() > 0.5f) Color.Black else Color.White,
        )
    }
}
