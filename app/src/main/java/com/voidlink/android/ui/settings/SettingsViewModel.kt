package com.voidlink.android.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.voidlink.android.data.SettingsRepository
import com.voidlink.android.data.StreamSettings
import com.voidlink.android.di.ServiceLocator
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Drives the settings sidebar.
 *
 * Deliberately thin: the sidebar edits one immutable [StreamSettings] value, and every control
 * funnels through [update], which hands the transform straight to the repository. That keeps the
 * write atomic and means a new setting needs no new plumbing here.
 */
class SettingsViewModel(
    private val repository: SettingsRepository,
) : ViewModel() {

    /** The current settings, defaulted until the first read from disk completes. */
    val settings: StateFlow<StreamSettings> = repository.settings.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = StreamSettings(),
    )

    /**
     * Applies [transform] to the stored settings.
     *
     * Every control in the sidebar goes through here, e.g.
     * `update { it.copy(hdrEnabled = enabled) }`.
     */
    fun update(transform: (StreamSettings) -> StreamSettings) {
        viewModelScope.launch { repository.update(transform) }
    }

    /** Restores every setting to its default. */
    fun resetToDefaults() {
        viewModelScope.launch { repository.resetToDefaults() }
    }

    companion object {
        private const val STOP_TIMEOUT_MILLIS = 5_000L

        /** Builds the production view model from [ServiceLocator]. */
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                SettingsViewModel(repository = ServiceLocator.settingsRepository)
            }
        }
    }
}
