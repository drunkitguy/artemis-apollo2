package com.voidlink.android.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.voidlink.android.data.HostRepository
import com.voidlink.android.data.KnownHost
import com.voidlink.android.data.SettingsRepository
import com.voidlink.android.data.StreamSettings
import com.voidlink.android.di.ServiceLocator
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Drives the settings sidebar, in both of its scopes.
 *
 * Deliberately thin: the sidebar edits one immutable [StreamSettings] value, and every control
 * funnels through [update] or [updateHostOverride], which hand the transform straight to a
 * repository. That keeps the write atomic and means a new setting needs no new plumbing here.
 *
 * The host list is exposed as well as the settings because the panel can be scoped to a single
 * host, and it needs that host's name and current override to render.
 */
class SettingsViewModel(
    private val repository: SettingsRepository,
    private val hostRepository: HostRepository,
) : ViewModel() {

    /** The current global settings, defaulted until the first read from disk completes. */
    val settings: StateFlow<StreamSettings> = repository.settings.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = StreamSettings(),
    )

    /** Every known host, so the panel can resolve the one it is scoped to. */
    val hosts: StateFlow<List<KnownHost>> = hostRepository.hosts.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = emptyList(),
    )

    /**
     * Applies [transform] to the global settings.
     *
     * Every control in the sidebar goes through here, e.g.
     * `update { it.copy(hdrEnabled = enabled) }`.
     */
    fun update(transform: (StreamSettings) -> StreamSettings) {
        viewModelScope.launch { repository.update(transform) }
    }

    /**
     * Applies [transform] to one host's override, creating the override on first edit.
     *
     * A host with no override yet inherits the global settings, so the first edit is seeded from
     * them; otherwise turning one switch would reset every other setting for that host to the
     * factory default.
     */
    fun updateHostOverride(uuid: String, transform: (StreamSettings) -> StreamSettings) {
        viewModelScope.launch {
            val global = settings.value
            hostRepository.updateHost(uuid) { host -> host.withOverride(global, transform) }
        }
    }

    /** Drops a host's overrides so it inherits the global settings again. */
    fun clearHostOverride(uuid: String) {
        viewModelScope.launch { hostRepository.setSettingsOverride(uuid, null) }
    }

    /** Restores every global setting to its default. */
    fun resetToDefaults() {
        viewModelScope.launch { repository.resetToDefaults() }
    }

    /**
     * Stars or unstars a settings row.
     *
     * Always writes the global settings, even while a host override is open: which rows a user
     * wants pinned to the top of the panel is a property of the person, not of the PC.
     */
    fun toggleFavoriteRow(rowId: String) {
        update { current ->
            val favorites = current.favoriteRowIds
            current.copy(
                favoriteRowIds = if (rowId in favorites) favorites - rowId else favorites + rowId,
            )
        }
    }

    companion object {
        private const val STOP_TIMEOUT_MILLIS = 5_000L

        /** Builds the production view model from [ServiceLocator]. */
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                SettingsViewModel(
                    repository = ServiceLocator.settingsRepository,
                    hostRepository = ServiceLocator.hostRepository,
                )
            }
        }
    }
}
