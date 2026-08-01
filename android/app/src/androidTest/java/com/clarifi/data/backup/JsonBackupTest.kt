package com.clarifi.data.backup

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.clarifi.core.model.ClariFiException
import com.clarifi.core.model.TxnType
import com.clarifi.data.db.ClariFiDatabase
import com.clarifi.data.repo.AccountRepository
import com.clarifi.data.repo.FixedRepository
import com.clarifi.data.repo.TxnRepository
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * With cloud sync on hold, this file *is* the bridge between the phone and the
 * desktop - so the format has to stay exactly what `modern_export` writes and
 * `modern_import` reads.
 */
@RunWith(AndroidJUnit4::class)
class JsonBackupTest {

    private lateinit var db: ClariFiDatabase
    private lateinit var accounts: AccountRepository
    private lateinit var txns: TxnRepository
    private lateinit var fixed: FixedRepository
    private lateinit var backup: JsonBackup

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, ClariFiDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        accounts = AccountRepository(db)
        txns = TxnRepository(db, accounts)
        fixed = FixedRepository(db, accounts, txns)
        backup = JsonBackup(db)
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun exportUsesTheDesktopsVersionTwoShape() = runBlocking {
        accounts.create("Santander", "usd", 100.0)

        val root = JSONObject(backup.export())

        assertEquals(2, root.getInt("version"))
        listOf("exported_at", "accounts", "config", "txns", "fixed", "applied").forEach { key ->
            assertTrue("missing key: $key", root.has(key))
        }
        val account = root.getJSONArray("accounts").getJSONObject(0)
        listOf("id", "bank", "currency", "balance", "created_at", "archived", "color").forEach { key ->
            assertTrue("account missing key: $key", account.has(key))
        }
    }

    @Test
    fun aRoundTripPreservesBalancesAndHistory() = runBlocking {
        val checking = accounts.create("Santander", "usd", 500.0)
        val euros = accounts.create("Deutsche", "eur", 200.0)
        txns.add(TxnType.EXPENSE, checking.id, 40.0, description = "Groceries", category = "Supermarket")
        txns.add(TxnType.FUND, checking.id, 100.0, description = "Refund")
        txns.transfer(checking.id, euros.id, amountSent = 50.0, amountReceived = 46.0)
        val rent = fixed.create("Rent", 800.0, checking.id, "Services", 1, TxnType.EXPENSE)
        fixed.apply(rent.id)

        val exported = backup.export()
        val balancesBefore = db.accountDao().allAccounts().associate { it.id to it.balance }
        val txnCountBefore = db.txnDao().allTxns().size

        // Wipe, then restore from the file.
        db.txnDao().clear()
        db.fixedDao().clearAppliedTable()
        db.fixedDao().clearPayments()
        db.accountDao().clear()

        val summary = backup.import(exported)

        assertEquals(2, summary.accounts)
        assertEquals(txnCountBefore, summary.transactions)
        assertEquals(1, summary.fixedPayments)
        assertEquals(balancesBefore, db.accountDao().allAccounts().associate { it.id to it.balance })
        assertEquals(1, db.fixedDao().allApplied().size)
    }

    @Test
    fun bothTransferLegsSurviveARoundTrip() = runBlocking {
        val source = accounts.create("Santander", "usd", 500.0)
        val destination = accounts.create("Deutsche", "eur", 0.0)
        txns.transfer(source.id, destination.id, 100.0, 92.0)

        val exported = backup.export()
        db.txnDao().clear()
        backup.import(exported)

        val legs = db.txnDao().allTxns().filter { it.isTransfer }
        assertEquals(2, legs.size)
        assertEquals(1, legs.mapNotNull { it.transferId }.toSet().size)
        assertEquals(setOf("out", "in"), legs.mapNotNull { it.transferDir }.toSet())
    }

    @Test
    fun anArchivedAccountStaysArchived() = runBlocking {
        val account = accounts.create("Old card", "usd", 0.0)
        accounts.archive(account.id)

        val exported = backup.export()
        db.accountDao().clear()
        backup.import(exported)

        assertTrue(db.accountDao().byId(account.id)!!.archived)
    }

    @Test
    fun theAiKeyIsNeverRestoredFromABackup() = runBlocking {
        accounts.create("Santander", "usd", 0.0)
        db.configDao().put(com.clarifi.data.db.ConfigEntry("ai_api_key", "gsk_secret"))

        val exported = backup.export()
        backup.import(exported)

        assertTrue(db.configDao().all().none { it.key == "ai_api_key" })
    }

    @Test
    fun aTransactionPointingAtAMissingAccountIsRefiledRatherThanDropped() = runBlocking {
        val json = """
            {"version": 2, "accounts": [
               {"id": "acct_1", "bank": "Santander", "currency": "usd", "balance": 0, "archived": false}
             ],
             "txns": [
               {"id": 1, "date": "2026-01-01", "description": "Orphan", "amount": 10,
                "category": "Food", "type": "expense", "account": "acct_gone"}
             ],
             "fixed": [], "applied": {}, "config": {}}
        """.trimIndent()

        backup.import(json)

        assertEquals("acct_1", db.txnDao().allTxns().single().account)
    }

    @Test
    fun aFileThatIsNotABackupIsRejectedWithoutTouchingAnything() = runBlocking {
        accounts.create("Santander", "usd", 250.0)

        assertThrows(ClariFiException::class.java) {
            runBlocking { backup.import("this is not json") }
        }
        assertThrows(ClariFiException::class.java) {
            runBlocking { backup.import("""{"version": 2, "accounts": []}""") }
        }

        // The existing data is still there.
        assertEquals(250.0, db.accountDao().allAccounts().single().balance, 0.001)
    }

    @Test
    fun duplicateTransactionIdsAreRenumberedInsteadOfCollapsing() = runBlocking {
        val json = """
            {"version": 2, "accounts": [
               {"id": "acct_1", "bank": "Santander", "currency": "usd", "balance": 0, "archived": false}
             ],
             "txns": [
               {"id": 1, "date": "2026-01-01", "description": "A", "amount": 10, "category": "Food",
                "type": "expense", "account": "acct_1"},
               {"id": 1, "date": "2026-01-02", "description": "B", "amount": 20, "category": "Food",
                "type": "expense", "account": "acct_1"}
             ],
             "fixed": [], "applied": {}, "config": {}}
        """.trimIndent()

        backup.import(json)

        val stored = db.txnDao().allTxns()
        assertEquals(2, stored.size)
        assertEquals(2, stored.map { it.id }.toSet().size)
    }
}
