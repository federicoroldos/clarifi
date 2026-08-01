package com.clarifi.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import com.clarifi.core.money.Currency
import com.clarifi.core.money.Money
import com.clarifi.ui.theme.Motion

/**
 * A money figure that counts to its new value instead of snapping to it.
 *
 * Only changes animate: the first composition shows the real figure straight away,
 * so opening a screen never shows a balance rolling up from zero.
 *
 * The settled value is always the exact [amount], never the animation's last
 * frame - the tween runs in Float and would round a large balance by a cent.
 */
@Composable
fun AnimatedAmount(
    currency: Currency,
    amount: Double,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
    signed: Boolean = false,
    isIncome: Boolean = false,
    maxLines: Int = 1,
) {
    val animated = remember { Animatable(amount.toFloat()) }
    LaunchedEffect(amount) { animated.animateTo(amount.toFloat(), Motion.number()) }

    val shown = if (animated.isRunning) animated.value.toDouble() else amount

    Text(
        text = if (signed) {
            Money.formatSigned(currency, shown, isIncome)
        } else {
            Money.format(currency, shown)
        },
        style = style,
        color = color,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}
