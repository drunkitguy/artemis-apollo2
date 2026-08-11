package com.voidlink.android.ui.hosts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.voidlink.android.data.HostReachability
import com.voidlink.android.data.HostRepository
import com.voidlink.android.data.HostStatus
import com.voidlink.android.data.HostStatusProvider
import com.voidlink.android.data.HostWaker
import com.voidlink.android.data.KnownHost
import com.voidlink.android.di.ServiceLocator
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One host as the Hosts grid needs to draw it: the persisted record joined with whatever the
 * status provider last reported.
 *
 * @property host the stored record.
 * @property status the most recent probe result, or [HostStatus.Unknown] before the first probe.
 */
data class HostCardState(
    val host: KnownHost,
    val status: HostStatus = HostStatus.Unknown,
) {
    /** True when the host answered its last probe. */
    val isOnline: Boolean get() = status.isOnline

    /**
     * True while the first probe of this host is still outstanding.
     *
     * A status provider only ever reports ONLINE or OFFLINE, so UNKNOWN means "not asked yet".
     * The distinction matters: on a cold start every card would otherwise claim the PC is offline
     * for as long as the network takes to answer, which reads as a broken app.
     */
    val isChecking: Boolean get() = status.reachability == HostReachability.UNKNOWN

    /**
     * True when the host is reachable but has not been paired.
     *
     * Both the stored flag and the host's own report have to agree — a host that forgot this
     * client reports `paired = false` even though the local record still says otherwise.
     */
    val needsPairing: Boolean get() = !host.paired || (isOnline && !status.paired)

    /** Name of the app streaming on this host right now, when it is online and running one. */
    val runningAppName: String?
        get() = status.runningAppName?.takeIf { isOnline && it.isNotBlank() }

    /** The footer button the card should show. */
    val primaryAction: HostAction
        get() = when {
            isChecking -> HostAction.CHECKING
            !isOnline -> HostAction.WAKE
            needsPairing -> HostAction.PAIR
            else -> HostAction.CONNECT
        }

    /**
     * Whether [primaryAction] can actually be carried out.
     *
     * Wake is the interesting case: without a MAC there is no packet to send. The button is still
     * drawn — hiding it would leave the user wondering where the feature went — but it is muted
     * and inert, and because it stops consuming taps the press falls through to the card, which
     * answers with the reason (spec §2.2, "Offline (MAC unknown)").
     */
    val isActionable: Boolean
        get() = when (primaryAction) {
            HostAction.CHECKING -> false
            HostAction.WAKE -> host.canWakeOnLan
            HostAction.PAIR, HostAction.CONNECT -> true
        }
}

/** The action offered by a host card's full-width footer button. */
enum class HostAction {
    /** No probe has answered yet — the card is waiting, and offers nothing. */
    CHECKING,

    /** Online and unpaired — start PIN pairing. */
    PAIR,

    /** Offline — try to wake the machine. */
    WAKE,

    /** Online and paired — open the app list. */
    CONNECT,
}

/**
 * UI state of the Hosts screen.
 *
 * @property hosts every known host, already ordered for display.
 * @property isDiscovering true while a discovery/probe sweep is running.
 * @property message a transient one-line notice to surface to the user, or `null`.
 * @property pairingHost the host a PIN dialog is currently open for, or `null`.
 * @property pairingPin the PIN the user should type on the host, when pairing is in progress.
 */
data class HostsUiState(
    val hosts: List<HostCardState> = emptyList(),
    val isDiscovering: Boolean = false,
    val message: String? = null,
    val pairingHost: KnownHost? = null,
    val pairingPin: String? = null,
) {
    /** True when there is nothing at all to draw in the grid. */
    val isEmpty: Boolean get() = hosts.isEmpty()
}

/**
 * Drives the Hosts screen.
 *
 * Owns the join between the persisted host list ([HostRepository]) and live reachability
 * ([HostStatusProvider]). It never talks to a socket itself; with the stub providers in place every
 * host simply reports offline and the screen stays fully usable.
 */
class HostsViewModel(
    private val hostRepository: HostRepository,
    private val statusProvider: HostStatusProvider,
    private val hostWaker: HostWaker,
) : ViewModel() {

    private val statuses = MutableStateFlow<Map<String, HostStatus>>(emptyMap())
    private val discovering = MutableStateFlow(false)
    private val messages = MutableStateFlow<String?>(null)
    private val pairing = MutableStateFlow<Pair<KnownHost, String>?>(null)

    private var refreshJob: Job? = null

    /** The state the screen renders. */
    val uiState: StateFlow<HostsUiState> = combine(
        hostRepository.hosts,
        statuses,
        discovering,
        messages,
        pairing,
    ) { hosts, statusMap, isDiscovering, message, pairingState ->
        HostsUiState(
            hosts = hosts.map { host ->
                HostCardState(host = host, status = statusMap[host.uuid] ?: HostStatus.Unknown)
            },
            isDiscovering = isDiscovering,
            message = message,
            pairingHost = pairingState?.first,
            pairingPin = pairingState?.second,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = HostsUiState(),
    )

    init {
        refresh()
    }

    /**
     * Re-probes every known host and runs a bounded discovery sweep.
     *
     * Calling this while a sweep is already running cancels the previous one, so a user tapping
     * refresh repeatedly cannot pile up work.
     */
    fun refresh() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            discovering.value = true
            try {
                probeAll()
                withTimeoutOrNull(DISCOVERY_WINDOW_MILLIS) {
                    statusProvider.discover().collect { discovered ->
                        hostRepository.mergeDiscovered(discovered, System.currentTimeMillis())
                    }
                }
                probeAll()
            } finally {
                discovering.value = false
            }
        }
    }

    /** Adds a host the user typed in and immediately probes it. */
    fun addManualHost(address: String, name: String?) {
        if (address.isBlank()) {
            messages.value = "Enter an address first."
            return
        }
        viewModelScope.launch {
            val host = hostRepository.addManualHost(address, name)
            messages.value = "Added ${host.name}."
            probe(host)
        }
    }

    /** Renames a host. */
    fun rename(uuid: String, newName: String) {
        viewModelScope.launch { hostRepository.rename(uuid, newName) }
    }

    /** Forgets a host entirely. */
    fun delete(uuid: String) {
        viewModelScope.launch {
            hostRepository.delete(uuid)
            statuses.update { it - uuid }
        }
    }

    /** Drops the local pairing so the host can be paired again. */
    fun unpair(uuid: String) {
        viewModelScope.launch {
            hostRepository.markUnpaired(uuid)
            messages.value = "Unpaired."
        }
    }

    /** Broadcasts a Wake-on-LAN packet, if the host has a MAC on record. */
    fun wake(host: KnownHost) {
        if (!host.canWakeOnLan) {
            messages.value = "No MAC address on record for ${host.name}."
            return
        }
        viewModelScope.launch {
            val sent = hostWaker.wake(host)
            messages.value = if (sent) {
                "Wake packet sent to ${host.name}."
            } else {
                "Could not send a wake packet."
            }
        }
    }

    /**
     * Opens the PIN pairing sheet for [host].
     *
     * The PIN is generated locally and displayed for the user to type on the host; the handshake
     * that consumes it belongs to the protocol layer, which will drive this state later.
     */
    fun beginPairing(host: KnownHost) {
        pairing.value = host to generatePin()
    }

    /** Closes the PIN pairing sheet without pairing. */
    fun cancelPairing() {
        pairing.value = null
    }

    /** Clears the transient message after the screen has shown it. */
    fun consumeMessage() {
        messages.value = null
    }

    private suspend fun probeAll() {
        hostRepository.snapshot().forEach { probe(it) }
    }

    private suspend fun probe(host: KnownHost) {
        val status = statusProvider.probe(host)
        // A manual add probes on its own coroutine while a refresh sweep may be running, so the map
        // has to be updated atomically or one of the two results is silently dropped.
        statuses.update { it + (host.uuid to status) }
        if (status.isOnline) {
            hostRepository.updateHost(host.uuid) { stored ->
                stored.markSeen(System.currentTimeMillis())
            }
        }
    }

    private fun generatePin(): String = (0 until PIN_LENGTH)
        .map { PIN_ALPHABET.random() }
        .joinToString(separator = "")

    companion object {
        private const val STOP_TIMEOUT_MILLIS = 5_000L
        private const val DISCOVERY_WINDOW_MILLIS = 4_000L
        private const val PIN_LENGTH = 4
        private const val PIN_ALPHABET = "0123456789"

        /** Builds the production view model from [ServiceLocator]. */
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                HostsViewModel(
                    hostRepository = ServiceLocator.hostRepository,
                    statusProvider = ServiceLocator.hostStatusProvider,
                    hostWaker = ServiceLocator.hostWaker,
                )
            }
        }
    }
}
