package com.clarifi.data.repo

import com.clarifi.core.model.Categories
import com.clarifi.core.model.TransferDirection
import com.clarifi.core.model.TxnType
import com.clarifi.core.time.Dates
import com.clarifi.data.db.Account
import com.clarifi.data.db.Txn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `SummaryRepository.build` is the dashboard. It is a pure function on purpose,
 * so all of `build_summary`'s subtler rules can be pinned down here without a
 * database or an emulator.
 */
class SummaryBuilderTest {

    private val usd = account("a_usd", "usd")
    private val eur = account("a_eur", "eur")

    @Test
    fun `transfers are excluded from spending, income and categories`() {
        val summary = SummaryRepository.build(
            active = listOf(usd, eur),
            all = listOf(usd, eur),
            allTxns = listOf(
                expense(1, usd.id, 40.0, "Food"),
                transfer(2, usd.id, 100.0, TransferDirection.OUT, eur.id),
                transfer(3, eur.id, 92.0, TransferDirection.IN, usd.id),
            ),
            fixedViews = emptyList(),
        )

        val stats = summary.statsFor(usd.id)
        assertEquals(40.0, stats.last30Spend, 0.001)
        assertEquals(mapOf("Food" to 40.0), stats.expenseByCategory)
        assertEquals(0.0, summary.statsFor(eur.id).last30Income, 0.001)
        assertFalse(summary.overview.expenseByCategory("usd").containsKey(Categories.TRANSFER))
    }

    @Test
    fun `category spend is kept apart per currency`() {
        val summary = SummaryRepository.build(
            active = listOf(usd, eur),
            all = listOf(usd, eur),
            allTxns = listOf(
                expense(1, usd.id, 200.0, "Food"),
                expense(2, eur.id, 45.0, "Food"),
            ),
            fixedViews = emptyList(),
        )

        // The donut used to add these into one 245 figure and label it with whichever
        // currency happened to be showing.
        assertEquals(200.0, summary.overview.expenseByCategory("usd").getValue("Food"), 0.001)
        assertEquals(45.0, summary.overview.expenseByCategory("eur").getValue("Food"), 0.001)
    }

    @Test
    fun `transfers still count towards an account's transaction total`() {
        val summary = SummaryRepository.build(
            active = listOf(usd, eur),
            all = listOf(usd, eur),
            allTxns = listOf(
                expense(1, usd.id, 40.0, "Food"),
                transfer(2, usd.id, 100.0, TransferDirection.OUT, eur.id),
            ),
            fixedViews = emptyList(),
        )

        assertEquals(2, summary.statsFor(usd.id).txnCount)
        assertEquals(2, summary.overview.totalTxns)
    }

    @Test
    fun `transactions of a deleted account are skipped entirely`() {
        val summary = SummaryRepository.build(
            active = listOf(usd),
            all = listOf(usd),
            allTxns = listOf(
                expense(1, usd.id, 40.0, "Food"),
                expense(2, "gone", 999.0, "Food"),
            ),
            fixedViews = emptyList(),
        )

        assertEquals(1, summary.recent.size)
        assertEquals(40.0, summary.overview.expenseByCategory("usd").getValue("Food"), 0.001)
        // total_txns counts every stored row, orphans included, as the desktop does.
        assertEquals(2, summary.overview.totalTxns)
    }

    @Test
    fun `only the last six months are kept`() {
        val txns = (1..9).map { monthsAgo ->
            expense(monthsAgo, usd.id, 10.0, "Food", date = monthStart(monthsAgo.toLong()))
        }

        val summary = SummaryRepository.build(listOf(usd), listOf(usd), txns, emptyList())

        assertEquals(6, summary.statsFor(usd.id).monthly.size)
        assertTrue(summary.overview.monthly.size <= 6)
    }

    @Test
    fun `spending older than thirty days is out of the last-30 figure but still in the category totals`() {
        val summary = SummaryRepository.build(
            active = listOf(usd),
            all = listOf(usd),
            allTxns = listOf(
                expense(1, usd.id, 25.0, "Food", date = Dates.today()),
                expense(2, usd.id, 75.0, "Food", date = Dates.daysAgo(45)),
            ),
            fixedViews = emptyList(),
        )

        assertEquals(25.0, summary.statsFor(usd.id).last30Spend, 0.001)
        assertEquals(100.0, summary.overview.expenseByCategory("usd").getValue("Food"), 0.001)
    }

    @Test
    fun `currency totals group every account sharing a currency`() {
        val secondUsd = account("a_usd2", "usd", balance = 60.0)

        val summary = SummaryRepository.build(
            active = listOf(usd.copy(balance = 40.0), secondUsd, eur.copy(balance = 10.0)),
            all = emptyList(),
            allTxns = emptyList(),
            fixedViews = emptyList(),
        )

        val usdTotals = summary.overview.byCurrency.first { it.currency.id == "usd" }
        assertEquals(100.0, usdTotals.balance, 0.001)
        assertEquals(2, usdTotals.accountCount)
        assertEquals(1, summary.overview.byCurrency.first { it.currency.id == "eur" }.accountCount)
    }

    @Test
    fun `income and expense land in the right side of the monthly flow`() {
        val month = Dates.today().take(7)

        val summary = SummaryRepository.build(
            active = listOf(usd),
            all = listOf(usd),
            allTxns = listOf(
                expense(1, usd.id, 30.0, "Food"),
                Txn(2, Dates.today(), "Salary", 500.0, Categories.OTHERS, TxnType.FUND.wire, usd.id),
            ),
            fixedViews = emptyList(),
        )

        val flow = summary.statsFor(usd.id).monthly.getValue(month)
        assertEquals(500.0, flow.income, 0.001)
        assertEquals(30.0, flow.expense, 0.001)
        assertEquals(500.0, summary.statsFor(usd.id).last30Income, 0.001)
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private fun account(id: String, currency: String, balance: Double = 0.0) = Account(
        id = id,
        bank = "Bank $id",
        currency = currency,
        balance = balance,
        createdAt = "2026-01-01T00:00:00",
        archived = false,
        color = "#10b981",
    )

    private fun expense(
        id: Int,
        accountId: String,
        amount: Double,
        category: String,
        date: String = Dates.today(),
    ) = Txn(id, date, "Expense $id", amount, category, TxnType.EXPENSE.wire, accountId)

    private fun transfer(
        id: Int,
        accountId: String,
        amount: Double,
        direction: TransferDirection,
        counterpart: String,
    ) = Txn(
        id = id,
        date = Dates.today(),
        description = "Transfer",
        amount = amount,
        category = Categories.TRANSFER,
        type = TxnType.TRANSFER.wire,
        account = accountId,
        transferId = "tx_test",
        counterpart = counterpart,
        transferDir = direction.wire,
    )

    private fun monthStart(monthsAgo: Long): String =
        java.time.LocalDate.now().minusMonths(monthsAgo).withDayOfMonth(1).format(Dates.ISO)
}
