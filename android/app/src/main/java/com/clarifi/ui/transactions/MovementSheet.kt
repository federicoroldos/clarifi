package com.clarifi.ui.transactions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.clarifi.core.model.Categories
import com.clarifi.core.model.TxnType
import com.clarifi.core.money.Money
import com.clarifi.core.time.Dates
import com.clarifi.data.db.Account
import com.clarifi.data.db.Txn
import com.clarifi.ui.components.AccountPicker
import com.clarifi.ui.components.AmountField
import com.clarifi.ui.components.CategoryPicker
import com.clarifi.ui.components.DateField
import com.clarifi.ui.components.parseAmount
import com.clarifi.ui.theme.PillShape
import com.clarifi.ui.theme.clarifiPalette

/** Which form the sheet is showing. */
enum class MovementKind(val label: String) {
    Expense("Expense"),
    Income("Income"),
    Transfer("Transfer"),
}

/**
 * The one place money is entered.
 *
 * New entries start on Expense - by far the most frequent thing to log on a phone
 * - and the type is chosen with a segmented control rather than three separate
 * screens. Editing an existing row hides the control entirely, because the
 * desktop does not allow changing a transaction's type either.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MovementSheet(
    accounts: List<Account>,
    editing: Txn? = null,
    rates: Map<String, Double> = emptyMap(),
    onDismiss: () -> Unit,
    onSave: (MovementResult) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var kind by remember(editing?.id) {
        mutableStateOf(
            when {
                editing == null -> MovementKind.Expense
                editing.txnType == TxnType.FUND -> MovementKind.Income
                else -> MovementKind.Expense
            }
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Column(
            modifier = Modifier
                // The height is fixed on purpose. A ModalBottomSheet re-anchors when
                // its content resizes, and switching type resizes it a lot (Income
                // drops the category picker, Transfer swaps the whole form): the
                // sheet then settled on Hidden and closed itself, which left Income
                // and Transfer unreachable. A constant height never re-anchors.
                .fillMaxHeight(0.88f)
                .padding(horizontal = 22.dp)
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = if (editing == null) "New movement" else "Edit transaction",
                style = MaterialTheme.typography.headlineSmall,
            )

            if (editing == null) {
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    MovementKind.entries.forEachIndexed { index, option ->
                        // Each type wears the colour the web gives it in the header:
                        // Expense is btn-red, Fund is btn-pri, Transfer is btn-glass.
                        val container = when (option) {
                            MovementKind.Expense -> clarifiPalette.redDim
                            MovementKind.Income -> MaterialTheme.colorScheme.primaryContainer
                            MovementKind.Transfer -> MaterialTheme.colorScheme.surfaceContainerHigh
                        }
                        val content = when (option) {
                            MovementKind.Expense -> clarifiPalette.red
                            MovementKind.Income -> MaterialTheme.colorScheme.primary
                            MovementKind.Transfer -> MaterialTheme.colorScheme.onSurface
                        }
                        SegmentedButton(
                            selected = kind == option,
                            onClick = { kind = option },
                            shape = SegmentedButtonDefaults.itemShape(index, MovementKind.entries.size),
                            colors = SegmentedButtonDefaults.colors(
                                activeContainerColor = container,
                                activeContentColor = content,
                                activeBorderColor = content.copy(alpha = 0.45f),
                            ),
                            label = { Text(option.label) },
                        )
                    }
                }
            }

            if (kind == MovementKind.Transfer) {
                TransferForm(accounts = accounts, rates = rates, onSave = onSave)
            } else {
                EntryForm(
                    accounts = accounts,
                    editing = editing,
                    isIncome = kind == MovementKind.Income,
                    onSave = onSave,
                )
            }

            Spacer(Modifier.height(12.dp))
        }
    }
}

/** What the sheet produces; the caller decides which repository call to make. */
sealed interface MovementResult {
    data class Entry(
        val editingId: Int?,
        val type: TxnType,
        val accountId: String,
        val amount: Double,
        val date: String,
        val description: String,
        val category: String,
    ) : MovementResult

    data class Transfer(
        val sourceId: String,
        val destinationId: String,
        val amountSent: Double,
        val amountReceived: Double,
        val date: String,
        val note: String,
    ) : MovementResult
}

@Composable
private fun EntryForm(
    accounts: List<Account>,
    editing: Txn?,
    isIncome: Boolean,
    onSave: (MovementResult) -> Unit,
) {
    var accountId by remember(editing?.id) {
        mutableStateOf(editing?.account ?: accounts.firstOrNull()?.id.orEmpty())
    }
    var amount by remember(editing?.id) { mutableStateOf(editing?.amount?.let(::plainAmount).orEmpty()) }
    var date by remember(editing?.id) { mutableStateOf(editing?.date ?: Dates.today()) }
    var description by remember(editing?.id) { mutableStateOf(editing?.description.orEmpty()) }
    var category by remember(editing?.id) { mutableStateOf(editing?.category ?: Categories.OTHERS) }

    val account = accounts.firstOrNull { it.id == accountId }
    val parsed = parseAmount(amount)
    val canSave = account != null && parsed != null && parsed > 0

    AmountField(value = amount, onValueChange = { amount = it }, currency = account?.currencyMeta)

    AccountPicker(accounts = accounts, selectedId = accountId, onSelect = { accountId = it })

    OutlinedTextField(
        value = description,
        onValueChange = { description = it },
        label = { Text("Description") },
        singleLine = true,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    )

    DateField(value = date, onValueChange = { date = it })

    // Income has no spending category on the desktop either; it always files as Others.
    if (!isIncome) {
        CategoryPicker(selected = category, onSelect = { category = it })
    }

    Button(
        onClick = {
            onSave(
                MovementResult.Entry(
                    editingId = editing?.id,
                    type = if (isIncome) TxnType.FUND else TxnType.EXPENSE,
                    accountId = accountId,
                    amount = parsed ?: 0.0,
                    date = date,
                    description = description.trim(),
                    category = if (isIncome) Categories.OTHERS else category,
                )
            )
        },
        enabled = canSave,
        shape = PillShape,
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp),
    ) {
        Text(
            text = when {
                editing != null -> "Save changes"
                isIncome -> "Add income"
                else -> "Add expense"
            },
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
        )
    }
}

@Composable
private fun TransferForm(
    accounts: List<Account>,
    rates: Map<String, Double>,
    onSave: (MovementResult) -> Unit,
) {
    var sourceId by remember { mutableStateOf(accounts.firstOrNull()?.id.orEmpty()) }
    var destinationId by remember { mutableStateOf(accounts.getOrNull(1)?.id.orEmpty()) }
    var sent by remember { mutableStateOf("") }
    var received by remember { mutableStateOf("") }
    var receivedTouched by remember { mutableStateOf(false) }
    var date by remember { mutableStateOf(Dates.today()) }
    var note by remember { mutableStateOf("") }

    val source = accounts.firstOrNull { it.id == sourceId }
    val destination = accounts.firstOrNull { it.id == destinationId }
    val sameCurrency = source != null && destination != null && source.currency == destination.currency

    // Same currency means the two amounts are always equal; different currencies get
    // a suggestion from the last rate seen for that pair, which the user can override.
    LaunchedEffect(sent, sourceId, destinationId) {
        if (receivedTouched) return@LaunchedEffect
        received = when {
            sameCurrency -> sent
            source != null && destination != null -> {
                val rate = rates["${source.currency}_${destination.currency}"]
                val value = parseAmount(sent)
                if (rate != null && value != null) plainAmount(value * rate) else received
            }
            else -> received
        }
    }

    val sentValue = parseAmount(sent)
    val receivedValue = parseAmount(received)
    val differentAccounts = sourceId.isNotEmpty() && destinationId.isNotEmpty() && sourceId != destinationId
    val canSave = differentAccounts &&
        sentValue != null && sentValue > 0 &&
        receivedValue != null && receivedValue > 0

    AccountPicker(
        accounts = accounts,
        selectedId = sourceId,
        onSelect = { sourceId = it },
        label = "From",
    )
    AccountPicker(
        accounts = accounts,
        selectedId = destinationId,
        onSelect = { destinationId = it },
        label = "To",
    )

    if (sourceId.isNotEmpty() && sourceId == destinationId) {
        Text(
            text = "Pick two different accounts.",
            style = MaterialTheme.typography.bodySmall,
            color = clarifiPalette.red,
        )
    }

    AmountField(
        value = sent,
        onValueChange = {
            sent = it
            if (sameCurrency) receivedTouched = false
        },
        currency = source?.currencyMeta,
        label = "Amount sent",
    )

    if (!sameCurrency) {
        AmountField(
            value = received,
            onValueChange = {
                received = it
                receivedTouched = true
            },
            currency = destination?.currencyMeta,
            label = "Amount received",
        )
        if (sentValue != null && receivedValue != null && sentValue > 0 && source != null && destination != null) {
            Text(
                text = "Rate: 1 ${source.currencyMeta.code} = " +
                    "${Money.format(destination.currencyMeta, receivedValue / sentValue)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    DateField(value = date, onValueChange = { date = it })

    OutlinedTextField(
        value = note,
        onValueChange = { note = it },
        label = { Text("Note (optional)") },
        singleLine = true,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    )

    Button(
        onClick = {
            onSave(
                MovementResult.Transfer(
                    sourceId = sourceId,
                    destinationId = destinationId,
                    amountSent = sentValue ?: 0.0,
                    amountReceived = receivedValue ?: 0.0,
                    date = date,
                    note = note.trim(),
                )
            )
        },
        enabled = canSave,
        shape = PillShape,
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp),
    ) {
        Text(
            text = "Transfer",
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
        )
    }
}

/** `1200.0` reads badly in an input; `1200` does. */
private fun plainAmount(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
