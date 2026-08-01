package com.clarifi.ui.scan

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.clarifi.core.model.TxnType
import com.clarifi.data.ai.ReceiptFields
import com.clarifi.data.db.Account
import com.clarifi.ui.components.AccountPicker
import com.clarifi.ui.components.AmountField
import com.clarifi.ui.components.CategoryPicker
import com.clarifi.ui.components.ClariFiCard
import com.clarifi.ui.components.DateField
import com.clarifi.ui.components.parseAmount
import com.clarifi.ui.icons.ClariFiIcons
import com.clarifi.ui.theme.PillShape
import com.clarifi.ui.theme.clarifiPalette

/**
 * The editable review of what the model read.
 *
 * Nothing is written until this form is confirmed - same rule as the desktop.
 * The model is a good reader, not an authority, and a wrong total silently
 * entered into a ledger is worse than no scanning at all.
 */
@Composable
fun ReceiptReview(
    fields: ReceiptFields,
    accounts: List<Account>,
    suggestedAccountId: String?,
    onCancel: () -> Unit,
    onSave: (
        type: TxnType,
        accountId: String,
        amount: Double,
        date: String,
        description: String,
        category: String,
    ) -> Unit,
) {
    var type by remember(fields) { mutableStateOf(fields.type) }
    var amount by remember(fields) {
        mutableStateOf(fields.amount?.let { plain(it) }.orEmpty())
    }
    var accountId by remember(fields) {
        mutableStateOf(suggestedAccountId ?: accounts.firstOrNull()?.id.orEmpty())
    }
    var date by remember(fields) { mutableStateOf(fields.date) }
    var description by remember(fields) { mutableStateOf(fields.merchant) }
    var category by remember(fields) { mutableStateOf(fields.category) }

    val account = accounts.firstOrNull { it.id == accountId }
    val parsed = parseAmount(amount)
    val canSave = account != null && parsed != null && parsed > 0

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .imePadding(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ClariFiCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = ClariFiIcons.Check,
                    contentDescription = null,
                    tint = clarifiPalette.green,
                    modifier = Modifier.size(20.dp),
                )
                Column(modifier = Modifier.padding(start = 12.dp)) {
                    Text("Receipt read", style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = "Check the details below, then save.",
                        style = MaterialTheme.typography.bodySmall,
                        color = clarifiPalette.textMuted,
                    )
                }
            }
        }

        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            listOf(TxnType.EXPENSE to "Expense", TxnType.FUND to "Refund")
                .forEachIndexed { index, (option, label) ->
                    SegmentedButton(
                        selected = type == option,
                        onClick = { type = option },
                        shape = SegmentedButtonDefaults.itemShape(index, 2),
                        colors = SegmentedButtonDefaults.colors(
                            activeContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            activeContentColor = MaterialTheme.colorScheme.primary,
                        ),
                        label = { Text(label) },
                    )
                }
        }

        AmountField(value = amount, onValueChange = { amount = it }, currency = account?.currencyMeta)

        OutlinedTextField(
            value = description,
            onValueChange = { description = it },
            label = { Text("Merchant") },
            singleLine = true,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(),
        )

        DateField(value = date, onValueChange = { date = it })

        AccountPicker(accounts = accounts, selectedId = accountId, onSelect = { accountId = it })

        if (type == TxnType.EXPENSE) {
            CategoryPicker(selected = category, onSelect = { category = it })
        }

        Button(
            onClick = {
                onSave(type, accountId, parsed ?: 0.0, date, description.trim(), category)
            },
            enabled = canSave,
            shape = PillShape,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
        ) {
            Text(
                text = "Save transaction",
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
            )
        }

        OutlinedButton(
            onClick = onCancel,
            shape = PillShape,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
        ) {
            Text("Discard and scan another", style = MaterialTheme.typography.labelLarge)
        }

        Spacer(Modifier.height(20.dp))
    }
}

private fun plain(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
