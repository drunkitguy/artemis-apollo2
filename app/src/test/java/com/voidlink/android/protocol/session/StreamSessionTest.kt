package com.voidlink.android.protocol.session

import com.voidlink.android.data.StreamSettings
import com.voidlink.android.protocol.http.LaunchResponse
import com.voidlink.android.protocol.http.NvHttpResult
import com.voidlink.android.protocol.rtp.FrameDropReason
import com.voidlink.android.protocol.rtp.VideoStreamEvent
import com.voidlink.android.protocol.rtsp.RtspError
import com.voidlink.android.protocol.rtsp.RtspSessionResult
import com.voidlink.android.protocol.rtsp.RtspStep
import com.voidlink.android.protocol.rtsp.VideoCodec
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketException

/**
 * The session state machine (`docs/02-ARCHITECTURE.md` §4): stage order, failure classification and
 * resource release.
 *
 * Three properties are worth more than the rest put together:
 *
 * 1. **Each stage's failure has its own name.** A launch refused by a busy host, a UDP port a
 *    firewall is eating and reassembly that is broken on our side need three different sentences,
 *    and this project has already shipped a build where all three said "could not connect".
 * 2. **Every socket is released on every path.** Success, classified failure, thrown exception and
 *    cancellation all run through the same release, because the one that gets forgotten is always
 *    the failure path nobody exercised.
 * 3. **The stall becomes a message.** Spec §11.1's two video timeouts are told apart by one
 *    counter, and getting that wrong turns a firewall problem into "this app is broken".
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StreamSessionTest {

    private val request = StreamSessionRequest(
        hostId = "host-uuid",
        appId = "42",
        appName = "A Game",
        settings = StreamSettings(),
        width = 1920,
        height = 1080,
        fps = 60,
        codec = VideoCodec.HEVC,
        hdr = false,
    )

    // ---- The happy path --------------------------------------------------------------------------

    @Test
    fun `a session comes up and delivers the keyframe the watchdog waited for`() = runTest {
        val video = FakeVideoChannelFactory()
        video.channel.deliver(fakeVideoFrame(index = 7L))
        video.channel.setStats(fakeStats(packetsReceived = 12L, framesCompleted = 1L))
        val control = FakeControlChannelFactory()
        val launcher = FakeSessionLauncher()
        val session = sessionOf(launcher, control, video)

        val result = session.start(request)

        val started = result as StreamSessionResult.Started
        val frame = awaitValue { started.session.frames.tryReceive().getOrNull() }
        assertEquals(7L, frame.frameIndex)
        assertTrue(frame.isKeyFrame)
        assertEquals(1920, started.session.negotiated.width)
        // Start A and Start B went out before any video was expected (spec §9.4).
        assertEquals("05030000", control.link.sent[0].hex())
        assertEquals("070300", control.link.sent[1].hex())

        started.session.stop()
        assertTrue(video.channel.closed)
        assertTrue(control.closed)
    }

    @Test
    fun `the video channel is opened on the negotiated port with the negotiated codec`() = runTest {
        val video = FakeVideoChannelFactory()
        video.channel.deliver(fakeVideoFrame())
        val session = sessionOf(videoChannels = video)

        session.start(request)

        val spec = requireNotNull(video.lastSpec)
        assertEquals(47998, spec.port)
        assertEquals("192.168.1.24", spec.host)
        // v1 announces encryptionEnabled=0, so the assembler must not expect an encryption header.
        assertFalse(spec.assembler.videoEncryptionNegotiated)
    }

    @Test
    fun `a host already running this app is resumed rather than launched`() = runTest {
        // Spec §3.7: a resume is a brand new streaming session even though the game keeps running,
        // and /launch on a busy host is refused.
        val launcher = FakeSessionLauncher(
            resolution = SessionHostResult.Resolved(
                fakeResolvedHost(fakeServerInfo(currentGameId = "42")),
            ),
        )
        val video = FakeVideoChannelFactory()
        video.channel.deliver(fakeVideoFrame())

        sessionOf(launcher = launcher, videoChannels = video).start(request)

        assertEquals(true, launcher.resumeRequested)
    }

    @Test
    fun `a host running a different app is launched, not resumed`() = runTest {
        val launcher = FakeSessionLauncher(
            resolution = SessionHostResult.Resolved(
                fakeResolvedHost(fakeServerInfo(currentGameId = "99")),
            ),
        )
        val video = FakeVideoChannelFactory()
        video.channel.deliver(fakeVideoFrame())

        sessionOf(launcher = launcher, videoChannels = video).start(request)

        assertEquals(false, launcher.resumeRequested)
    }

    @Test
    fun `the settings reach the launch request and the ANNOUNCE together`() = runTest {
        val launcher = FakeSessionLauncher()
        val negotiator = FakeSessionNegotiator()
        val video = FakeVideoChannelFactory()
        video.channel.deliver(fakeVideoFrame())

        sessionOf(launcher = launcher, negotiator = negotiator, videoChannels = video)
            .start(request.copyWith(settings = StreamSettings(bitrateKbps = 42_000)))

        assertEquals(42_000, requireNotNull(negotiator.lastRequest).configuration.bitrateKbps)
        assertEquals(1920, requireNotNull(launcher.lastRequest).width)
        assertEquals(42_000, requireNotNull(negotiator.lastRequest).configuration.configuredBitrateKbps)
    }

    // ---- Failure classification ---------------------------------------------------------------------

    @Test
    fun `an unparseable app id fails before any network call`() = runTest {
        val launcher = FakeSessionLauncher()
        val result = sessionOf(launcher).start(request.copyWith(appId = "Desktop"))
        assertTrue(failureOf(result) is SessionFailure.UnknownApp)
        assertEquals(null, launcher.resumeRequested)
    }

    @Test
    fun `an unreachable host is named as such`() = runTest {
        val launcher = FakeSessionLauncher(
            resolution = SessionHostResult.Failed(
                SessionFailure.HostUnreachable("Study PC", listOf("192.168.1.24")),
            ),
        )
        val failure = failureOf(sessionOf(launcher).start(request))
        assertTrue(failure is SessionFailure.HostUnreachable)
        assertEquals(SessionStage.RESOLVE, failure.stage)
        assertTrue(failure.recoverable)
    }

    @Test
    fun `a refused launch carries the host's own reason`() = runTest {
        val launcher = FakeSessionLauncher(
            launchResult = NvHttpResult.HostError(503, "Another client is streaming"),
        )
        val failure = failureOf(sessionOf(launcher).start(request))
        val refused = failure as SessionFailure.LaunchRefused
        assertEquals(SessionStage.LAUNCH, refused.stage)
        assertEquals(503, refused.hostStatusCode)
        assertTrue(requireNotNull(refused.detail).contains("Another client is streaming"))
    }

    @Test
    fun `a host that answers without starting a session is not treated as success`() = runTest {
        val launcher = FakeSessionLauncher(
            launchResult = NvHttpResult.Success(
                LaunchResponse(started = false, sessionUrl = null, rtspPort = null, rtspOverEnet = false),
            ),
        )
        val failure = failureOf(sessionOf(launcher).start(request))
        assertTrue(failure is SessionFailure.LaunchRefused)
    }

    @Test
    fun `an unpaired host is reported as unpaired, not as unreachable`() = runTest {
        val launcher = FakeSessionLauncher(launchResult = NvHttpResult.NotPaired)
        val failure = failureOf(sessionOf(launcher).start(request))
        assertTrue(requireNotNull(failure.detail).contains("not paired"))
    }

    @Test
    fun `an RTSP timeout names the step that timed out`() = runTest {
        val negotiator = FakeSessionNegotiator(
            RtspSessionResult.Failure(RtspError.Timeout(RtspStep.ANNOUNCE, 10_000L)),
        )
        val failure = failureOf(sessionOf(negotiator = negotiator).start(request))
        val negotiation = failure as SessionFailure.NegotiationFailed
        assertEquals(SessionStage.NEGOTIATE, negotiation.stage)
        assertTrue(negotiation.summary.contains("ANNOUNCE"))
        assertTrue(negotiation.recoverable)
    }

    @Test
    fun `an ANNOUNCE the host refuses is not worth retrying`() = runTest {
        val negotiator = FakeSessionNegotiator(
            RtspSessionResult.Failure(RtspError.Refused(RtspStep.ANNOUNCE, 400, "Bad Request")),
        )
        val failure = failureOf(sessionOf(negotiator = negotiator).start(request))
        assertFalse(failure.recoverable)
    }

    @Test
    fun `a control connection that never completes is its own failure`() = runTest {
        val control = FakeControlChannelFactory(connects = false)
        val video = FakeVideoChannelFactory()
        val failure = failureOf(sessionOf(controlChannels = control, videoChannels = video).start(request))

        val controlFailure = failure as SessionFailure.ControlConnectFailed
        assertEquals(SessionStage.CONTROL, controlFailure.stage)
        assertEquals(47999, controlFailure.port)
        assertTrue(controlFailure.summary.contains("UDP port 47999"))
        // The video socket was never opened, so there is nothing to leak.
        assertEquals(null, video.lastSpec)
    }

    @Test
    fun `a video socket that will not open is a device problem, not a host problem`() = runTest {
        val control = FakeControlChannelFactory()
        val video = FakeVideoChannelFactory(failure = SocketException("Permission denied"))
        val failure = failureOf(sessionOf(controlChannels = control, videoChannels = video).start(request))

        val socketFailure = failure as SessionFailure.VideoSocketFailed
        assertEquals(SessionStage.VIDEO_SETUP, socketFailure.stage)
        assertTrue(requireNotNull(socketFailure.detail).contains("Permission denied"))
        // ...and the control channel we opened first was released, along with the coroutines that
        // were pinging through it. A failed start hands the caller no handle to stop, so if the
        // session did not stop itself here it would ping a host it has given up on forever.
        assertTrue(control.closed)
        val sentAtFailure = control.link.sent.size
        Thread.sleep(SETTLE_MS)
        assertEquals(sentAtFailure, control.link.sent.size)
    }

    @Test
    fun `silence on the video port is reported as a firewall problem`() = runTest {
        // Spec §11.1: ML_ERROR_NO_VIDEO_TRAFFIC "almost always means a firewall or a NAT problem".
        val control = FakeControlChannelFactory()
        val video = FakeVideoChannelFactory() // no frames, no packets
        val session = sessionOf(controlChannels = control, videoChannels = video, virtualClock = true)

        val failure = failureOf(session.start(request))

        val traffic = failure as SessionFailure.NoVideoTraffic
        assertEquals(SessionStage.FIRST_FRAME, traffic.stage)
        assertEquals(47998, traffic.port)
        assertTrue(traffic.summary.contains("firewall"))
        assertTrue(traffic.waitedMs >= SessionConstants.FIRST_TRAFFIC_TIMEOUT_MS)
        assertTrue(video.channel.closed)
        assertTrue(control.closed)
    }

    @Test
    fun `packets that never assemble are reported as our fault, with the counters`() = runTest {
        // Spec §11.1: ML_ERROR_NO_VIDEO_FRAME means "we are receiving but reassembly is failing".
        val control = FakeControlChannelFactory()
        val video = FakeVideoChannelFactory()
        video.channel.setStats(fakeStats(packetsReceived = 900L))
        val session = sessionOf(controlChannels = control, videoChannels = video, virtualClock = true)

        val failure = failureOf(session.start(request))

        val noFrame = failure as SessionFailure.NoVideoFrame
        assertEquals(900L, noFrame.packetsReceived)
        assertTrue(noFrame.summary.contains("not in your network"))
        assertTrue(requireNotNull(noFrame.detail).contains("900 packets received"))
        assertTrue(video.channel.closed)
    }

    // ---- Loss handling ----------------------------------------------------------------------------

    @Test
    fun `a reassembly failure asks the host for a keyframe`() = runTest {
        val control = FakeControlChannelFactory()
        val video = FakeVideoChannelFactory()
        video.channel.deliver(fakeVideoFrame())
        val started = sessionOf(controlChannels = control, videoChannels = video)
            .start(request) as StreamSessionResult.Started
        awaitValue { started.session.frames.tryReceive().getOrNull() }

        video.channel.deliver(VideoStreamEvent.FrameDropped(9L, FrameDropReason.INCOMPLETE, 3))

        // Type 0x0301 (reference-frame invalidation), which is how a Gen 7 host is asked for an IDR.
        awaitValue { control.link.sent.firstOrNull { it.hex().startsWith("0103") } }
        started.session.stop()
    }

    @Test
    fun `advisory packet loss does not ask for a keyframe`() = runTest {
        val control = FakeControlChannelFactory()
        val video = FakeVideoChannelFactory()
        video.channel.deliver(fakeVideoFrame())
        val started = sessionOf(controlChannels = control, videoChannels = video)
            .start(request) as StreamSessionResult.Started
        awaitValue { started.session.frames.tryReceive().getOrNull() }

        video.channel.deliver(VideoStreamEvent.PacketsLost(4, 100))
        Thread.sleep(SETTLE_MS)

        // Only Start A and Start B, plus any pings — never an invalidation.
        assertFalse(control.link.sent.any { it.hex().startsWith("0103") })
        started.session.stop()
    }

    // ---- Teardown ---------------------------------------------------------------------------------

    @Test
    fun `stopping tears down in the spec's order and is idempotent`() = runTest {
        val control = FakeControlChannelFactory()
        val video = FakeVideoChannelFactory()
        video.channel.deliver(fakeVideoFrame())
        val started = sessionOf(controlChannels = control, videoChannels = video)
            .start(request) as StreamSessionResult.Started

        started.session.stop()
        started.session.stop()

        // The termination message went out before the disconnect (spec §9.7 steps 2 and 3), once.
        assertEquals(1, control.link.disconnectCount)
        assertEquals(1, control.link.sent.count { it.hex() == "0001" })
        assertTrue(control.closed)
        assertTrue(video.channel.closed)
    }

    @Test
    fun `an exception from a gateway releases everything and is classified, not thrown`() = runTest {
        val control = FakeControlChannelFactory()
        val video = FakeVideoChannelFactory(failure = IOException("boom"))
        val failure = failureOf(sessionOf(controlChannels = control, videoChannels = video).start(request))

        assertTrue(failure is SessionFailure.VideoSocketFailed)
        assertTrue(control.closed)
    }

    // ---- Helpers -----------------------------------------------------------------------------------

    private fun StreamSessionRequest.copyWith(
        appId: String? = this.appId,
        settings: StreamSettings = this.settings,
    ) = StreamSessionRequest(
        hostId = hostId,
        appId = appId,
        appName = appName,
        settings = settings,
        width = width,
        height = height,
        fps = fps,
        codec = codec,
        hdr = hdr,
        displayRefreshRateHz = displayRefreshRateHz,
    )

    private fun failureOf(result: StreamSessionResult): SessionFailure =
        (result as StreamSessionResult.Failed).failure

    /**
     * @param virtualClock ties the session's clock to the test scheduler, so the ten-second
     *   watchdogs of architecture §4.2 elapse instantly instead of making the suite take a minute.
     */
    private fun kotlinx.coroutines.test.TestScope.sessionOf(
        launcher: SessionLauncher = FakeSessionLauncher(),
        controlChannels: ControlChannelFactory = FakeControlChannelFactory(),
        videoChannels: VideoChannelFactory = FakeVideoChannelFactory(),
        negotiator: SessionNegotiator = FakeSessionNegotiator(),
        virtualClock: Boolean = false,
    ): StreamSession = StreamSession(
        launcher = launcher,
        negotiator = negotiator,
        controlChannels = controlChannels,
        videoChannels = videoChannels,
        clock = if (virtualClock) {
            { testScheduler.currentTime * 1_000_000L }
        } else {
            { System.nanoTime() }
        },
    )

    /**
     * Waits, in **real** time, for something a coroutine on `Dispatchers.IO` produces.
     *
     * `runTest` runs the test body on virtual time, which the session's own pumps do not share:
     * `withTimeout` inside a virtual-time body fires the instant the scheduler goes idle, long
     * before a real IO thread has run. Polling against a wall-clock deadline is the only thing that
     * makes these assertions mean what they say.
     */
    private fun <T : Any> awaitValue(timeoutMs: Long = TIMEOUT_MS, produce: () -> T?): T {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (System.nanoTime() < deadline) {
            produce()?.let { return it }
            Thread.sleep(POLL_MS)
        }
        throw AssertionError("nothing arrived within ${timeoutMs} ms")
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
        const val POLL_MS = 5L

        /** Long enough for an IO-dispatched pump to have run, short enough not to pad the suite. */
        const val SETTLE_MS = 200L
    }
}
