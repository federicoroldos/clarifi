package com.clarifi.ui.statement

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.clarifi.core.model.TxnType
import com.clarifi.core.money.Money
import com.clarifi.core.time.Dates
import com.clarifi.data.ai.StatementItem
import com.clarifi.ui.components.AccountPicker
import com.clarifi.ui.components.ClariFiCard
import com.clarifi.ui.components.EmptyState
import com.clarifi.ui.components.SectionHeader
import com.clarifi.ui.components.rememberBarAwareLazyListState
import com.clarifi.ui.containerViewModel
import com.clarifi.ui.icons.ClariFiIcons
import com.clarifi.ui.nav.Destination
import com.clarifi.ui.theme.PillShape
import com.clarifi.ui.theme.clarifiPalette

@Composable
fun StatementScreen(
    contentPadding: PaddingValues,
    snackbarHostState: SnackbarHostState,
    onNavigate: (Destination) -> Unit,
) {
    val viewModel: StatementViewModel = containerViewModel {
        StatementViewModel(it.statementScanner, it.secrets, it.accounts, it.txns)
    }
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.refreshKey() }
    LaunchedEffect(viewModel) {
        viewModel.messages.collect { snackbarHostState.showSnackbar(it) }
    }

    val pdfLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let(viewModel::analyse) }

    when (val stage = state.stage) {
        is StatementStage.Idle -> Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .padding(horizontal = 16.dp),
        ) {
            when {
                !state.hasApiKey -> EmptyState(
                    icon = ClariFiIcons.Statement,
                    title = "Importing needs an AI key",
                    message = "ClariFi renders your statement's pages and has the model read every " +
                        "movement off them. Add a key to get started.",
                    actionLabel = "Open Settings",
                    onAction = { onNavigate(Destination.Settings) },
                )

                state.accounts.isEmpty() -> EmptyState(
                    icon = ClariFiIcons.Wallet,
                    title = "No accounts yet",
                    message = "Add the account this statement belongs to first.",
                    actionLabel = "Go to Accounts",
                    onAction = { onNavigate(Destination.Accounts) },
                )

                else -> {
                    Spacer(Modifier.height(16.dp))
                    Text("Import a statement", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        text = "Pick the account it belongs to, then choose the PDF. Nothing is " +
                            "saved until you review the list.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = clarifiPalette.textMuted,
                        modifier = Modifier.padding(top = 6.dp, bottom = 18.dp),
                    )

                    AccountPicker(
                        accounts = state.accounts,
                        selectedId = state.selectedAccountId,
                        onSelect = viewModel::selectAccount,
                    )

                    Spacer(Modifier.height(22.dp))
                    Button(
                        onClick = { pdfLauncher.launch(arrayOf("application/pdf")) },
                        shape = PillShape,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(54.dp),
                    ) {
                        Icon(ClariFiIcons.Statement, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(10.dp))
                        Text("Choose a PDF", style = MaterialTheme.typography.labelLarge)
                    }
                    Text(
                        text = "Up to 8 pages are read per statement.",
                        style = MaterialTheme.typography.bodySmall,
                        color = clarifiPalette.textMuted,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp),
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }

        is StatementStage.Analysing -> Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                Text("Reading the statement…", style = MaterialTheme.typography.titleSmall)
                Text(
                    text = "Multi-page statements can take up to a minute.",
                    style = MaterialTheme.typography.bodySmall,
                    color = clarifiPalette.textMuted,
                )
            }
        }

        is StatementStage.Review -> StatementReview(
            items = stage.items,
            truncated = stage.truncated,
            currencySymbol = state.account?.currencyMeta,
            selectedCount = state.selectedCount,
            contentPadding = contentPadding,
            onToggle = viewModel::toggle,
            onSelectAll = { viewModel.setAllIncluded(true) },
            onSelectNone = { viewModel.setAllIncluded(false) },
            onImport = viewModel::importSelected,
            onCancel = viewModel::reset,
        )

        is StatementStage.Failed -> Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding),
        ) {
            EmptyState(
                icon = ClariFiIcons.Statement,
                title = "That didn't work",
                message = stage.message,
                actionLabel = "Try another file",
                onAction = viewModel::reset,
            )
        }
    }
}

@Composable
private fun StatementReview(
    items: List<StatementItem>,
    truncated: Boolean,
    currencySymbol: com.clarifi.core.money.Currency?,
    selectedCount: Int,
    contentPadding: PaddingValues,
    onToggle: (Int) -> Unit,
    onSelectAll: () -> Unit,
    onSelectNone: () -> Unit,
    onImport: () -> Unit,
    onCancel: () -> Unit,
) {
    val duplicates = items.count { it.duplicate }

    LazyColumn(
        state = rememberBarAwareLazyListState(),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            top = contentPadding.calculateTopPadding(),
            bottom = contentPadding.calculateBottomPadding() + 28.dp,
            start = 16.dp,
            end = 16.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            ClariFiCard {
                Text(
                    text = "${items.size} movements found",
                    style = MaterialTheme.typography.titleSmall,
                )
                if (duplicates > 0) {
                    Text(
                        text = "$duplicates already exist in this account and are unticked. " +
                            "Matching is by date, type and amount, because the bank's wording " +
                            "rarely matches what you typed.",
                        style = MaterialTheme.typography.bodySmall,
                        color = clarifiPalette.textMuted,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                if (truncated) {
                    Text(
                        text = "Only the first 8 pages were read.",
                        style = MaterialTheme.typography.bodySmall,
                        color = clarifiPalette.orange,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                Row(modifier = Modifier.padding(top = 4.dp)) {
                    TextButton(onClick = onSelectAll) { Text("Select all") }
                    TextButton(onClick = onSelectNone) { Text("Select none") }
                }
            }
        }

        item { SectionHeader("Movements") }

        itemsIndexed(items) { index, item ->
            StatementRow(
                item = item,
                currency = currencySymbol,
                onToggle = { onToggle(index) },
            )
        }

        item {
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = onImport,
                enabled = selectedCount > 0,
                shape = PillShape,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
            ) {
                Text(
                    text = if (selectedCount == 1) "Import 1 transaction" else "Import $selectedCount transactions",
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            OutlinedButton(
                onClick = onCancel,
                shape = PillShape,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp)
                    .height(48.dp),
            ) {
                Text("Discard", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun StatementRow(
    item: StatementItem,
    currency: com.clarifi.core.money.Currency?,
    onToggle: () -> Unit,
) {
    val palette = clarifiPalette
    val isIncome = item.type == TxnType.FUND

    ClariFiCard(
        modifier = Modifier.clickable(onClick = onToggle),
        contentPadding = PaddingValues(start = 6.dp, end = 14.dp, top = 8.dp, bottom = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = item.include, onCheckedChange = { onToggle() })

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.description.ifBlank { if (isIncome) "Credit" else "Charge" },
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = buildString {
                        append(Dates.display(item.date))
                        append(" · ")
                        append(item.category)
                        if (item.duplicate) append(" · already imported")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (item.duplicate) palette.orange else palette.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Text(
                text = currency?.let { Money.formatSigned(it, item.amount, isIncome) }
                    ?: item.amount.toString(),
                style = MaterialTheme.typography.titleSmall,
                color = if (isIncome) palette.green else palette.red,
                maxLines = 1,
            )
        }
    }
}
