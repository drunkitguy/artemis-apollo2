package com.voidlink.android.ui.apps

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.voidlink.android.data.AppCatalogProvider
import com.voidlink.android.data.HostApp
import com.voidlink.android.data.HostRepository
import com.voidlink.android.data.HostStatus
import com.voidlink.android.data.HostStatusProvider
import com.voidlink.android.data.KnownHost
import com.voidlink.android.di.ServiceLocator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * UI state of the Apps screen.
 *
 * @property host the host whose library is shown, or `null` while it is still being looked up.
 * @property apps the host's applications, Desktop first.
 * @property runningAppId id of the app currently streaming on the host, or `null`.
 * @property isLoading true while the app list is being fetched.
 * @property message a transient one-line notice, or `null`.
 */
data class AppsUiState(
    val host: KnownHost? = null,
    val apps: List<HostApp> = emptyList(),
    val runningAppId: String? = null,
    val isLoading: Boolean = false,
    val message: String? = null,
) {
    /** Title for the screen — the host's name, or a neutral fallback while it loads. */
    val title: String get() = host?.name ?: "Apps"

    /** True when the host is reachable but genuinely offers nothing. */
    val isEmpty: Boolean get() = !isLoading && apps.isEmpty()
}

/**
 * Drives the Apps screen for a single host.
 *
 * The app list itself comes from [AppCatalogProvider], which the protocol layer implements later;
 * until then the stub returns the synthetic Desktop entry so the grid, the running indicator and
 * the launch hook are all exercised.
 */
class AppsViewModel(
    private val hostId: String,
    private val hostRepository: HostRepository,
    private val catalogProvider: AppCatalogProvider,
    private val statusProvider: HostStatusProvider,
) : ViewModel() {

    private val state = MutableStateFlow(AppsUiState(isLoading = true))

    /** The state the screen renders. */
    val uiState: StateFlow<AppsUiState> = state.asStateFlow()

    init {
        refresh()
    }

    /** Re-reads the host record, its status and its app list. */
    fun refresh() {
        viewModelScope.launch {
            state.value = state.value.copy(isLoading = true)
            val host = hostRepository.snapshot().firstOrNull { it.uuid == hostId }
            if (host == null) {
                state.value = AppsUiState(isLoading = false, message = "That host is no longer saved.")
                return@launch
            }
            val status: HostStatus = statusProvider.probe(host)
            val apps = catalogProvider.listApps(host).sortedWith(desktopFirst)
            state.value = AppsUiState(
                host = host,
                apps = apps,
                runningAppId = status.runningAppId,
                isLoading = false,
                message = state.value.message,
            )
        }
    }

    /**
     * Asks the host to quit whatever it is currently running.
     *
     * A failure is reported to the user rather than thrown — the host may simply have gone away.
     */
    fun quitRunningApp() {
        val host = state.value.host ?: return
        viewModelScope.launch {
            val quit = catalogProvider.quitRunningApp(host)
            state.value = state.value.copy(
                runningAppId = if (quit) null else state.value.runningAppId,
                message = if (quit) "Stopped the running app." else "Could not stop the running app.",
            )
            if (quit) refresh()
        }
    }

    /** Clears the transient message after the screen has shown it. */
    fun consumeMessage() {
        state.value = state.value.copy(message = null)
    }

    companion object {
        /** Desktop always sorts first; everything else is alphabetical. */
        private val desktopFirst: Comparator<HostApp> =
            compareByDescending<HostApp> { it.isDesktop }.thenBy { it.name.lowercase() }

        /**
         * Builds the production view model for [hostId] from [ServiceLocator].
         *
         * @param hostId the [KnownHost.uuid] whose library should be shown.
         */
        fun factory(hostId: String): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                AppsViewModel(
                    hostId = hostId,
                    hostRepository = ServiceLocator.hostRepository,
                    catalogProvider = ServiceLocator.appCatalogProvider,
                    statusProvider = ServiceLocator.hostStatusProvider,
                )
            }
        }
    }
}
