package com.clarifi.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.clarifi.data.cloud.CloudException
import com.clarifi.data.cloud.CloudSync
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

data class CloudUiState(
    val configured: Boolean = false,
    val host: String = "",
    val lastPush: String? = null,
    val lastPull: String? = null,
    val busy: Boolean = false,
    /** Shown in place of a toast, because a sync failure is worth reading twice. */
    val error: String? = null,
)

class CloudViewModel(private val cloud: CloudSync) : ViewModel() {

    private val _state = MutableStateFlow(read())
    val state: StateFlow<CloudUiState> = _state.asStateFlow()

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = _messages.receiveAsFlow()

    fun connect(dsn: String) = run("Connected") { cloud.save(dsn); null }

    fun push() = run(null) {
        val counts = cloud.push()
        "Pushed ${counts.accounts} accounts and ${counts.transactions} transactions"
    }

    fun pull() = run(null) {
        val counts = cloud.pull()
        "Pulled ${counts.accounts} accounts and ${counts.transactions} transactions"
    }

    fun forget() {
        cloud.forget()
        _state.value = read()
        viewModelScope.launch { _messages.send("Disconnected from Supabase") }
    }

    private fun run(fallback: String?, block: suspend () -> String?) {
        if (_state.value.busy) return
        _state.value = _state.value.copy(busy = true, error = null)
        viewModelScope.launch {
            try {
                val message = block() ?: fallback
                _state.value = read()
                message?.let { _messages.send(it) }
            } catch (e: CloudException) {
                _state.value = read().copy(error = e.message)
            } catch (e: Exception) {
                _state.value = read().copy(error = e.message ?: "The sync did not finish")
            }
        }
    }

    private fun read() = CloudUiState(
        configured = cloud.isConfigured,
        host = cloud.description,
        lastPush = cloud.lastPush,
        lastPull = cloud.lastPull,
    )
}
