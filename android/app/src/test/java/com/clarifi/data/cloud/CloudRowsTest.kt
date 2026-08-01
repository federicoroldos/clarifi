package com.clarifi.data.cloud

import com.clarifi.data.db.Account
import com.clarifi.data.db.Txn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The phone and the desktop write the same rows into the same tables. If this
 * mapping drifts, one of them starts reading the other's data wrong, which is far
 * worse than a sync that plainly fails.
 */
class CloudRowsTest {

    @Test
    fun `an account survives the round trip through the cloud's column names`() {
        val account = Account(
            id = "acct_3f4a8b2c",
            bank = "Itau Pesos",
            currency = "uyu",
            balance = 17172.92,
            createdAt = "2026-01-04",
            archived = true,
            color = "#4a90f8",
        )

        val row = CloudRows.accountRow(account)

        // The column names are the desktop's, not Kotlin's property names.
        assertEquals("acct_3f4a8b2c", row["id"])
        assertEquals("2026-01-04", row["created_at"])
        assertEquals(true, row["archived"])
        assertEquals(account, CloudRows.account(row))
    }

    @Test
    fun `a transfer leg keeps its pairing columns and a plain expense leaves them null`() {
        val leg = Txn(
            id = 12,
            date = "2026-07-31",
            description = "To savings",
            amount = 100.0,
            category = "Transfer",
            type = "transfer",
            account = "acct_a",
            transferId = "tr_9f",
            counterpart = "acct_b",
            transferDir = "out",
        )

        assertEquals(leg, CloudRows.txn(CloudRows.txnRow(leg)))

        val expense = leg.copy(
            id = 13,
            type = "expense",
            category = "Food",
            transferId = null,
            counterpart = null,
            transferDir = null,
        )
        val row = CloudRows.txnRow(expense)

        // Written as SQL NULL, not the string "null": the desktop reads these columns
        // as empty and would otherwise treat the expense as half of a transfer.
        assertNull(row["transfer_id"])
        assertEquals(expense, CloudRows.txn(row))
    }

    @Test
    fun `columns stored as text on an older database still read correctly`() {
        val row = mapOf(
            "id" to "usd",
            "bank" to "Prex",
            "currency" to "USD",
            "balance" to "1.5",
            "created_at" to "2025-02-01",
            "archived" to "True",
            "color" to "#fff",
        )

        val account = CloudRows.account(row)

        assertTrue(account.archived)
        assertEquals(1.5, account.balance, 0.0001)
        // Currencies are lowercase everywhere inside the app.
        assertEquals("usd", account.currency)
    }

    @Test
    fun `column types match the desktop's, including the ids that differ per table`() {
        // accounts.id is TEXT while transactions.id is INTEGER: the same name, two
        // types, which is why app.py keys them by (sheet, column).
        assertEquals("TEXT", CloudSchema.ACCOUNTS.type("id"))
        assertEquals("INTEGER", CloudSchema.TRANSACTIONS.type("id"))
        assertEquals("INTEGER", CloudSchema.FIXED_PAYMENTS.type("id"))
        assertEquals("INTEGER", CloudSchema.FIXED_APPLIED.type("payment_id"))
        assertEquals("INTEGER", CloudSchema.FIXED_PAYMENTS.type("day"))
        assertEquals("DOUBLE PRECISION", CloudSchema.ACCOUNTS.type("balance"))
        assertEquals("DOUBLE PRECISION", CloudSchema.TRANSACTIONS.type("amount"))
        assertEquals("BOOLEAN", CloudSchema.ACCOUNTS.type("archived"))
        assertEquals("TEXT", CloudSchema.CONFIG.type("value"))
    }

    @Test
    fun `TLS is required even when the string does not ask for it`() {
        val plain = "postgresql://postgres.ref:pw@aws-1-sa-east-1.pooler.supabase.com:5432/postgres"

        assertTrue(PostgresCloud.connectionUrl(plain).endsWith("?sslmode=require"))
        assertTrue(PostgresCloud.connectionUrl("$plain?application_name=clarifi").contains("&sslmode=require"))
        // An explicit choice is left alone.
        assertEquals("$plain?sslmode=verify-full", PostgresCloud.connectionUrl("$plain?sslmode=verify-full"))
    }

    @Test
    fun `the status line shows the host without the password`() {
        val described = PostgresCloud.describe(
            "postgresql://postgres.abcdefghijklmnopqrst:hunter2@aws-1-sa-east-1.pooler.supabase.com:5432/postgres"
        )

        assertTrue(described.contains("pooler.supabase.com"))
        assertTrue("the password leaked into the status line", !described.contains("hunter2"))
    }
}
