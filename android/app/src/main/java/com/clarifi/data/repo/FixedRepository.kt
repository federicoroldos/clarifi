package com.clarifi.data.repo

import androidx.room.withTransaction
import com.clarifi.core.ids.Ids
import com.clarifi.core.model.Categories
import com.clarifi.core.model.ClariFiException
import com.clarifi.core.model.TxnType
import com.clarifi.core.money.Currency
import com.clarifi.core.money.roundCurrency
import com.clarifi.core.time.Dates
import com.clarifi.data.db.Account
import com.clarifi.data.db.ClariFiDatabase
import com.clarifi.data.db.FixedApplied
import com.clarifi.data.db.FixedPayment
import com.clarifi.data.db.Txn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlin.math.abs

/**
 * A fixed payment enriched with everything the UI needs to render one row, so
 * screens never have to join the three underlying tables themselves. Mirrors the
 * `normalized_fixed` entries `build_summary` returns.
 */
data class FixedPaymentView(
    val payment: FixedPayment,
    val account: Account,
    val appliedThisMonth: Boolean,
    val dueThisMonth: Boolean,
) {
    val id: Int get() = payment.id
    val name: String get() = payment.name
    val amount: Double get() = payment.amount
    val day: Int get() = payment.day
    val type: TxnType get() = payment.fixedType
    val currency: Currency get() = account.currencyMeta
    val isIncome: Boolean get() = type == TxnType.FUND
}

/**
 * Recurring payments and income.
 *
 * Mirrors `apply_fixed`, `undo_fixed`, `delete_fixed` and `modern_create_fixed`
 * / `modern_edit_fixed` in app.py.
 */
class FixedRepository(
    private val db: ClariFiDatabase,
    private val accounts: AccountRepository,
    private val txnRepository: TxnRepository,
) {

    private val fixed = db.fixedDao()
    private val txns = db.txnDao()

    /**
     * Live list of payments belonging to an existing account, annotated with
     * applied/due state for the current month. Payments whose account was
     * permanently deleted are dropped, as `build_summary` does.
     */
    val payments: Flow<List<FixedPaymentView>> =
        combine(
            fixed.observeAll(),
            fixed.observeApplied(),
            accounts.activeAccounts,
        ) { payments, applied, accountList ->
            val accountsById = accountList.associateBy { it.id }
            val month = Dates.currentMonth()
            val today = Dates.todayDayOfMonth()
            val appliedKeys = applied.filter { it.yearMonth == month }.map { it.paymentId }.toSet()

            payments.mapNotNull { payment ->
                val account = accountsById[payment.account] ?: return@mapNotNull null
                val isApplied = payment.id in appliedKeys
                FixedPaymentView(
                    payment = payment,
                    account = account,
                    appliedThisMonth = isApplied,
                    dueThisMonth = Dates.dueDayThisMonth(payment.day) <= today && !isApplied,
                )
            }
        }

    suspend fun create(
        name: String,
        amount: Double,
        accountId: String,
        category: String,
        day: Int,
        type: TxnType,
    ): FixedPayment {
        val account = accounts.require(accountId)
        val payment = FixedPayment(
            id = Ids.nextId(fixed.maxId()),
            name = validateName(name),
            amount = validateAmount(account, amount),
            account = account.id,
            category = Categories.normalize(category),
            day = validateDay(day),
            type = normalizeType(type).wire,
        )
        fixed.insert(payment)
        return payment
    }

    suspend fun edit(
        id: Int,
        name: String,
        amount: Double,
        accountId: String,
        category: String,
        day: Int,
        type: TxnType,
    ): FixedPayment {
        val existing = fixed.byId(id) ?: throw ClariFiException("not found")
        val account = accounts.require(accountId)
        val updated = existing.copy(
            name = validateName(name),
            amount = validateAmount(account, amount),
            account = account.id,
            category = Categories.normalize(category),
            day = validateDay(day),
            type = normalizeType(type).wire,
        )
        fixed.update(updated)
        return updated
    }

    /** Removes the payment and every record of it having been applied. */
    suspend fun delete(id: Int) {
        db.withTransaction {
            fixed.clearAllApplied(id)
            fixed.deletePayment(id)
        }
    }

    /**
     * Applies this month's instalment: marks it applied and creates the matching
     * transaction, which moves the balance.
     */
    suspend fun apply(id: Int) {
        val payment = fixed.byId(id) ?: throw ClariFiException("not found")
        val month = Dates.currentMonth()
        if (fixed.appliedCount(id, month) > 0) throw ClariFiException("already applied this month")

        db.withTransaction {
            fixed.markApplied(FixedApplied(id, month))
            txnRepository.add(
                type = payment.fixedType,
                accountId = payment.account,
                amount = payment.amount,
                date = Dates.today(),
                description = payment.name,
                category = payment.category,
            )
        }
    }

    /**
     * Reverses an applied instalment.
     *
     * Like the desktop, the transaction to remove is found by name + account +
     * type within the current month rather than by id, because the applied
     * record does not store one. A manually added expense with the same name on
     * the same account is therefore a candidate - matching the desktop's
     * behaviour rather than silently diverging from it.
     */
    suspend fun undo(id: Int) {
        val payment = fixed.byId(id) ?: throw ClariFiException("not found")
        val month = Dates.currentMonth()

        db.withTransaction {
            val match: Txn? = txns.lastMatching(
                description = payment.name,
                accountId = payment.account,
                type = payment.fixedType.wire,
                monthPrefix = month,
            )
            if (match != null) {
                txns.deleteByIds(listOf(match.id))
                accounts.applyDeltaIfPresent(match.account, -match.balanceDelta)
            }
            fixed.clearApplied(id, month)
        }
    }

    private fun validateName(name: String): String =
        name.trim().ifEmpty { throw ClariFiException("name is required") }

    private fun validateAmount(account: Account, amount: Double): Double {
        val value = try {
            roundCurrency(account.currencyMeta, abs(amount))
        } catch (e: IllegalArgumentException) {
            throw ClariFiException("invalid amount")
        }
        if (value <= 0) throw ClariFiException("amount must be greater than zero")
        return value
    }

    private fun validateDay(day: Int): Int =
        day.takeIf { it in 1..31 } ?: throw ClariFiException("day must be between 1 and 31")

    /** Fixed payments are income or expense only - never a transfer. */
    private fun normalizeType(type: TxnType): TxnType =
        if (type == TxnType.FUND) TxnType.FUND else TxnType.EXPENSE
}
