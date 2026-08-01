package com.clarifi.ui.transactions

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.clarifi.core.model.TxnType
import com.clarifi.core.time.Dates
import com.clarifi.data.db.Txn
import com.clarifi.ui.components.EmptyState
import com.clarifi.ui.components.SectionHeader
import com.clarifi.ui.components.rememberBarAwareLazyListState
import com.clarifi.ui.containerViewModel
import com.clarifi.ui.icons.ClariFiIcons
import com.clarifi.ui.theme.PillShape
import com.clarifi.ui.theme.clarifiPalette

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionsScreen(
    contentPadding: PaddingValues,
    snackbarHostState: SnackbarHostState,
) {
    val viewModel: TransactionsViewModel = containerViewModel {
        TransactionsViewModel(it.accounts, it.txns)
    }
    val state by viewModel.state.collectAsStateWithLifecycle()

    var editing by remember { mutableStateOf<Txn?>(null) }
    var filtersOpen by remember { mutableStateOf(false) }

    LaunchedEffect(viewModel) {
        viewModel.messages.collect { message ->
            // Only a delete can be undone, and its message is the one that says so.
            val undoable = message.endsWith("deleted") || message.contains("reversed")
            val result = snackbarHostState.showSnackbar(
                message = message,
                actionLabel = if (undoable) "Undo" else null,
                duration = if (undoable) SnackbarDuration.Long else SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) viewModel.undoDelete()
        }
    }

    LazyColumn(
        state = rememberBarAwareLazyListState(),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            top = contentPadding.calculateTopPadding(),
            bottom = contentPadding.calculateBottomPadding() + 88.dp,
            start = 16.dp,
            end = 16.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            QuickFilters(
                filters = state.filters,
                onChange = viewModel::setFilters,
                onOpenAdvanced = { filtersOpen = true },
            )
        }

        if (state.loaded && state.isEmpty) {
            item {
                if (state.isFiltered) {
                    EmptyState(
                        icon = ClariFiIcons.Filter,
                        title = "Nothing matches",
                        message = "No transaction fits the filters you have set.",
                        actionLabel = "Clear filters",
                        onAction = viewModel::clearFilters,
                    )
                } else {
                    EmptyState(
                        icon = ClariFiIcons.Transactions,
                        title = "No transactions yet",
                        message = "Tap the + button to log your first expense or income.",
                    )
                }
            }
        }

        state.days.forEach { day ->
            item(key = "header-${day.date}") {
                SectionHeader(
                    title = Dates.display(day.date),
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            items(day.items, key = { it.id }) { txn ->
                SwipeToDeleteRow(
                    onDelete = { viewModel.delete(txn) },
                ) {
                    TransactionRow(
                        txn = txn,
                        account = state.accountsById[txn.account],
                        counterpart = txn.counterpart?.let { state.accountsById[it] },
                        modifier = Modifier.clickable(
                            // Transfers cannot be edited in place, on either platform.
                            enabled = !txn.isTransfer,
                            onClick = { editing = txn },
                        ),
                    )
                }
            }
        }
    }

    editing?.let { txn ->
        MovementSheet(
            accounts = state.activeAccounts,
            editing = txn,
            onDismiss = { editing = null },
            onSave = { result ->
                if (result is MovementResult.Entry && result.editingId != null) {
                    viewModel.edit(
                        id = result.editingId,
                        accountId = result.accountId,
                        amount = result.amount,
                        date = result.date,
                        description = result.description,
                        category = result.category,
                    )
                }
                editing = null
            },
        )
    }

    if (filtersOpen) {
        TransactionFiltersSheet(
            filters = state.filters,
            accounts = state.activeAccounts,
            onApply = {
                viewModel.setFilters(it)
                filtersOpen = false
            },
            onClear = {
                viewModel.clearFilters()
                filtersOpen = false
            },
            onDismiss = { filtersOpen = false },
        )
    }
}

/** The three taps that cover most filtering, plus a door to the rest. */
@Composable
private fun QuickFilters(
    filters: TxnFilters,
    onChange: (TxnFilters) -> Unit,
    onOpenAdvanced: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TypeChip("All", filters.type == null) { onChange(filters.copy(type = null)) }
        TypeChip("Income", filters.type == TxnType.FUND) { onChange(filters.copy(type = TxnType.FUND)) }
        TypeChip("Expenses", filters.type == TxnType.EXPENSE) { onChange(filters.copy(type = TxnType.EXPENSE)) }
        TypeChip("Transfers", filters.type == TxnType.TRANSFER) { onChange(filters.copy(type = TxnType.TRANSFER)) }

        val extras = filters.activeCount - (if (filters.type != null) 1 else 0)
        FilterChip(
            selected = extras > 0,
            onClick = onOpenAdvanced,
            label = { Text(if (extras > 0) "Filters ($extras)" else "Filters") },
            leadingIcon = {
                Icon(ClariFiIcons.Filter, contentDescription = null, modifier = Modifier.size(16.dp))
            },
            shape = PillShape,
            colors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                selectedLabelColor = MaterialTheme.colorScheme.primary,
            ),
        )
    }
}

@Composable
private fun TypeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        shape = PillShape,
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            selectedLabelColor = MaterialTheme.colorScheme.primary,
        ),
    )
}

/**
 * Swipe right-to-left to delete.
 *
 * One direction only: a two-way swipe invites accidents, and the deletion is
 * undoable from the snackbar rather than guarded by a dialog.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeToDeleteRow(
    onDelete: () -> Unit,
    content: @Composable () -> Unit,
) {
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) {
                onDelete()
                true
            } else {
                false
            }
        },
    )

    SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(clarifiPalette.redDim, MaterialTheme.shapes.extraLarge)
                    .padding(end = 24.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(
                    imageVector = ClariFiIcons.Delete,
                    contentDescription = null,
                    tint = clarifiPalette.red,
                    modifier = Modifier.size(22.dp),
                )
            }
        },
        content = { content() },
    )
}
