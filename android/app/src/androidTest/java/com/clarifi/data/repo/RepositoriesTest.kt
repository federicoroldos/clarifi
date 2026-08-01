package com.clarifi.data.repo

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.clarifi.core.model.ClariFiException
import com.clarifi.core.model.TxnType
import com.clarifi.core.time.Dates
import com.clarifi.data.db.ClariFiDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The business rules against a real database.
 *
 * These are the invariants that keep the ledger honest - balances always
 * reflecting the rows that produced them - so each test states a rule the
 * desktop also enforces, and checks it end to end.
 */
@RunWith(AndroidJUnit4::class)
class RepositoriesTest {

    private lateinit var db: ClariFiDatabase
    private lateinit var accounts: AccountRepository
    private lateinit var txns: TxnRepository
    private lateinit var fixed: FixedRepository

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, ClariFiDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        accounts = AccountRepository(db)
        txns = TxnRepository(db, accounts)
        fixed = FixedRepository(db, accounts, txns)
    }

    @After
    fun tearDown() = db.close()

    // ── accounts ───────────────────────────────────────────────────────────────

    @Test
    fun newAccountsGetRandomPrefixedIds() = runBlocking {
        val account = accounts.create("Santander", "usd", 100.0)

        assertTrue(account.id.startsWith("acct_"))
        assertEquals(100.0, account.balance, 0.001)
        assertEquals("#32d74b", account.color) // the USD default swatch
    }

    @Test
    fun balancesAreRoundedToTheCurrencyOnCreate() = runBlocking {
        val won = accounts.create("Woori", "krw", 1234.6)
        val dollars = accounts.create("Chase", "usd", 1234.567)

        assertEquals(1235.0, won.balance, 0.0)
        assertEquals(1234.57, dollars.balance, 0.0)
    }

    @Test
    fun archivingHidesTheAccountButKeepsIt() = runBlocking {
        val account = accounts.create("Santander", "usd", 0.0)

        accounts.archive(account.id)

        assertTrue(accounts.activeAccounts.first().none { it.id == account.id })
        assertNotNull(accounts.byId(account.id))
        assertTrue(accounts.byId(account.id)!!.archived)
    }

    @Test
    fun anArchivedAccountCanBeRestored() = runBlocking {
        val account = accounts.create("Santander", "usd", 0.0)
        accounts.archive(account.id)

        accounts.restore(account.id)

        assertTrue(accounts.activeAccounts.first().any { it.id == account.id })
    }

    @Test
    fun anActiveAccountCannotBePermanentlyDeleted() = runBlocking {
        val account = accounts.create("Santander", "usd", 0.0)

        val error = assertThrows(ClariFiException::class.java) {
            runBlocking { accounts.permanentDelete(account.id) }
        }

        assertEquals("deactivate account before deleting it", error.message)
    }

    @Test
    fun permanentDeleteCascadesTransactionsAndFixedPayments() = runBlocking {
        val account = accounts.create("Santander", "usd", 500.0)
        txns.add(TxnType.EXPENSE, account.id, 40.0, description = "Groceries")
        val payment = fixed.create("Rent", 800.0, account.id, "Services", 1, TxnType.EXPENSE)
        fixed.apply(payment.id)

        accounts.archive(account.id)
        accounts.permanentDelete(account.id)

        assertNull(accounts.byId(account.id))
        assertTrue(db.txnDao().allTxns().isEmpty())
        assertTrue(db.fixedDao().allPayments().isEmpty())
        assertTrue(db.fixedDao().allApplied().isEmpty())
    }

    // ── transactions ───────────────────────────────────────────────────────────

    @Test
    fun addingIncomeAndExpensesMovesTheBalance() = runBlocking {
        val account = accounts.create("Santander", "usd", 100.0)

        txns.add(TxnType.FUND, account.id, 50.0)
        txns.add(TxnType.EXPENSE, account.id, 30.0)

        assertEquals(120.0, accounts.byId(account.id)!!.balance, 0.001)
    }

    @Test
    fun amountsAreStoredPositiveWhateverSignIsPassed() = runBlocking {
        val account = accounts.create("Santander", "usd", 100.0)

        txns.add(TxnType.EXPENSE, account.id, -30.0)

        val stored = db.txnDao().allTxns().single()
        assertEquals(30.0, stored.amount, 0.001)
        assertEquals(70.0, accounts.byId(account.id)!!.balance, 0.001)
    }

    @Test
    fun zeroAmountsAreRejected() = runBlocking {
        val account = accounts.create("Santander", "usd", 100.0)

        val error = assertThrows(ClariFiException::class.java) {
            runBlocking { txns.add(TxnType.EXPENSE, account.id, 0.0) }
        }

        assertEquals("amount must be greater than zero", error.message)
        assertEquals(100.0, accounts.byId(account.id)!!.balance, 0.001)
    }

    @Test
    fun goingNegativeIsAllowed() = runBlocking {
        // The desktop deliberately does not validate against the current balance.
        val account = accounts.create("Santander", "usd", 10.0)

        txns.add(TxnType.EXPENSE, account.id, 90.0)

        assertEquals(-80.0, accounts.byId(account.id)!!.balance, 0.001)
    }

    @Test
    fun deletingATransactionReversesItsBalanceChange() = runBlocking {
        val account = accounts.create("Santander", "usd", 100.0)
        txns.add(TxnType.EXPENSE, account.id, 30.0)
        val stored = db.txnDao().allTxns().single()

        txns.delete(stored.id)

        assertEquals(100.0, accounts.byId(account.id)!!.balance, 0.001)
        assertTrue(db.txnDao().allTxns().isEmpty())
    }

    @Test
    fun editingAnAmountAdjustsTheBalanceByTheDifference() = runBlocking {
        val account = accounts.create("Santander", "usd", 100.0)
        txns.add(TxnType.EXPENSE, account.id, 30.0)
        val stored = db.txnDao().allTxns().single()

        txns.edit(stored.id, account.id, 50.0, stored.date, "Groceries", "Food")

        assertEquals(50.0, accounts.byId(account.id)!!.balance, 0.001)
    }

    @Test
    fun movingATransactionToAnotherAccountMovesTheMoneyWithIt() = runBlocking {
        val from = accounts.create("Santander", "usd", 100.0)
        val to = accounts.create("Chase", "usd", 100.0)
        txns.add(TxnType.EXPENSE, from.id, 30.0)
        val stored = db.txnDao().allTxns().single()

        txns.edit(stored.id, to.id, 30.0, stored.date, "Groceries", "Food")

        assertEquals(100.0, accounts.byId(from.id)!!.balance, 0.001)
        assertEquals(70.0, accounts.byId(to.id)!!.balance, 0.001)
    }

    // ── transfers ──────────────────────────────────────────────────────────────

    @Test
    fun aTransferWritesTwoLegsAndMovesBothBalances() = runBlocking {
        val source = accounts.create("Santander", "usd", 500.0)
        val destination = accounts.create("Deutsche", "eur", 100.0)

        txns.transfer(source.id, destination.id, amountSent = 100.0, amountReceived = 92.0)

        val legs = db.txnDao().allTxns()
        assertEquals(2, legs.size)
        assertEquals(1, legs.map { it.transferId }.toSet().size)
        assertEquals(400.0, accounts.byId(source.id)!!.balance, 0.001)
        assertEquals(192.0, accounts.byId(destination.id)!!.balance, 0.001)
    }

    @Test
    fun deletingEitherLegRemovesBothAndReversesBothBalances() = runBlocking {
        val source = accounts.create("Santander", "usd", 500.0)
        val destination = accounts.create("Deutsche", "eur", 100.0)
        txns.transfer(source.id, destination.id, 100.0, 92.0)
        val incomingLeg = db.txnDao().allTxns().first { it.transferDir == "in" }

        txns.delete(incomingLeg.id)

        assertTrue(db.txnDao().allTxns().isEmpty())
        assertEquals(500.0, accounts.byId(source.id)!!.balance, 0.001)
        assertEquals(100.0, accounts.byId(destination.id)!!.balance, 0.001)
    }

    @Test
    fun transfersCannotBeEdited() = runBlocking {
        val source = accounts.create("Santander", "usd", 500.0)
        val destination = accounts.create("Deutsche", "eur", 100.0)
        txns.transfer(source.id, destination.id, 100.0, 92.0)
        val leg = db.txnDao().allTxns().first()

        val error = assertThrows(ClariFiException::class.java) {
            runBlocking { txns.edit(leg.id, source.id, 10.0, leg.date, "x", "Food") }
        }

        assertEquals("transfers cannot be edited; delete and recreate", error.message)
    }

    @Test
    fun aCrossCurrencyTransferRemembersTheRateBothWays() = runBlocking {
        val source = accounts.create("Santander", "usd", 500.0)
        val destination = accounts.create("Deutsche", "eur", 0.0)

        txns.transfer(source.id, destination.id, 100.0, 92.0)

        val rates = txns.exchangeRates()
        assertEquals(0.92, rates.getValue("usd_eur"), 0.0001)
        assertEquals(1.0 / 0.92, rates.getValue("eur_usd"), 0.0001)
    }

    @Test
    fun transferringToTheSameAccountIsRejected() = runBlocking {
        val account = accounts.create("Santander", "usd", 500.0)

        val error = assertThrows(ClariFiException::class.java) {
            runBlocking { txns.transfer(account.id, account.id, 10.0, 10.0) }
        }

        assertEquals("source and destination must differ", error.message)
    }

    // ── fixed payments ─────────────────────────────────────────────────────────

    @Test
    fun applyingAFixedPaymentCreatesTheTransactionAndMarksTheMonth() = runBlocking {
        val account = accounts.create("Santander", "usd", 1000.0)
        val payment = fixed.create("Rent", 800.0, account.id, "Services", 1, TxnType.EXPENSE)

        fixed.apply(payment.id)

        assertEquals(200.0, accounts.byId(account.id)!!.balance, 0.001)
        assertEquals("Rent", db.txnDao().allTxns().single().description)
        assertEquals(1, db.fixedDao().appliedCount(payment.id, Dates.currentMonth()))
    }

    @Test
    fun aFixedPaymentCannotBeAppliedTwiceInAMonth() = runBlocking {
        val account = accounts.create("Santander", "usd", 1000.0)
        val payment = fixed.create("Rent", 800.0, account.id, "Services", 1, TxnType.EXPENSE)
        fixed.apply(payment.id)

        val error = assertThrows(ClariFiException::class.java) {
            runBlocking { fixed.apply(payment.id) }
        }

        assertEquals("already applied this month", error.message)
        assertEquals(200.0, accounts.byId(account.id)!!.balance, 0.001)
    }

    @Test
    fun undoingAFixedPaymentRemovesTheTransactionAndRestoresTheBalance() = runBlocking {
        val account = accounts.create("Santander", "usd", 1000.0)
        val payment = fixed.create("Rent", 800.0, account.id, "Services", 1, TxnType.EXPENSE)
        fixed.apply(payment.id)

        fixed.undo(payment.id)

        assertEquals(1000.0, accounts.byId(account.id)!!.balance, 0.001)
        assertTrue(db.txnDao().allTxns().isEmpty())
        assertEquals(0, db.fixedDao().appliedCount(payment.id, Dates.currentMonth()))
    }

    @Test
    fun recurringIncomeAddsInsteadOfSubtracting() = runBlocking {
        val account = accounts.create("Santander", "usd", 100.0)
        val salary = fixed.create("Salary", 2000.0, account.id, "Others", 1, TxnType.FUND)

        fixed.apply(salary.id)

        assertEquals(2100.0, accounts.byId(account.id)!!.balance, 0.001)
    }

    @Test
    fun aPaymentIsDueOnceItsDayHasArrivedAndItHasNotBeenApplied(): Unit = runBlocking {
        val account = accounts.create("Santander", "usd", 1000.0)
        val today = Dates.todayDayOfMonth()
        val due = fixed.create("Rent", 800.0, account.id, "Services", 1, TxnType.EXPENSE)
        // Only assert the "not due yet" half when today actually precedes the 28th.
        val notYet = if (today < 28) {
            fixed.create("Gym", 30.0, account.id, "Health", 28, TxnType.EXPENSE)
        } else {
            null
        }

        val views = fixed.payments.first()

        assertTrue(views.first { it.id == due.id }.dueThisMonth)
        if (notYet != null) {
            assertTrue(!views.first { view -> view.id == notYet.id }.dueThisMonth)
        }
    }

    @Test
    fun deletingAPaymentForgetsThatItWasApplied() = runBlocking {
        val account = accounts.create("Santander", "usd", 1000.0)
        val payment = fixed.create("Rent", 800.0, account.id, "Services", 1, TxnType.EXPENSE)
        fixed.apply(payment.id)

        fixed.delete(payment.id)

        assertTrue(db.fixedDao().allApplied().isEmpty())
        // The transaction it already created is intentionally kept: the money did move.
        assertEquals(1, db.txnDao().allTxns().size)
    }
}
