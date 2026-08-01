package com.clarifi.ui.statement

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.clarifi.data.ai.AiException
import com.clarifi.data.ai.StatementItem
import com.clarifi.data.ai.StatementProgress
import com.clarifi.data.ai.StatementScanner
import com.clarifi.data.db.Account
import com.clarifi.data.prefs.SecretStore
import com.clarifi.data.repo.AccountRepository
import com.clarifi.data.repo.NewEntry
import com.clarifi.data.repo.TxnRepository
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface StatementStage {
    data object Idle : StatementStage

    /** [caption] tracks the scan page by page, and says so when it has to wait. */
    data class Analysing(val caption: String = "Reading the statement…") : StatementStage
    data class Review(val items: List<StatementItem>, val truncated: Boolean) : StatementStage
    data class Failed(val message: String) : StatementStage
}

data class StatementUiState(
    val stage: StatementStage = StatementStage.Idle,
    val accounts: List<Account> = emptyList(),
    val selectedAccountId: String? = null,
    val hasApiKey: Boolean = false,
) {
    val account: Account? get() = accounts.firstOrNull { it.id == selectedAccountId }
    val selectedCount: Int
        get() = (stage as? StatementStage.Review)?.items?.count { it.include } ?: 0
}

class StatementViewModel(
    private val scanner: StatementScanner,
    private val secrets: SecretStore,
    private val accounts: AccountRepository,
    private val txns: TxnRepository,
) : ViewModel() {

    private val stage = MutableStateFlow<StatementStage>(StatementStage.Idle)
    private val selected = MutableStateFlow<String?>(null)
    private val keyPresent = MutableStateFlow(secrets.hasAiKey)

    val state: StateFlow<StatementUiState> = combine(
        stage,
        accounts.activeAccounts,
        selected,
        keyPresent,
    ) { currentStage, accountList, selectedId, hasKey ->
        StatementUiState(
            stage = currentStage,
            accounts = accountList,
            selectedAccountId = selectedId ?: accountList.firstOrNull()?.id,
            hasApiKey = hasKey,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StatementUiState())

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = _messages.receiveAsFlow()

    fun refreshKey() {
        keyPresent.value = secrets.hasAiKey
    }

    fun selectAccount(id: String) {
        selected.value = id
    }

    fun reset() {
        stage.value = StatementStage.Idle
    }

    fun analyse(uri: Uri) {
        val apiKey = secrets.aiApiKey ?: run {
            stage.value = StatementStage.Failed("Add an AI key under Settings first.")
            return
        }
        val account = state.value.account ?: run {
            stage.value = StatementStage.Failed("Pick the account this statement belongs to.")
            return
        }

        stage.value = StatementStage.Analysing()
        viewModelScope.launch {
            stage.value = try {
                // Existing rows are needed to flag duplicates, which is what makes it
                // safe to import the same statement twice.
                val existing = txns.allTxns.first().filter { it.account == account.id }
                val result = scanner.scan(uri, apiKey, account.currencyMeta, existing) { progress ->
                    // A page can take a while and a rate limit can add a minute on top;
                    // a spinner with nothing under it reads as a hang.
                    stage.value = StatementStage.Analysing(caption(progress))
                }
                if (result.items.isEmpty()) {
                    StatementStage.Failed("No transactions were found in that PDF.")
                } else {
                    StatementStage.Review(result.items, result.truncated)
                }
            } catch (e: AiException) {
                StatementStage.Failed(e.message ?: "That statement could not be read.")
            } catch (e: Exception) {
                StatementStage.Failed("That statement could not be read.")
            }
        }
    }

    fun toggle(index: Int) {
        val current = stage.value as? StatementStage.Review ?: return
        stage.value = current.copy(
            items = current.items.mapIndexed { position, item ->
                if (position == index) item.copy(include = !item.include) else item
            }
        )
    }

    fun setAllIncluded(included: Boolean) {
        val current = stage.value as? StatementStage.Review ?: return
        stage.value = current.copy(items = current.items.map { it.copy(include = included) })
    }

    fun importSelected() {
        val current = stage.value as? StatementStage.Review ?: return
        val accountId = state.value.selectedAccountId ?: return
        val chosen = current.items.filter { it.include }
        if (chosen.isEmpty()) {
            report("Nothing selected to import.")
            return
        }

        viewModelScope.launch {
            try {
                val written = txns.addAll(
                    accountId = accountId,
                    entries = chosen.map { item ->
                        NewEntry(
                            type = item.type,
                            amount = item.amount,
                            date = item.date,
                            description = item.description,
                            category = item.category,
                        )
                    },
                )
                stage.value = StatementStage.Idle
                _messages.send(
                    if (written == 1) "1 transaction imported" else "$written transactions imported"
                )
            } catch (e: Exception) {
                _messages.send(e.message ?: "The import failed.")
            }
        }
    }

    private fun report(message: String) {
        viewModelScope.launch { _messages.send(message) }
    }

    private fun caption(progress: StatementProgress): String = when (progress) {
        is StatementProgress.Page ->
            if (progress.total == 1) {
                "Reading the statement…"
            } else {
                "Reading page ${progress.number} of ${progress.total}…"
            }

        is StatementProgress.Waiting -> "Waiting ${progress.seconds}s for the AI rate limit…"
    }
}
