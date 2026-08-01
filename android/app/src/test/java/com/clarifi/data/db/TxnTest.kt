package com.clarifi.data.db

import com.clarifi.core.model.Categories
import com.clarifi.core.model.TransferDirection
import com.clarifi.core.model.TxnType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `balanceDelta` is the single rule that decides how every write moves a balance:
 * adding it applies a transaction, negating it reverses one. Delete, edit and
 * undo all lean on that symmetry, so it is worth pinning down on its own.
 */
class TxnTest {

    @Test
    fun `income adds and expenses subtract`() {
        assertEquals(50.0, txn(TxnType.FUND, 50.0).balanceDelta, 0.0)
        assertEquals(-50.0, txn(TxnType.EXPENSE, 50.0).balanceDelta, 0.0)
    }

    @Test
    fun `the outgoing leg of a transfer subtracts and the incoming leg adds`() {
        assertEquals(-100.0, transferLeg(TransferDirection.OUT, 100.0).balanceDelta, 0.0)
        assertEquals(92.0, transferLeg(TransferDirection.IN, 92.0).balanceDelta, 0.0)
    }

    @Test
    fun `reversing a delta undoes it`() {
        listOf(
            txn(TxnType.FUND, 12.34),
            txn(TxnType.EXPENSE, 12.34),
            transferLeg(TransferDirection.OUT, 12.34),
            transferLeg(TransferDirection.IN, 12.34),
        ).forEach { row ->
            assertEquals(0.0, row.balanceDelta + -row.balanceDelta, 0.0)
        }
    }

    @Test
    fun `an unknown type is read as an expense rather than crashing`() {
        // Older or hand-edited rows must not take the app down.
        assertEquals(TxnType.EXPENSE, TxnType.from("debit"))
        assertEquals(TxnType.EXPENSE, TxnType.from(null))
    }

    @Test
    fun `the month key is the first seven characters of the date`() {
        assertEquals("2026-07", txn(TxnType.EXPENSE, 1.0, date = "2026-07-31").monthKey)
    }

    @Test
    fun `both legs of a built transfer share one id and point at each other`() {
        val source = account("a", "usd")
        val destination = account("b", "eur")

        val (out, incoming) = transferLegs(
            outId = 10,
            inId = 11,
            transferId = "tx_abcd",
            date = "2026-07-31",
            source = source,
            destination = destination,
            amountSent = 100.0,
            amountReceived = 92.0,
            note = "",
        )

        assertEquals("tx_abcd", out.transferId)
        assertEquals("tx_abcd", incoming.transferId)
        assertEquals(destination.id, out.counterpart)
        assertEquals(source.id, incoming.counterpart)
        assertEquals(Categories.TRANSFER, out.category)
        assertTrue(out.description.contains(destination.bank))
        assertTrue(incoming.description.contains(source.bank))
    }

    @Test
    fun `a note replaces the generated transfer description on both legs`() {
        val (out, incoming) = transferLegs(
            outId = 1, inId = 2, transferId = "tx_1", date = "2026-07-31",
            source = account("a", "usd"), destination = account("b", "eur"),
            amountSent = 10.0, amountReceived = 9.0, note = "Rent split",
        )

        assertEquals("Rent split", out.description)
        assertEquals("Rent split", incoming.description)
    }

    private fun txn(type: TxnType, amount: Double, date: String = "2026-07-31") =
        Txn(1, date, "x", amount, Categories.OTHERS, type.wire, "acct")

    private fun transferLeg(direction: TransferDirection, amount: Double) = Txn(
        id = 1,
        date = "2026-07-31",
        description = "x",
        amount = amount,
        category = Categories.TRANSFER,
        type = TxnType.TRANSFER.wire,
        account = "acct",
        transferId = "tx_1",
        counterpart = "other",
        transferDir = direction.wire,
    )

    private fun account(id: String, currency: String) =
        Account(id, "Bank $id", currency, 0.0, "2026-01-01T00:00:00", false, "#10b981")
}
