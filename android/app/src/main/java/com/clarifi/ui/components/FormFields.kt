package com.clarifi.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.clarifi.core.model.Categories
import com.clarifi.core.money.Currency
import com.clarifi.core.time.Dates
import com.clarifi.data.db.Account
import com.clarifi.ui.icons.ClariFiIcons
import com.clarifi.ui.theme.PillShape
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Shared inputs for the add/edit sheets.
 *
 * Everything here is stateless: the caller owns the values, which keeps each
 * sheet's validation in one readable place instead of scattered across fields.
 */

/** Only accepts characters that can form a number; the comma is allowed for `1,50`. */
fun sanitizeAmountInput(raw: String): String =
    raw.filter { it.isDigit() || it == '.' || it == ',' }

fun parseAmount(raw: String): Double? = raw.replace(',', '.').toDoubleOrNull()

@Composable
fun AmountField(
    value: String,
    onValueChange: (String) -> Unit,
    currency: Currency?,
    modifier: Modifier = Modifier,
    label: String = "Amount",
) {
    OutlinedTextField(
        value = value,
        onValueChange = { onValueChange(sanitizeAmountInput(it)) },
        label = { Text(label) },
        // The symbol goes in the leading slot, not `prefix`: Material only reveals a
        // prefix once the field is focused, and which currency you are typing in is
        // exactly what you want to know before you start typing.
        leadingIcon = currency?.let {
            {
                Text(
                    text = it.symbol,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        placeholder = { Text("0") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        shape = MaterialTheme.shapes.medium,
        modifier = modifier.fillMaxWidth(),
    )
}

/**
 * A read-only field that opens the Material date picker.
 *
 * Typing a date on a phone is slower and more error-prone than picking one, and
 * the stored format (`YYYY-MM-DD`) is never shown raw to the user.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showPicker by remember { mutableStateOf(false) }

    // A read-only field still consumes taps, so the press is observed through its
    // interaction source rather than by stacking a clickable on top of it.
    val interactionSource = remember { MutableInteractionSource() }
    LaunchedEffect(interactionSource) {
        interactionSource.interactions.collect { interaction ->
            if (interaction is PressInteraction.Release) showPicker = true
        }
    }

    OutlinedTextField(
        value = Dates.display(value),
        onValueChange = {},
        readOnly = true,
        label = { Text("Date") },
        trailingIcon = {
            Icon(ClariFiIcons.Calendar, contentDescription = null, modifier = Modifier.size(18.dp))
        },
        shape = MaterialTheme.shapes.medium,
        interactionSource = interactionSource,
        modifier = modifier.fillMaxWidth(),
    )

    if (showPicker) {
        val initial = Dates.parseOrNull(value) ?: LocalDate.now()
        val state = rememberDatePickerState(
            initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { millis ->
                        // The picker works in UTC, so read it back in UTC too or a
                        // date picked in the evening can come back a day early.
                        onValueChange(
                            Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate().format(Dates.ISO)
                        )
                    }
                    showPicker = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showPicker = false }) { Text("Cancel") }
            },
            colors = androidx.compose.material3.DatePickerDefaults.colors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
        ) {
            DatePicker(state = state, showModeToggle = false)
        }
    }
}

/** A horizontal strip of accounts; the selected one shows in the accent container. */
@Composable
fun AccountPicker(
    accounts: List<Account>,
    selectedId: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "Account",
) {
    Column(modifier = modifier) {
        SectionHeader(label)
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            accounts.forEach { account ->
                FilterChip(
                    selected = account.id == selectedId,
                    onClick = { onSelect(account.id) },
                    label = { Text(account.bank) },
                    leadingIcon = {
                        AccountAvatar(account = account, size = 20.dp)
                    },
                    shape = PillShape,
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.primary,
                    ),
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CategoryPicker(
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        SectionHeader("Category")
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Categories.ALL.forEach { category ->
                FilterChip(
                    selected = category == selected,
                    onClick = { onSelect(category) },
                    label = { Text("${Categories.emoji(category)}  $category") },
                    shape = PillShape,
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.primary,
                    ),
                    modifier = Modifier.padding(vertical = 2.dp),
                )
            }
        }
    }
}

