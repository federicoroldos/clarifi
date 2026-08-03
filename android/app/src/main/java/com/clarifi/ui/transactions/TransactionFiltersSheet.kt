package com.clarifi.ui.transactions

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.clarifi.core.model.Categories
import com.clarifi.data.db.Account
import com.clarifi.ui.components.AmountField
import com.clarifi.ui.components.DateField
import com.clarifi.ui.components.SectionHeader
import com.clarifi.ui.components.parseAmount
import com.clarifi.ui.theme.PillShape

/**
 * The desktop's advanced filters: account, category, date range and amount range.
 *
 * Edits are held locally and only committed on Apply, so half-typed values never
 * make the list flicker underneath the sheet.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TransactionFiltersSheet(
    filters: TxnFilters,
    accounts: List<Account>,
    onApply: (TxnFilters) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var accountId by remember { mutableStateOf(filters.accountId) }
    var category by remember { mutableStateOf(filters.category) }
    var from by remember { mutableStateOf(filters.from.orEmpty()) }
    var to by remember { mutableStateOf(filters.to.orEmpty()) }
    var min by remember { mutableStateOf(filters.minAmount?.toString().orEmpty()) }
    var max by remember { mutableStateOf(filters.maxAmount?.toString().orEmpty()) }

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
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Filters", style = MaterialTheme.typography.headlineSmall)

            Column {
                SectionHeader("Account")
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ChoiceChip("Any", accountId == null) { accountId = null }
                    accounts.forEach { account ->
                        ChoiceChip(account.bank, accountId == account.id) { accountId = account.id }
                    }
                }
            }

            Column {
                SectionHeader("Category")
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    ChoiceChip("Any", category == null) { category = null }
                    Categories.ALL.forEach { option ->
                        ChoiceChip("${Categories.emoji(option)}  $option", category == option) {
                            category = option
                        }
                    }
                }
            }

            SectionHeader("Date range")
            DateField(value = from, onValueChange = { from = it }, warnOnFuture = false)
            DateField(value = to, onValueChange = { to = it }, warnOnFuture = false)

            SectionHeader("Amount range")
            AmountField(value = min, onValueChange = { min = it }, currency = null, label = "Minimum")
            AmountField(value = max, onValueChange = { max = it }, currency = null, label = "Maximum")

            Button(
                onClick = {
                    onApply(
                        filters.copy(
                            accountId = accountId,
                            category = category,
                            from = from.ifBlank { null },
                            to = to.ifBlank { null },
                            minAmount = parseAmount(min),
                            maxAmount = parseAmount(max),
                        )
                    )
                },
                shape = PillShape,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
            ) {
                Text(
                    "Apply filters",
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                )
            }

            OutlinedButton(
                onClick = onClear,
                shape = PillShape,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
            ) {
                Text("Clear all", style = MaterialTheme.typography.labelLarge)
            }

            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun ChoiceChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        shape = PillShape,
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            selectedLabelColor = MaterialTheme.colorScheme.primary,
        ),
        modifier = Modifier.padding(vertical = 2.dp),
    )
}
