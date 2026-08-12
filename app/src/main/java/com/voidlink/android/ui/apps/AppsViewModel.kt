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
import kotlinx.coroutines.flow.update
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
/**
 * Why a library came back empty.
 *
 * The three causes need three different things from the user — switch the PC on, pair with it, or
 * add a game to it — so showing one grey "No apps to show yet." for all of them is a dead end.
 */
enum class EmptyLibraryReason {
    /** The host did not answer. */
    UNREACHABLE,

    /** The host answered but does not trust this device, so `/applist` is not available. */
    UNPAIRED,

    /** The host answered and genuinely lists nothing. */
    NO_APPS,
}

data class AppsUiState(
    val host: KnownHost? = null,
    val apps: List<HostApp> = emptyList(),
    val runningAppId: String? = null,
    val isLoading: Boolean = false,
    val message: String? = null,
    val emptyReason: EmptyLibraryReason? = null,
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
            state.update { it.copy(isLoading = true) }
            val host = hostRepository.snapshot().firstOrNull { it.uuid == hostId }
            if (host == null) {
                state.update {
                    AppsUiState(
                        isLoading = false,
                        message = "That host is no longer saved.",
                        emptyReason = EmptyLibraryReason.UNREACHABLE,
                    )
                }
                return@launch
            }
            val status: HostStatus = statusProvider.probe(host)
            val apps = catalogProvider.listApps(host).sortedWith(HostApp.displayOrder)
            state.update { previous ->
                AppsUiState(
                    host = host,
                    apps = apps,
                    runningAppId = status.runningAppId,
                    isLoading = false,
                    // The probe already told us why an empty list is empty; the catalogue cannot,
                    // because "unreachable", "unpaired" and "no games" all arrive as an empty list.
                    emptyReason = when {
                        apps.isNotEmpty() -> null
                        !status.isOnline -> EmptyLibraryReason.UNREACHABLE
                        !status.paired -> EmptyLibraryReason.UNPAIRED
                        else -> EmptyLibraryReason.NO_APPS
                    },
                    // A notice set just before the refresh (e.g. "Stopped the running app.") has to
                    // survive it, or the user never gets to read the outcome of what they did.
                    message = previous.message,
                )
            }
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
            state.update { previous ->
                previous.copy(
                    runningAppId = if (quit) null else previous.runningAppId,
                    message = if (quit) {
                        "Stopped the running app."
                    } else {
                        "Could not stop the running app."
                    },
                )
            }
            if (quit) refresh()
        }
    }

    /**
     * Loads one tile's box art.
     *
     * Called per visible tile rather than for the whole library at once; the provider caches to
     * disk, so scrolling back to a tile does not go to the network again.
     */
    suspend fun boxArt(app: HostApp): ByteArray? {
        val host = state.value.host ?: return null
        return catalogProvider.boxArt(host, app.id)
    }

    /** Clears the transient message after the screen has shown it. */
    fun consumeMessage() {
        state.update { it.copy(message = null) }
    }

    companion object {
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
