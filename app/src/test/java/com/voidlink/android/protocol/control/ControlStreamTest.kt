package com.voidlink.android.protocol.control

import com.voidlink.android.protocol.Hex
import com.voidlink.android.protocol.enet.EnetDelivery
import com.voidlink.android.protocol.enet.EnetUnverifiedConstants
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The control stream's behaviour: the start sequence, the ping cadence, the IDR rate limit, channel
 * discipline and host-feedback dispatch (`docs/01-PROTOCOL.md` §9.4–§9.7).
 *
 * The rate limiter and the teardown order are the two behaviours here that a user would notice if
 * they broke, and neither is visible from a packet fixture: a lossy link that produces one IDR
 * request per lost frame is spec §9.5's "IDR storm that makes things worse", and a teardown that
 * skips the termination message leaves the host holding a session for half a minute (spec §9.7).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ControlStreamTest {

    private fun streamOf(
        link: FakeControlLink,
        generation: Int = 7,
        isSunshine: Boolean = true,
        usePeriodicPing: Boolean = true,
        clock: () -> Long = { 0L },
    ) = ControlStream(
        link = link,
        table = ControlMessageTable.forHost(generation, encrypted = false),
        generation = generation,
        isSunshine = isSunshine,
        usePeriodicPing = usePeriodicPing,
        clock = clock,
    )

    // ---- Session start (spec §9.4) ---------------------------------------------------------------

    @Test
    fun `starting sends Start A then Start B reliably on the generic channel`() = runTest {
        val link = FakeControlLink()
        streamOf(link).start(backgroundScope)
        runCurrent()

        // Start A first, then Start B, both before anything else goes out. The first periodic ping
        // follows immediately, which is why this asserts the prefix rather than the whole list.
        assertEquals("05030000", link.sent[0].hex())
        assertEquals("070300", link.sent[1].hex())
        for (message in link.sent.take(2)) {
            assertEquals(EnetUnverifiedConstants.CHANNEL_GENERIC, message.channelId)
            assertEquals(EnetDelivery.RELIABLE, message.delivery)
        }
    }

    // ---- Periodic ping (spec §9.5) ---------------------------------------------------------------

    @Test
    fun `the periodic ping repeats on its interval and is always reliable`() = runTest {
        val link = FakeControlLink()
        val stream = streamOf(link)
        stream.start(backgroundScope)
        runCurrent()

        advanceTimeBy(UnverifiedControlConstants.PERIODIC_PING_INTERVAL_MS * 5)
        runCurrent()

        val pings = link.sent.filter { it.hex().startsWith("0002") }
        // One immediately, then one per interval: five intervals of virtual time is five or six
        // pings depending on where the boundary falls. The point is the cadence, not the fencepost.
        assertTrue("expected repeated pings, got ${pings.size}", pings.size >= 5)
        for (ping in pings) {
            assertEquals("0002" + "0400000000000000", ping.hex())
            assertEquals(EnetDelivery.RELIABLE, ping.delivery)
            assertEquals(EnetUnverifiedConstants.CHANNEL_GENERIC, ping.channelId)
        }
        assertEquals(pings.size.toLong(), stream.stats().pingsSent)
    }

    @Test
    fun `a host too old for the periodic ping gets loss statistics instead`() = runTest {
        val link = FakeControlLink()
        val stream = streamOf(link, usePeriodicPing = false)
        stream.start(backgroundScope)
        runCurrent()

        val report = link.sent.last()
        // Type 0x0201 little-endian, then the 32-byte payload.
        assertTrue(report.hex().startsWith("0102"))
        assertEquals(ControlConstants.HEADER_SIZE_V1 + 32, report.payload.size)
    }

    // ---- IDR requests (spec §9.5) ----------------------------------------------------------------

    @Test
    fun `an IDR request on Gen 7 is a reference-frame invalidation on the urgent channel`() {
        val link = FakeControlLink()
        val stream = streamOf(link)
        stream.onFrameProgress(lastSeenFrameIndex = 0x50L, lastGoodFrameIndex = 0x4fL)

        assertTrue(stream.requestIdrFrame())

        val request = link.sent.single()
        assertEquals(EnetUnverifiedConstants.CHANNEL_URGENT, request.channelId)
        assertEquals(EnetDelivery.RELIABLE, request.delivery)
        assertEquals(
            "0103" + "3000000000000000" + "5000000000000000" + "0000000000000000",
            request.hex(),
        )
    }

    @Test
    fun `an IDR request on a host with a real IDR message uses it`() {
        val link = FakeControlLink()
        val stream = ControlStream(
            link = link,
            table = ControlMessageTable.GEN7_ENCRYPTED,
            generation = 7,
            isSunshine = true,
            usePeriodicPing = true,
            clock = { 0L },
        )
        assertTrue(stream.requestIdrFrame())
        assertEquals("0203" + "0000", link.sent.single().hex())
    }

    @Test
    fun `a burst of loss produces one IDR request, not one per frame`() {
        // Spec §9.5: "at most one per ~100 ms, or a lossy link turns into an IDR storm that makes
        // things worse". This is the test that stands between a lossy link and that storm.
        var nanos = 0L
        val link = FakeControlLink()
        val stream = streamOf(link, clock = { nanos })

        assertTrue(stream.requestIdrFrame())
        repeat(19) { assertFalse(stream.requestIdrFrame()) }
        assertEquals(1, link.sent.size)
        assertEquals(1L, stream.stats().idrRequestsSent)
        assertEquals(19L, stream.stats().idrRequestsSuppressed)

        // Just short of the window: still suppressed.
        nanos = (UnverifiedControlConstants.IDR_REQUEST_MIN_INTERVAL_MS - 1) * 1_000_000L
        assertFalse(stream.requestIdrFrame())
        assertEquals(1, link.sent.size)

        // At the window: allowed again.
        nanos = UnverifiedControlConstants.IDR_REQUEST_MIN_INTERVAL_MS * 1_000_000L
        assertTrue(stream.requestIdrFrame())
        assertEquals(2, link.sent.size)
        assertEquals(2L, stream.stats().idrRequestsSent)
    }

    // ---- Channel discipline (spec §9.1) -----------------------------------------------------------

    @Test
    fun `a non-Sunshine host gets everything reliably on channel zero`() {
        val link = FakeControlLink()
        val stream = streamOf(link, isSunshine = false)
        stream.requestIdrFrame()
        stream.sendFrameFecStatus(fecStatus())

        // The FEC status is a Sunshine extension and is not sent at all to GFE.
        assertEquals(1, link.sent.size)
        assertEquals(EnetUnverifiedConstants.CHANNEL_GENERIC, link.sent[0].channelId)
        assertEquals(EnetDelivery.RELIABLE, link.sent[0].delivery)
    }

    @Test
    fun `a peer that negotiated fewer channels than we ask for gets channel zero`() {
        val link = FakeControlLink(negotiatedChannelCount = 1)
        streamOf(link).requestIdrFrame()
        assertEquals(EnetUnverifiedConstants.CHANNEL_GENERIC, link.sent.single().channelId)
    }

    @Test
    fun `the per-frame FEC status is unsequenced on a Sunshine host`() {
        val link = FakeControlLink()
        assertTrue(streamOf(link).sendFrameFecStatus(fecStatus()))
        val message = link.sent.single()
        assertEquals(EnetDelivery.UNSEQUENCED, message.delivery)
        assertEquals(EnetUnverifiedConstants.CHANNEL_GENERIC, message.channelId)
        assertTrue(message.hex().startsWith("0255")) // type 0x5502, little-endian
    }

    // ---- Host feedback (spec §9.6) ------------------------------------------------------------------

    @Test
    fun `a termination message becomes a Terminated event with its big-endian code`() = runTest {
        val link = FakeControlLink()
        val stream = streamOf(link)
        stream.start(backgroundScope)
        link.deliver(Hex.decodeOrNull("0001" + "800e9403")!!)

        val event = withTimeout(TIMEOUT_MS) { stream.events.receive() }
        val terminated = event as ControlEvent.Terminated
        assertEquals(ControlConstants.TERMINATION_FRAME_CONVERSION, terminated.errorCode)
        assertFalse(terminated.graceful)
        assertTrue(terminated.describe().contains("video encoder"))
    }

    @Test
    fun `a termination with no code says so rather than inventing one`() = runTest {
        val link = FakeControlLink()
        val stream = streamOf(link)
        stream.start(backgroundScope)
        link.deliver(Hex.decodeOrNull("0001")!!)

        val terminated = withTimeout(TIMEOUT_MS) { stream.events.receive() } as ControlEvent.Terminated
        assertNull(terminated.errorCode)
        assertTrue(terminated.describe().contains("without giving a reason"))
    }

    @Test
    fun `an HDR message reports only its enable flag`() = runTest {
        val link = FakeControlLink()
        val stream = streamOf(link)
        stream.start(backgroundScope)
        link.deliver(Hex.decodeOrNull("0e01" + "01" + "deadbeef")!!)

        val hdr = withTimeout(TIMEOUT_MS) { stream.events.receive() } as ControlEvent.HdrModeChanged
        assertTrue(hdr.enabled)
        assertEquals(5, hdr.payload.size)
    }

    @Test
    fun `a rumble payload long enough for the leading bytes is read from offset four`() = runTest {
        val link = FakeControlLink()
        val stream = streamOf(link)
        stream.start(backgroundScope)
        // Type 0x010b, four leading bytes, then controller 1, low 0x2211, high 0x4433.
        link.deliver(Hex.decodeOrNull("0b01" + "00000000" + "0100" + "1122" + "3344")!!)

        val rumble = withTimeout(TIMEOUT_MS) { stream.events.receive() } as ControlEvent.Rumble
        assertTrue(rumble.fromOffsetFour)
        assertEquals(1, rumble.controllerNumber)
        assertEquals(0x2211, rumble.lowFrequencyMotor)
        assertEquals(0x4433, rumble.highFrequencyMotor)
    }

    @Test
    fun `a short rumble payload is read from offset zero`() = runTest {
        val link = FakeControlLink()
        val stream = streamOf(link)
        stream.start(backgroundScope)
        link.deliver(Hex.decodeOrNull("0b01" + "0200" + "1122" + "3344")!!)

        val rumble = withTimeout(TIMEOUT_MS) { stream.events.receive() } as ControlEvent.Rumble
        assertFalse(rumble.fromOffsetFour)
        assertEquals(2, rumble.controllerNumber)
    }

    @Test
    fun `an unrecognised message is reported and counted, never treated as an error`() = runTest {
        val link = FakeControlLink()
        val stream = streamOf(link)
        stream.start(backgroundScope)
        link.deliver(Hex.decodeOrNull("0055" + "0102")!!) // 0x5500, a Sunshine extension

        val event = withTimeout(TIMEOUT_MS) { stream.events.receive() } as ControlEvent.Unrecognized
        assertEquals(0x5500, event.type)
        assertEquals(2, event.payloadLength)
        assertEquals(1L, stream.stats().unrecognizedReceived)
        assertEquals(1L, stream.stats().messagesReceived)
    }

    // ---- Teardown (spec §9.7) -------------------------------------------------------------------

    @Test
    fun `teardown sends the termination message before disconnecting`() = runTest {
        val link = FakeControlLink()
        val stream = streamOf(link)

        assertTrue(stream.terminate())

        val last = link.sent.single()
        assertEquals("0001", last.hex())
        assertEquals(EnetUnverifiedConstants.CHANNEL_URGENT, last.channelId)
        assertEquals(1, link.disconnectCount)
        assertEquals(ControlConstants.LINGER_TIMEOUT_MS, link.lastLingerMs)
    }

    @Test
    fun `an unacknowledged disconnect is reported rather than retried`() = runTest {
        val link = FakeControlLink()
        link.disconnectAcknowledged = false
        assertFalse(streamOf(link).terminate())
        assertEquals(1, link.disconnectCount)
    }

    @Test
    fun `a host with no termination message still disconnects`() = runTest {
        val link = FakeControlLink()
        // Gen 5 has no termination type at all (spec §9.3).
        assertTrue(streamOf(link, generation = 5).terminate())
        assertTrue(link.sent.isEmpty())
        assertEquals(1, link.disconnectCount)
    }

    private fun fecStatus() = FrameFecStatus(
        frameIndex = 1L,
        highestReceivedSequenceNumber = 2,
        nextContiguousSequenceNumber = 3,
        missingPacketsBeforeHighestReceived = 0,
        totalDataPackets = 4,
        totalParityPackets = 1,
        receivedDataPackets = 4,
        receivedParityPackets = 1,
        fecPercentage = 20,
        multiFecBlockIndex = 0,
        multiFecBlockCount = 1,
    )

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
