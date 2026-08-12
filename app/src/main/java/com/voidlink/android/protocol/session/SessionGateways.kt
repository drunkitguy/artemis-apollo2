package com.voidlink.android.protocol.session

import com.voidlink.android.data.HostRepository
import com.voidlink.android.protocol.HostAddress
import com.voidlink.android.protocol.ProtocolLog
import com.voidlink.android.protocol.bridge.HostEndpointResolver
import com.voidlink.android.protocol.control.ControlLink
import com.voidlink.android.protocol.control.EnetControlLink
import com.voidlink.android.protocol.enet.DatagramEnetTransport
import com.voidlink.android.protocol.enet.EnetHost
import com.voidlink.android.protocol.http.LaunchRequest
import com.voidlink.android.protocol.http.LaunchResponse
import com.voidlink.android.protocol.http.NvHttpClient
import com.voidlink.android.protocol.http.NvHttpResult
import com.voidlink.android.protocol.http.ServerInfo
import com.voidlink.android.protocol.rtp.FrameAssemblerConfig
import com.voidlink.android.protocol.rtp.FrameAssemblerStats
import com.voidlink.android.protocol.rtp.VideoFrame
import com.voidlink.android.protocol.rtp.VideoFramePipeline
import com.voidlink.android.protocol.rtp.VideoStreamEvent
import com.voidlink.android.protocol.rtsp.RtspSessionNegotiator
import com.voidlink.android.protocol.rtsp.RtspSessionRequest
import com.voidlink.android.protocol.rtsp.RtspSessionResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.ReceiveChannel
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * A host that answered, with everything the launch step needs (spec §3.3).
 *
 * @property hostKey the stable per-host key — [com.voidlink.android.data.KnownHost.uuid] — which is
 *   what the pinned certificate is filed under.
 * @property name the host's display name, for user-facing failure text.
 * @property address the address that actually answered.
 * @property httpsPort the port learned from `<HttpsPort>`.
 * @property serverInfo the parsed document, which supplies the generation, the server family and
 *   `currentgame`.
 */
class ResolvedSessionHost(
    val hostKey: String,
    val name: String,
    val address: HostAddress,
    val httpsPort: Int,
    val serverInfo: ServerInfo,
)

/** The outcome of finding a host. */
sealed interface SessionHostResult {
    class Resolved(val host: ResolvedSessionHost) : SessionHostResult
    class Failed(val failure: SessionFailure) : SessionHostResult
}

/**
 * The NVHTTP half of session start-up: find the host, then `/launch` or `/resume`.
 *
 * An interface so [StreamSession] can be driven end to end by a test with no network — the state
 * machine, the teardown order and the failure classification are the parts most worth testing and
 * the parts least testable through a real socket.
 */
interface SessionLauncher {

    /** Finds a reachable address for [hostId] and reads `/serverinfo`. */
    suspend fun resolve(hostId: String?): SessionHostResult

    /**
     * Starts or resumes the session (spec §3.6, §3.7).
     *
     * @param resume true when the host reports this app already running, in which case `/resume`
     *   is the correct call and `/launch` would be refused.
     */
    suspend fun launch(
        host: ResolvedSessionHost,
        request: LaunchRequest,
        resume: Boolean,
    ): NvHttpResult<LaunchResponse>
}

/** The RTSP half, behind an interface for the same reason. */
interface SessionNegotiator {
    suspend fun negotiate(request: RtspSessionRequest): RtspSessionResult
}

/**
 * A connected control transport plus the means to release it.
 *
 * @property link what [com.voidlink.android.protocol.control.ControlStream] writes onto.
 * @property close releases the ENet socket and stops its service loop. Called exactly once, by the
 *   session, after the disconnect of spec §9.7 step 3.
 */
class ControlChannel(val link: ControlLink, val close: () -> Unit)

/** Opens the ENet control connection (spec §9.1). */
interface ControlChannelFactory {

    /**
     * Connects, or returns `null` when the handshake did not complete in [timeoutMs].
     *
     * A `null` is [SessionFailure.ControlConnectFailed] and nothing else: the socket has already
     * been released by the time it returns, so the caller has nothing to clean up.
     *
     * @param scope the session scope the ENet service loop runs in.
     */
    suspend fun connect(
        scope: CoroutineScope,
        host: String,
        port: Int,
        connectData: Int,
        timeoutMs: Long,
    ): ControlChannel?
}

/** What a video channel must offer the session, whether it is a real socket or a test double. */
interface VideoChannel {

    /** Complete frames from the reassembler. */
    val frames: ReceiveChannel<VideoFrame>

    /** Loss and status notices, which drive IDR requests (spec §9.5). */
    val events: ReceiveChannel<VideoStreamEvent>

    /** Counters, which separate `NO_VIDEO_TRAFFIC` from `NO_VIDEO_FRAME` (spec §11.1). */
    fun stats(): FrameAssemblerStats

    /** Releases the socket and stops the receive and ping threads. Idempotent. */
    fun close()
}

/**
 * What to open a video channel for.
 *
 * @property host the host to ping (spec §7.5).
 * @property port the negotiated video port.
 * @property pingPayload the `X-SS-Ping-Payload`, or `null` for the legacy `PING`.
 * @property assembler how frames are reassembled — codec, and whether the negotiated session said
 *   video would be encrypted (spec §7.6).
 */
class VideoChannelSpec(
    val host: String,
    val port: Int,
    val pingPayload: String?,
    val assembler: FrameAssemblerConfig,
)

/** Opens the video UDP socket (spec §7.5). */
fun interface VideoChannelFactory {
    /** @throws java.io.IOException when the socket cannot be opened or bound. */
    fun open(spec: VideoChannelSpec): VideoChannel
}

// ---- Production implementations ----------------------------------------------------------------

/**
 * [SessionLauncher] over the real NVHTTP client.
 *
 * The whole of the launch seam is here, in one place, because it is where two halves of the app
 * that were built separately finally meet: `NvHttpClient.launch`/`resume` and `LaunchRequest` were
 * implemented and tested with no callers at all, and the RTSP negotiator was written to accept an
 * already-obtained `LaunchResponse` precisely so this join would be a few lines rather than a
 * rewrite of either side.
 */
class NvHttpSessionLauncher(
    private val client: NvHttpClient,
    private val resolver: HostEndpointResolver,
    private val hosts: HostRepository,
) : SessionLauncher {

    override suspend fun resolve(hostId: String?): SessionHostResult {
        if (hostId.isNullOrBlank()) {
            return SessionHostResult.Failed(SessionFailure.UnknownHost(hostId))
        }
        val known = hosts.snapshot().firstOrNull { it.uuid == hostId }
            ?: return SessionHostResult.Failed(SessionFailure.UnknownHost(hostId))
        if (!known.paired) {
            // A weak check — the authoritative one is whether HTTPS accepts our certificate, which
            // /launch performs — but it turns "we have never paired" into the right sentence
            // without a round trip.
            ProtocolLog.i(
                SessionConstants.TAG,
                "${known.name} is saved as unpaired; attempting anyway, the HTTPS call decides",
            )
        }
        val resolved = resolver.resolve(known, resolver.timeoutFor(known))
            ?: return SessionHostResult.Failed(
                SessionFailure.HostUnreachable(known.name, known.addresses),
            )
        return SessionHostResult.Resolved(
            ResolvedSessionHost(
                hostKey = known.uuid,
                name = known.name,
                address = resolved.address,
                httpsPort = resolver.httpsPort(known.uuid),
                serverInfo = resolved.serverInfo,
            ),
        )
    }

    override suspend fun launch(
        host: ResolvedSessionHost,
        request: LaunchRequest,
        resume: Boolean,
    ): NvHttpResult<LaunchResponse> = if (resume) {
        client.resume(
            hostKey = host.hostKey,
            address = host.address,
            request = request,
            isNvidiaGfe = host.serverInfo.isNvidiaGfe,
            httpsPort = host.httpsPort,
        )
    } else {
        client.launch(
            hostKey = host.hostKey,
            address = host.address,
            request = request,
            isNvidiaGfe = host.serverInfo.isNvidiaGfe,
            httpsPort = host.httpsPort,
        )
    }
}

/** [SessionNegotiator] over the real RTSP negotiator. */
class RtspSessionNegotiatorAdapter(
    private val negotiator: RtspSessionNegotiator = RtspSessionNegotiator(),
) : SessionNegotiator {
    override suspend fun negotiate(request: RtspSessionRequest): RtspSessionResult =
        negotiator.negotiate(request)
}

/**
 * [ControlChannelFactory] over the real ENet host (spec §9.1).
 *
 * The service loop is launched into the session scope *before* the handshake, because
 * [EnetHost.connect] completes only while the loop is pumping. On a failed handshake everything
 * opened here is released here, so the caller never has to unwind a half-open connection.
 */
class EnetControlChannelFactory(
    private val socketFactory: () -> DatagramSocket = { DatagramSocket() },
) : ControlChannelFactory {

    override suspend fun connect(
        scope: CoroutineScope,
        host: String,
        port: Int,
        connectData: Int,
        timeoutMs: Long,
    ): ControlChannel? {
        val socket = try {
            socketFactory()
        } catch (failure: Exception) {
            ProtocolLog.w(SessionConstants.TAG, "could not open the ENet socket: ${failure.message}")
            return null
        }
        val enet = EnetHost(DatagramEnetTransport(socket))
        val loop: Job = enet.startIn(scope)
        val connected = try {
            enet.connect(InetSocketAddress(InetAddress.getByName(host), port), connectData, timeoutMs)
        } catch (failure: Exception) {
            ProtocolLog.w(
                SessionConstants.TAG,
                "the ENet handshake to $host:$port threw: ${failure.message}",
            )
            false
        }
        if (!connected) {
            enet.close()
            loop.cancel()
            return null
        }
        return ControlChannel(EnetControlLink(enet)) {
            enet.close()
            loop.cancel()
        }
    }
}

/** [VideoChannelFactory] over the real UDP socket (spec §7.5). */
class UdpVideoChannelFactory(
    private val socketFactory: () -> DatagramSocket = { DatagramSocket() },
) : VideoChannelFactory {

    override fun open(spec: VideoChannelSpec): VideoChannel {
        val receiver = VideoReceiver(
            hostAddress = InetAddress.getByName(spec.host),
            videoPort = spec.port,
            pingPayload = spec.pingPayload,
            pipeline = VideoFramePipeline(spec.assembler),
            socketFactory = socketFactory,
        )
        receiver.start()
        return receiver
    }
}
