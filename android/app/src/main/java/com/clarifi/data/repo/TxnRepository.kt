package com.clarifi.data.repo

import androidx.room.withTransaction
import com.clarifi.core.ids.Ids
import com.clarifi.core.model.Categories
import com.clarifi.core.model.ClariFiException
import com.clarifi.core.model.TxnType
import com.clarifi.core.money.roundCurrency
import com.clarifi.core.time.Dates
import com.clarifi.data.db.Account
import com.clarifi.data.db.ClariFiDatabase
import com.clarifi.data.db.ConfigEntry
import com.clarifi.data.db.Txn
import com.clarifi.data.db.transferLegs
import kotlinx.coroutines.flow.Flow
import kotlin.math.abs

/** A transaction to be created, before it has an id. */
data class NewEntry(
    val type: TxnType,
    val amount: Double,
    val date: String,
    val description: String,
    val category: String,
)

/**
 * Transactions and transfers.
 *
 * Every mutation runs inside a database transaction that covers both the row and
 * the affected balances - the desktop cannot do this (it writes the sheet and the
 * balance separately) and it is the one place where being a real app buys
 * correctness for free.
 */
class TxnRepository(
    private val db: ClariFiDatabase,
    private val accounts: AccountRepository,
) {

    private val txns = db.txnDao()
    private val config = db.configDao()

    val allTxns: Flow<List<Txn>> = txns.observeAll()

    suspend fun byId(id: Int): Txn? = txns.byId(id)

    /**
     * Every row that deleting [txn] would remove: both legs for a transfer, just
     * itself otherwise. Callers use it to capture state before a destructive
     * action so they can offer an undo.
     */
    suspend fun legsOf(txn: Txn): List<Txn> =
        txn.transferId
            ?.takeIf { txn.isTransfer }
            ?.let { txns.byTransferId(it) }
            ?.takeIf { it.isNotEmpty() }
            ?: listOf(txn)

    /**
     * Records income or an expense. Mirrors `_add_txn`.
     *
     * @return the account's new balance.
     */
    suspend fun add(
        type: TxnType,
        accountId: String,
        amount: Double,
        date: String = Dates.today(),
        description: String = "",
        category: String = Categories.OTHERS,
    ): Double {
        if (type == TxnType.TRANSFER) throw ClariFiException("use transfer() to move money between accounts")
        val account = accounts.require(accountId)
        val value = normalizeAmount(account, amount)

        return db.withTransaction {
            val txn = Txn(
                id = Ids.nextId(txns.maxId()),
                date = date.ifBlank { Dates.today() },
                description = description,
                amount = value,
                category = Categories.normalize(category),
                type = type.wire,
                account = account.id,
            )
            txns.insert(txn)
            accounts.applyDelta(account.id, txn.balanceDelta)
        }
    }

    /**
     * Adds several transactions at once, in a single database transaction.
     *
     * Used by the statement import, where writing rows one at a time would leave a
     * half-imported statement behind if anything failed partway.
     *
     * @return how many rows were written.
     */
    suspend fun addAll(accountId: String, entries: List<NewEntry>): Int {
        if (entries.isEmpty()) return 0
        val account = accounts.require(accountId)

        return db.withTransaction {
            var nextId = Ids.nextId(txns.maxId())
            var delta = 0.0
            val rows = entries.map { entry ->
                val value = normalizeAmount(account, entry.amount)
                val txn = Txn(
                    id = nextId++,
                    date = entry.date.ifBlank { Dates.today() },
                    description = entry.description,
                    amount = value,
                    category = Categories.normalize(entry.category),
                    type = entry.type.wire,
                    account = account.id,
                )
                delta += txn.balanceDelta
                txn
            }
            txns.insertAll(rows)
            accounts.applyDelta(account.id, delta)
            rows.size
        }
    }

    /**
     * Edits a non-transfer transaction. Mirrors `modern_edit_txn`: the type is
     * fixed, but the amount, date, description, category and even the account can
     * change, so the old effect is reversed on the old account before the new one
     * is applied to the new account.
     */
    suspend fun edit(
        id: Int,
        accountId: String,
        amount: Double,
        date: String,
        description: String,
        category: String,
    ) {
        val existing = txns.byId(id) ?: throw ClariFiException("not found")
        if (existing.isTransfer) {
            throw ClariFiException("transfers cannot be edited; delete and recreate")
        }

        val target = accounts.require(accountId)
        val value = normalizeAmount(target, amount)

        db.withTransaction {
            // Reverse the old row first; the account may have been deleted since.
            accounts.applyDeltaIfPresent(existing.account, -existing.balanceDelta)

            val updated = existing.copy(
                date = date.ifBlank { existing.date },
                description = description,
                amount = value,
                category = Categories.normalize(category),
                account = target.id,
            )
            txns.update(updated)
            accounts.applyDelta(target.id, updated.balanceDelta)
        }
    }

    /**
     * Deletes a transaction and reverses its effect. Deleting either leg of a
     * transfer removes both legs and reverses both balances, exactly as
     * `modern_delete_txn` does.
     */
    suspend fun delete(id: Int) {
        val primary = txns.byId(id) ?: throw ClariFiException("not found")

        db.withTransaction {
            val legs = legsOf(primary)
            txns.deleteByIds(legs.map { it.id })
            legs.forEach { leg ->
                accounts.applyDeltaIfPresent(leg.account, -leg.balanceDelta)
            }
        }
    }

    /**
     * Moves money between two accounts. Mirrors `modern_transfer`: two rows sharing
     * a transfer id, separate sent/received amounts so cross-currency moves keep
     * both sides exact, and the implied exchange rate remembered for next time.
     */
    suspend fun transfer(
        sourceId: String,
        destinationId: String,
        amountSent: Double,
        amountReceived: Double,
        date: String = Dates.today(),
        note: String = "",
    ): String {
        if (sourceId.isBlank() || destinationId.isBlank() || sourceId == destinationId) {
            throw ClariFiException("source and destination must differ")
        }
        val source = accounts.require(sourceId)
        val destination = accounts.require(destinationId)

        val sent = normalizeAmount(source, amountSent)
        val received = normalizeAmount(destination, amountReceived)
        val transferId = Ids.newTransferId()

        db.withTransaction {
            val outId = Ids.nextId(txns.maxId())
            val (outLeg, inLeg) = transferLegs(
                outId = outId,
                inId = outId + 1,
                transferId = transferId,
                date = date.ifBlank { Dates.today() },
                source = source,
                destination = destination,
                amountSent = sent,
                amountReceived = received,
                note = note.trim(),
            )
            txns.insertAll(listOf(outLeg, inLeg))
            accounts.applyDelta(source.id, outLeg.balanceDelta)
            accounts.applyDelta(destination.id, inLeg.balanceDelta)

            if (source.currency != destination.currency) {
                saveRate(source.currency, destination.currency, received / sent)
                saveRate(destination.currency, source.currency, sent / received)
            }
        }
        return transferId
    }

    /** Remembered exchange rates, keyed `"<from>_<to>"`. Mirrors `api_fxrates`. */
    suspend fun exchangeRates(): Map<String, Double> =
        config.withPrefix("fxrate_").mapNotNull { entry ->
            val parts = entry.key.split('_')
            val rate = entry.value.toDoubleOrNull()
            if (parts.size == 3 && rate != null) "${parts[1]}_${parts[2]}" to rate else null
        }.toMap()

    private suspend fun saveRate(from: String, to: String, rate: Double) {
        if (rate.isFinite() && rate > 0) {
            config.put(ConfigEntry("fxrate_${from}_$to", rate.toString()))
        }
    }

    /** Amounts are stored positive and must be non-zero; the type carries the sign. */
    private fun normalizeAmount(account: Account, amount: Double): Double {
        val value = try {
            roundCurrency(account.currencyMeta, abs(amount))
        } catch (e: IllegalArgumentException) {
            throw ClariFiException("invalid amount")
        }
        if (value <= 0) throw ClariFiException("amount must be greater than zero")
        return value
    }
}
