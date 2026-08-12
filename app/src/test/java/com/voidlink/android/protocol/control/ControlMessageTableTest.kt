package com.voidlink.android.protocol.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The per-generation message-type table of `docs/01-PROTOCOL.md` §9.3.
 *
 * Five columns of small integers is exactly the kind of table that is transcribed once and then
 * never looked at again, so every value the client can actually send or receive is pinned here. The
 * consequence of a wrong number is not a crash: it is a host that ignores us, which is
 * indistinguishable from a network problem from the outside.
 */
class ControlMessageTableTest {

    @Test
    fun `the Gen 7 column matches the spec's table`() {
        val table = ControlMessageTable.GEN7
        assertEquals(0x0305, table.typeOf(ControlMessageIndex.START_A))
        assertEquals(0x0307, table.typeOf(ControlMessageIndex.START_B))
        assertEquals(0x0301, table.typeOf(ControlMessageIndex.INVALIDATE_REFERENCE_FRAMES))
        assertEquals(0x0201, table.typeOf(ControlMessageIndex.LOSS_STATS))
        assertEquals(0x0204, table.typeOf(ControlMessageIndex.FRAME_STATS))
        assertEquals(0x0206, table.typeOf(ControlMessageIndex.INPUT_DATA))
        assertEquals(0x010b, table.typeOf(ControlMessageIndex.RUMBLE))
        assertEquals(0x0100, table.typeOf(ControlMessageIndex.TERMINATION))
        assertEquals(0x010e, table.typeOf(ControlMessageIndex.HDR_MODE))
    }

    @Test
    fun `the Gen 5 column has input but no termination or rumble`() {
        val table = ControlMessageTable.GEN5
        assertEquals(0x0207, table.typeOf(ControlMessageIndex.INPUT_DATA))
        assertNull(table.typeOf(ControlMessageIndex.TERMINATION))
        assertNull(table.typeOf(ControlMessageIndex.RUMBLE))
        assertNull(table.typeOf(ControlMessageIndex.HDR_MODE))
    }

    @Test
    fun `the legacy columns keep their own magic numbers`() {
        assertEquals(0x1407, ControlMessageTable.GEN3.typeOf(ControlMessageIndex.START_A))
        assertEquals(0x140c, ControlMessageTable.GEN3.typeOf(ControlMessageIndex.LOSS_STATS))
        assertEquals(0x0606, ControlMessageTable.GEN4.typeOf(ControlMessageIndex.START_A))
        assertEquals(0x060a, ControlMessageTable.GEN4.typeOf(ControlMessageIndex.LOSS_STATS))
        assertNull(ControlMessageTable.GEN3.typeOf(ControlMessageIndex.INPUT_DATA))
    }

    @Test
    fun `the encrypted Gen 7 column swaps slot zero for a real IDR request`() {
        val table = ControlMessageTable.GEN7_ENCRYPTED
        assertEquals(0x0302, table.typeOf(ControlMessageIndex.START_A))
        assertEquals(0x0109, table.typeOf(ControlMessageIndex.TERMINATION))
        assertTrue(table.supportsIdrRequest)
    }

    @Test
    fun `only the columns whose slot zero is an IDR request advertise one`() {
        // Slot 0 is "Start A" on unencrypted Gen 5/7, so there is no IDR message there and the
        // client has to fall back to a reference-frame invalidation (spec §9.5).
        assertTrue(ControlMessageTable.GEN3.supportsIdrRequest)
        assertTrue(ControlMessageTable.GEN4.supportsIdrRequest)
        assertFalse(ControlMessageTable.GEN5.supportsIdrRequest)
        assertFalse(ControlMessageTable.GEN7.supportsIdrRequest)
        assertTrue(ControlMessageTable.GEN7_ENCRYPTED.supportsIdrRequest)
    }

    @Test
    fun `an inbound type maps back to its slot`() {
        assertEquals(
            ControlMessageIndex.TERMINATION,
            ControlMessageTable.GEN7.indexOf(0x0100),
        )
        assertEquals(ControlMessageIndex.RUMBLE, ControlMessageTable.GEN7.indexOf(0x010b))
        assertEquals(ControlMessageIndex.HDR_MODE, ControlMessageTable.GEN7.indexOf(0x010e))
    }

    @Test
    fun `an unknown type maps to nothing, which is what makes it ignorable`() {
        // Spec §9.3: "v1: ignore unrecognized control message types".
        assertNull(ControlMessageTable.GEN7.indexOf(0x0000))
        assertNull(ControlMessageTable.GEN5.indexOf(0x0100))
        assertNull(ControlMessageTable.GEN3.indexOf(0x5500))
    }

    @Test
    fun `the Sunshine feedback extensions are recognised on an unencrypted Gen 7 host too`() {
        // Spec §9.3's table lists these only in the encrypted column, but they are a Sunshine
        // feature rather than an encryption feature, and v1 negotiates encryptionEnabled=0
        // (spec §6.5) — so without them here, trigger rumble and the host's motion-report request
        // would be discarded as unrecognised on every real Sunshine session.
        assertEquals(
            ControlMessageIndex.RUMBLE_TRIGGERS,
            ControlMessageTable.GEN7.indexOf(0x5500),
        )
        assertEquals(
            ControlMessageIndex.SET_MOTION_EVENT,
            ControlMessageTable.GEN7.indexOf(0x5501),
        )
        assertEquals(0x5500, ControlMessageTable.GEN7.typeOf(ControlMessageIndex.RUMBLE_TRIGGERS))
    }

    @Test
    fun `host selection picks the documented column`() {
        assertSame(ControlMessageTable.GEN7, ControlMessageTable.forHost(7, encrypted = false))
        assertSame(ControlMessageTable.GEN5, ControlMessageTable.forHost(5, encrypted = false))
        assertSame(ControlMessageTable.GEN4, ControlMessageTable.forHost(4, encrypted = false))
        assertSame(ControlMessageTable.GEN3, ControlMessageTable.forHost(3, encrypted = false))
        assertSame(
            ControlMessageTable.GEN7_ENCRYPTED,
            ControlMessageTable.forHost(7, encrypted = true),
        )
        // An unrecognised generation falls back to Gen 7 rather than failing: every modern host
        // reports 7, and refusing to stream because a version string was odd helps nobody.
        assertSame(ControlMessageTable.GEN7, ControlMessageTable.forHost(0, encrypted = false))
    }
}
