package com.clarifi.ui.settings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import com.clarifi.data.ai.AiClient
import com.clarifi.data.ai.AiException
import com.clarifi.data.ai.AiProvider
import com.clarifi.data.backup.JsonBackup
import com.clarifi.data.db.ClariFiDatabase
import com.clarifi.data.prefs.SecretStore
import com.clarifi.data.prefs.SettingsStore
import com.clarifi.data.repo.seedIfEmpty
import com.clarifi.ui.theme.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SettingsUiState(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val hasApiKey: Boolean = false,
    val provider: AiProvider? = null,
    /** Last four characters of the stored key, the only part the desktop shows. */
    val keyHint: String = "",
    val verifying: Boolean = false,
    val busy: Boolean = false,
)

/** Mirrors the desktop's `key_hint`: enough to tell two keys apart, no more. */
private fun hintOf(key: String?): String =
    key.orEmpty().filter(Char::isLetterOrDigit).takeLast(4)

class SettingsViewModel(
    private val context: Context,
    private val settings: SettingsStore,
    private val secrets: SecretStore,
    private val aiClient: AiClient,
    private val backup: JsonBackup,
    private val database: ClariFiDatabase,
) : ViewModel() {

    private val _state = MutableStateFlow(
        SettingsUiState(
            themeMode = settings.themeMode,
            hasApiKey = secrets.hasAiKey,
            provider = secrets.aiApiKey?.let(AiProvider::detect),
            keyHint = hintOf(secrets.aiApiKey),
        )
    )
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = _messages.receiveAsFlow()

    fun setTheme(mode: ThemeMode) {
        settings.themeMode = mode
        _state.value = _state.value.copy(themeMode = mode)
    }

    /**
     * Saves the key only after the provider accepts it.
     *
     * Storing an unverified key just moves the failure to the moment the user is
     * standing at a till with a receipt in hand.
     */
    fun saveApiKey(key: String) {
        val trimmed = key.trim()
        if (trimmed.isEmpty()) {
            report("Paste a key first.")
            return
        }

        _state.value = _state.value.copy(verifying = true)
        viewModelScope.launch {
            try {
                val provider = aiClient.verifyKey(trimmed)
                secrets.aiApiKey = trimmed
                _state.value = _state.value.copy(
                    hasApiKey = true,
                    provider = provider,
                    keyHint = hintOf(trimmed),
                    verifying = false,
                )
                _messages.send("${provider.label} key saved")
            } catch (e: AiException) {
                _state.value = _state.value.copy(verifying = false)
                _messages.send(e.message ?: "That key was not accepted.")
            } catch (e: Exception) {
                _state.value = _state.value.copy(verifying = false)
                _messages.send("That key was not accepted.")
            }
        }
    }

    fun removeApiKey() {
        secrets.aiApiKey = null
        _state.value = _state.value.copy(hasApiKey = false, provider = null, keyHint = "")
        report("Key removed from this device")
    }

    fun export(uri: Uri) = busy {
        val json = backup.export()
        withContext(Dispatchers.IO) {
            context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
                ?: throw IllegalStateException("could not open the file for writing")
        }
        "Backup saved"
    }

    fun import(uri: Uri) = busy {
        val text = withContext(Dispatchers.IO) {
            context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                ?: throw IllegalStateException("could not read the file")
        }
        val summary = backup.import(text)
        "Imported ${summary.accounts} accounts and ${summary.transactions} transactions"
    }

    /** Wipes everything and starts over with the two default accounts, like the desktop's Clear. */
    fun clearAll() = busy {
        database.withTransaction {
            database.txnDao().clear()
            database.fixedDao().clearAppliedTable()
            database.fixedDao().clearPayments()
            database.accountDao().clear()
            database.configDao().clear()
        }
        database.seedIfEmpty()
        "All data cleared"
    }

    private fun busy(block: suspend () -> String) {
        if (_state.value.busy) return
        _state.value = _state.value.copy(busy = true)
        viewModelScope.launch {
            val message = try {
                block()
            } catch (e: Exception) {
                e.message ?: "Something went wrong"
            }
            _state.value = _state.value.copy(busy = false)
            _messages.send(message)
        }
    }

    private fun report(message: String) {
        viewModelScope.launch { _messages.send(message) }
    }
}
