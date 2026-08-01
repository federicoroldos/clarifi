package com.clarifi.ui.transactions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.clarifi.core.model.ClariFiException
import com.clarifi.core.model.TxnType
import com.clarifi.core.time.Dates
import com.clarifi.data.db.Account
import com.clarifi.data.db.Txn
import com.clarifi.data.repo.AccountRepository
import com.clarifi.data.repo.TxnRepository
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The same filter set the desktop's advanced filters offer. `null` means
 * "no restriction" throughout, so an untouched filter never narrows anything.
 */
data class TxnFilters(
    val type: TxnType? = null,
    val category: String? = null,
    val accountId: String? = null,
    val from: String? = null,
    val to: String? = null,
    val minAmount: Double? = null,
    val maxAmount: Double? = null,
) {
    val activeCount: Int
        get() = listOf(type, category, accountId, from, to, minAmount, maxAmount).count { it != null }

    fun matches(txn: Txn): Boolean {
        if (type != null && txn.txnType != type) return false
        if (category != null && txn.category != category) return false
        if (accountId != null && txn.account != accountId) return false
        // Dates are ISO strings, so lexicographic comparison is chronological.
        if (from != null && txn.date < from) return false
        if (to != null && txn.date > to) return false
        if (minAmount != null && txn.amount < minAmount) return false
        if (maxAmount != null && txn.amount > maxAmount) return false
        return true
    }
}

/** A day's worth of transactions, ready to render under one sticky header. */
data class TxnDay(val date: String, val items: List<Txn>)

data class TransactionsUiState(
    val days: List<TxnDay> = emptyList(),
    val accountsById: Map<String, Account> = emptyMap(),
    val activeAccounts: List<Account> = emptyList(),
    val filters: TxnFilters = TxnFilters(),
    val totalCount: Int = 0,
    val loaded: Boolean = false,
) {
    val isEmpty: Boolean get() = days.isEmpty()
    val isFiltered: Boolean get() = filters.activeCount > 0
}

class TransactionsViewModel(
    private val accounts: AccountRepository,
    private val txns: TxnRepository,
) : ViewModel() {

    private val filters = MutableStateFlow(TxnFilters())

    val state: StateFlow<TransactionsUiState> = combine(
        txns.allTxns,
        accounts.allAccounts,
        filters,
    ) { allTxns, allAccounts, activeFilters ->
        val byId = allAccounts.associateBy { it.id }
        // Rows whose account was permanently deleted are skipped, as on the desktop.
        val visible = allTxns.filter { it.account in byId && activeFilters.matches(it) }

        TransactionsUiState(
            days = visible.groupBy { it.date }.map { (date, items) -> TxnDay(date, items) },
            accountsById = byId,
            activeAccounts = allAccounts.filter { !it.archived },
            filters = activeFilters,
            totalCount = allTxns.count { it.account in byId },
            loaded = true,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TransactionsUiState())

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = _messages.receiveAsFlow()

    fun setFilters(value: TxnFilters) {
        filters.value = value
    }

    fun clearFilters() {
        filters.value = TxnFilters()
    }

    fun add(
        type: TxnType,
        accountId: String,
        amount: Double,
        date: String,
        description: String,
        category: String,
    ) = execute {
        txns.add(type, accountId, amount, date, description, category)
        if (type == TxnType.FUND) "Income added" else "Expense added"
    }

    fun edit(
        id: Int,
        accountId: String,
        amount: Double,
        date: String,
        description: String,
        category: String,
    ) = execute {
        txns.edit(id, accountId, amount, date, description, category)
        "Transaction updated"
    }

    fun transfer(
        sourceId: String,
        destinationId: String,
        amountSent: Double,
        amountReceived: Double,
        date: String,
        note: String,
    ) = execute {
        txns.transfer(sourceId, destinationId, amountSent, amountReceived, date, note)
        "Transfer recorded"
    }

    /**
     * The rows removed by the last [delete], kept so the snackbar can offer Undo.
     *
     * Deleting is the one destructive action reachable by a single gesture, so it
     * has to be reversible - the desktop can rely on a confirm dialog, a swipe
     * cannot.
     */
    private var lastDeleted: List<Txn> = emptyList()

    fun delete(txn: Txn) = execute {
        lastDeleted = txns.legsOf(txn)
        txns.delete(txn.id)
        if (txn.isTransfer) "Transfer deleted, both sides reversed" else "Transaction deleted"
    }

    /**
     * Recreates what the last delete removed. The new rows get fresh ids - invisible
     * to the user - but the same amounts, dates and accounts, so every balance ends
     * up exactly where it started.
     */
    fun undoDelete() = execute {
        val legs = lastDeleted
        lastDeleted = emptyList()
        when {
            legs.isEmpty() -> "Nothing to undo"

            legs.size == 2 && legs.all { it.isTransfer } -> {
                val out = legs.first { it.direction == com.clarifi.core.model.TransferDirection.OUT }
                val incoming = legs.first { it.direction == com.clarifi.core.model.TransferDirection.IN }
                txns.transfer(
                    sourceId = out.account,
                    destinationId = incoming.account,
                    amountSent = out.amount,
                    amountReceived = incoming.amount,
                    date = out.date,
                    note = out.description,
                )
                "Transfer restored"
            }

            else -> {
                val row = legs.first()
                txns.add(row.txnType, row.account, row.amount, row.date, row.description, row.category)
                "Transaction restored"
            }
        }
    }

    /** Remembered rates, used to prefill the receiving amount on a cross-currency transfer. */
    suspend fun exchangeRates(): Map<String, Double> = txns.exchangeRates()

    fun today(): String = Dates.today()

    private fun execute(block: suspend () -> String) {
        viewModelScope.launch {
            val message = try {
                block()
            } catch (e: ClariFiException) {
                e.message ?: "Something went wrong"
            } catch (e: Exception) {
                "Unexpected error: ${e.javaClass.simpleName}"
            }
            _messages.send(message)
        }
    }
}
