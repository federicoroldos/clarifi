package com.clarifi.ui.dashboard

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.clarifi.core.money.Currency
import com.clarifi.core.money.Money
import com.clarifi.data.db.Account
import com.clarifi.data.repo.AccountStats
import com.clarifi.ui.charts.CategoryDonutChart
import com.clarifi.ui.charts.MonthlyBarsChart
import com.clarifi.ui.components.AccountAvatar
import com.clarifi.ui.components.AnimatedAmount
import com.clarifi.ui.components.ClariFiCard
import com.clarifi.ui.components.EmptyState
import com.clarifi.ui.components.SectionHeader
import com.clarifi.ui.components.rememberBarAwareLazyListState
import com.clarifi.ui.containerViewModel
import com.clarifi.ui.icons.ClariFiIcons
import com.clarifi.ui.nav.Destination
import com.clarifi.ui.theme.PillShape
import com.clarifi.ui.theme.clarifiPalette
import com.clarifi.ui.theme.parseAccountColor
import com.clarifi.ui.transactions.TransactionRow

@Composable
fun DashboardScreen(
    contentPadding: PaddingValues,
    onNavigate: (Destination) -> Unit,
) {
    val viewModel: DashboardViewModel = containerViewModel { DashboardViewModel(it.summaries) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val summary = state.summary

    LazyColumn(
        state = rememberBarAwareLazyListState(),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            top = contentPadding.calculateTopPadding(),
            bottom = contentPadding.calculateBottomPadding() + 96.dp,
            start = 16.dp,
            end = 16.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (!state.hasAccounts) {
            item {
                EmptyState(
                    icon = ClariFiIcons.Wallet,
                    title = "Nothing to show yet",
                    message = "Add an account to start tracking where your money goes.",
                    actionLabel = "Go to Accounts",
                    onAction = { onNavigate(Destination.Accounts) },
                )
            }
            return@LazyColumn
        }

        item(key = "chips") {
            AccountFilterRow(
                accounts = summary.accounts,
                view = state.view,
                onSelect = viewModel::show,
            )
        }

        val account = state.selectedAccount

        // The landing view is deliberately just balances and what happened lately.
        // Charts for every account at once made it a wall; each account's are one
        // tap away, on the account itself.
        if (account == null) {
            items(summary.accounts, key = { it.id }) { row ->
                AccountBalanceCard(
                    account = row,
                    stats = summary.statsFor(row.id),
                    modifier = Modifier.animateItem(),
                    onClick = { viewModel.show(DashboardView.Account(row.id)) },
                )
            }
        } else {
            // Same key as in the list above, so the card you tapped stays put and
            // the others fade around it instead of everything being rebuilt.
            item(key = account.id) {
                AccountBalanceCard(
                    account = account,
                    stats = state.stats ?: AccountStats(),
                    modifier = Modifier.animateItem(),
                    showTxnCount = true,
                )
            }
        }

        if (summary.dueCount > 0) {
            item(key = "due") {
                DueBanner(
                    count = summary.dueCount,
                    onOpen = { onNavigate(Destination.Fixed) },
                    modifier = Modifier.animateItem(),
                )
            }
        }

        // Charts belong to one account, so they are always in that account's own
        // currency; nothing here ever adds dollars to won.
        if (account != null) {
            val chartCurrency = account.currencyMeta
            val stats = state.stats ?: AccountStats()
            val monthly = stats.monthly

            if (monthly.isNotEmpty()) {
                item(key = "flow-title") {
                    SectionHeader(
                        title = "Money flow · ${chartCurrency.code}",
                        modifier = Modifier
                            .animateItem()
                            .padding(top = 8.dp),
                    )
                }
                item(key = "flow") {
                    ClariFiCard(modifier = Modifier.animateItem()) {
                        MonthlyBarsChart(monthly = monthly, currency = chartCurrency)
                    }
                }
            }

            val categories = stats.expenseByCategory

            if (categories.isNotEmpty()) {
                item(key = "categories-title") {
                    SectionHeader(
                        title = "Spending by category",
                        modifier = Modifier
                            .animateItem()
                            .padding(top = 8.dp),
                    )
                }
                item(key = "categories") {
                    ClariFiCard(modifier = Modifier.animateItem()) {
                        CategoryDonutChart(spendByCategory = categories, currency = chartCurrency)
                    }
                }
            }
        }

        if (summary.recent.isNotEmpty()) {
            item(key = "recent-title") {
                SectionHeader(
                    title = "Recent",
                    modifier = Modifier
                        .animateItem()
                        .padding(top = 8.dp),
                    trailing = {
                        TextButton(onClick = { onNavigate(Destination.Transactions) }) {
                            Text("View all", style = MaterialTheme.typography.labelMedium)
                        }
                    },
                )
            }

            val recent = summary.recent
                .filter { account == null || it.account == account.id }
                .take(5)

            items(recent, key = { it.id }) { txn ->
                TransactionRow(
                    txn = txn,
                    account = summary.accountsById[txn.account],
                    counterpart = txn.counterpart?.let { summary.accountsById[it] },
                    modifier = Modifier.animateItem(),
                )
            }
        }
    }
}

@Composable
private fun AccountFilterRow(
    accounts: List<Account>,
    view: DashboardView,
    onSelect: (DashboardView) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        DashboardChip(
            label = "All accounts",
            selected = view is DashboardView.Accounts,
            onClick = { onSelect(DashboardView.Accounts) },
        )
        accounts.forEach { account ->
            DashboardChip(
                label = account.bank,
                selected = (view as? DashboardView.Account)?.id == account.id,
                onClick = { onSelect(DashboardView.Account(account.id)) },
                leadingIcon = { AccountAvatar(account = account, size = 20.dp) },
            )
        }
    }
}

@Composable
private fun DashboardChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    leadingIcon: @Composable (() -> Unit)? = null,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = leadingIcon,
        shape = PillShape,
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            selectedLabelColor = MaterialTheme.colorScheme.primary,
        ),
    )
}

/** The desktop's `.bal-card`: colour stripe, the account's name in that colour, balance. */
@Composable
private fun AccountBalanceCard(
    account: Account,
    stats: AccountStats,
    modifier: Modifier = Modifier,
    showTxnCount: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val currency = account.currencyMeta
    val color = parseAccountColor(account.color, MaterialTheme.colorScheme.primary)

    ClariFiCard(
        stripe = listOf(color),
        // Tapping the card is the same as tapping its chip: it opens the account.
        modifier = if (onClick != null) modifier.clickable(onClick = onClick) else modifier,
    ) {
        Text(
            text = account.bank,
            style = MaterialTheme.typography.bodySmall,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        AnimatedAmount(
            currency = currency,
            amount = account.balance,
            style = MaterialTheme.typography.displaySmall,
            color = if (account.balance < 0) clarifiPalette.red else MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(14.dp))
        ThirtyDayRow(currency = currency, received = stats.last30Income, spent = stats.last30Spend)
        if (showTxnCount) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = if (stats.txnCount == 1) "1 transaction" else "${stats.txnCount} transactions",
                style = MaterialTheme.typography.bodySmall,
                color = clarifiPalette.textMuted,
            )
        }
    }
}

@Composable
private fun ThirtyDayRow(currency: Currency, received: Double, spent: Double) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "In · 30 days",
                style = MaterialTheme.typography.bodySmall,
                color = clarifiPalette.textMuted,
            )
            AnimatedAmount(
                currency = currency,
                amount = received,
                style = MaterialTheme.typography.titleMedium,
                color = clarifiPalette.green,
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Out · 30 days",
                style = MaterialTheme.typography.bodySmall,
                color = clarifiPalette.textMuted,
            )
            AnimatedAmount(
                currency = currency,
                amount = spent,
                style = MaterialTheme.typography.titleMedium,
                color = clarifiPalette.red,
            )
        }
    }
}

@Composable
private fun DueBanner(count: Int, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    ClariFiCard(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = ClariFiIcons.Fixed,
                contentDescription = null,
                tint = clarifiPalette.orange,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = if (count == 1) {
                    "1 fixed transaction is due"
                } else {
                    "$count fixed transactions are due"
                },
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp),
            )
            TextButton(onClick = onOpen) {
                Text("Review", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}
