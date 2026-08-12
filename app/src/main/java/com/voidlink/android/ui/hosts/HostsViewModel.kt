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
import com.voidlink.android.protocol.bridge.HostPairingCoordinator
import com.voidlink.android.protocol.pairing.PairProgress
import com.voidlink.android.protocol.pairing.PairResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
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
 * How a pairing attempt ended, in the UI's own vocabulary.
 *
 * Mirrors the protocol's `PairResult` so the screen never has to import the protocol layer, and so
 * that the mapping between the two is one exhaustive, reviewable `when`.
 */
enum class PairingOutcome {
    /** The host now trusts this client. */
    PAIRED,

    /** The PIN typed on the host did not match. */
    PIN_WRONG,

    /** Another device is already pairing with this host. */
    ALREADY_IN_PROGRESS,

    /** The handshake failed for some other reason. */
    FAILED,

    /** The user backed out. */
    CANCELLED,
}

/**
 * What the pairing dialog is showing.
 *
 * @property host the host being paired with.
 * @property pin the PIN the user must type on the host, once the handshake has generated it.
 *   Null only in the moment between opening the dialog and the engine producing it.
 * @property phase the handshake phase last reported, 1..[PAIRING_PHASE_COUNT]; 0 before the first.
 * @property outcome the terminal result, or `null` while the attempt is still running.
 * @property detail a short explanation for a failure, when the protocol supplied one.
 */
data class PairingUiState(
    val host: KnownHost,
    val pin: String? = null,
    val phase: Int = 0,
    val outcome: PairingOutcome? = null,
    val detail: String? = null,
) {
    /** True once the attempt has finished, whichever way it went. */
    val isFinished: Boolean get() = outcome != null

    /**
     * True while the host is waiting for the user to type the PIN.
     *
     * Phase 1 blocks on the host's own prompt for as long as the user takes, so this is the state
     * the dialog spends most of its life in.
     */
    val isAwaitingPin: Boolean get() = !isFinished && pin != null && phase <= 1
}

/** How many phases the pairing handshake reports, used for the "Step 3 of 5" counter. */
const val PAIRING_PHASE_COUNT: Int = 5

/**
 * UI state of the Hosts screen.
 *
 * @property hosts every known host, already ordered for display.
 * @property isDiscovering true while a discovery/probe sweep is running.
 * @property message a transient one-line notice to surface to the user, or `null`.
 * @property pairing the state of the pairing dialog, or `null` when it is closed.
 */
data class HostsUiState(
    val hosts: List<HostCardState> = emptyList(),
    val isDiscovering: Boolean = false,
    val message: String? = null,
    val pairing: PairingUiState? = null,
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
    private val pairingCoordinator: HostPairingCoordinator,
) : ViewModel() {

    private val statuses = MutableStateFlow<Map<String, HostStatus>>(emptyMap())
    private val discovering = MutableStateFlow(false)
    private val messages = MutableStateFlow<String?>(null)
    private val pairing = MutableStateFlow<PairingUiState?>(null)

    private var refreshJob: Job? = null
    private var pairingJob: Job? = null

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
            pairing = pairingState,
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
     * Runs the real five-phase pairing handshake against [host].
     *
     * The PIN comes from the handshake, never from here: the host commits to a PIN derived from
     * the salt sent in phase 1, so a PIN invented by the UI would simply never match.
     */
    fun beginPairing(host: KnownHost) {
        pairingJob?.cancel()
        pairing.value = PairingUiState(host = host)
        pairingJob = viewModelScope.launch {
            // The HTTP calls already switch to Dispatchers.IO themselves, but the handshake also
            // does RSA and AES work between them; running the producer off the main thread keeps
            // that off the frame budget. Collection stays on main, so state updates do not hop.
            val progressFlow = pairingCoordinator.pair(host).flowOn(Dispatchers.Default)
            progressFlow.collect { progress ->
                when (progress) {
                    is PairProgress.PinReady -> pairing.update { it?.copy(pin = progress.pin) }
                    is PairProgress.Phase -> pairing.update { it?.copy(phase = progress.phase) }
                    is PairProgress.Done -> onPairingFinished(host, progress)
                }
            }
        }
    }

    /**
     * Abandons a pairing attempt.
     *
     * Cancelling the job is what makes the host stop showing its PIN prompt: the engine catches the
     * cancellation and runs `/unpair` under [kotlinx.coroutines.NonCancellable]. Simply hiding the
     * dialog would leave the PC waiting, and a half-finished pairing wedges every later attempt.
     */
    fun cancelPairing() {
        pairingJob?.cancel()
        pairingJob = null
        pairing.value = null
    }

    /** Retries after a failed attempt, from the same dialog. */
    fun retryPairing() {
        val host = pairing.value?.host ?: return
        beginPairing(host)
    }

    private suspend fun onPairingFinished(host: KnownHost, done: PairProgress.Done) {
        val outcome = done.result.toOutcome()
        pairing.update { it?.copy(outcome = outcome, detail = done.detail) }
        if (outcome != PairingOutcome.PAIRED) return

        // Record the pairing and re-probe, so the card's footer becomes "Connect" and the app list
        // is reachable the moment the dialog closes rather than after the next manual refresh.
        hostRepository.markPaired(host.uuid)
        probe(host)
        messages.value = "Paired with ${host.name}."
        delay(PAIRED_DISMISS_MILLIS)
        // Only close the dialog if it is still showing this attempt's success; the user may have
        // dismissed it, or started pairing with a different host, while we were re-probing.
        pairing.update { current ->
            val showingThisSuccess = current != null &&
                current.host.uuid == host.uuid &&
                current.outcome == PairingOutcome.PAIRED
            if (showingThisSuccess) null else current
        }
    }

    private fun PairResult.toOutcome(): PairingOutcome = when (this) {
        PairResult.PAIRED -> PairingOutcome.PAIRED
        PairResult.PIN_WRONG -> PairingOutcome.PIN_WRONG
        PairResult.ALREADY_IN_PROGRESS -> PairingOutcome.ALREADY_IN_PROGRESS
        PairResult.FAILED -> PairingOutcome.FAILED
        PairResult.CANCELLED -> PairingOutcome.CANCELLED
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
                // A paired host answers over HTTPS with its real MAC; persisting it here is what
                // makes Wake-on-LAN possible later, once the PC is asleep and cannot be asked.
                stored.markSeen(System.currentTimeMillis()).withLearnedMac(status.macAddress)
            }
        }
    }

    companion object {
        private const val STOP_TIMEOUT_MILLIS = 5_000L
        private const val DISCOVERY_WINDOW_MILLIS = 4_000L

        /** How long "Paired" stays on screen before the dialog closes itself. */
        private const val PAIRED_DISMISS_MILLIS = 900L

        /** Builds the production view model from [ServiceLocator]. */
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                HostsViewModel(
                    hostRepository = ServiceLocator.hostRepository,
                    statusProvider = ServiceLocator.hostStatusProvider,
                    hostWaker = ServiceLocator.hostWaker,
                    pairingCoordinator = ServiceLocator.pairingCoordinator,
                )
            }
        }
    }
}
