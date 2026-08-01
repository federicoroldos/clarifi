package com.clarifi.data.ai

import com.clarifi.core.model.Categories
import com.clarifi.core.model.TxnType
import com.clarifi.core.money.Currencies
import com.clarifi.data.db.Txn
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The statement pipeline's judgement calls: what to drop, what to merge and what
 * to treat as already imported. All ported from app.py, all pure, all testable
 * without a PDF or a network call.
 */
class StatementScannerTest {

    private val uyu = Currencies.UYU

    // ── normalisation ─────────────────────────────────────────────────────────

    @Test
    fun `reads a well-formed row`() {
        val item = normalizeItem(
            JSONObject(
                """{"date":"2026-02-11","description":"Tienda Inglesa","amount":1234.5,
                    "type":"expense","category":"Supermarket","iva_refund":false}"""
            ),
            uyu,
        )!!

        assertEquals("2026-02-11", item.date)
        assertEquals(1234.5, item.amount, 0.001)
        assertEquals(TxnType.EXPENSE, item.type)
        assertEquals("Supermarket", item.category)
        assertTrue(item.include)
    }

    @Test
    fun `rows without a usable amount or date are dropped, not guessed at`() {
        assertNull(normalizeItem(JSONObject("""{"date":"2026-02-11"}"""), uyu))
        assertNull(normalizeItem(JSONObject("""{"date":"2026-02-11","amount":0}"""), uyu))
        assertNull(normalizeItem(JSONObject("""{"amount":10,"date":"11/02/2026"}"""), uyu))
        assertNull(normalizeItem(JSONObject("""{"amount":10}"""), uyu))
    }

    @Test
    fun `amounts are made positive and rounded to the account's currency`() {
        val item = normalizeItem(JSONObject("""{"date":"2026-02-11","amount":-12.345}"""), uyu)!!

        assertEquals(12.35, item.amount, 0.0001)
    }

    @Test
    fun `only a credit can be marked as a tax refund`() {
        val debit = normalizeItem(
            JSONObject("""{"date":"2026-02-11","amount":10,"type":"expense","iva_refund":true}"""),
            uyu,
        )!!
        val credit = normalizeItem(
            JSONObject("""{"date":"2026-02-11","amount":10,"type":"fund","iva_refund":true}"""),
            uyu,
        )!!

        assertFalse(debit.ivaRefund)
        assertTrue(credit.ivaRefund)
    }

    // ── IVA consolidation ─────────────────────────────────────────────────────

    @Test
    fun `several tax refunds collapse into one row that keeps the first position`() {
        val items = listOf(
            item("2026-02-01", "Supermarket run", 500.0, TxnType.EXPENSE),
            item("2026-02-02", "REDIVA", 12.0, TxnType.FUND, ivaRefund = true),
            item("2026-02-03", "Coffee", 90.0, TxnType.EXPENSE),
            item("2026-02-05", "Reintegro de IVA", 8.5, TxnType.FUND, ivaRefund = true),
        )

        val result = consolidateIvaRefunds(items, uyu)

        assertEquals(3, result.size)
        val merged = result[1]
        assertEquals("Reintegro de IVA", merged.description)
        // Summed in code, and dated by the most recent refund.
        assertEquals(20.5, merged.amount, 0.0001)
        assertEquals("2026-02-05", merged.date)
        assertEquals(TxnType.FUND, merged.type)
        assertEquals(Categories.OTHERS, merged.category)
        // Everything else keeps its order.
        assertEquals("Supermarket run", result[0].description)
        assertEquals("Coffee", result[2].description)
    }

    @Test
    fun `a single tax refund is left alone`() {
        val items = listOf(item("2026-02-02", "REDIVA", 12.0, TxnType.FUND, ivaRefund = true))

        assertEquals(items, consolidateIvaRefunds(items, uyu))
    }

    // ── duplicate detection ───────────────────────────────────────────────────

    @Test
    fun `rows already in the account are flagged and unticked`() {
        val existing = listOf(
            Txn(1, "2026-02-11", "Groceries", 1234.5, "Supermarket", TxnType.EXPENSE.wire, "acct"),
        )
        val items = listOf(
            item("2026-02-11", "TIENDA INGLESA S.A.", 1234.5, TxnType.EXPENSE),
            item("2026-02-12", "Coffee", 90.0, TxnType.EXPENSE),
        )

        val result = flagDuplicates(items, existing, uyu)

        // Matched on date, type and amount - the wording differs entirely and that is fine.
        assertTrue(result[0].duplicate)
        assertFalse(result[0].include)
        assertFalse(result[1].duplicate)
        assertTrue(result[1].include)
    }

    @Test
    fun `the same amount on a different day is not a duplicate`() {
        val existing = listOf(
            Txn(1, "2026-02-11", "Groceries", 100.0, "Supermarket", TxnType.EXPENSE.wire, "acct"),
        )

        val result = flagDuplicates(listOf(item("2026-02-12", "Groceries", 100.0, TxnType.EXPENSE)), existing, uyu)

        assertFalse(result[0].duplicate)
    }

    @Test
    fun `a credit is not a duplicate of a debit for the same amount`() {
        val existing = listOf(
            Txn(1, "2026-02-11", "Refund", 100.0, "Others", TxnType.FUND.wire, "acct"),
        )

        val result = flagDuplicates(listOf(item("2026-02-11", "Charge", 100.0, TxnType.EXPENSE)), existing, uyu)

        assertFalse(result[0].duplicate)
    }

    // ── response parsing ──────────────────────────────────────────────────────

    @Test
    fun `the transactions array is found however the model wraps it`() {
        assertEquals(2, extractTransactionsArray("""{"transactions":[{"a":1},{"b":2}]}""").length())
        assertEquals(1, extractTransactionsArray("""{"items":[{"a":1}]}""").length())
        assertEquals(1, extractTransactionsArray("""Sure! [{"a":1}]""").length())
        assertEquals(
            0,
            extractTransactionsArray("""```json {"transactions": []} ```""").length(),
        )
    }

    @Test
    fun `a reply with no array at all is reported`() {
        val error = runCatching { extractTransactionsArray("I could not read the statement.") }
            .exceptionOrNull()

        assertTrue(error is AiException)
    }

    private fun item(
        date: String,
        description: String,
        amount: Double,
        type: TxnType,
        ivaRefund: Boolean = false,
    ) = StatementItem(
        date = date,
        description = description,
        amount = amount,
        type = type,
        category = Categories.OTHERS,
        ivaRefund = ivaRefund,
    )
}
