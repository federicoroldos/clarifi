package com.clarifi.ui.accounts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.clarifi.core.model.AccountColors
import com.clarifi.core.money.Currencies
import com.clarifi.data.db.Account
import com.clarifi.ui.components.AccountColorPicker
import com.clarifi.ui.components.SectionHeader
import com.clarifi.ui.theme.PillShape

/**
 * Create/edit form for an account.
 *
 * A bottom sheet rather than a full screen: the form is four fields, and keeping
 * the list visible behind it makes it obvious what is being changed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountEditorSheet(
    editing: Account?,
    onDismiss: () -> Unit,
    onSave: (bank: String, currency: String, balance: Double, color: String) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Keyed on the account so reopening the sheet for a different row starts fresh.
    var bank by rememberSaveable(editing?.id) { mutableStateOf(editing?.bank.orEmpty()) }
    var currency by rememberSaveable(editing?.id) { mutableStateOf(editing?.currency ?: Currencies.USD.id) }
    var balance by rememberSaveable(editing?.id) {
        mutableStateOf(editing?.balance?.let { formatForInput(it) } ?: "")
    }
    var color by rememberSaveable(editing?.id) {
        mutableStateOf(editing?.displayColor ?: AccountColors.defaultFor(Currencies.USD.id))
    }
    // Only follow the currency's default swatch until the user picks one deliberately.
    var colorTouched by rememberSaveable(editing?.id) { mutableStateOf(editing != null) }

    val parsedBalance = balance.replace(',', '.').toDoubleOrNull()
    val canSave = bank.isNotBlank() && (balance.isBlank() || parsedBalance != null)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 22.dp)
                .navigationBarsPadding()
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text(
                text = if (editing == null) "New account" else "Edit account",
                style = MaterialTheme.typography.headlineSmall,
            )

            OutlinedTextField(
                value = bank,
                onValueChange = { bank = it },
                label = { Text("Bank or account name") },
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            )

            Column {
                SectionHeader("Currency")
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Currencies.ALL.forEach { option ->
                        FilterChip(
                            selected = option.id == currency,
                            onClick = {
                                currency = option.id
                                if (!colorTouched) color = AccountColors.defaultFor(option.id)
                            },
                            label = { Text(option.code) },
                            shape = PillShape,
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                selectedLabelColor = MaterialTheme.colorScheme.primary,
                            ),
                        )
                    }
                }
            }

            OutlinedTextField(
                value = balance,
                onValueChange = { balance = it.filter { ch -> ch.isDigit() || ch == '.' || ch == ',' || ch == '-' } },
                label = { Text(if (editing == null) "Starting balance" else "Balance") },
                placeholder = { Text("0") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                shape = MaterialTheme.shapes.medium,
                supportingText = {
                    Text(
                        text = "Editing the balance here does not create a transaction.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            )

            Column {
                SectionHeader("Colour")
                AccountColorPicker(
                    selected = color,
                    onSelect = {
                        color = it
                        colorTouched = true
                    },
                )
            }

            Button(
                onClick = { onSave(bank.trim(), currency, parsedBalance ?: 0.0, color) },
                enabled = canSave,
                shape = PillShape,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
            ) {
                Text(
                    text = if (editing == null) "Create account" else "Save changes",
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                )
            }

            Spacer(Modifier.height(12.dp))
        }
    }
}

/** Drops the trailing `.0` so a whole balance does not come back as `1200.0`. */
private fun formatForInput(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
