package com.clarifi.data.repo

import androidx.room.withTransaction
import com.clarifi.core.model.AccountColors
import com.clarifi.core.model.Categories
import com.clarifi.core.model.TxnType
import com.clarifi.core.time.Dates
import com.clarifi.data.db.Account
import com.clarifi.data.db.ClariFiDatabase
import com.clarifi.data.db.FixedPayment
import com.clarifi.data.db.Txn
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * First-run seeding, mirroring the fresh-install branch of `init_data`: a brand
 * new ClariFi starts with a US Dollar and a Euro account, both empty.
 *
 * These two use the currency as their id (`usd`, `eur`) rather than an `acct_*`
 * id - the same legacy-style ids the desktop seeds - so a device that later
 * pulls from a desktop's cloud backup lines up instead of ending with duplicates.
 */
suspend fun ClariFiDatabase.seedIfEmpty() {
    val dao = accountDao()
    if (dao.allIds().isNotEmpty()) return

    val createdAt = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"))
    dao.insertAll(
        listOf(
            defaultAccount("usd", "US Dollar Account", createdAt),
            defaultAccount("eur", "Euro Account", createdAt),
        )
    )
}

/**
 * A few example rows, written once on a brand new install.
 *
 * The tour points at real controls, and half of them are rows: a row to tap, a
 * row to swipe away, a fixed payment to mark paid. On an empty ledger those steps
 * have nothing to point at and fall back to a card, which is the dull version of
 * the tour and the one a first-time user would get. Three transactions and one
 * fixed payment are enough to make all of it work, and they are ordinary enough
 * to delete without a second thought.
 *
 * Only ever on the first run: [seedIfEmpty] is also what Clear All Data calls, and
 * that has to leave the ledger empty like it says on the button.
 */
suspend fun ClariFiDatabase.seedExamples() {
    val account = accountDao().byId("eur") ?: return
    if (txnDao().maxId() != null) return

    val today = LocalDate.now()
    val examples = listOf(
        Txn(
            id = 1,
            date = today.minusDays(4).format(Dates.ISO),
            description = "Salary",
            amount = 1_500.0,
            category = Categories.OTHERS,
            type = TxnType.FUND.wire,
            account = account.id,
        ),
        Txn(
            id = 2,
            date = today.minusDays(2).format(Dates.ISO),
            description = "Supermarket",
            amount = 48.30,
            category = "Supermarket",
            type = TxnType.EXPENSE.wire,
            account = account.id,
        ),
        Txn(
            id = 3,
            date = today.format(Dates.ISO),
            description = "Coffee",
            amount = 3.20,
            category = "Food",
            type = TxnType.EXPENSE.wire,
            account = account.id,
        ),
    )

    withTransaction {
        txnDao().insertAll(examples)
        fixedDao().insert(
            FixedPayment(
                id = 1,
                name = "Rent",
                amount = 700.0,
                account = account.id,
                category = "Services",
                day = 1,
                type = TxnType.EXPENSE.wire,
            )
        )
        // The balance is the sum of what the rows did, or the dashboard would open
        // on a number that does not match its own history.
        accountDao().update(account.copy(balance = examples.sumOf { it.balanceDelta }))
    }
}

private fun defaultAccount(currency: String, bank: String, createdAt: String) = Account(
    id = currency,
    bank = bank,
    currency = currency,
    balance = 0.0,
    createdAt = createdAt,
    archived = false,
    color = AccountColors.defaultFor(currency),
)
