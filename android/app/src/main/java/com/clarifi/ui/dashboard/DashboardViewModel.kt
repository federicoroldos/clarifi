package com.clarifi.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.clarifi.data.repo.AccountStats
import com.clarifi.data.repo.Summary
import com.clarifi.data.repo.SummaryRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** What the dashboard is currently showing. */
sealed interface DashboardView {
    /** Just the accounts and what happened recently: the landing view. */
    data object Accounts : DashboardView

    /** One account: its balance, its charts, its recent rows. */
    data class Account(val id: String) : DashboardView
}

data class DashboardUiState(
    val summary: Summary = Summary(),
    val view: DashboardView = DashboardView.Accounts,
) {
    val selectedAccountId: String? get() = (view as? DashboardView.Account)?.id

    val selectedAccount get() = selectedAccountId?.let { id -> summary.accounts.firstOrNull { it.id == id } }

    val stats: AccountStats? get() = selectedAccountId?.let { summary.statsFor(it) }

    val hasAccounts: Boolean get() = summary.accounts.isNotEmpty()
}

class DashboardViewModel(summaries: SummaryRepository) : ViewModel() {

    private val view = MutableStateFlow<DashboardView>(DashboardView.Accounts)

    val state: StateFlow<DashboardUiState> = combine(
        summaries.summary,
        view,
    ) { summary, current ->
        DashboardUiState(
            summary = summary,
            // An account can be archived or deleted from another screen while it is
            // the one on show; fall back rather than render an empty drilldown.
            view = current.takeUnless {
                it is DashboardView.Account && summary.accounts.none { a -> a.id == it.id }
            } ?: DashboardView.Accounts,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DashboardUiState())

    fun show(next: DashboardView) {
        view.value = next
    }
}
