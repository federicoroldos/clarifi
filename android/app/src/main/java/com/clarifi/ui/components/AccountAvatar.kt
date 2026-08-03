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
import androidx.compose.ui.unit.sp
import com.clarifi.core.money.Currencies
import com.clarifi.data.db.Account
import com.clarifi.ui.theme.parseAccountColor

/**
 * An account's colour tile, carrying the flag of the currency's country - the
 * same badge the desktop draws on its account rows.
 *
 * The flag is emoji here because Android ships the glyphs; the desktop draws its
 * own SVG only because Windows does not. A currency with no flag falls back to
 * two letters, which is what every account showed before, so the label is drawn
 * in black or white depending on the swatch's luminance: the bright presets
 * (yellow, cyan) would wash out under a fixed white.
 */
@Composable
fun AccountAvatar(
    account: Account,
    modifier: Modifier = Modifier,
    size: Dp = 42.dp,
) {
    val color = parseAccountColor(account.displayColor, MaterialTheme.colorScheme.primary)
    val currency = Currencies.find(account.currency)
    val label = currency?.flag ?: account.currency.uppercase().take(2)

    Box(
        modifier = modifier
            .size(size)
            .background(color, MaterialTheme.shapes.small),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            // Scales with the tile, which is drawn at 42dp in lists and 20dp in
            // chips. labelMedium's own 16sp line height would clip the larger one.
            style = MaterialTheme.typography.labelMedium.copy(
                fontSize = (size.value * 0.46f).sp,
                lineHeight = (size.value * 0.56f).sp,
            ),
            color = if (color.luminance() > 0.5f) Color.Black else Color.White,
        )
    }
}
