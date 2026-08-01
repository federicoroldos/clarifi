package com.clarifi.data.repo

import androidx.room.withTransaction
import com.clarifi.core.ids.Ids
import com.clarifi.core.model.AccountColors
import com.clarifi.core.model.ClariFiException
import com.clarifi.core.money.Currencies
import com.clarifi.core.money.roundCurrency
import com.clarifi.data.db.Account
import com.clarifi.data.db.ClariFiDatabase
import kotlinx.coroutines.flow.Flow
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Accounts and their balances.
 *
 * Mirrors `modern_create_account` / `modern_edit_account` / `modern_delete_account`
 * / `modern_permanent_delete_account` / `set_balance` in app.py, including the
 * legacy `balance_*` config mirror that older desktop installs still read.
 */
class AccountRepository(private val db: ClariFiDatabase) {

    private val accounts = db.accountDao()
    private val config = db.configDao()

    val activeAccounts: Flow<List<Account>> = accounts.observeActive()
    val allAccounts: Flow<List<Account>> = accounts.observeAll()

    suspend fun active(): List<Account> = accounts.activeAccounts()

    suspend fun byId(id: String): Account? = accounts.byId(id)

    suspend fun require(id: String?): Account =
        id?.let { accounts.byId(it) } ?: throw ClariFiException("unknown account")

    suspend fun create(
        bank: String,
        currencyId: String,
        balance: Double,
        color: String? = null,
    ): Account {
        val name = bank.trim()
        if (name.isEmpty()) throw ClariFiException("bank is required")

        val currency = try {
            Currencies.require(currencyId)
        } catch (e: IllegalArgumentException) {
            throw ClariFiException("invalid currency or balance")
        }

        val account = Account(
            id = Ids.newAccountId(accounts.allIds().toSet()),
            bank = name,
            currency = currency.id,
            balance = roundCurrency(currency, balance),
            createdAt = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")),
            archived = false,
            color = color?.trim()?.takeIf { it.isNotEmpty() } ?: AccountColors.defaultFor(currency.id),
        )
        accounts.insert(account)
        return account
    }

    suspend fun edit(
        id: String,
        bank: String,
        currencyId: String? = null,
        balance: Double? = null,
        color: String? = null,
    ): Account {
        val existing = require(id)
        val name = bank.trim()
        if (name.isEmpty()) throw ClariFiException("bank name is required")

        val currency = if (currencyId != null) {
            try {
                Currencies.require(currencyId)
            } catch (e: IllegalArgumentException) {
                throw ClariFiException("invalid currency")
            }
        } else {
            existing.currencyMeta
        }

        val updated = existing.copy(
            bank = name,
            currency = currency.id,
            balance = balance?.let { roundCurrency(currency, it) } ?: existing.balance,
            color = color?.trim()?.takeIf { it.isNotEmpty() } ?: existing.color,
        )
        db.withTransaction {
            accounts.update(updated)
            mirrorLegacyBalance(updated.id, updated.balance)
        }
        return updated
    }

    /** Soft delete: the account disappears from the UI but keeps all of its history. */
    suspend fun archive(id: String) {
        require(id)
        accounts.setArchived(id, true)
    }

    /**
     * Brings an archived account back.
     *
     * The desktop offers no way back once archived; since the flag is just a
     * column, restoring here is safe for both apps and saves a user who
     * archived the wrong account from having to rebuild it by hand.
     */
    suspend fun restore(id: String) {
        require(id)
        accounts.setArchived(id, false)
    }

    /**
     * Permanent delete. Only allowed once archived, and it cascades exactly as
     * `modern_permanent_delete_account` does: fixed payments, their applied
     * records, every transaction, and the legacy balance config row.
     */
    suspend fun permanentDelete(id: String) {
        val account = require(id)
        if (!account.archived) throw ClariFiException("deactivate account before deleting it")

        db.withTransaction {
            val fixedIds = db.fixedDao().idsForAccount(id)
            if (fixedIds.isNotEmpty()) {
                db.fixedDao().clearAllAppliedFor(fixedIds)
                db.fixedDao().deletePayments(fixedIds)
            }
            db.txnDao().deleteForAccount(id)
            config.remove("balance_$id")
            accounts.deleteById(id)
        }
    }

    /**
     * Overwrites a balance without recording a transaction - the desktop's
     * `POST /api/balance` behaves the same way, and that is intentional.
     */
    suspend fun setBalance(id: String, value: Double): Double {
        val account = require(id)
        val rounded = roundCurrency(account.currencyMeta, value)
        db.withTransaction {
            accounts.updateBalance(id, rounded)
            mirrorLegacyBalance(id, rounded)
        }
        return rounded
    }

    /**
     * Applies a delta to a balance. Every transaction write goes through here so
     * the rounding rule lives in exactly one place.
     *
     * The balance is re-read rather than taken from a caller-held [Account], so
     * two deltas applied in the same operation (both legs of a transfer, or the
     * reverse-then-reapply of an edit) cannot overwrite each other.
     *
     * Must be called from inside a [ClariFiDatabase.withTransaction] block by
     * callers that also write the transaction row, so a crash can never leave a
     * balance updated without its transaction.
     */
    suspend fun applyDelta(accountId: String, delta: Double): Double {
        val account = require(accountId)
        val rounded = roundCurrency(account.currencyMeta, account.balance + delta)
        accounts.updateBalance(account.id, rounded)
        mirrorLegacyBalance(account.id, rounded)
        return rounded
    }

    /**
     * Same as [applyDelta] but silently skips accounts that no longer exist -
     * transactions can outlive a permanently deleted account, and the desktop
     * skips those rows too rather than failing the whole operation.
     */
    suspend fun applyDeltaIfPresent(accountId: String, delta: Double) {
        if (accounts.byId(accountId) != null) applyDelta(accountId, delta)
    }

    /**
     * Legacy accounts are keyed by their currency (`usd`, `uyu`, `krw`) and older
     * desktop builds still read `config.balance_<currency>`. Keeping the mirror in
     * step costs one row and avoids a stale balance after a cloud pull.
     */
    private suspend fun mirrorLegacyBalance(accountId: String, balance: Double) {
        if (Currencies.find(accountId)?.id == accountId) {
            config.put(com.clarifi.data.db.ConfigEntry("balance_$accountId", balance.toString()))
        }
    }
}
