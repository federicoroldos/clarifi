package com.clarifi.ui.scan

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.clarifi.data.ai.AiException
import com.clarifi.data.ai.ReceiptFields
import com.clarifi.data.ai.ReceiptScanner
import com.clarifi.data.db.Account
import com.clarifi.data.prefs.SecretStore
import com.clarifi.data.repo.AccountRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Where the scan flow currently is. */
sealed interface ScanStage {
    /** Nothing started yet - offer the camera and the gallery. */
    data object Idle : ScanStage

    /** The in-app camera is open. */
    data object Capturing : ScanStage

    /** The photo is with the model. */
    data object Analysing : ScanStage

    /** The model answered; the user edits and confirms before anything is saved. */
    data class Review(val fields: ReceiptFields, val suggestedAccountId: String?) : ScanStage

    data class Failed(val message: String) : ScanStage
}

data class ScanUiState(
    val stage: ScanStage = ScanStage.Idle,
    val accounts: List<Account> = emptyList(),
    val hasApiKey: Boolean = false,
)

class ScanViewModel(
    private val scanner: ReceiptScanner,
    private val secrets: SecretStore,
    accounts: AccountRepository,
) : ViewModel() {

    private val stage = MutableStateFlow<ScanStage>(ScanStage.Idle)
    private val keyPresent = MutableStateFlow(secrets.hasAiKey)

    val state: StateFlow<ScanUiState> = combine(
        stage,
        accounts.activeAccounts,
        keyPresent,
    ) { currentStage, accountList, hasKey ->
        ScanUiState(stage = currentStage, accounts = accountList, hasApiKey = hasKey)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ScanUiState())

    /** Re-read on every return to the screen: the key may have been added meanwhile. */
    fun refreshKey() {
        keyPresent.value = secrets.hasAiKey
    }

    fun openCamera() {
        stage.value = ScanStage.Capturing
    }

    fun reset() {
        stage.value = ScanStage.Idle
    }

    fun analyse(uri: Uri) {
        val apiKey = secrets.aiApiKey
        if (apiKey == null) {
            stage.value = ScanStage.Failed("Add an AI key under Settings first.")
            return
        }

        stage.value = ScanStage.Analysing
        viewModelScope.launch {
            stage.value = try {
                val fields = scanner.scan(uri, apiKey)
                ScanStage.Review(
                    fields = fields,
                    suggestedAccountId = suggestAccount(fields.currencyId),
                )
            } catch (e: AiException) {
                ScanStage.Failed(e.message ?: "The AI could not read this receipt.")
            } catch (e: Exception) {
                ScanStage.Failed("Could not read this receipt. Try a clearer, straight-on photo.")
            }
        }
    }

    /**
     * Picks the account to prefill: the first one in the detected currency, else
     * simply the first. Mirrors `_suggest_account`.
     */
    private fun suggestAccount(currencyId: String?): String? {
        val accounts = state.value.accounts
        return currencyId?.let { currency -> accounts.firstOrNull { it.currency == currency }?.id }
            ?: accounts.firstOrNull()?.id
    }
}
