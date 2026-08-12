package com.voidlink.android.ui.diagnostics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.voidlink.android.data.ConnectionTester
import com.voidlink.android.data.HostRepository
import com.voidlink.android.data.KnownHost
import com.voidlink.android.data.LinkQuality
import com.voidlink.android.data.LinkTestProgress
import com.voidlink.android.data.ThroughputEvidence
import com.voidlink.android.data.ThroughputFailure
import com.voidlink.android.data.ThroughputMode
import com.voidlink.android.data.ThroughputProgress
import com.voidlink.android.di.ServiceLocator
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Where the Tier 1 link measurement has got to. */
enum class LinkPhase {
    /** Not started. */
    IDLE,

    /** Sampling. */
    RUNNING,

    /** Finished with figures. */
    DONE,

    /** The host never answered, so there was nothing to sample. */
    FAILED,
}

/** Where the Tier 2 throughput measurement has got to. */
enum class ThroughputPhase {
    /** Never run — the normal state, since it needs the user to start `iperf3 -s` first. */
    IDLE,

    /** Opening the control connection. */
    CONNECTING,

    /** Transferring. */
    RUNNING,

    /** Finished with a figure. */
    DONE,

    /** Did not produce a figure; [ConnectionTestUiState.throughputFailure] says why. */
    FAILED,
}

/**
 * Everything the connection-test screen draws.
 *
 * @property hostName the PC being measured.
 * @property linkPhase progress of the latency/jitter measurement.
 * @property samplesDone how many round trips have come back.
 * @property samplesTotal how many will be attempted.
 * @property lastRoundTripMs the most recent round trip, for a live read-out.
 * @property link the finished link statistics, or `null` until they are.
 * @property linkError why the link test could not run.
 * @property mode which throughput test the user has selected.
 * @property port the iperf3 port to use.
 * @property throughputPhase progress of the throughput measurement.
 * @property elapsedSeconds how far into the transfer we are.
 * @property totalSeconds how long the transfer will run.
 * @property liveMegabitsPerSecond the running rate, for a live read-out.
 * @property throughput the finished throughput evidence, or `null`.
 * @property throughputFailure why the throughput test produced nothing.
 * @property throughputDetail the underlying message behind [throughputFailure].
 */
data class ConnectionTestUiState(
    val hostName: String = "",
    val linkPhase: LinkPhase = LinkPhase.IDLE,
    val samplesDone: Int = 0,
    val samplesTotal: Int = ConnectionTester.DEFAULT_LINK_SAMPLES,
    val lastRoundTripMs: Long? = null,
    val link: LinkQuality? = null,
    val linkError: String? = null,
    val mode: ThroughputMode = ThroughputMode.PACED_UDP,
    val port: Int = ConnectionTester.DEFAULT_IPERF_PORT,
    val throughputPhase: ThroughputPhase = ThroughputPhase.IDLE,
    val elapsedSeconds: Double = 0.0,
    val totalSeconds: Int = ConnectionTester.DEFAULT_TEST_SECONDS,
    val liveMegabitsPerSecond: Double = 0.0,
    val throughput: ThroughputEvidence? = null,
    val throughputFailure: ThroughputFailure? = null,
    val throughputDetail: String? = null,
) {
    /** True while either measurement is in flight, which is when Cancel is meaningful. */
    val isBusy: Boolean
        get() = linkPhase == LinkPhase.RUNNING ||
            throughputPhase == ThroughputPhase.CONNECTING ||
            throughputPhase == ThroughputPhase.RUNNING
}

/**
 * Drives the connection test for one host.
 *
 * Owns only the measuring. It deliberately does **not** write settings: which scope an applied
 * bitrate belongs in — the global set or one host's override — is a question the settings panel
 * already answers, and duplicating that decision here is how the two would drift apart.
 */
class ConnectionTestViewModel(
    private val hostId: String,
    private val hostRepository: HostRepository,
    private val tester: ConnectionTester,
) : ViewModel() {

    private val state = MutableStateFlow(ConnectionTestUiState())

    /** The state the screen renders. */
    val uiState: StateFlow<ConnectionTestUiState> = state.asStateFlow()

    private var linkJob: Job? = null
    private var throughputJob: Job? = null

    init {
        viewModelScope.launch {
            val host = host()
            if (host != null) state.update { it.copy(hostName = host.name) }
        }
        measureLink()
    }

    /** Runs, or re-runs, the Tier 1 link measurement. */
    fun measureLink() {
        linkJob?.cancel()
        state.update {
            it.copy(
                linkPhase = LinkPhase.RUNNING,
                samplesDone = 0,
                lastRoundTripMs = null,
                link = null,
                linkError = null,
            )
        }
        linkJob = viewModelScope.launch {
            val host = host()
            if (host == null) {
                state.update {
                    it.copy(
                        linkPhase = LinkPhase.FAILED,
                        linkError = "This PC is no longer in the host list.",
                    )
                }
                return@launch
            }
            tester.measureLink(host, ConnectionTester.DEFAULT_LINK_SAMPLES).collect { progress ->
                when (progress) {
                    is LinkTestProgress.Sampled -> state.update {
                        it.copy(
                            samplesDone = progress.completed,
                            samplesTotal = progress.total,
                            lastRoundTripMs = progress.lastRoundTripMs,
                        )
                    }

                    is LinkTestProgress.Finished -> state.update {
                        it.copy(linkPhase = LinkPhase.DONE, link = progress.quality)
                    }

                    is LinkTestProgress.Unreachable -> state.update {
                        it.copy(linkPhase = LinkPhase.FAILED, linkError = progress.detail)
                    }
                }
            }
        }
    }

    /**
     * Runs the Tier 2 throughput measurement.
     *
     * @param targetMbps the rate the paced UDP test should drive at. The caller passes the **wire**
     *   cost of the bitrate it is considering — error correction and audio included — because that
     *   is what the network will actually be asked to carry, and testing the video figure alone
     *   would pass a rate that then fails in practice.
     */
    fun measureThroughput(targetMbps: Double) {
        throughputJob?.cancel()
        state.update {
            it.copy(
                throughputPhase = ThroughputPhase.CONNECTING,
                elapsedSeconds = 0.0,
                liveMegabitsPerSecond = 0.0,
                throughput = null,
                throughputFailure = null,
                throughputDetail = null,
            )
        }
        throughputJob = viewModelScope.launch {
            val host = host()
            if (host == null) {
                state.update {
                    it.copy(
                        throughputPhase = ThroughputPhase.FAILED,
                        throughputFailure = ThroughputFailure.UNREACHABLE,
                        throughputDetail = "This PC is no longer in the host list.",
                    )
                }
                return@launch
            }
            val current = state.value
            tester.measureThroughput(
                host = host,
                port = current.port,
                mode = current.mode,
                seconds = current.totalSeconds,
                targetMbps = targetMbps,
            ).collect { progress ->
                when (progress) {
                    ThroughputProgress.Connecting -> state.update {
                        it.copy(throughputPhase = ThroughputPhase.CONNECTING)
                    }

                    is ThroughputProgress.Running -> state.update {
                        it.copy(
                            throughputPhase = ThroughputPhase.RUNNING,
                            elapsedSeconds = progress.elapsedSeconds,
                            totalSeconds = progress.totalSeconds,
                            liveMegabitsPerSecond = progress.megabitsPerSecond,
                        )
                    }

                    is ThroughputProgress.Finished -> state.update {
                        it.copy(
                            throughputPhase = ThroughputPhase.DONE,
                            throughput = progress.evidence,
                        )
                    }

                    is ThroughputProgress.Failed -> state.update {
                        it.copy(
                            throughputPhase = ThroughputPhase.FAILED,
                            throughputFailure = progress.reason,
                            throughputDetail = progress.detail,
                        )
                    }
                }
            }
        }
    }

    /** Chooses which throughput test to run. Ignored while one is in flight. */
    fun setMode(mode: ThroughputMode) {
        if (state.value.isBusy) return
        state.update { it.copy(mode = mode, throughputPhase = ThroughputPhase.IDLE) }
    }

    /** Sets the iperf3 port. Out-of-range values are ignored rather than silently clamped. */
    fun setPort(port: Int) {
        if (port !in MIN_PORT..MAX_PORT) return
        state.update { it.copy(port = port) }
    }

    /**
     * Stops whatever is running.
     *
     * Cancelling the job is what actually tears the sockets down: the iperf3 client registers a
     * completion handler that closes them, so a blocked read does not have to time out first.
     */
    fun cancel() {
        linkJob?.cancel()
        throughputJob?.cancel()
        linkJob = null
        throughputJob = null
        state.update {
            it.copy(
                linkPhase = if (it.linkPhase == LinkPhase.RUNNING) {
                    if (it.samplesDone > 0) LinkPhase.DONE else LinkPhase.IDLE
                } else {
                    it.linkPhase
                },
                throughputPhase = if (
                    it.throughputPhase == ThroughputPhase.RUNNING ||
                    it.throughputPhase == ThroughputPhase.CONNECTING
                ) {
                    ThroughputPhase.FAILED
                } else {
                    it.throughputPhase
                },
                throughputFailure = if (
                    it.throughputPhase == ThroughputPhase.RUNNING ||
                    it.throughputPhase == ThroughputPhase.CONNECTING
                ) {
                    ThroughputFailure.CANCELLED
                } else {
                    it.throughputFailure
                },
                throughputDetail = if (
                    it.throughputPhase == ThroughputPhase.RUNNING ||
                    it.throughputPhase == ThroughputPhase.CONNECTING
                ) {
                    "Stopped before the transfer finished."
                } else {
                    it.throughputDetail
                },
            )
        }
    }

    private suspend fun host(): KnownHost? =
        hostRepository.snapshot().firstOrNull { it.uuid == hostId }

    companion object {
        private const val MIN_PORT = 1
        private const val MAX_PORT = 65_535

        /**
         * Builds the production view model for [hostId] from [ServiceLocator].
         *
         * @param hostId the [KnownHost.uuid] to measure the path to.
         */
        fun factory(hostId: String): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                ConnectionTestViewModel(
                    hostId = hostId,
                    hostRepository = ServiceLocator.hostRepository,
                    tester = ServiceLocator.connectionTester,
                )
            }
        }
    }
}
