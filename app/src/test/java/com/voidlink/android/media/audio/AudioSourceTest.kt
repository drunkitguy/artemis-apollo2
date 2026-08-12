package com.voidlink.android.media.audio

import com.voidlink.android.protocol.ProtocolLog
import com.voidlink.android.protocol.audio.AudioChannel
import com.voidlink.android.protocol.audio.AudioPacketFixtures
import com.voidlink.android.protocol.audio.AudioSamplePipeline
import com.voidlink.android.protocol.audio.AudioStreamEvent
import com.voidlink.android.protocol.audio.AudioStreamStats
import com.voidlink.android.protocol.audio.OpusSample
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The seam the session plugs into: one call in, one close out, and **never a thrown exception**.
 *
 * Spec §8.5's v1 decisions live here too — stereo-only playback, and an explained unavailability
 * for everything this build cannot do. Every one of these tests exists to pin the same rule: a
 * stream with no audio is far better than no stream.
 */
class AudioSourceTest {

    /** An [AudioChannel] whose samples the test feeds by hand. */
    private class FakeAudioChannel : AudioChannel {
        val sampleChannel = Channel<OpusSample>(Channel.UNLIMITED)
        val eventChannel = Channel<AudioStreamEvent>(Channel.UNLIMITED)
        var closed: Boolean = false
        var stats: AudioStreamStats = AudioStreamStats.EMPTY

        override val samples: ReceiveChannel<OpusSample> get() = sampleChannel
        override val events: ReceiveChannel<AudioStreamEvent> get() = eventChannel
        override fun stats(): AudioStreamStats = stats

        override fun close() {
            closed = true
            sampleChannel.close()
            eventChannel.close()
        }
    }

    private lateinit var driver: FakeAudioCodecDriver
    private lateinit var channel: FakeAudioChannel

    private fun request(
        channelCount: Int = 2,
        mapping: IntArray = intArrayOf(0, 1),
        streams: Int = 1,
        coupledStreams: Int = 1,
        encrypted: Boolean = false,
    ) = AudioSourceRequest(
        host = "192.0.2.10",
        port = 48000,
        pingPayload = "0123456789abcdef",
        channelCount = channelCount,
        streams = streams,
        coupledStreams = coupledStreams,
        mapping = mapping,
        audioEncryptionNegotiated = encrypted,
    )

    private fun factory(
        multistreamEnabled: Boolean = false,
        driverFactory: AudioCodecDriverFactory = AudioCodecDriverFactory { _, _ -> driver },
        receiverFactory: (AudioSourceRequest, AudioSamplePipeline) -> AudioChannel =
            { _, _ -> channel },
    ) = DefaultAudioSourceFactory(
        driverFactory = driverFactory,
        receiverFactory = receiverFactory,
        multistreamEnabled = multistreamEnabled,
    )

    @Before
    fun setUp() {
        driver = FakeAudioCodecDriver()
        channel = FakeAudioChannel()
        ProtocolLog.resetUnverifiedForTesting()
    }

    @Test
    fun `a stereo stream opens ready and starts the decoder`() = runBlocking {
        val result = factory().open(request())

        assertTrue(result is AudioSourceResult.Ready)
        result as AudioSourceResult.Ready
        assertEquals(2, result.format.channelCount)
        assertEquals(48_000, result.format.sampleRateHz)
        assertTrue(driver.started)

        result.onClose()
        assertTrue(channel.closed)
        assertTrue(driver.released)
    }

    @Test
    fun `samples reach the decoder`() = runBlocking {
        val result = factory().open(request()) as AudioSourceResult.Ready

        channel.sampleChannel.send(OpusSample(AudioPacketFixtures.opusPayload(1), 1, 5))
        channel.sampleChannel.send(OpusSample.concealment(2, 10))
        // Give the pump a moment; the loop lives on Dispatchers.IO.
        repeat(200) {
            if (driver.decoded.size >= 2) return@repeat
            Thread.sleep(1L)
        }

        assertEquals(2, driver.decoded.size)
        assertFalse(driver.decoded[0].concealment)
        assertTrue(driver.decoded[1].concealment)

        result.onClose()
    }

    @Test
    fun `stats combine the receive path and the playback path`() = runBlocking {
        channel.stats = AudioStreamStats(packetsReceived = 7L, packetsLost = 2L)
        val result = factory().open(request()) as AudioSourceResult.Ready

        val stats = result.stats()

        assertEquals(7L, stats.receive.packetsReceived)
        assertEquals(2L, stats.receive.packetsLost)
        assertEquals(0L, stats.playback.packetsDecoded)
        assertTrue(stats.describe().contains("pkts=7"))

        result.onClose()
    }

    @Test
    fun `encrypted audio is refused with a sentence rather than played as noise`() = runBlocking {
        val result = factory().open(request(encrypted = true))

        assertTrue(result is AudioSourceResult.Unavailable)
        result as AudioSourceResult.Unavailable
        assertTrue(result.summary.contains("video only"))
        assertTrue(result.detail!!.contains("SS_ENC_AUDIO"))
        // Nothing was opened, so there is nothing to close.
        assertFalse(driver.started)
        assertFalse(channel.closed)
    }

    @Test
    fun `a channel layout spec section 8_2 does not tabulate is refused`() = runBlocking {
        val result = factory().open(request(channelCount = 4, mapping = IntArray(4)))

        assertTrue(result is AudioSourceResult.Unavailable)
        assertTrue((result as AudioSourceResult.Unavailable).summary.contains("4-channel"))
        assertFalse(driver.started)
    }

    @Test
    fun `surround is refused by default, and says how to get audio anyway`() = runBlocking {
        val result = factory(multistreamEnabled = false).open(
            request(channelCount = 6, mapping = intArrayOf(0, 1, 2, 5, 3, 4), streams = 4, coupledStreams = 2),
        )

        assertTrue(result is AudioSourceResult.Unavailable)
        result as AudioSourceResult.Unavailable
        assertTrue(result.summary.contains("Surround"))
        assertTrue(result.detail!!.contains("stereo"))
        assertFalse(driver.started)
    }

    @Test
    fun `surround is attempted when the multistream switch is on`() = runBlocking {
        val result = factory(multistreamEnabled = true).open(
            request(channelCount = 6, mapping = intArrayOf(0, 1, 2, 5, 3, 4), streams = 4, coupledStreams = 2),
        )

        assertTrue(result is AudioSourceResult.Ready)
        result as AudioSourceResult.Ready
        assertEquals(6, result.format.channelCount)
        assertTrue(result.format.isMultistream)
        assertTrue(driver.started)

        result.onClose()
    }

    @Test
    fun `a device with no Opus decoder is reported, not thrown`() = runBlocking {
        val result = factory(
            driverFactory = { _, _ -> throw IllegalStateException("no audio/opus decoder") },
        ).open(request())

        assertTrue(result is AudioSourceResult.Unavailable)
        result as AudioSourceResult.Unavailable
        assertTrue(result.summary.contains("no usable Opus decoder"))
        assertTrue(result.detail!!.contains("no audio/opus decoder"))
    }

    @Test
    fun `a codec that will not configure releases itself and reports why`() = runBlocking {
        driver.failToStart = true

        val result = factory().open(request())

        assertTrue(result is AudioSourceResult.Unavailable)
        assertTrue((result as AudioSourceResult.Unavailable).summary.contains("could not be configured"))
        assertTrue(driver.released)
        // The socket is never opened when the decoder cannot start.
        assertFalse(channel.closed)
    }

    @Test
    fun `a socket that will not open releases the codec and reports why`() = runBlocking {
        val result = factory(
            receiverFactory = { _, _ -> throw java.net.SocketException("permission denied") },
        ).open(request())

        assertTrue(result is AudioSourceResult.Unavailable)
        result as AudioSourceResult.Unavailable
        assertTrue(result.summary.contains("audio socket could not be opened"))
        assertTrue(driver.released)
    }

    @Test
    fun `an unexpected throw outside every inner guard still returns an explanation`() = runBlocking {
        // A nonsensical packet duration trips AudioStreamFormat's own require(), which sits outside
        // all the specific try/catch blocks. Nothing about audio may reach the session's failure
        // path, so the outermost guard has to catch even a programming error.
        val bad = AudioSourceRequest(
            host = "192.0.2.10",
            port = 48000,
            pingPayload = null,
            channelCount = 2,
            streams = 1,
            coupledStreams = 1,
            mapping = intArrayOf(0, 1),
            packetDurationMs = 0,
        )

        val result = factory().open(bad)

        assertTrue(result is AudioSourceResult.Unavailable)
        assertTrue((result as AudioSourceResult.Unavailable).summary.contains("video only"))
    }

    @Test
    fun `closing twice is harmless`() = runBlocking {
        val result = factory().open(request()) as AudioSourceResult.Ready

        result.onClose()
        result.onClose()

        assertTrue(driver.released)
    }

    @Test
    fun `the default pipeline factory is the real one, not a placeholder`() {
        // Unlike VideoPipeline, the audio path needs no Context, no surface and no decoder probe,
        // so there is nothing for a dependency graph to inject and nothing for a preview to break.
        AudioPipeline.resetForTesting()

        assertTrue(AudioPipeline.audioSourceFactory is DefaultAudioSourceFactory)
    }
}
