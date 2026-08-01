package com.clarifi.ui.transactions

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.clarifi.core.model.Categories
import com.clarifi.core.model.TransferDirection
import com.clarifi.core.model.TxnType
import com.clarifi.core.money.Money
import com.clarifi.data.db.Account
import com.clarifi.data.db.Txn
import com.clarifi.ui.components.ClariFiCard
import com.clarifi.ui.icons.ClariFiIcons
import com.clarifi.ui.theme.clarifiPalette

/**
 * One transaction.
 *
 * The leading badge carries the category emoji and the web's colour for the type
 * (red spent, green received, accent moved), exactly like `txn-ico` on the
 * desktop. Transfers have no real category, so they keep the transfer glyph.
 * The signed amount repeats the direction, so nothing rests on colour alone.
 */
@Composable
fun TransactionRow(
    txn: Txn,
    account: Account?,
    counterpart: Account?,
    modifier: Modifier = Modifier,
) {
    val palette = clarifiPalette
    val currency = account?.currencyMeta

    val isIncoming = when {
        txn.isTransfer -> txn.direction == TransferDirection.IN
        else -> txn.txnType == TxnType.FUND
    }

    val accent = when {
        txn.isTransfer -> palette.orange
        isIncoming -> palette.green
        else -> palette.red
    }

    val subtitle = buildString {
        append(account?.bank ?: "Unknown account")
        if (txn.isTransfer) {
            counterpart?.let {
                append(if (isIncoming) " ← " else " → ")
                append(it.bank)
            }
        } else {
            append(" · ")
            append(txn.category)
        }
    }

    ClariFiCard(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .background(accent.copy(alpha = 0.16f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (txn.isTransfer) {
                    Icon(
                        imageVector = ClariFiIcons.Transfer,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(18.dp),
                    )
                } else {
                    Text(
                        text = Categories.rowEmoji(txn.category),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp),
            ) {
                Text(
                    text = txn.description.ifBlank { if (isIncoming) "Income" else "Expense" },
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Text(
                text = currency?.let { Money.formatSigned(it, txn.amount, isIncoming) }
                    ?: txn.amount.toString(),
                style = MaterialTheme.typography.titleMedium,
                color = accent,
                maxLines = 1,
            )
        }
    }
}
