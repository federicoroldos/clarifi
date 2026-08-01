package com.clarifi.ui.about

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.clarifi.data.updates.ReleaseChecker
import com.clarifi.data.updates.ReleaseInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AboutUiState(
    val loading: Boolean = true,
    val release: ReleaseInfo? = null,
    /** Set when GitHub could not be reached; the screen stays useful without it. */
    val error: String? = null,
    /** True once GitHub answered that there are no releases yet. */
    val noReleases: Boolean = false,
)

class AboutViewModel(private val checker: ReleaseChecker) : ViewModel() {

    private val _state = MutableStateFlow(AboutUiState())
    val state: StateFlow<AboutUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        _state.value = AboutUiState(loading = true)
        viewModelScope.launch {
            try {
                val release = withContext(Dispatchers.IO) { checker.latest() }
                _state.value = AboutUiState(
                    loading = false,
                    release = release,
                    noReleases = release == null,
                )
            } catch (e: Exception) {
                _state.value = AboutUiState(loading = false, error = "Could not reach GitHub")
            }
        }
    }
}
