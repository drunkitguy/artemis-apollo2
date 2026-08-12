package com.voidlink.android.protocol.session

import com.voidlink.android.data.StreamSettings
import com.voidlink.android.media.audio.AudioPipeline
import com.voidlink.android.media.audio.AudioPlaybackStats
import com.voidlink.android.media.audio.AudioSessionStats
import com.voidlink.android.media.audio.AudioSourceFactory
import com.voidlink.android.media.audio.AudioSourceRequest
import com.voidlink.android.media.audio.AudioSourceResult
import com.voidlink.android.media.audio.AudioStreamFormat
import com.voidlink.android.protocol.Hex
import com.voidlink.android.protocol.audio.AudioStreamStats
import com.voidlink.android.protocol.enet.EnetDelivery
import com.voidlink.android.protocol.enet.EnetUnverifiedConstants
import com.voidlink.android.protocol.input.HostInputFeedback
import com.voidlink.android.protocol.input.InputPipeline
import com.voidlink.android.protocol.input.MotionType
import com.voidlink.android.protocol.rtsp.VideoCodec
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/** The two-byte control header every framed message carries (spec §9.2). */
private const val ControlHeaderBytes: Int = 2

/**
 * The audio and input layers attached to the session state machine
 * (`docs/01-PROTOCOL.md` §8, §9.6, §10; `docs/02-ARCHITECTURE.md` §4).
 *
 * Three properties, each of which fails silently if it regresses:
 *
 * 1. **Audio can never fail a session.** Unavailable audio, a throwing factory, a host with no
 *    audio at all — every one of them must produce a video stream and a log line, never a failure
 *    screen. A stream with no sound is far better than no stream.
 * 2. **The Opus channel mapping is passed through untouched.** `OpusMultistreamConfig` already
 *    applied spec §8.3's reordering; applying it again would move LFE a second time and put
 *    centre-channel dialogue in a surround speaker — inaudible in a log, obvious in a game.
 * 3. **Input is attached over the control stream and detached before the session ends.** Detach
 *    releases held keys and buttons *through the still-live transport*; skipping it leaves the host
 *    with a stuck key after every disconnect.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StreamSessionAudioInputTest {

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

    @After
    fun tearDown() {
        AudioPipeline.resetForTesting()
        InputPipeline.resetForTesting()
    }

    // ---- Audio ----------------------------------------------------------------------------------

    @Test
    fun `audio is opened with what RTSP negotiated, mapping included and unreordered`() = runTest {
        val audio = FakeAudioSourceFactory()
        AudioPipeline.audioSourceFactory = audio
        val video = FakeVideoChannelFactory()
        video.channel.deliver(fakeVideoFrame())

        val started = sessionOf(videoChannels = video).start(request) as StreamSessionResult.Started

        val opened = requireNotNull(audio.lastRequest)
        assertEquals("192.168.1.24", opened.host)
        assertEquals(48000, opened.port)
        assertEquals(2, opened.channelCount)
        assertEquals(48_000, opened.sampleRateHz)
        // Stereo's mapping, byte for byte as OpusMultistreamConfig produced it.
        assertArrayEquals(intArrayOf(0, 1), opened.mapping)
        // v1 announces encryptionEnabled=0, so audio must not be told it is encrypted (spec §6.5).
        assertFalse(opened.audioEncryptionNegotiated)
        assertEquals(5, opened.packetDurationMs)

        assertNotNull(started.session.audioStats())
        assertEquals(2, requireNotNull(started.session.audioFormat).channelCount)
        started.session.stop()
    }

    @Test
    fun `audio is closed on a normal teardown`() = runTest {
        val audio = FakeAudioSourceFactory()
        AudioPipeline.audioSourceFactory = audio
        val video = FakeVideoChannelFactory()
        video.channel.deliver(fakeVideoFrame())

        val started = sessionOf(videoChannels = video).start(request) as StreamSessionResult.Started
        started.session.stop()

        assertEquals(1, audio.closeCount)
        assertNull(started.session.audioStats())
    }

    @Test
    fun `audio is closed when the session fails after it started`() = runTest {
        // The first-frame watchdog gives up: audio is already running by then, and nothing else
        // will ever call stop().
        val audio = FakeAudioSourceFactory()
        AudioPipeline.audioSourceFactory = audio
        val video = FakeVideoChannelFactory()

        val result = sessionOf(videoChannels = video, virtualClock = true).start(request)

        assertTrue(result is StreamSessionResult.Failed)
        assertEquals(1, audio.closeCount)
    }

    @Test
    fun `unavailable audio does not fail the session`() = runTest {
        val audio = FakeAudioSourceFactory(
            result = AudioSourceResult.Unavailable("no decoder", "for the test"),
        )
        AudioPipeline.audioSourceFactory = audio
        val video = FakeVideoChannelFactory()
        video.channel.deliver(fakeVideoFrame())

        val started = sessionOf(videoChannels = video).start(request) as StreamSessionResult.Started

        assertNull(started.session.audioStats())
        assertNull(started.session.audioFormat)
        assertEquals(0, audio.closeCount)
        started.session.stop()
    }

    @Test
    fun `an audio factory that throws does not fail the session either`() = runTest {
        // The factory is documented never to throw. The session must not stake a stream on that.
        AudioPipeline.audioSourceFactory = object : AudioSourceFactory {
            override suspend fun open(request: AudioSourceRequest): AudioSourceResult =
                throw IllegalStateException("the audio layer misbehaved")
        }
        val video = FakeVideoChannelFactory()
        video.channel.deliver(fakeVideoFrame())

        val started = sessionOf(videoChannels = video).start(request) as StreamSessionResult.Started

        assertNull(started.session.audioStats())
        started.session.stop()
    }

    // ---- Input ------------------------------------------------------------------------------------

    @Test
    fun `input is attached once the control stream is up and detached on teardown`() = runTest {
        val control = FakeControlChannelFactory()
        val video = FakeVideoChannelFactory()
        video.channel.deliver(fakeVideoFrame())

        val started = sessionOf(controlChannels = control, videoChannels = video)
            .start(request) as StreamSessionResult.Started
        assertTrue(InputPipeline.isAttached)

        started.session.stop()
        assertFalse(InputPipeline.isAttached)
    }

    @Test
    fun `a keystroke travels from the sink to the control channel as INPUT_DATA`() = runTest {
        // The whole chain in one assertion: the stream screen's sink, the input layer's encryption,
        // this session's framing, the urgent channel. What is *inside* the payload belongs to
        // protocol/input and is asserted there; what this owns is that it arrives at all, framed as
        // an INPUT_DATA message and delivered reliably (spec §10.4).
        val control = FakeControlChannelFactory()
        val video = FakeVideoChannelFactory()
        video.channel.deliver(fakeVideoFrame())
        val started = sessionOf(controlChannels = control, videoChannels = video)
            .start(request) as StreamSessionResult.Started

        InputPipeline.sink.key(virtualKeyCode = 0x41, pressed = true, modifiers = 0)
        InputPipeline.sink.flush()

        val input = requireNotNull(control.link.sent.lastOrNull { it.hex().startsWith("0602") })
        assertEquals(EnetUnverifiedConstants.CHANNEL_URGENT, input.channelId)
        assertEquals(EnetDelivery.RELIABLE, input.delivery)
        assertTrue(input.payload.size > ControlHeaderBytes)

        // ...and detaching releases the key that is still down, over the same channel.
        val beforeDetach = control.link.sent.size
        started.session.stop()
        assertTrue(control.link.sent.size > beforeDetach)
    }

    @Test
    fun `a rumble message reaches the input layer with its fields read once`() = runTest {
        val control = FakeControlChannelFactory()
        val video = FakeVideoChannelFactory()
        video.channel.deliver(fakeVideoFrame())
        val received = CopyOnWriteArrayList<HostInputFeedback>()
        InputPipeline.addFeedbackListener { received += it }

        val started = sessionOf(controlChannels = control, videoChannels = video)
            .start(request) as StreamSessionResult.Started

        // Type 0x010b, four leading bytes, then controller 1, low 0x2211, high 0x4433.
        control.link.deliver(requireNotNull(Hex.decodeOrNull("0b01" + "00000000" + "0100" + "1122" + "3344")))

        val rumble = awaitValue { received.filterIsInstance<HostInputFeedback.Rumble>().firstOrNull() }
        assertEquals(1, rumble.controllerNumber)
        assertEquals(0x2211, rumble.lowFrequencyMotor)
        assertEquals(0x4433, rumble.highFrequencyMotor)
        started.session.stop()
    }

    @Test
    fun `trigger rumble and the motion-report request reach the input layer too`() = runTest {
        val control = FakeControlChannelFactory()
        val video = FakeVideoChannelFactory()
        video.channel.deliver(fakeVideoFrame())
        val received = CopyOnWriteArrayList<HostInputFeedback>()
        InputPipeline.addFeedbackListener { received += it }

        val started = sessionOf(controlChannels = control, videoChannels = video)
            .start(request) as StreamSessionResult.Started

        // 0x5500: rumble triggers, read from offset 0 — no leading bytes, unlike rumble.
        control.link.deliver(requireNotNull(Hex.decodeOrNull("0055" + "0100" + "1122" + "3344")))
        // 0x5501: (controllerNumber, reportRateHz, motionType) — not the order spec §10.3 implies.
        control.link.deliver(requireNotNull(Hex.decodeOrNull("0155" + "0100" + "3c00" + "02")))

        val triggers = awaitValue {
            received.filterIsInstance<HostInputFeedback.RumbleTriggers>().firstOrNull()
        }
        assertEquals(1, triggers.controllerNumber)
        assertEquals(0x2211, triggers.leftTriggerMotor)

        val motion = awaitValue {
            received.filterIsInstance<HostInputFeedback.SetMotionEventState>().firstOrNull()
        }
        assertEquals(1, motion.controllerNumber)
        assertEquals(60, motion.reportRateHz)
        assertEquals(MotionType.GYROSCOPE, motion.motionType)
        started.session.stop()
    }

    @Test
    fun `input is detached when the session fails after the control stream came up`() = runTest {
        val video = FakeVideoChannelFactory(failure = java.io.IOException("no socket"))

        val result = sessionOf(videoChannels = video).start(request)

        assertTrue(result is StreamSessionResult.Failed)
        assertFalse(InputPipeline.isAttached)
    }

    // ---- Helpers -----------------------------------------------------------------------------------

    private fun <T : Any> awaitValue(timeoutMs: Long = 5_000L, produce: () -> T?): T {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (System.nanoTime() < deadline) {
            produce()?.let { return it }
            Thread.sleep(5L)
        }
        throw AssertionError("nothing arrived within ${timeoutMs} ms")
    }

    private fun TestScope.sessionOf(
        launcher: SessionLauncher = FakeSessionLauncher(),
        controlChannels: ControlChannelFactory = FakeControlChannelFactory(),
        videoChannels: VideoChannelFactory = FakeVideoChannelFactory(),
        virtualClock: Boolean = false,
    ): StreamSession = StreamSession(
        launcher = launcher,
        negotiator = FakeSessionNegotiator(),
        controlChannels = controlChannels,
        videoChannels = videoChannels,
        clock = if (virtualClock) {
            { testScheduler.currentTime * 1_000_000L }
        } else {
            { System.nanoTime() }
        },
    )
}

/** An audio factory that records what it was asked for and whether it was closed. */
class FakeAudioSourceFactory(
    private val result: AudioSourceResult? = null,
) : AudioSourceFactory {

    var lastRequest: AudioSourceRequest? = null
        private set

    @Volatile
    var closeCount: Int = 0
        private set

    override suspend fun open(request: AudioSourceRequest): AudioSourceResult {
        lastRequest = request
        result?.let { return it }
        return AudioSourceResult.Ready(
            format = AudioStreamFormat(
                channelCount = request.channelCount,
                sampleRateHz = request.sampleRateHz,
                streams = request.streams,
                coupledStreams = request.coupledStreams,
                mapping = request.mapping.copyOf(),
                packetDurationMs = request.packetDurationMs,
            ),
            stats = {
                AudioSessionStats(
                    receive = AudioStreamStats(),
                    playback = AudioPlaybackStats(),
                    samplesDroppedByBackpressure = 0L,
                )
            },
            onClose = { closeCount++ },
        )
    }
}
