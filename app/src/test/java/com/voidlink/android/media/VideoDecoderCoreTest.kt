package com.voidlink.android.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Drives [VideoDecoderCore] through its whole state machine against a [FakeCodecDriver].
 *
 * These are the tests that stand in for the ones we cannot write: `MediaCodec` needs a device, so
 * the queueing, keyframe gating, drop accounting, buffer-lifecycle and recovery behaviour all live
 * on this side of the [CodecDriver] boundary, where CI can reach them.
 */
class VideoDecoderCoreTest {

    private val format = VideoStreamFormat(VideoCodecType.HEVC, 1920, 1080, 60)

    private fun frame(
        number: Int,
        keyFrame: Boolean,
        length: Int = 1024,
        onReleased: (() -> Unit)? = null,
    ): VideoFrame = VideoFrame(
        data = ByteArray(length),
        frameNumber = number,
        keyFrame = keyFrame,
        onReleased = onReleased,
    )

    private fun core(
        driver: FakeCodecDriver,
        clock: FakeClock = FakeClock(),
        events: MutableList<DecoderEvent> = mutableListOf(),
        queueCapacity: Int = 2,
        maxRecoveryAttempts: Int = 3,
    ): VideoDecoderCore = VideoDecoderCore(
        driver = driver,
        format = format,
        clock = clock,
        queueCapacity = queueCapacity,
        maxRecoveryAttempts = maxRecoveryAttempts,
        onEvent = { event -> events += event },
    )

    @Test
    fun `start reports the codec it started`() {
        val driver = FakeCodecDriver()
        val events = mutableListOf<DecoderEvent>()
        val decoder = core(driver, events = events)

        assertTrue(decoder.start())

        assertEquals(DecoderPhase.RUNNING, decoder.phase)
        assertEquals(1, driver.startCount)
        val started = events.filterIsInstance<DecoderEvent.Started>().singleOrNull()
        assertNotNull(started)
        assertEquals("fake.hw.decoder", started?.decoderName)
    }

    @Test
    fun `a codec that cannot be configured fails fatally rather than silently`() {
        val driver = FakeCodecDriver().apply { failOnStart = true }
        val events = mutableListOf<DecoderEvent>()
        val decoder = core(driver, events = events)

        assertFalse(decoder.start())

        assertEquals(DecoderPhase.FAILED, decoder.phase)
        val fatal = events.filterIsInstance<DecoderEvent.FatalError>().singleOrNull()
        assertNotNull(fatal)
        assertTrue(fatal?.message?.contains("fake.hw.decoder") == true)
    }

    @Test
    fun `frames before the first keyframe are dropped, not decoded`() {
        val driver = FakeCodecDriver()
        val decoder = core(driver)
        decoder.start()
        driver.offerInputBuffer(0)

        assertTrue(decoder.submit(frame(number = 1, keyFrame = false)))
        assertTrue(decoder.submit(frame(number = 2, keyFrame = false)))

        assertTrue(driver.submissions.isEmpty())
        assertEquals(2L, decoder.stats().framesDropped)
    }

    @Test
    fun `the first keyframe is submitted as soon as an input buffer is free`() {
        val driver = FakeCodecDriver()
        val decoder = core(driver)
        decoder.start()

        // No input buffer yet: the frame waits rather than being thrown away.
        assertTrue(decoder.submit(frame(number = 1, keyFrame = true)))
        assertTrue(driver.submissions.isEmpty())

        driver.offerInputBuffer(7)

        assertEquals(1, driver.submissions.size)
        assertEquals(7, driver.submissions[0].bufferIndex)
        assertTrue(driver.submissions[0].keyFrame)
    }

    @Test
    fun `queue overflow clears the queue, counts the drops and asks for a keyframe`() {
        val driver = FakeCodecDriver()
        val events = mutableListOf<DecoderEvent>()
        val decoder = core(driver, events = events, queueCapacity = 2)
        decoder.start()

        assertTrue(decoder.submit(frame(number = 1, keyFrame = true)))
        assertTrue(decoder.submit(frame(number = 2, keyFrame = false)))
        // Third frame with no input buffers: capacity is exceeded.
        assertFalse(decoder.submit(frame(number = 3, keyFrame = false)))

        assertEquals(3L, decoder.stats().framesDropped)
        assertTrue(events.any { it is DecoderEvent.KeyFrameRequested })

        // Everything is discarded until a keyframe arrives.
        driver.offerInputBuffer(0)
        assertTrue(driver.submissions.isEmpty())
        decoder.submit(frame(number = 4, keyFrame = false))
        assertTrue(driver.submissions.isEmpty())
        decoder.submit(frame(number = 5, keyFrame = true))
        assertEquals(1, driver.submissions.size)
        assertEquals(5, driver.submissions[0].frameNumber)
    }

    @Test
    fun `every frame is released exactly once, whether decoded or dropped`() {
        val driver = FakeCodecDriver()
        val decoder = core(driver, queueCapacity = 2)
        decoder.start()

        val releases = IntArray(4)
        val frames = (0 until 4).map { index ->
            frame(number = index, keyFrame = index == 0) { releases[index]++ }
        }

        driver.offerInputBuffer(0)
        frames.forEach { decoder.submit(it) }
        decoder.release()

        assertTrue(frames.all { it.isReleased })
        assertTrue(releases.all { it == 1 })
    }

    @Test
    fun `a frame the codec refuses is dropped and triggers a keyframe request`() {
        val driver = FakeCodecDriver().apply { rejectSubmissions = true }
        val events = mutableListOf<DecoderEvent>()
        val decoder = core(driver, events = events)
        decoder.start()
        driver.offerInputBuffer(0)

        decoder.submit(frame(number = 1, keyFrame = true))

        assertEquals(1L, decoder.stats().framesDropped)
        assertTrue(
            events.any {
                it is DecoderEvent.KeyFrameRequested && it.reason.contains("rejected")
            },
        )
    }

    @Test
    fun `an exception from the codec on submit degrades to a transient error`() {
        val driver = FakeCodecDriver().apply { throwOnSubmit = true }
        val events = mutableListOf<DecoderEvent>()
        val decoder = core(driver, events = events)
        decoder.start()
        driver.offerInputBuffer(0)

        decoder.submit(frame(number = 1, keyFrame = true))

        assertEquals(DecoderPhase.RUNNING, decoder.phase)
        assertTrue(events.any { it is DecoderEvent.TransientError })
    }

    @Test
    fun `rendering the first frame is announced once`() {
        val driver = FakeCodecDriver()
        val events = mutableListOf<DecoderEvent>()
        val decoder = core(driver, events = events)
        decoder.start()
        driver.offerInputBuffer(0)
        decoder.submit(frame(number = 1, keyFrame = true))
        val pts = driver.submissions[0].presentationTimeUs

        driver.completeOutput(pts, index = 3)
        driver.offerInputBuffer(1)
        decoder.submit(frame(number = 2, keyFrame = false))
        driver.completeOutput(driver.submissions[1].presentationTimeUs, index = 4)

        assertEquals(listOf(3, 4), driver.rendered)
        assertEquals(1, events.count { it is DecoderEvent.FirstFrameRendered })
    }

    @Test
    fun `decode time is measured from submission to output`() {
        val clock = FakeClock(micros = 1_000_000L)
        val driver = FakeCodecDriver()
        val decoder = core(driver, clock = clock)
        decoder.start()
        driver.offerInputBuffer(0)
        decoder.submit(frame(number = 1, keyFrame = true))
        val pts = driver.submissions[0].presentationTimeUs

        clock.advance(5_000L)
        driver.completeOutput(pts)

        clock.advance(495_000L)
        val stats = decoder.stats()
        assertEquals(5.0f, stats.averageDecodeTimeMs, 0.001f)
        assertEquals(5.0f, stats.peakDecodeTimeMs, 0.001f)
        assertEquals(1L, stats.framesDecoded)
        assertEquals(1L, stats.framesSubmitted)
    }

    @Test
    fun `presentation timestamps strictly increase even inside one microsecond`() {
        val clock = FakeClock(micros = 42L)
        val driver = FakeCodecDriver()
        val decoder = core(driver)
        val decoderWithClock = VideoDecoderCore(driver, format, clock, onEvent = {})
        decoderWithClock.start()
        driver.offerInputBuffer(0)
        driver.offerInputBuffer(1)
        decoderWithClock.submit(frame(number = 1, keyFrame = true))
        decoderWithClock.submit(frame(number = 2, keyFrame = false))

        assertEquals(2, driver.submissions.size)
        assertTrue(driver.submissions[1].presentationTimeUs > driver.submissions[0].presentationTimeUs)
        // Silences the unused-variable warning while documenting that the default clock is fine.
        assertEquals(DecoderPhase.IDLE, decoder.phase)
    }

    @Test
    fun `a transient codec error flushes and keeps streaming`() {
        val driver = FakeCodecDriver()
        val events = mutableListOf<DecoderEvent>()
        val decoder = core(driver, events = events)
        decoder.start()
        driver.offerInputBuffer(0)
        decoder.submit(frame(number = 1, keyFrame = true))

        driver.reportFailure(CodecFailure("busy", transient = true))

        assertEquals(DecoderPhase.RUNNING, decoder.phase)
        assertEquals(1, driver.flushCount)
        assertEquals(0, driver.restartCount)
        assertTrue(events.any { it is DecoderEvent.TransientError })
        assertTrue(events.any { it is DecoderEvent.KeyFrameRequested })
    }

    @Test
    fun `a recoverable codec error rebuilds the codec instead of ending the session`() {
        val driver = FakeCodecDriver()
        val events = mutableListOf<DecoderEvent>()
        val decoder = core(driver, events = events)
        decoder.start()

        driver.reportFailure(CodecFailure("hardware hiccup", recoverable = true))

        assertEquals(DecoderPhase.RUNNING, decoder.phase)
        assertEquals(1, driver.restartCount)
        assertTrue(events.any { it is DecoderEvent.Recovered })
    }

    @Test
    fun `stale input buffer indices are dropped across a rebuild`() {
        val driver = FakeCodecDriver()
        val decoder = core(driver)
        decoder.start()
        driver.offerInputBuffer(5)

        driver.reportFailure(CodecFailure("hardware hiccup", recoverable = true))
        decoder.submit(frame(number = 1, keyFrame = true))

        // Buffer 5 belonged to the codec that no longer exists, so nothing may be queued yet.
        assertTrue(driver.submissions.isEmpty())

        driver.offerInputBuffer(0)
        assertEquals(1, driver.submissions.size)
        assertEquals(0, driver.submissions[0].bufferIndex)
    }

    @Test
    fun `a rebuild that itself fails is fatal`() {
        val driver = FakeCodecDriver().apply { failOnRestart = true }
        val events = mutableListOf<DecoderEvent>()
        val decoder = core(driver, events = events)
        decoder.start()

        driver.reportFailure(CodecFailure("hardware hiccup", recoverable = true))

        assertEquals(DecoderPhase.FAILED, decoder.phase)
        assertTrue(events.any { it is DecoderEvent.FatalError })
    }

    @Test
    fun `the recovery budget runs out`() {
        val driver = FakeCodecDriver()
        val events = mutableListOf<DecoderEvent>()
        val decoder = core(driver, events = events, maxRecoveryAttempts = 2)
        decoder.start()

        driver.reportFailure(CodecFailure("hiccup", recoverable = true))
        driver.reportFailure(CodecFailure("hiccup", recoverable = true))
        driver.reportFailure(CodecFailure("hiccup", recoverable = true))

        assertEquals(2, driver.restartCount)
        assertEquals(DecoderPhase.FAILED, decoder.phase)
        assertEquals(1, events.count { it is DecoderEvent.FatalError })
    }

    @Test
    fun `a successfully rendered frame restores the recovery budget`() {
        val driver = FakeCodecDriver()
        val decoder = core(driver, maxRecoveryAttempts = 1)
        decoder.start()

        driver.reportFailure(CodecFailure("hiccup", recoverable = true))
        assertEquals(DecoderPhase.RUNNING, decoder.phase)

        driver.offerInputBuffer(0)
        decoder.submit(frame(number = 1, keyFrame = true))
        driver.completeOutput(driver.submissions[0].presentationTimeUs)

        driver.reportFailure(CodecFailure("hiccup", recoverable = true))
        assertEquals(DecoderPhase.RUNNING, decoder.phase)
        assertEquals(2, driver.restartCount)
    }

    @Test
    fun `an unclassified codec error ends the session with the vendor diagnostic`() {
        val driver = FakeCodecDriver()
        val events = mutableListOf<DecoderEvent>()
        val decoder = core(driver, events = events)
        decoder.start()

        driver.reportFailure(CodecFailure("component failure", diagnosticInfo = "android.media.err(-1)"))

        assertEquals(DecoderPhase.FAILED, decoder.phase)
        val fatal = events.filterIsInstance<DecoderEvent.FatalError>().single()
        assertTrue(fatal.message.contains("component failure"))
        assertTrue(fatal.message.contains("android.media.err(-1)"))
    }

    @Test
    fun `submitting after release is refused, not queued`() {
        val driver = FakeCodecDriver()
        val decoder = core(driver)
        decoder.start()
        decoder.release()

        val orphan = frame(number = 1, keyFrame = true)
        assertFalse(decoder.submit(orphan))
        assertTrue(orphan.isReleased)
        assertTrue(driver.submissions.isEmpty())
    }

    @Test
    fun `release is idempotent`() {
        val driver = FakeCodecDriver()
        val events = mutableListOf<DecoderEvent>()
        val decoder = core(driver, events = events)
        decoder.start()

        decoder.release()
        decoder.release()

        assertEquals(DecoderPhase.RELEASED, decoder.phase)
        assertEquals(1, driver.releaseCount)
        assertEquals(1, events.count { it is DecoderEvent.Released })
    }

    @Test
    fun `output buffers arriving after release are discarded rather than rendered`() {
        val driver = FakeCodecDriver()
        val decoder = core(driver)
        decoder.start()
        decoder.release()

        driver.completeOutput(presentationTimeUs = 1L, index = 9)

        assertTrue(driver.rendered.isEmpty())
        assertEquals(listOf(9), driver.discarded)
    }

    @Test
    fun `a format change is reported through`() {
        val driver = FakeCodecDriver()
        val events = mutableListOf<DecoderEvent>()
        val decoder = core(driver, events = events)
        decoder.start()

        driver.reportFormat(1280, 720)

        val changed = events.filterIsInstance<DecoderEvent.FormatChanged>().single()
        assertEquals(1280, changed.width)
        assertEquals(720, changed.height)
    }

    @Test
    fun `an explicit flush re-arms the keyframe gate`() {
        val driver = FakeCodecDriver()
        val decoder = core(driver)
        decoder.start()
        driver.offerInputBuffer(0)
        decoder.submit(frame(number = 1, keyFrame = true))
        assertEquals(1, driver.submissions.size)

        decoder.flush()
        driver.offerInputBuffer(1)
        decoder.submit(frame(number = 2, keyFrame = false))

        assertEquals(1, driver.flushCount)
        assertEquals(1, driver.submissions.size)
    }
}
