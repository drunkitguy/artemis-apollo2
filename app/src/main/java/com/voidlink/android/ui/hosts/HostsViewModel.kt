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
import com.voidlink.android.protocol.ProtocolLog
import com.voidlink.android.protocol.bridge.HostPairingCoordinator
import com.voidlink.android.protocol.http.HostTrustStore
import com.voidlink.android.protocol.pairing.PairProgress
import com.voidlink.android.protocol.pairing.PairResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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

    /**
     * The status line's text.
     *
     * A host reports *that* something is streaming (`currentgame`) on every probe, but its title
     * only with an extra `/applist` round trip per host — too expensive for a list that re-probes
     * on a timer. So the card says a session is running even when it cannot name it, which is the
     * part that actually changes what the user does: tapping Connect will resume, not start fresh.
     */
    val statusLabel: String
        get() = when {
            !isOnline -> "Offline"
            runningAppName != null -> "Online · $runningAppName"
            status.runningAppId != null -> "Online · In game"
            else -> "Online"
        }

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

    /**
     * The PC accepted this device, but no secure connection to it can be established at all.
     *
     * A separate outcome because the remedy is nothing to do with pairing, and offering "Try again"
     * as the obvious action would send the user round a loop that cannot terminate.
     */
    HOST_TLS_UNREACHABLE,

    /** The user backed out. */
    CANCELLED,
}

/**
 * What the dialog is doing after the host has accepted us.
 *
 * Present only during the post-phase-4 stretch, which is the one part of pairing that can take
 * tens of seconds with nothing to show — and the one part where a user who gives up and cancels
 * used to destroy a pairing that had already succeeded.
 */
sealed interface PairingVerification {

    /**
     * Confirming over a secure connection that the pairing works.
     *
     * @property attempt 1-based attempt now running.
     * @property totalAttempts how many will be tried.
     */
    data class Confirming(val attempt: Int, val totalAttempts: Int) : PairingVerification

    /** Every secure call timed out; the transport self-test is running. */
    data object Diagnosing : PairingVerification
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
    val verification: PairingVerification? = null,
    val hostHasAccepted: Boolean = false,
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

    /**
     * Whether cancelling now would leave the pairing intact.
     *
     * The dialog says so out loud from this point, because the honest answer changes halfway
     * through and a user who believes Cancel undoes everything will hesitate to press it — or press
     * it expecting a clean slate and get something else.
     */
    val cancelIsHarmless: Boolean get() = hostHasAccepted && !isFinished
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
    private val trustStore: HostTrustStore,
) : ViewModel() {

    private val statuses = MutableStateFlow<Map<String, HostStatus>>(emptyMap())
    private val discovering = MutableStateFlow(false)
    private val messages = MutableStateFlow<String?>(null)
    private val pairing = MutableStateFlow<PairingUiState?>(null)

    private var refreshJob: Job? = null
    private var pairingJob: Job? = null

    /** The transient parts of the state, pre-combined so the main [combine] stays within arity. */
    private val transientState: Flow<Triple<Boolean, String?, PairingUiState?>> =
        combine(discovering, messages, pairing) { isDiscovering, message, pairingState ->
            Triple(isDiscovering, message, pairingState)
        }

    /**
     * Re-probes on a slow cadence for as long as the screen is actually watching.
     *
     * A PC that boots after the app opened would otherwise sit at "Offline" until the user thought
     * to hit refresh. This lives in [uiState]'s upstream deliberately: with
     * [SharingStarted.WhileSubscribed] the loop starts when the screen subscribes and is cancelled
     * shortly after it stops, so a backgrounded app is not quietly probing the network.
     */
    private val autoProbe: Flow<Unit> = flow {
        // Emit first: `combine` produces nothing until every source has a value.
        emit(Unit)
        while (true) {
            delay(AUTO_PROBE_INTERVAL_MILLIS)
            try {
                probeAll()
            } catch (cancellation: CancellationException) {
                // The screen stopped watching; that is not a failure.
                throw cancellation
            } catch (failure: Throwable) {
                // This loop is part of the state flow's upstream, so letting anything escape would
                // tear down the whole screen's state over one bad probe. The next tick retries.
                ProtocolLog.w(ProtocolLog.TAG_HTTP, "Background probe sweep failed", failure)
            }
            emit(Unit)
        }
    }

    /** The state the screen renders. */
    val uiState: StateFlow<HostsUiState> = combine(
        hostRepository.hosts,
        statuses,
        transientState,
        autoProbe,
    ) { hosts, statusMap, transient, _ ->
        HostsUiState(
            hosts = hosts.map { host ->
                HostCardState(host = host, status = statusMap[host.uuid] ?: HostStatus.Unknown)
            },
            isDiscovering = transient.first,
            message = transient.second,
            pairing = transient.third,
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

    /**
     * Unpairs from [host] on the host as well as locally.
     *
     * Clearing only the local flag would leave the PC still trusting this device and our pinned
     * certificate still on disk, so "Unpair" would not actually unpair anything — and the next
     * probe, which asks the trust store rather than the flag, would quietly mark it paired again.
     */
    fun unpair(host: KnownHost) {
        viewModelScope.launch {
            val toldTheHost = pairingCoordinator.unpair(host)
            hostRepository.markUnpaired(host.uuid)
            messages.value = if (toldTheHost) {
                "Unpaired from ${host.name}."
            } else {
                "Forgot ${host.name} on this device; it may still be paired on the PC."
            }
            probe(host)
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
                    is PairProgress.Phase -> pairing.update { current ->
                        if (current == null) {
                            null
                        } else {
                            current.copy(
                                phase = progress.phase,
                                // Phase 5 only ever starts once phase 4 has answered, which is the
                                // point at which the PC has committed to this device.
                                hostHasAccepted = current.hostHasAccepted ||
                                    progress.phase >= HOST_HAS_ACCEPTED_FROM_PHASE,
                                verification = null,
                            )
                        }
                    }
                    is PairProgress.Verifying -> pairing.update {
                        it?.copy(
                            hostHasAccepted = true,
                            verification = PairingVerification.Confirming(
                                attempt = progress.attempt,
                                totalAttempts = progress.totalAttempts,
                            ),
                        )
                    }
                    is PairProgress.Diagnosing -> pairing.update {
                        it?.copy(
                            hostHasAccepted = true,
                            verification = PairingVerification.Diagnosing,
                        )
                    }
                    is PairProgress.Done -> onPairingFinished(host, progress)
                }
            }
        }
    }

    /**
     * Abandons a pairing attempt.
     *
     * Before the host has accepted us, cancelling the job is what makes the host stop showing its
     * PIN prompt: the engine catches the cancellation and runs `/unpair` under
     * [kotlinx.coroutines.NonCancellable].
     *
     * **After** it has accepted us, cancelling stops the waiting and nothing else — the PC has
     * recorded this device and the pinned certificate stays on disk. So instead of discarding
     * anything, this immediately re-probes the host: the probe's HTTPS check is the same proof the
     * dialog was waiting for, and it will promote the record to paired without the user doing
     * another thing. That turns "I gave up on a stuck dialog" into "it just worked".
     */
    fun cancelPairing() {
        val abandoned = pairing.value
        pairingJob?.cancel()
        pairingJob = null
        pairing.value = null
        if (abandoned != null && abandoned.hostHasAccepted && abandoned.outcome == null) {
            ProtocolLog.i(
                ProtocolLog.TAG_PAIR,
                "Pairing dialog dismissed after ${abandoned.host.name} had already accepted this " +
                    "device; re-probing to confirm and adopt it rather than discarding it",
            )
            viewModelScope.launch { probe(abandoned.host) }
        }
    }

    /** Retries after a failed attempt, from the same dialog. */
    fun retryPairing() {
        val host = pairing.value?.host ?: return
        beginPairing(host)
    }

    private suspend fun onPairingFinished(host: KnownHost, done: PairProgress.Done) {
        val outcome = done.result.toOutcome()
        val hadBeenAccepted = pairing.value?.hostHasAccepted == true
        pairing.update { it?.copy(outcome = outcome, detail = done.detail, verification = null) }
        if (outcome != PairingOutcome.PAIRED) {
            // A failure *after* the host accepted us leaves a real pairing and a real pinned
            // certificate behind. Probe once now: if the host's secure service comes good, the
            // record is promoted without the user being asked to pair a PC that already trusts
            // them. Costs one probe and can only ever improve the state.
            if (hadBeenAccepted) probe(host)
            return
        }

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
        PairResult.HOST_TLS_UNREACHABLE -> PairingOutcome.HOST_TLS_UNREACHABLE
        PairResult.CANCELLED -> PairingOutcome.CANCELLED
    }

    /** Clears the transient message after the screen has shown it. */
    fun consumeMessage() {
        messages.value = null
    }

    /**
     * Probes every saved host, a few at a time.
     *
     * Sequentially, a handful of switched-off PCs serialise their connect timeouts and the sweep
     * takes longer than the discovery window that follows it — so a machine that is actually on
     * appears late for no reason. The bound keeps a large host list from opening every socket at
     * once.
     */
    private suspend fun probeAll() = coroutineScope {
        val gate = Semaphore(PROBE_PARALLELISM)
        hostRepository.snapshot()
            .map { host -> async { gate.withPermit { probe(host) } } }
            .awaitAll()
    }

    private suspend fun probe(host: KnownHost) {
        val status = statusProvider.probe(host)
        val uuid = reconcileIdentity(host, status)
        // Probes run concurrently and a manual add probes on its own coroutine, so the map has to
        // be updated atomically or one of the results is silently dropped.
        statuses.update { it + (uuid to status) }
        if (status.isOnline) {
            hostRepository.updateHost(uuid) { stored ->
                // A paired host answers over HTTPS with its real MAC; persisting it here is what
                // makes Wake-on-LAN possible later, once the PC is asleep and cannot be asked.
                stored.markSeen(System.currentTimeMillis()).withLearnedMac(status.macAddress)
            }
            adoptPairingTheHostConfirms(host, uuid, status)
        }
    }

    /**
     * Repairs the case where the PC trusts us but our own record says it does not.
     *
     * `status.paired` is only ever true because a client-certificate HTTPS request to the host
     * actually succeeded, and nothing but a paired client can do that — so the host's answer is
     * strictly better evidence than the stored flag. The disagreement is reachable whenever a
     * pairing completes on the PC but its final confirmation is lost on the way back (a
     * `pairchallenge` that times out is the known way), and without this the user is asked to pair
     * a PC that has already paired with them, over and over.
     *
     * Only ever promotes. A host that has genuinely forgotten us is demoted by the probe itself,
     * which is a different and already-handled path.
     */
    private suspend fun adoptPairingTheHostConfirms(
        host: KnownHost,
        uuid: String,
        status: HostStatus,
    ) {
        if (!status.paired || host.paired) return
        ProtocolLog.i(
            ProtocolLog.TAG_PAIR,
            "${host.name} accepts our client certificate but was stored as unpaired; " +
                "adopting the host's answer and marking it paired",
        )
        hostRepository.markPaired(uuid)
    }

    /**
     * Folds a locally-keyed host onto the identity the host itself reported.
     *
     * @return the uuid the host is stored under afterwards, which is what the status map must be
     *   keyed by.
     */
    private suspend fun reconcileIdentity(host: KnownHost, status: HostStatus): String {
        val realId = status.uniqueId?.takeIf { it.isNotBlank() && it != host.uuid }
            ?: return host.uuid
        // The certificate moves first: if the record were re-filed and then the move failed, the
        // host would claim to be paired while every HTTPS call failed against a missing pin.
        trustStore.rekey(host.uuid, realId)
        if (!hostRepository.reconcileIdentity(host.uuid, realId)) return host.uuid
        statuses.update { it - host.uuid }
        return realId
    }

    companion object {
        private const val STOP_TIMEOUT_MILLIS = 5_000L
        private const val DISCOVERY_WINDOW_MILLIS = 4_000L

        /**
         * How often the visible host list re-probes.
         *
         * Long enough that a phone is not waking its radio constantly, short enough that a PC the
         * user has just switched on turns green before they give up and press refresh.
         */
        private const val AUTO_PROBE_INTERVAL_MILLIS = 20_000L

        /** How many hosts are probed at once; each probe is a socket and a timeout. */
        private const val PROBE_PARALLELISM = 4

        /** How long "Paired" stays on screen before the dialog closes itself. */
        private const val PAIRED_DISMISS_MILLIS = 900L

        /**
         * The phase from which the PC has committed to this device.
         *
         * Phase 5 is only ever entered after phase 4 answered `<paired>1</paired>`, so seeing it is
         * how the UI knows that cancelling is now harmless and that a failure still leaves a real
         * pairing behind.
         */
        private const val HOST_HAS_ACCEPTED_FROM_PHASE = 5

        /** Builds the production view model from [ServiceLocator]. */
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                HostsViewModel(
                    hostRepository = ServiceLocator.hostRepository,
                    statusProvider = ServiceLocator.hostStatusProvider,
                    hostWaker = ServiceLocator.hostWaker,
                    pairingCoordinator = ServiceLocator.pairingCoordinator,
                    trustStore = ServiceLocator.hostTrustStore,
                )
            }
        }
    }
}
