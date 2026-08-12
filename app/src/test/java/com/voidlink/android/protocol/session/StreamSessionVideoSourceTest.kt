package com.voidlink.android.protocol.session

import com.voidlink.android.data.StreamSettings
import com.voidlink.android.media.VideoCodecType
import com.voidlink.android.media.VideoSourceRequest
import com.voidlink.android.media.VideoSourceResult
import com.voidlink.android.media.VideoStreamFormat
import com.voidlink.android.protocol.http.NvHttpResult
import com.voidlink.android.protocol.rtsp.RtspSessionResult
import com.voidlink.android.protocol.rtsp.VideoCodec
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The seam between the stream screen and the session layer (`media/VideoSource.kt`).
 *
 * Two conversions and one contract. The conversions are format and frame; the contract is that a
 * failure comes back as a *sentence*, never as an exception — the stream screen puts
 * [VideoSourceResult.Unavailable.summary] on the failure screen and has nowhere to put a stack
 * trace. The re-negotiated format matters just as much: the screen re-runs decoder selection when
 * it differs from what it asked for, which is what makes a host clamping the resolution survivable
 * rather than a black window.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StreamSessionVideoSourceTest {

    private val format = VideoStreamFormat(
        codec = VideoCodecType.HEVC,
        width = 1920,
        height = 1080,
        frameRate = 60,
    )

    private val request = VideoSourceRequest(
        hostId = "host-uuid",
        appId = "42",
        appName = "A Game",
        format = format,
        settings = StreamSettings(),
    )

    private fun sourceOf(
        launcher: SessionLauncher = FakeSessionLauncher(),
        control: FakeControlChannelFactory = FakeControlChannelFactory(),
        video: FakeVideoChannelFactory = FakeVideoChannelFactory(),
        negotiator: SessionNegotiator = FakeSessionNegotiator(),
    ) = StreamSessionVideoSource(launcher) {
        StreamSession(
            launcher = it,
            negotiator = negotiator,
            controlChannels = control,
            videoChannels = video,
        )
    }

    @Test
    fun `a started session hands back frames as decode units`() = runTest {
        val video = FakeVideoChannelFactory()
        video.channel.deliver(fakeVideoFrame(index = 3L, keyFrame = true))
        val control = FakeControlChannelFactory()

        val result = sourceOf(control = control, video = video).open(request)

        val ready = result as VideoSourceResult.Ready
        val unit = awaitValue { ready.frames.tryReceive().getOrNull() }
        assertEquals(3, unit.frameNumber)
        assertTrue(unit.keyFrame)
        assertEquals(5, unit.length)

        ready.onClose.invoke()
        assertTrue(video.channel.closed)
        assertTrue(control.closed)
    }

    @Test
    fun `the negotiated format comes back, not the requested one`() = runTest {
        // The host clamped us to 720p H.264; the stream screen must be told so it can re-select.
        val video = FakeVideoChannelFactory()
        video.channel.deliver(fakeVideoFrame())
        val negotiator = FakeSessionNegotiator(
            RtspSessionResult.Success(
                fakeNegotiatedSession(codec = VideoCodec.H264, width = 1280, height = 720, fps = 30),
            ),
        )

        val ready = sourceOf(video = video, negotiator = negotiator).open(request)
            as VideoSourceResult.Ready

        assertEquals(VideoCodecType.H264, ready.format.codec)
        assertEquals(1280, ready.format.width)
        assertEquals(30, ready.format.frameRate)
        ready.onClose.invoke()
    }

    @Test
    fun `a failure becomes a sentence with its stage, never an exception`() = runTest {
        val launcher = FakeSessionLauncher(
            launchResult = NvHttpResult.HostError(503, "Another client is streaming"),
        )

        val unavailable = sourceOf(launcher = launcher).open(request)
            as VideoSourceResult.Unavailable

        assertTrue(unavailable.summary.contains("would not start the game"))
        val detail = requireNotNull(unavailable.detail)
        assertTrue(detail.contains("Another client is streaming"))
        assertTrue(detail.contains(SessionStage.LAUNCH.label))
        assertTrue(detail.contains("Retrying may work"))
    }

    @Test
    fun `an unrecoverable failure does not suggest retrying`() = runTest {
        val launcher = FakeSessionLauncher(
            resolution = SessionHostResult.Failed(SessionFailure.NotPaired("Study PC")),
        )
        val unavailable = sourceOf(launcher = launcher).open(request)
            as VideoSourceResult.Unavailable
        assertTrue(unavailable.summary.contains("not been paired"))
        assertTrue(!requireNotNull(unavailable.detail).contains("Retrying may work"))
    }

    /** See `StreamSessionTest.awaitValue`: the session's pumps run on `Dispatchers.IO`, not on the
     * test scheduler's virtual time. */
    private fun <T : Any> awaitValue(timeoutMs: Long = 5_000L, produce: () -> T?): T {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (System.nanoTime() < deadline) {
            produce()?.let { return it }
            Thread.sleep(5L)
        }
        throw AssertionError("nothing arrived within ${timeoutMs} ms")
    }
}
