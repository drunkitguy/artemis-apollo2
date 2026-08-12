package com.voidlink.android.protocol.session

import com.voidlink.android.protocol.HostAddress
import com.voidlink.android.protocol.control.ControlLink
import com.voidlink.android.protocol.control.FakeControlLink
import com.voidlink.android.protocol.http.AppVersion
import com.voidlink.android.protocol.http.AudioChannelLayout
import com.voidlink.android.protocol.http.LaunchRequest
import com.voidlink.android.protocol.http.LaunchResponse
import com.voidlink.android.protocol.http.NvHttpResult
import com.voidlink.android.protocol.http.ServerInfo
import com.voidlink.android.protocol.rtp.FrameAssemblerStats
import com.voidlink.android.protocol.rtp.VideoFrame
import com.voidlink.android.protocol.rtp.VideoStreamEvent
import com.voidlink.android.protocol.rtsp.NegotiatedSession
import com.voidlink.android.protocol.rtsp.OpusMultistreamConfig
import com.voidlink.android.protocol.rtsp.RtspSessionRequest
import com.voidlink.android.protocol.rtsp.RtspSessionResult
import com.voidlink.android.protocol.rtsp.SessionDescription
import com.voidlink.android.protocol.rtsp.VideoCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel

/**
 * Test doubles for the four seams [StreamSession] talks to, plus the fixtures they answer with.
 *
 * The state machine is the part of the session layer worth testing hardest and the part least
 * testable through real sockets: what has to be right is the *order* of the stages, the
 * classification of each failure, and the guarantee that every socket is released on every path.
 * All three are structural, and all three are invisible to a test that needs a host on the network.
 */

/** A Sunshine-family Gen 7 host, which is the primary target of spec §0.3. */
fun fakeServerInfo(
    currentGameId: String? = null,
    appVersion: String = "7.1.431.0",
    state: String = "SUNSHINE_SERVER_FREE",
): ServerInfo = ServerInfo(
    hostname = "study-pc",
    appVersion = requireNotNull(AppVersion.parse(appVersion)),
    gfeVersion = null,
    uniqueId = "unique",
    httpsPort = 47984,
    externalPort = null,
    macAddress = null,
    localIp = null,
    externalIp = null,
    maxLumaPixelsHevc = 1L,
    maxLumaPixelsH264 = 1L,
    serverCodecModeSupport = null,
    pairStatus = true,
    currentGameId = currentGameId,
    state = state,
    gpuType = null,
    displayModes = emptyList(),
)

fun fakeResolvedHost(serverInfo: ServerInfo = fakeServerInfo()) = ResolvedSessionHost(
    hostKey = "host-uuid",
    name = "Study PC",
    address = HostAddress("192.168.1.24"),
    httpsPort = 47984,
    serverInfo = serverInfo,
)

fun fakeNegotiatedSession(
    videoPort: Int = 47998,
    controlPort: Int = 47999,
    codec: VideoCodec = VideoCodec.HEVC,
    width: Int = 1920,
    height: Int = 1080,
    fps: Int = 60,
) = NegotiatedSession(
    host = "192.168.1.24",
    rtspPort = 48010,
    sessionId = "0",
    videoPort = videoPort,
    audioPort = 48000,
    controlPort = controlPort,
    controlSetupPerformed = true,
    videoPingPayload = null,
    audioPingPayload = null,
    controlConnectData = 0,
    codec = codec,
    hdr = false,
    chromaSamplingType = 0,
    width = width,
    height = height,
    fps = fps,
    bitrateKbps = 20_000,
    configuredBitrateKbps = 20_000,
    packetSize = 1392,
    encryptionFlags = 0,
    audioLayout = AudioChannelLayout.STEREO,
    audioLayoutDowngraded = false,
    opusConfig = OpusMultistreamConfig.stereo(),
    announcedSdp = "",
    hostDescription = SessionDescription.parse(""),
)

fun fakeVideoFrame(index: Long = 1L, keyFrame: Boolean = true) = VideoFrame(
    frameIndex = index,
    rtpTimestamp = 0,
    data = byteArrayOf(0, 0, 0, 1, 0x65),
    isKeyFrame = keyFrame,
    isLongTermReferenceFrame = false,
    recoveredShardCount = 0,
)

fun fakeStats(packetsReceived: Long = 0L, framesCompleted: Long = 0L) = FrameAssemblerStats(
    packetsReceived = packetsReceived,
    packetsRejected = 0L,
    packetsLost = 0L,
    packetsDuplicated = 0L,
    packetsLate = 0L,
    framesCompleted = framesCompleted,
    framesDropped = 0L,
    framesRecovered = 0L,
    shardsRecovered = 0L,
    highestSequenceNumber = 0,
    nextContiguousSequenceNumber = 0,
)

/** A launcher that answers from canned values and records what it was asked for. */
class FakeSessionLauncher(
    private val resolution: SessionHostResult = SessionHostResult.Resolved(fakeResolvedHost()),
    private val launchResult: NvHttpResult<LaunchResponse> = NvHttpResult.Success(
        LaunchResponse(started = true, sessionUrl = "rtsp://192.168.1.24:48010", rtspPort = 48010, rtspOverEnet = false),
    ),
) : SessionLauncher {

    var resumeRequested: Boolean? = null
        private set
    var lastRequest: LaunchRequest? = null
        private set

    override suspend fun resolve(hostId: String?): SessionHostResult = resolution

    override suspend fun launch(
        host: ResolvedSessionHost,
        request: LaunchRequest,
        resume: Boolean,
    ): NvHttpResult<LaunchResponse> {
        resumeRequested = resume
        lastRequest = request
        return launchResult
    }
}

/** A negotiator that answers from a canned result. */
class FakeSessionNegotiator(
    private val result: RtspSessionResult = RtspSessionResult.Success(fakeNegotiatedSession()),
) : SessionNegotiator {
    var lastRequest: RtspSessionRequest? = null
        private set

    override suspend fun negotiate(request: RtspSessionRequest): RtspSessionResult {
        lastRequest = request
        return result
    }
}

/** A control-channel factory that hands back a [FakeControlLink] and records its release. */
class FakeControlChannelFactory(
    val link: FakeControlLink = FakeControlLink(),
    private val connects: Boolean = true,
) : ControlChannelFactory {

    var closed: Boolean = false
        private set
    var connectAttempts: Int = 0
        private set

    override suspend fun connect(
        scope: CoroutineScope,
        host: String,
        port: Int,
        connectData: Int,
        timeoutMs: Long,
    ): ControlChannel? {
        connectAttempts++
        if (!connects) return null
        return ControlChannel(link as ControlLink) { closed = true }
    }
}

/** A video channel whose frames, counters and closure a test drives directly. */
class FakeVideoChannel(
    private var stats: FrameAssemblerStats = fakeStats(),
) : VideoChannel {

    private val frameChannel = Channel<VideoFrame>(Channel.UNLIMITED)
    private val eventChannel = Channel<VideoStreamEvent>(Channel.UNLIMITED)

    var closed: Boolean = false
        private set

    override val frames: ReceiveChannel<VideoFrame> = frameChannel
    override val events: ReceiveChannel<VideoStreamEvent> = eventChannel

    override fun stats(): FrameAssemblerStats = stats

    override fun close() {
        closed = true
        frameChannel.close()
        eventChannel.close()
    }

    fun deliver(frame: VideoFrame) {
        frameChannel.trySend(frame)
    }

    fun deliver(event: VideoStreamEvent) {
        eventChannel.trySend(event)
    }

    fun setStats(stats: FrameAssemblerStats) {
        this.stats = stats
    }
}

/** A factory that hands back one [FakeVideoChannel], or throws as a failing socket would. */
class FakeVideoChannelFactory(
    val channel: FakeVideoChannel = FakeVideoChannel(),
    private val failure: Exception? = null,
) : VideoChannelFactory {

    var lastSpec: VideoChannelSpec? = null
        private set

    override fun open(spec: VideoChannelSpec): VideoChannel {
        lastSpec = spec
        failure?.let { throw it }
        return channel
    }
}
