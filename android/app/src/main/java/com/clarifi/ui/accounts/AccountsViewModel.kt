package com.clarifi.ui.accounts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.clarifi.core.model.ClariFiException
import com.clarifi.data.db.Account
import com.clarifi.data.repo.AccountRepository
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class AccountsUiState(
    val active: List<Account> = emptyList(),
    val archived: List<Account> = emptyList(),
    val loaded: Boolean = false,
)

class AccountsViewModel(private val accounts: AccountRepository) : ViewModel() {

    val state: StateFlow<AccountsUiState> = accounts.allAccounts
        .map { all ->
            AccountsUiState(
                active = all.filter { !it.archived },
                archived = all.filter { it.archived },
                loaded = true,
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AccountsUiState())

    private val _messages = Channel<String>(Channel.BUFFERED)

    /** One-shot user-facing outcomes, shown as snackbars and never replayed on rotation. */
    val messages: Flow<String> = _messages.receiveAsFlow()

    /** Creates when [editingId] is null, updates otherwise. */
    fun save(
        editingId: String?,
        bank: String,
        currency: String,
        balance: Double,
        color: String,
    ) = execute {
        if (editingId == null) {
            accounts.create(bank, currency, balance, color)
            "Account created"
        } else {
            accounts.edit(editingId, bank, currency, balance, color)
            "Account updated"
        }
    }

    fun archive(id: String) = execute {
        accounts.archive(id)
        "Archived"
    }

    fun restore(id: String) = execute {
        accounts.restore(id)
        "Restored"
    }

    fun permanentDelete(id: String) = execute {
        accounts.permanentDelete(id)
        "Account deleted"
    }

    /**
     * Runs a repository call and reports the outcome.
     *
     * Domain failures ([ClariFiException]) carry a message written for the user,
     * so they are shown as-is; anything else is a bug and says so rather than
     * leaking the first line of a stack trace.
     */
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
