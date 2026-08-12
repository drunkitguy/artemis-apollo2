package com.voidlink.android.protocol.input

import com.voidlink.android.protocol.Hex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * A transport that keeps every payload, and decrypts them back into input packets.
 *
 * Decrypting rather than inspecting the ciphertext is what makes these tests readable: the assertion
 * is about the *packet* the host will parse, not about an opaque blob, and it exercises the real
 * encryptor on the way rather than a test double that could disagree with it.
 */
private class RecordingTransport(
    private val key: ByteArray,
    private val keyId: Int,
    private val encrypted: Boolean = true,
) : InputPacketTransport {

    val payloads = mutableListOf<ByteArray>()
    private val plaintexts = mutableListOf<ByteArray>()
    private var iv = ConfigurableIvStrategy.initialIv(keyId)

    var refuseEverything: Boolean = false

    override fun sendInputPayload(payload: ByteArray): Boolean {
        if (refuseEverything) return false
        payloads += payload
        plaintexts += if (encrypted) decrypt(payload) else payload
        return true
    }

    /**
     * Forgets what has been sent **without** resetting the IV chain.
     *
     * The distinction matters: the sender's IV keeps advancing across a `clear()`, so a mirror that
     * restarted from the key id would fail to authenticate everything after the first controller
     * packet — a test-harness bug that looks exactly like a protocol bug.
     */
    fun clear() {
        payloads.clear()
        plaintexts.clear()
    }

    /** The plaintext packets, in order. */
    fun packets(): List<ByteArray> = plaintexts.toList()

    /** The magic of packet [index], as the little-endian `uint32` it is on the wire. */
    fun magicAt(index: Int): Int {
        val packet = packets()[index]
        return (packet[4].toInt() and 0xFF) or
            ((packet[5].toInt() and 0xFF) shl 8) or
            ((packet[6].toInt() and 0xFF) shl 16) or
            ((packet[7].toInt() and 0xFF) shl 24)
    }

    private fun decrypt(payload: ByteArray): ByteArray {
        val blob = payload.copyOfRange(InputConstants.ENCRYPTED_LENGTH_PREFIX_BYTES, payload.size)
        val tag = blob.copyOfRange(0, InputConstants.GCM_TAG_BYTES)
        val ciphertext = blob.copyOfRange(InputConstants.GCM_TAG_BYTES, blob.size)
        val cipher = Cipher.getInstance(InputConstants.GCM_TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, InputConstants.KEY_ALGORITHM),
            GCMParameterSpec(InputConstants.GCM_TAG_BITS, iv.copyOfRange(0, InputConstants.GCM_IV_BYTES)),
        )
        val plaintext = cipher.doFinal(ciphertext + tag)
        if (blob.size >= InputConstants.GCM_TAG_BYTES + InputConstants.IV_BYTES) {
            iv = blob.copyOfRange(blob.size - InputConstants.IV_BYTES, blob.size)
        }
        return plaintext
    }
}

/**
 * Batching, held state and host quirks (`docs/01-PROTOCOL.md` §10.4, UI spec §5.4).
 *
 * Spec §10.4's rules are all "do not send a packet" rules, which makes them invisible when they fail
 * — nothing breaks, the control channel just fills with traffic that adds latency to the input that
 * matters. Each one is pinned here with a fake clock, so the behaviour is asserted rather than
 * timed.
 *
 * The other half is [InputSender.releaseAll], where UI spec §5.4 is blunt: *"Stuck-key bugs are
 * unforgivable here."* A held key that survives a backgrounded app is a host typing `wwwwww` into a
 * chat window until someone notices.
 */
class InputSenderTest {

    private val key = ByteArray(InputConstants.KEY_BYTES) { (it * 7).toByte() }
    private val keyId = 0x0A0B0C0D

    private var now: Long = 0L

    private fun sender(
        transport: RecordingTransport,
        generation: Int = 7,
        sunshine: Boolean = true,
    ): InputSender = InputSender(
        transport = transport,
        profile = InputProfile(generation, sunshine),
        encryptor = InputEncryptor(key, keyId, InputProfile(generation, sunshine)),
        clock = { now },
    )

    private fun transport() = RecordingTransport(key, keyId)

    private fun advanceMillis(millis: Long) {
        now += millis * 1_000_000L
    }

    // ---- Mouse batching (spec §10.4) ------------------------------------------------------------

    @Test
    fun `relative moves inside one batching window are coalesced into a single packet`() {
        val transport = transport()
        val sender = sender(transport)

        sender.mouseMoveRelative(1, 1) // first move flushes immediately: nothing to wait behind
        repeat(10) { sender.mouseMoveRelative(2, 3) }
        assertEquals(1, transport.payloads.size)

        advanceMillis(InputConstants.MOUSE_BATCH_INTERVAL_MS)
        sender.flush()

        val packets = transport.packets()
        assertEquals(2, packets.size)
        // 10 moves of (2, 3) became one packet of (20, 30).
        assertEquals("00000008" + "07000000" + "0014" + "001e", Hex.encode(packets[1]))
        assertEquals(10L, sender.stats().mouseMovesCoalesced)
    }

    @Test
    fun `a delta larger than int16 is split across packets rather than clamped`() {
        val transport = transport()
        val sender = sender(transport)
        sender.mouseMoveRelative(40_000, 0)
        sender.flush()

        val packets = transport.packets()
        assertEquals(2, packets.size)
        assertEquals("00000008" + "07000000" + "7fff" + "0000", Hex.encode(packets[0]))
        // 40000 - 32767 = 7233 = 0x1C41.
        assertEquals("00000008" + "07000000" + "1c41" + "0000", Hex.encode(packets[1]))
    }

    @Test
    fun `only the newest absolute position survives a batching window`() {
        val transport = transport()
        val sender = sender(transport)
        sender.mouseMoveAbsolute(1, 1, 1920, 1080)
        sender.mouseMoveAbsolute(2, 2, 1920, 1080)
        sender.mouseMoveAbsolute(300, 400, 1920, 1080)
        advanceMillis(InputConstants.MOUSE_BATCH_INTERVAL_MS)
        sender.flush()

        val packets = transport.packets()
        assertEquals(2, packets.size)
        // x = 300 = 0x012C, y = 400 = 0x0190; reference dimensions carry the reference client's
        // off-by-one: 1919 = 0x077F, 1079 = 0x0437.
        assertEquals("0000000e" + "05000000" + "012c" + "0190" + "0000" + "077f" + "0437", Hex.encode(packets[1]))
    }

    @Test
    fun `a mouse button flushes pending movement first, so the click lands where the finger is`() {
        val transport = transport()
        val sender = sender(transport)
        sender.mouseMoveRelative(1, 1)
        sender.mouseMoveRelative(50, 50) // coalesced, not yet sent
        sender.mouseButton(MouseButton.LEFT, pressed = true)

        assertEquals(InputConstants.MAGIC_MOUSE_MOVE_REL_GEN5, transport.magicAt(1))
        assertEquals(InputConstants.MAGIC_MOUSE_BUTTON_DOWN_GEN5, transport.magicAt(2))
    }

    @Test
    fun `fractional scrolling accumulates instead of rounding away to nothing`() {
        val transport = transport()
        val sender = sender(transport)
        // A tenth of a click is 12 high-resolution units; the first one is sent, and nothing is
        // lost across the rest.
        repeat(10) { sender.scroll(0.1f) }
        val total = transport.packets().sumOf { packet ->
            ((packet[8].toInt() and 0xFF) shl 8) or (packet[9].toInt() and 0xFF)
        }
        assertEquals(InputConstants.WHEEL_DELTA, total)
    }

    @Test
    fun `horizontal scrolling is dropped on a host that is not Sunshine`() {
        val transport = transport()
        sender(transport, sunshine = false).horizontalScroll(1f)
        assertTrue(transport.payloads.isEmpty())
    }

    // ---- Controllers (spec §10.3, §10.4) --------------------------------------------------------

    @Test
    fun `an arrival sends the Sunshine packet and then a neutral multi-controller event`() {
        val transport = transport()
        val sender = sender(transport)
        sender.controllerArrived(0, ControllerType.XBOX, InputConstants.CCAP_RUMBLE, InputConstants.BUTTONS_STANDARD)

        assertEquals(2, transport.payloads.size)
        assertEquals(InputConstants.MAGIC_SS_CONTROLLER_ARRIVAL, transport.magicAt(0))
        assertEquals(InputConstants.MAGIC_MULTI_CONTROLLER_GEN5, transport.magicAt(1))
        // activeGamepadMask has pad 0's bit set, and everything else is zero.
        assertTrue(Hex.encode(transport.packets()[1]).contains("1a00" + "0000" + "0100" + "1400" + "0000"))
    }

    @Test
    fun `a GFE host gets no arrival packet, only the empty multi-controller event`() {
        val transport = transport()
        sender(transport, sunshine = false)
            .controllerArrived(0, ControllerType.XBOX, 0, InputConstants.BUTTONS_STANDARD)
        assertEquals(1, transport.payloads.size)
        assertEquals(InputConstants.MAGIC_MULTI_CONTROLLER_GEN5, transport.magicAt(0))
    }

    @Test
    fun `a removal clears the pad's mask bit and leaves the others alone`() {
        val transport = transport()
        val sender = sender(transport)
        sender.controllerArrived(0, ControllerType.XBOX, 0, 0)
        sender.controllerArrived(1, ControllerType.XBOX, 0, 0)
        transport.clear()

        sender.controllerRemoved(0)
        // Mask 0b10 survives for pad 1.
        assertTrue(Hex.encode(transport.packets()[0]).contains("1a00" + "0000" + "0200" + "1400"))
    }

    @Test
    fun `an unchanged controller state is not resent`() {
        val transport = transport()
        val sender = sender(transport)
        val state = ControllerState(buttonFlags = InputConstants.BUTTON_A, leftStickX = 1000)
        sender.controllerState(state)
        sender.controllerState(state)
        sender.controllerState(state)

        assertEquals(1, transport.payloads.size)
        assertEquals(2L, sender.stats().controllerUpdatesSuppressed)
    }

    @Test
    fun `GFE folds the MISC button onto Guide and drops the extended bits`() {
        val transport = transport()
        val sender = sender(transport, sunshine = false)
        sender.controllerState(ControllerState(buttonFlags = InputConstants.BUTTON_MISC))
        // SPECIAL = 0x0400, little-endian "0004"; buttonFlags2 stays zero on GFE.
        val hex = Hex.encode(transport.packets()[0])
        assertTrue(hex.contains("1400" + "0004"))
        assertTrue(hex.endsWith("9c00" + "0000" + "5500"))
    }

    @Test
    fun `a pad number beyond the host's capacity is wrapped rather than dropped`() {
        val transport = transport()
        // GFE exposes four pads; pad 5 wraps to 1 rather than vanishing.
        sender(transport, sunshine = false).controllerState(ControllerState(controllerNumber = 5))
        assertTrue(Hex.encode(transport.packets()[0]).contains("1a00" + "0100"))
    }

    // ---- Motion (spec §10.3) --------------------------------------------------------------------

    @Test
    fun `motion samples are throttled to the rate the host asked for`() {
        val transport = transport()
        val sender = sender(transport)
        sender.setMotionReportRate(0, MotionType.GYROSCOPE, 100) // one per 10 ms

        sender.controllerMotion(0, MotionType.GYROSCOPE, 1f, 0f, 0f)
        repeat(5) { sender.controllerMotion(0, MotionType.GYROSCOPE, 1f, 0f, 0f) }
        assertEquals(1, transport.payloads.size)

        advanceMillis(10)
        sender.controllerMotion(0, MotionType.GYROSCOPE, 1f, 0f, 0f)
        assertEquals(2, transport.payloads.size)
        assertEquals(5L, sender.stats().motionSamplesDropped)
    }

    @Test
    fun `a zero report rate stops motion entirely`() {
        val transport = transport()
        val sender = sender(transport)
        sender.setMotionReportRate(0, MotionType.ACCELEROMETER, 0)
        repeat(5) { sender.controllerMotion(0, MotionType.ACCELEROMETER, 1f, 2f, 3f) }
        assertTrue(transport.payloads.isEmpty())
    }

    @Test
    fun `motion and native touch are dropped on a host that is not Sunshine`() {
        val transport = transport()
        val sender = sender(transport, sunshine = false)
        sender.controllerMotion(0, MotionType.GYROSCOPE, 1f, 1f, 1f)
        sender.touch(TouchEventType.DOWN, 0, 0.5f, 0.5f, 1f)
        assertTrue(transport.payloads.isEmpty())
    }

    // ---- Held state (UI spec §5.4) --------------------------------------------------------------

    @Test
    fun `releaseAll lets go of every held key, button, touch and controller`() {
        val transport = transport()
        val sender = sender(transport)
        sender.controllerArrived(0, ControllerType.XBOX, 0, 0)
        sender.controllerState(ControllerState(buttonFlags = InputConstants.BUTTON_A))
        sender.mouseButton(MouseButton.LEFT, pressed = true)
        sender.key(0x41, pressed = true, modifiers = 0)
        sender.touch(TouchEventType.DOWN, 1, 0.5f, 0.5f, 1f)
        transport.clear()

        sender.releaseAll()

        val magics = transport.payloads.indices.map { transport.magicAt(it) }
        assertEquals(
            listOf(
                InputConstants.MAGIC_SS_TOUCH,
                InputConstants.MAGIC_MOUSE_BUTTON_UP_GEN5,
                InputConstants.MAGIC_KEY_UP,
                InputConstants.MAGIC_MULTI_CONTROLLER_GEN5,
            ),
            magics,
        )
        // The touch release is a cancel-all, not a per-pointer up.
        assertEquals(TouchEventType.CANCEL_ALL.code, transport.packets()[0][8].toInt())
        // The controller goes neutral: no buttons, both sticks centred.
        assertTrue(Hex.encode(transport.packets()[3]).contains("1400" + "0000" + "00" + "00" + "0000"))
    }

    @Test
    fun `releasing twice sends nothing the second time`() {
        val transport = transport()
        val sender = sender(transport)
        sender.key(0x41, pressed = true, modifiers = 0)
        sender.releaseAll()
        transport.clear()
        sender.releaseAll()
        assertTrue(transport.payloads.isEmpty())
    }

    @Test
    fun `a released key is no longer held`() {
        val transport = transport()
        val sender = sender(transport)
        sender.key(0x41, pressed = true, modifiers = 0)
        sender.key(0x41, pressed = false, modifiers = 0)
        transport.clear()
        sender.releaseAll()
        assertTrue(transport.payloads.isEmpty())
    }

    // ---- Keyboard quirks -------------------------------------------------------------------------

    @Test
    fun `GFE gets the right-shift modifier cleared so it does not synthesise a stuck left shift`() {
        val transport = transport()
        val sender = sender(transport, sunshine = false)
        // VK_RSHIFT with the shift bit set is what a naive client sends, and what leaves GFE
        // holding a left shift forever.
        sender.key(0xA1, pressed = true, modifiers = InputConstants.MODIFIER_SHIFT)
        assertEquals(0, transport.packets()[0][11].toInt())
    }

    @Test
    fun `Sunshine's modifiers are passed through untouched`() {
        val transport = transport()
        sender(transport, sunshine = true)
            .key(0xA1, pressed = true, modifiers = InputConstants.MODIFIER_SHIFT)
        assertEquals(InputConstants.MODIFIER_SHIFT, transport.packets()[0][11].toInt())
    }

    @Test
    fun `text is split into one packet per code point`() {
        val transport = transport()
        // An emoji outside the BMP is a surrogate pair in Kotlin and four bytes in UTF-8; splitting
        // it across packets is a parsing error on the host.
        sender(transport).text("a😀b")

        val packets = transport.packets()
        assertEquals(3, packets.size)
        assertEquals(InputConstants.HEADER_SIZE + 1, packets[0].size)
        assertEquals(InputConstants.HEADER_SIZE + 4, packets[1].size)
        assertEquals(InputConstants.HEADER_SIZE + 1, packets[2].size)
    }

    // ---- Failures --------------------------------------------------------------------------------

    @Test
    fun `a transport that refuses everything is counted rather than thrown from`() {
        val transport = transport()
        transport.refuseEverything = true
        val sender = sender(transport)
        sender.key(0x41, pressed = true, modifiers = 0)
        assertEquals(0L, sender.stats().packetsSent)
        assertEquals(1L, sender.stats().packetsFailed)
    }

    @Test
    fun `the no-op sink accepts everything and sends nothing`() {
        // The stream screen holds this until the session attaches; a touch during connect must be a
        // no-op rather than a crash.
        assertFalse(NoOpInputSink.supportsSunshineExtensions)
        NoOpInputSink.mouseMoveRelative(1, 1)
        NoOpInputSink.touch(TouchEventType.DOWN, 0, 0f, 0f, 0f)
        NoOpInputSink.releaseAll()
        assertNotNull(NoOpInputSink)
    }
}
