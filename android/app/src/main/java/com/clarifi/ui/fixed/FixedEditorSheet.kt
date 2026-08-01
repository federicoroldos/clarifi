package com.clarifi.ui.fixed

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.clarifi.core.model.Categories
import com.clarifi.core.model.TxnType
import com.clarifi.core.time.Dates
import com.clarifi.data.db.Account
import com.clarifi.data.repo.FixedPaymentView
import com.clarifi.ui.components.AccountPicker
import com.clarifi.ui.components.AmountField
import com.clarifi.ui.components.CategoryPicker
import com.clarifi.ui.components.parseAmount
import com.clarifi.ui.theme.PillShape

/**
 * Create/edit form for a recurring payment or income.
 *
 * The day of the month is a plain number field rather than a date picker: these
 * repeat every month, so a full calendar would suggest a specific date that does
 * not exist here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FixedEditorSheet(
    editing: FixedPaymentView?,
    accounts: List<Account>,
    onDismiss: () -> Unit,
    onSave: (name: String, amount: Double, accountId: String, category: String, day: Int, type: TxnType) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var name by remember(editing?.id) { mutableStateOf(editing?.name.orEmpty()) }
    var amount by remember(editing?.id) {
        mutableStateOf(editing?.amount?.let { plain(it) }.orEmpty())
    }
    var accountId by remember(editing?.id) {
        mutableStateOf(editing?.account?.id ?: accounts.firstOrNull()?.id.orEmpty())
    }
    var category by remember(editing?.id) {
        mutableStateOf(editing?.payment?.category ?: Categories.OTHERS)
    }
    var day by remember(editing?.id) { mutableStateOf(editing?.day?.toString() ?: "1") }
    var type by remember(editing?.id) { mutableStateOf(editing?.type ?: TxnType.EXPENSE) }

    val account = accounts.firstOrNull { it.id == accountId }
    val parsedAmount = parseAmount(amount)
    val parsedDay = day.toIntOrNull()
    val dayValid = parsedDay != null && parsedDay in 1..31
    val canSave = name.isNotBlank() && parsedAmount != null && parsedAmount > 0 && dayValid && account != null

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
                .imePadding()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = if (editing == null) "New fixed transaction" else "Edit fixed transaction",
                style = MaterialTheme.typography.headlineSmall,
            )

            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                listOf(TxnType.EXPENSE to "Payment", TxnType.FUND to "Income")
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

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                placeholder = { Text("Rent, Netflix, Salary…") },
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            )

            AmountField(value = amount, onValueChange = { amount = it }, currency = account?.currencyMeta)

            OutlinedTextField(
                value = day,
                onValueChange = { day = it.filter(Char::isDigit).take(2) },
                label = { Text("Day of the month") },
                singleLine = true,
                isError = day.isNotEmpty() && !dayValid,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                supportingText = {
                    Text(
                        text = if (dayValid) "Falls due on the ${Dates.ordinal(parsedDay!!)}" else "Enter a day between 1 and 31",
                        style = MaterialTheme.typography.bodySmall,
                    )
                },
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            )

            AccountPicker(accounts = accounts, selectedId = accountId, onSelect = { accountId = it })

            if (type == TxnType.EXPENSE) {
                CategoryPicker(selected = category, onSelect = { category = it })
            }

            Button(
                onClick = {
                    onSave(
                        name.trim(),
                        parsedAmount ?: 0.0,
                        accountId,
                        if (type == TxnType.FUND) Categories.OTHERS else category,
                        parsedDay ?: 1,
                        type,
                    )
                },
                enabled = canSave,
                shape = PillShape,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
            ) {
                Text(
                    text = if (editing == null) "Create" else "Save changes",
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                )
            }

            Spacer(Modifier.height(12.dp))
        }
    }
}

private fun plain(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
