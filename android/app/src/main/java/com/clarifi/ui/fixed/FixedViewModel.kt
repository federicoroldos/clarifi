package com.clarifi.ui.fixed

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.clarifi.core.model.ClariFiException
import com.clarifi.core.model.TxnType
import com.clarifi.data.db.Account
import com.clarifi.data.repo.AccountRepository
import com.clarifi.data.repo.FixedPaymentView
import com.clarifi.data.repo.FixedRepository
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class FixedUiState(
    /** Payments whose day has passed and that have not been applied yet. */
    val due: List<FixedPaymentView> = emptyList(),
    val rest: List<FixedPaymentView> = emptyList(),
    val accounts: List<Account> = emptyList(),
    val loaded: Boolean = false,
) {
    val isEmpty: Boolean get() = due.isEmpty() && rest.isEmpty()
}

class FixedViewModel(
    private val fixed: FixedRepository,
    accounts: AccountRepository,
) : ViewModel() {

    val state: StateFlow<FixedUiState> = combine(
        fixed.payments,
        accounts.activeAccounts,
    ) { payments, accountList ->
        FixedUiState(
            // What needs action goes first; everything else keeps the day order.
            due = payments.filter { it.dueThisMonth },
            rest = payments.filterNot { it.dueThisMonth },
            accounts = accountList,
            loaded = true,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FixedUiState())

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = _messages.receiveAsFlow()

    fun save(
        editingId: Int?,
        name: String,
        amount: Double,
        accountId: String,
        category: String,
        day: Int,
        type: TxnType,
    ) = execute {
        if (editingId == null) {
            fixed.create(name, amount, accountId, category, day, type)
            "Fixed transaction created"
        } else {
            fixed.edit(editingId, name, amount, accountId, category, day, type)
            "Fixed transaction updated"
        }
    }

    fun apply(view: FixedPaymentView) = execute {
        fixed.apply(view.id)
        if (view.isIncome) "Income recorded" else "Payment recorded"
    }

    fun undo(view: FixedPaymentView) = execute {
        fixed.undo(view.id)
        "Reverted"
    }

    fun delete(id: Int) = execute {
        fixed.delete(id)
        "Fixed transaction deleted"
    }

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
