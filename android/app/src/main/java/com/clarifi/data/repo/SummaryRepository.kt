package com.clarifi.data.repo

import com.clarifi.core.model.Categories
import com.clarifi.core.model.TransferDirection
import com.clarifi.core.model.TxnType
import com.clarifi.core.money.Currencies
import com.clarifi.core.money.Currency
import com.clarifi.core.time.Dates
import com.clarifi.data.db.Account
import com.clarifi.data.db.Txn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/** Money in and out for one month. */
data class MonthFlow(val income: Double = 0.0, val expense: Double = 0.0)

/** Per-account figures behind the dashboard's account drilldown. */
data class AccountStats(
    val expenseByCategory: Map<String, Double> = emptyMap(),
    /** At most the last six months, oldest first. */
    val monthly: Map<String, MonthFlow> = emptyMap(),
    val last30Spend: Double = 0.0,
    val last30Income: Double = 0.0,
    val txnCount: Int = 0,
)

/** One row of the "all accounts" header: every account sharing a currency. */
data class CurrencyTotals(
    val currency: Currency,
    val balance: Double,
    val last30Spend: Double,
    val last30Income: Double,
    val accountCount: Int,
)

data class Overview(
    val byCurrency: List<CurrencyTotals> = emptyList(),
    /** month → currency id → flow, for the stacked all-accounts chart. */
    val monthly: Map<String, Map<String, MonthFlow>> = emptyMap(),
    /** currency id → category → spend. Kept split so no chart adds dollars to won. */
    val expenseByCategory: Map<String, Map<String, Double>> = emptyMap(),
    val totalTxns: Int = 0,
) {
    fun expenseByCategory(currencyId: String): Map<String, Double> =
        expenseByCategory[currencyId].orEmpty()
}

/** Everything the dashboard renders, computed in one pass. */
data class Summary(
    val accounts: List<Account> = emptyList(),
    val allAccounts: List<Account> = emptyList(),
    val stats: Map<String, AccountStats> = emptyMap(),
    val overview: Overview = Overview(),
    val recent: List<Txn> = emptyList(),
    val fixed: List<FixedPaymentView> = emptyList(),
) {
    val accountsById: Map<String, Account> get() = accounts.associateBy { it.id }
    val dueCount: Int get() = fixed.count { it.dueThisMonth }

    fun statsFor(accountId: String): AccountStats = stats[accountId] ?: AccountStats()
}

/**
 * Reimplements `build_summary` (app.py) on top of Room.
 *
 * The rules that matter and are easy to get wrong:
 *  - a transfer counts as income or spending for the account it touched, because
 *    from that account money genuinely arrived or left, but never in the totals
 *    across accounts, where both legs are the same money and would inflate both
 *    sides at once. It is not a category either, so it stays out of the donut;
 *  - transactions whose account no longer exists are skipped entirely;
 *  - only the last six months are kept.
 */
class SummaryRepository(
    accounts: AccountRepository,
    txns: TxnRepository,
    fixed: FixedRepository,
) {

    val summary: Flow<Summary> = combine(
        accounts.activeAccounts,
        accounts.allAccounts,
        txns.allTxns,
        fixed.payments,
    ) { active, all, allTxns, fixedViews ->
        build(active, all, allTxns, fixedViews)
    }

    companion object {
        private const val MONTHS_KEPT = 6
        private const val RECENT_LIMIT = 15
        private const val LAST_30_DAYS = 30L

        fun build(
            active: List<Account>,
            all: List<Account>,
            allTxns: List<Txn>,
            fixedViews: List<FixedPaymentView>,
        ): Summary {
            val accountsById = active.associateBy { it.id }
            val cutoff = Dates.daysAgo(LAST_30_DAYS)

            val stats = active.associate { it.id to MutableStats() }
            val currencyTotals = linkedMapOf<String, MutableCurrencyTotals>()
            val overviewCategories = mutableMapOf<String, MutableMap<String, Double>>()
            val overviewMonthly = mutableMapOf<String, MutableMap<String, MonthFlow>>()

            active.forEach { account ->
                val bucket = currencyTotals.getOrPut(account.currency) { MutableCurrencyTotals() }
                bucket.balance += account.balance
                bucket.accountCount += 1
            }

            val recent = mutableListOf<Txn>()

            for (txn in allTxns) {
                val account = accountsById[txn.account] ?: continue
                recent += txn

                val bucket = stats[account.id] ?: continue
                bucket.txnCount += 1

                val month = txn.monthKey
                val within30 = txn.date >= cutoff
                val isIncome = if (txn.isTransfer) {
                    txn.direction == TransferDirection.IN
                } else {
                    txn.txnType == TxnType.FUND
                }

                // Seen from one account, a transfer is money that really did arrive or
                // leave, so the account's own figures count it.
                if (within30) {
                    if (isIncome) bucket.last30Income += txn.amount else bucket.last30Spend += txn.amount
                }
                if (month.isNotEmpty()) {
                    bucket.monthly[month] = bucket.monthly.getOrElse(month) { MonthFlow() }.add(isIncome, txn.amount)
                }

                // Across accounts the two legs are the same money twice, so everything
                // below stays clear of transfers: the currency totals would count a
                // transfer as both income and spending, and `Transfer` is a placeholder
                // rather than a category the donut should show.
                if (txn.isTransfer) continue

                val currencyBucket = currencyTotals.getOrPut(account.currency) { MutableCurrencyTotals() }

                if (txn.txnType == TxnType.EXPENSE) {
                    val category = Categories.normalize(txn.category)
                    bucket.categories.merge(category, txn.amount, Double::plus)
                    overviewCategories.getOrPut(account.currency) { mutableMapOf() }
                        .merge(category, txn.amount, Double::plus)
                    if (within30) currencyBucket.last30Spend += txn.amount
                } else if (within30) {
                    currencyBucket.last30Income += txn.amount
                }

                if (month.isNotEmpty()) {
                    val perCurrency = overviewMonthly.getOrPut(month) { mutableMapOf() }
                    perCurrency[account.currency] =
                        perCurrency.getOrElse(account.currency) { MonthFlow() }.add(isIncome, txn.amount)
                }
            }

            return Summary(
                accounts = active,
                allAccounts = all,
                stats = stats.mapValues { (_, value) -> value.toStats() },
                overview = Overview(
                    byCurrency = currencyTotals
                        .filterValues { it.accountCount > 0 }
                        .mapNotNull { (currencyId, totals) ->
                            Currencies.find(currencyId)?.let { totals.toTotals(it) }
                        },
                    monthly = overviewMonthly.lastMonths(),
                    expenseByCategory = overviewCategories
                        .mapValues { (_, categories) -> categories.sortedByValueDescending() },
                    totalTxns = allTxns.size,
                ),
                recent = recent.take(RECENT_LIMIT),
                fixed = fixedViews,
            )
        }

        private fun MonthFlow.add(isIncome: Boolean, amount: Double): MonthFlow =
            if (isIncome) copy(income = income + amount) else copy(expense = expense + amount)

        /** Keeps only the most recent months, oldest first - the chart's x axis. */
        private fun <T> Map<String, T>.lastMonths(): Map<String, T> =
            toSortedMap().entries.toList().takeLast(MONTHS_KEPT).associate { it.key to it.value }

        private fun Map<String, Double>.sortedByValueDescending(): Map<String, Double> =
            entries.sortedByDescending { it.value }.associate { it.key to it.value }

        private class MutableStats {
            val categories = mutableMapOf<String, Double>()
            val monthly = mutableMapOf<String, MonthFlow>()
            var last30Spend = 0.0
            var last30Income = 0.0
            var txnCount = 0

            fun toStats() = AccountStats(
                expenseByCategory = categories.sortedByValueDescending(),
                monthly = monthly.lastMonths(),
                last30Spend = last30Spend,
                last30Income = last30Income,
                txnCount = txnCount,
            )
        }

        private class MutableCurrencyTotals {
            var balance = 0.0
            var last30Spend = 0.0
            var last30Income = 0.0
            var accountCount = 0

            fun toTotals(currency: Currency) = CurrencyTotals(
                currency = currency,
                balance = balance,
                last30Spend = last30Spend,
                last30Income = last30Income,
                accountCount = accountCount,
            )
        }
    }
}
