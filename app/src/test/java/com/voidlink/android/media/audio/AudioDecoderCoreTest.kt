package com.voidlink.android.media.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The audio decoder's behaviour: concealment, drift, error tolerance, and the rule that outranks all
 * of them — **audio failing must never end the session** (`docs/01-PROTOCOL.md` §8.5).
 */
class AudioDecoderCoreTest {

    private val format = AudioStreamFormat.stereo()
    private val clock = FakeAudioClock()
    private val events = mutableListOf<AudioPlaybackEvent>()

    private fun core(
        driver: FakeAudioCodecDriver = FakeAudioCodecDriver(),
        policy: AudioLatencyPolicy = AudioLatencyPolicy(),
        maxConsecutiveErrors: Int = AudioDecoderCore.DEFAULT_MAX_CONSECUTIVE_ERRORS,
    ) = AudioDecoderCore(
        driver = driver,
        format = format,
        policy = policy,
        clock = clock,
        maxConsecutiveErrors = maxConsecutiveErrors,
        onEvent = { events += it },
    )

    private fun packet(size: Int = 40): ByteArray = ByteArray(size) { it.toByte() }

    @Test
    fun `starting configures the driver and announces the format`() {
        val driver = FakeAudioCodecDriver()
        val core = core(driver)

        assertTrue(core.start())

        assertTrue(driver.started)
        assertEquals(AudioDecoderPhase.RUNNING, core.phase)
        val started = events.filterIsInstance<AudioPlaybackEvent.Started>().single()
        assertEquals("fake.opus.decoder", started.decoderName)
    }

    @Test
    fun `a device with no Opus decoder stops audio and does not throw`() {
        // The whole contract of this layer in one test: no exception escapes, so nothing above can
        // mistake "no audio" for "no stream".
        val driver = FakeAudioCodecDriver().apply { failToStart = true }
        val core = core(driver)

        assertFalse(core.start())

        assertEquals(AudioDecoderPhase.FAILED, core.phase)
        val stopped = events.filterIsInstance<AudioPlaybackEvent.Stopped>().single()
        assertTrue(stopped.failure.message.contains("could not be started"))
    }

    @Test
    fun `packets are decoded and played while the backlog is small`() {
        val driver = FakeAudioCodecDriver()
        val core = core(driver)
        core.start()

        repeat(4) {
            // The device keeps up: everything written has been played.
            driver.playbackHeadFrames += 240L
            assertTrue(core.submit(packet()))
        }

        assertEquals(4, driver.playedCount())
        assertEquals(0, driver.droppedCount())
        assertEquals(4L, core.stats().packetsPlayed)
        assertEquals(0L, core.stats().packetsDroppedForLatency)
    }

    @Test
    fun `a backlog past the threshold is dropped rather than queued`() {
        // The single most important behaviour in the audio path. A video decoder that falls behind
        // catches up; an AudioTrack plays exactly one second per second and never does, so audio
        // that drifts 200 ms behind video stays there for the rest of the session.
        val driver = FakeAudioCodecDriver()
        val core = core(driver, policy = AudioLatencyPolicy(maxBacklogMs = 40))
        core.start()

        // The device plays nothing at all: every packet written adds 5 ms of backlog.
        repeat(20) { core.submit(packet()) }

        // 40 ms of backlog is 1920 frames = 8 packets; the ninth is the first past the threshold.
        assertEquals(9, driver.playedCount())
        assertEquals(11, driver.droppedCount())
        assertEquals(11L, core.stats().packetsDroppedForLatency)
    }

    @Test
    fun `a dropped packet is still decoded, because Opus is predictive`() {
        // Skipping the decode entirely would leave the decoder's state referring to audio it never
        // saw, and the next packet that *is* played would arrive with an audible artefact.
        val driver = FakeAudioCodecDriver()
        val core = core(driver, policy = AudioLatencyPolicy(maxBacklogMs = 1))
        core.start()

        core.submit(packet())
        core.submit(packet())
        core.submit(packet())

        assertEquals(3, driver.decoded.size)
        assertTrue(driver.decoded.any { !it.played })
    }

    @Test
    fun `the backlog stops growing once dropping starts`() {
        val driver = FakeAudioCodecDriver()
        val core = core(driver, policy = AudioLatencyPolicy(maxBacklogMs = 40))
        core.start()

        repeat(200) { core.submit(packet()) }

        // Everything written is bounded by the threshold plus the one packet that crossed it.
        val backlogMs = core.stats().backlogMs
        assertTrue("backlog was ${backlogMs}ms", backlogMs <= 45)
    }

    @Test
    fun `catching up resumes playback and reports the burst once`() {
        val driver = FakeAudioCodecDriver()
        val core = core(driver, policy = AudioLatencyPolicy(maxBacklogMs = 40))
        core.start()

        repeat(20) { core.submit(packet()) }
        assertTrue(driver.droppedCount() > 0)

        // The device drains everything we wrote.
        driver.playbackHeadFrames = 100_000L
        assertTrue(core.submit(packet()))

        val trimmed = events.filterIsInstance<AudioPlaybackEvent.BacklogTrimmed>().single()
        assertEquals(11L, trimmed.dropped)
    }

    @Test
    fun `a concealment submission decodes nothing and asks the driver for silence`() {
        val driver = FakeAudioCodecDriver()
        val core = core(driver)
        core.start()

        driver.playbackHeadFrames += 240L
        core.submit(ByteArray(0), concealment = true)

        val decoded = driver.decoded.single()
        assertTrue(decoded.concealment)
        assertEquals(0, decoded.length)
        assertEquals(1L, core.stats().packetsConcealed)
        assertEquals(1L, core.stats().packetsDecoded)
    }

    @Test
    fun `concealment obeys the drift policy too`() {
        // A loss burst during a stall must not itself become a backlog.
        val driver = FakeAudioCodecDriver()
        val core = core(driver, policy = AudioLatencyPolicy(maxBacklogMs = 5))
        core.start()

        repeat(10) { core.submit(ByteArray(0), concealment = true) }

        assertTrue(driver.droppedCount() > 0)
        assertEquals(10L, core.stats().packetsConcealed)
    }

    @Test
    fun `the first packet to reach the speaker is announced once`() {
        val driver = FakeAudioCodecDriver()
        val core = core(driver)
        core.start()

        driver.playbackHeadFrames += 240L
        core.submit(packet())
        driver.playbackHeadFrames += 240L
        core.submit(packet())

        assertEquals(1, events.count { it is AudioPlaybackEvent.FirstPacketPlayed })
    }

    @Test
    fun `underruns are differenced from the platform's lifetime counter`() {
        val driver = FakeAudioCodecDriver()
        val core = core(driver)
        core.start()

        driver.underruns = 3
        core.submit(packet())
        driver.underruns = 5
        core.submit(packet())

        assertEquals(5L, core.stats().underruns)
        assertEquals(2, events.count { it is AudioPlaybackEvent.Underrun })
    }

    @Test
    fun `a platform that cannot report underruns reports none rather than a negative count`() {
        val driver = FakeAudioCodecDriver().apply { underruns = -1 }
        val core = core(driver)
        core.start()

        repeat(3) { core.submit(packet()) }

        assertEquals(0L, core.stats().underruns)
    }

    @Test
    fun `an underruns-since-last-snapshot figure resets on every snapshot`() {
        val driver = FakeAudioCodecDriver()
        val core = core(driver)
        core.start()

        driver.underruns = 2
        core.submit(packet())
        assertEquals(2L, core.stats().underrunsThisInterval)
        assertEquals(0L, core.stats().underrunsThisInterval)
        assertEquals(2L, core.stats().underruns)
    }

    @Test
    fun `one decode failure is counted and does not stop audio`() {
        val driver = FakeAudioCodecDriver().apply { failDecode = true }
        val core = core(driver, maxConsecutiveErrors = 4)
        core.start()

        assertFalse(core.submit(packet()))
        driver.failDecode = false
        driver.playbackHeadFrames += 240L
        assertTrue(core.submit(packet()))

        assertEquals(1L, core.stats().decodeErrors)
        assertEquals(AudioDecoderPhase.RUNNING, core.phase)
        assertTrue(events.filterIsInstance<AudioPlaybackEvent.Stopped>().isEmpty())
    }

    @Test
    fun `a run of failures stops audio, and the session is expected to carry on`() {
        val driver = FakeAudioCodecDriver().apply { failDecode = true }
        val core = core(driver, maxConsecutiveErrors = 3)
        core.start()

        repeat(3) { core.submit(packet()) }

        assertEquals(AudioDecoderPhase.FAILED, core.phase)
        assertFalse(core.isRunning)
        val stopped = events.filterIsInstance<AudioPlaybackEvent.Stopped>().single()
        assertTrue(stopped.failure.message.contains("stream continues without it"))
        // And nothing further is submitted to a dead codec.
        val before = driver.decoded.size
        core.submit(packet())
        assertEquals(before, driver.decoded.size)
    }

    @Test
    fun `the failure run resets after a successful decode`() {
        // Otherwise three glitches spread over an hour would silence a session.
        val driver = FakeAudioCodecDriver()
        val core = core(driver, maxConsecutiveErrors = 3)
        core.start()

        repeat(10) {
            driver.failDecode = true
            core.submit(packet())
            driver.failDecode = false
            driver.playbackHeadFrames += 240L
            core.submit(packet())
        }

        assertEquals(AudioDecoderPhase.RUNNING, core.phase)
        assertEquals(10L, core.stats().decodeErrors)
    }

    @Test
    fun `a driver that throws is treated as a decode error, not an escaping exception`() {
        val driver = FakeAudioCodecDriver().apply { throwOnDecode = true }
        val core = core(driver, maxConsecutiveErrors = 2)
        core.start()

        // No try/catch here on purpose: an exception escaping submit would reach the session.
        assertFalse(core.submit(packet()))
        assertFalse(core.submit(packet()))

        assertEquals(AudioDecoderPhase.FAILED, core.phase)
        assertNotNull(events.filterIsInstance<AudioPlaybackEvent.Stopped>().singleOrNull())
    }

    @Test
    fun `presentation timestamps are strictly increasing even inside one microsecond`() {
        val driver = FakeAudioCodecDriver()
        val core = core(driver)
        core.start()
        clock.micros = 1_000L

        repeat(4) { core.submit(packet()) }

        val stamps = driver.decoded.map { it.presentationTimeUs }
        assertEquals(stamps.sorted(), stamps)
        assertEquals(stamps.distinct().size, stamps.size)
    }

    @Test
    fun `flushing rebases the backlog on the device's own position`() {
        // After a flush the playback head refers to audio that no longer exists; a backlog computed
        // across it would be meaningless in whichever direction the two counters disagreed.
        val driver = FakeAudioCodecDriver()
        val core = core(driver)
        core.start()
        repeat(4) { core.submit(packet()) }
        driver.playbackHeadFrames = 5_000L

        core.flush()
        driver.playbackHeadFrames = 5_000L

        assertEquals(1, driver.flushes)
        assertTrue(core.submit(packet()))
        assertEquals(0, core.stats().backlogMs)
    }

    @Test
    fun `submitting after release neither throws nor reaches the driver`() {
        val driver = FakeAudioCodecDriver()
        val core = core(driver)
        core.start()
        core.release()

        assertFalse(core.submit(packet()))

        assertTrue(driver.released)
        assertEquals(AudioDecoderPhase.RELEASED, core.phase)
        assertEquals(0, driver.decoded.size)
    }

    @Test
    fun `release is idempotent`() {
        val driver = FakeAudioCodecDriver()
        val core = core(driver)
        core.start()

        core.release()
        core.release()

        assertEquals(AudioDecoderPhase.RELEASED, core.phase)
    }

    @Test
    fun `stats report decoded, dropped and underruns as the video decoder's do`() {
        val driver = FakeAudioCodecDriver()
        val core = core(driver, policy = AudioLatencyPolicy(maxBacklogMs = 5))
        core.start()
        driver.underruns = 1

        core.submit(packet())
        core.submit(packet())
        core.submit(ByteArray(0), concealment = true)

        val stats = core.stats()
        assertEquals(3L, stats.packetsDecoded)
        assertEquals(1L, stats.packetsConcealed)
        assertTrue(stats.packetsDroppedForLatency > 0L)
        assertEquals(1L, stats.underruns)
        assertTrue(stats.describe().contains("decoded=3"))
    }
}
