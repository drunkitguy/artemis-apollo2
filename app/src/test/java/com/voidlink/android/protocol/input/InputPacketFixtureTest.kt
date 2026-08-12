package com.voidlink.android.protocol.input

import com.voidlink.android.protocol.Hex
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hex fixtures for every input packet this client builds (`docs/01-PROTOCOL.md` §10.2, §10.3).
 *
 * This is the most load-bearing test file in the input path, for the reason spec §10 gives: a
 * misplaced byte does not produce an error anywhere. The host reads a field it does not understand,
 * discards the packet or acts on nonsense, and the user reports that "the mouse feels weird" or
 * "the left stick does nothing" — symptoms three layers away from the cause. Byte order is also the
 * one thing about the input protocol that is *fully* verifiable without a host, so every builder is
 * pinned here byte for byte rather than round-tripped against a parser of our own that could be
 * wrong in the same direction.
 *
 * Each fixture is annotated with the field boundaries so a future reader can diff it against
 * `Input.h` without counting nibbles.
 */
class InputPacketFixtureTest {

    @After
    fun restoreDefaults() {
        UnverifiedInputConstants.keyCodeLittleEndian = true
        UnverifiedInputConstants.controllerFieldsLittleEndian = true
        UnverifiedInputConstants.sunshineFieldsLittleEndian = true
        UnverifiedInputConstants.keyCodeHighBit = false
    }

    private fun hex(bytes: ByteArray) = Hex.encode(bytes)

    // ---- The common header (spec §10.2) --------------------------------------------------------

    @Test
    fun `the header is a big-endian size followed by a little-endian magic`() {
        // The single most confusable eight bytes in the protocol: two adjacent uint32s in opposite
        // orders. size = 4 (magic) + 4 (body) = 8, which spec §10.2 spells out as its example.
        val packet = InputPackets.mouseMoveRelative(0x0102, 0x0304, gen5OrLater = true)
        assertEquals(
            "00000008" + // size, BIG-endian, excluding itself
                "07000000" + // magic 0x00000007, LITTLE-endian
                "0102" + "0304", // deltas, big-endian
            hex(packet),
        )
    }

    @Test
    fun `size counts the magic but never itself`() {
        for (packet in everyPacket()) {
            val declared = ((packet[0].toInt() and 0xFF) shl 24) or
                ((packet[1].toInt() and 0xFF) shl 16) or
                ((packet[2].toInt() and 0xFF) shl 8) or
                (packet[3].toInt() and 0xFF)
            assertEquals(
                "size field of ${hex(packet)}",
                packet.size - InputConstants.SIZE_FIELD_BYTES,
                declared,
            )
        }
    }

    // ---- Keyboard (spec §10.3) -----------------------------------------------------------------

    @Test
    fun `a key down carries the VK code little-endian, as the reference client writes it`() {
        // VK_A = 0x41 with Ctrl held. Spec §10.3's blanket "body fields are big-endian" rule would
        // put "0041" here; moonlight-common-c writes LE16(keyCode), and that is what hosts parse.
        // Sent big-endian, VK_A becomes 0x4100 — not a virtual key at all, so nothing happens and
        // nothing is logged.
        val packet = InputPackets.keyboard(0x41, pressed = true, modifiers = InputConstants.MODIFIER_CTRL)
        assertEquals(
            "0000000a" + // size = 4 + 6
                "03000000" + // KEY_DOWN_EVENT_MAGIC
                "00" + // flags (0 for GFE)
                "4100" + // keyCode, LITTLE-endian
                "02" + // modifiers: CTRL
                "0000", // zero2
            hex(packet),
        )
    }

    @Test
    fun `a key up differs from a key down only in the magic`() {
        val down = hex(InputPackets.keyboard(0x41, pressed = true, modifiers = 0))
        val up = hex(InputPackets.keyboard(0x41, pressed = false, modifiers = 0))
        assertEquals(down.replace("03000000", "04000000"), up)
    }

    @Test
    fun `the big-endian keyCode variant is one flag away`() {
        UnverifiedInputConstants.keyCodeLittleEndian = false
        assertTrue(hex(InputPackets.keyboard(0x41, true, 0)).contains("0041"))
    }

    @Test
    fun `the 0x8000 keyCode variant spec section 10-3 names is one flag away`() {
        UnverifiedInputConstants.keyCodeHighBit = true
        // 0x41 or 0x8000 = 0x8041, little-endian on the wire.
        assertTrue(hex(InputPackets.keyboard(0x41, true, 0)).contains("4180"))
    }

    @Test
    fun `a UTF-8 text packet is sized to its text`() {
        val packet = InputPackets.utf8Text("hi".toByteArray(Charsets.UTF_8))
        assertEquals(
            "00000006" + // size = 4 + 2
                "17000000" + // UTF8_TEXT_EVENT_MAGIC
                "6869", // "hi"
            hex(packet),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `text longer than the protocol's 32-byte maximum is refused rather than truncated`() {
        InputPackets.utf8Text(ByteArray(InputConstants.UTF8_TEXT_MAX_BYTES + 1) { 0x41 })
    }

    // ---- Mouse (spec §10.3) --------------------------------------------------------------------

    @Test
    fun `relative mouse deltas are big-endian and signed`() {
        val packet = InputPackets.mouseMoveRelative(-1, 2, gen5OrLater = true)
        assertEquals("00000008" + "07000000" + "ffff" + "0002", hex(packet))
    }

    @Test
    fun `Gen 4 uses the older relative-move magic`() {
        assertTrue(hex(InputPackets.mouseMoveRelative(1, 1, gen5OrLater = false)).contains("06000000"))
    }

    @Test
    fun `an absolute position carries the reference frame it is scaled against`() {
        val packet = InputPackets.mouseMoveAbsolute(960, 540, 1920, 1080)
        assertEquals(
            "0000000e" + // size = 4 + 10
                "05000000" + // MOUSE_MOVE_ABS_MAGIC
                "03c0" + // x = 960, big-endian
                "021c" + // y = 540
                "0000" + // unused
                "0780" + // width = 1920
                "0438", // height = 1080
            hex(packet),
        )
    }

    @Test
    fun `mouse buttons are one byte, and Gen 5 shifted both magics by one`() {
        assertEquals(
            "00000005" + "08000000" + "01",
            hex(InputPackets.mouseButton(MouseButton.LEFT, pressed = true, gen5OrLater = true)),
        )
        assertEquals(
            "00000005" + "09000000" + "03",
            hex(InputPackets.mouseButton(MouseButton.RIGHT, pressed = false, gen5OrLater = true)),
        )
        // Pre-Gen 5 the same actions are 0x07 and 0x08 — the reference client's `magic = action;
        // if (gen >= 5) magic++`.
        assertTrue(
            hex(InputPackets.mouseButton(MouseButton.LEFT, pressed = true, gen5OrLater = false))
                .contains("07000000"),
        )
    }

    @Test
    fun `a scroll duplicates its amount into the legacy second field`() {
        // One wheel click up = +120 = 0x0078.
        val packet = InputPackets.scroll(InputConstants.WHEEL_DELTA, gen5OrLater = true)
        assertEquals(
            "0000000a" + // size = 4 + 6
                "0a000000" + // SCROLL_MAGIC_GEN5
                "0078" + "0078" + "0000",
            hex(packet),
        )
    }

    @Test
    fun `a horizontal scroll is a Sunshine extension with a single amount`() {
        assertEquals(
            "00000006" + "01000055" + "ff88", // -120, big-endian
            hex(InputPackets.horizontalScroll(-InputConstants.WHEEL_DELTA)),
        )
    }

    // ---- Controllers (spec §10.3) --------------------------------------------------------------

    @Test
    fun `the multi-controller packet is little-endian throughout, structural constants included`() {
        val state = ControllerState(
            controllerNumber = 1,
            buttonFlags = InputConstants.BUTTON_A or InputConstants.BUTTON_LB,
            leftTrigger = 0xFF,
            rightTrigger = 0x10,
            leftStickX = 0x1234,
            leftStickY = -0x1234,
            rightStickX = InputConstants.STICK_MAX,
            rightStickY = InputConstants.STICK_MIN,
        )
        val packet = InputPackets.multiController(
            state = state,
            activeGamepadMask = 0x3,
            gen5OrLater = true,
            sunshine = true,
        )
        assertEquals(
            "0000001e" + // size = 4 + 26
                "0c000000" + // MULTI_CONTROLLER_MAGIC_GEN5
                "1a00" + // headerB = 0x001A, LITTLE-endian
                "0100" + // controllerNumber = 1
                "0300" + // activeGamepadMask = 0b11
                "1400" + // midB = 0x0014
                "0011" + // buttonFlags = 0x1100 (A | LB), little-endian
                "ff" + "10" + // triggers, single bytes and therefore order-free
                "3412" + // leftStickX = 0x1234
                "cced" + // leftStickY = -0x1234
                "ff7f" + // rightStickX = 32767
                "0080" + // rightStickY = -32768
                "9c00" + // tailA = 0x009C
                "0000" + // buttonFlags2: no Sunshine buttons set
                "5500", // tailB = 0x0055
            hex(packet),
        )
    }

    @Test
    fun `Sunshine's extended buttons travel in buttonFlags2 and GFE never sees them`() {
        val state = ControllerState(buttonFlags = InputConstants.BUTTON_PADDLE1 or InputConstants.BUTTON_A)
        val sunshine = hex(
            InputPackets.multiController(state, 0x1, gen5OrLater = true, sunshine = true),
        )
        val gfe = hex(InputPackets.multiController(state, 0x1, gen5OrLater = true, sunshine = false))
        // PADDLE1 = 0x010000, i.e. bit 0 of the high half: "0100" little-endian.
        assertTrue(sunshine.endsWith("9c00" + "0100" + "5500"))
        assertTrue(gfe.endsWith("9c00" + "0000" + "5500"))
    }

    @Test
    fun `an arrival is an empty multi-controller event with the pad's mask bit set`() {
        val packet = InputPackets.multiController(
            state = ControllerState(controllerNumber = 2),
            activeGamepadMask = 0b100,
            gen5OrLater = true,
            sunshine = true,
        )
        assertTrue(hex(packet).contains("0200" + "0400" + "1400" + "0000"))
    }

    @Test
    fun `the Gen 3 single-controller packet keeps its 32-bit tail`() {
        val packet = InputPackets.controller(ControllerState(buttonFlags = InputConstants.BUTTON_B))
        assertEquals(
            "00000018" + // size = 4 + 20
                "0a000000" + // CONTROLLER_MAGIC
                "0014" + // headerB = 0x1400, little-endian
                "0020" + // buttonFlags = 0x2000 (B)
                "00" + "00" +
                "0000" + "0000" + "0000" + "0000" +
                "9c000000" + // tailA = 0x0000009C as an int32
                "5500", // tailB
            hex(packet),
        )
    }

    @Test
    fun `a controller arrival names the pad type and its capabilities`() {
        val packet = InputPackets.controllerArrival(
            controllerNumber = 0,
            type = ControllerType.PLAYSTATION,
            capabilities = InputConstants.CCAP_ANALOG_TRIGGERS or InputConstants.CCAP_RUMBLE,
            supportedButtonFlags = InputConstants.BUTTONS_STANDARD,
        )
        assertEquals(
            "0000000c" + // size = 4 + 8
                "04000055" + // SS_CONTROLLER_ARRIVAL_MAGIC, little-endian: 0x55000004
                "00" + // controllerNumber
                "02" + // type: PlayStation
                "0300" + // capabilities 0x0003, little-endian
                "fff70000", // supportedButtonFlags 0x0000f7ff, little-endian
            hex(packet),
        )
    }

    @Test
    fun `declaring a dual touchpad implies the single-touchpad capability`() {
        val packet = InputPackets.controllerArrival(
            controllerNumber = 0,
            type = ControllerType.PLAYSTATION,
            capabilities = InputConstants.CCAP_DUAL_TOUCHPAD,
            supportedButtonFlags = 0,
        )
        // 0x100 or 0x008 = 0x108. A host told only about the newer bit may conclude there is no
        // touchpad at all, which is why the reference client sets both.
        assertTrue(hex(packet).contains("0801"))
    }

    @Test
    fun `motion samples are three little-endian netfloats behind a two-byte pad`() {
        val packet = InputPackets.controllerMotion(0, MotionType.GYROSCOPE, 1f, -2f, 0.5f)
        assertEquals(
            "00000014" + // size = 4 + 16
                "06000055" + // SS_CONTROLLER_MOTION_MAGIC
                "00" + // controllerNumber
                "02" + // motionType: gyroscope
                "0000" + // zero[2]
                "0000803f" + // 1.0f little-endian
                "000000c0" + // -2.0f
                "0000003f", // 0.5f
            hex(packet),
        )
    }

    @Test
    fun `a native touch carries its pointer id and normalized coordinates`() {
        val packet = InputPackets.touch(
            eventType = TouchEventType.DOWN,
            pointerId = 3,
            x = 0.5f,
            y = 0.25f,
            pressureOrDistance = 1f,
            rotation = InputConstants.ROTATION_UNKNOWN,
        )
        assertEquals(
            "00000020" + // size = 4 + 28
                "02000055" + // SS_TOUCH_MAGIC
                "01" + // eventType: DOWN
                "00" + // zero[1]
                "ffff" + // rotation: unknown
                "03000000" + // pointerId, little-endian
                "0000003f" + // 0.5f
                "0000803e" + // 0.25f
                "0000803f" + // pressure 1.0f
                "00000000" + "00000000", // contact area
            hex(packet),
        )
    }

    @Test
    fun `a controller touchpad event addresses one of the pad's touchpads`() {
        val packet = InputPackets.controllerTouch(
            controllerNumber = 1,
            eventType = TouchEventType.MOVE,
            touchpadIndex = 1,
            pointerId = 7,
            x = 0f,
            y = 1f,
            pressure = 0f,
        )
        assertEquals(
            "00000018" + // size = 4 + 20
                "05000055" +
                "01" + "03" + "00" + "01" +
                "07000000" +
                "00000000" + "0000803f" + "00000000",
            hex(packet),
        )
    }

    @Test
    fun `a battery report is four bytes of state`() {
        assertEquals(
            "00000008" + "07000055" + "00" + "03" + "64" + "00",
            hex(InputPackets.controllerBattery(0, BatteryState.CHARGING, 100)),
        )
    }

    @Test
    fun `the pen packet interleaves netfloats with its rotation and tilt`() {
        val packet = InputPackets.pen(
            eventType = TouchEventType.DOWN,
            toolType = 1,
            penButtons = 0,
            x = 0.5f,
            y = 0.5f,
            pressureOrDistance = 1f,
            rotation = 90,
            tilt = 45,
        )
        assertEquals(
            "00000020" + // size = 4 + 28
                "03000055" +
                "01" + "01" + "00" + "00" +
                "0000003f" + "0000003f" + "0000803f" +
                "5a00" + // rotation = 90, little-endian
                "2d" + // tilt = 45
                "00" +
                "00000000" + "00000000",
            hex(packet),
        )
    }

    @Test
    fun `the haptics-enable packet writes its flag little-endian`() {
        // Not sent in v1 (spec §10.3 marks it UNVERIFIED), but pinned: the reference writes LE16(1),
        // which is one more contradiction of the spec's blanket big-endian body rule.
        assertEquals("00000006" + "0d000000" + "0100", hex(InputPackets.enableHaptics(true)))
    }

    // ---- Cross-cutting ---------------------------------------------------------------------------

    @Test
    fun `every Sunshine magic keeps its 0x55 prefix in the last wire byte`() {
        val sunshinePackets = listOf(
            InputPackets.horizontalScroll(1),
            InputPackets.touch(TouchEventType.DOWN, 0, 0f, 0f, 0f),
            InputPackets.pen(TouchEventType.DOWN, 0, 0, 0f, 0f, 0f),
            InputPackets.controllerArrival(0, ControllerType.XBOX, 0, 0),
            InputPackets.controllerTouch(0, TouchEventType.DOWN, 0, 0, 0f, 0f, 0f),
            InputPackets.controllerMotion(0, MotionType.ACCELEROMETER, 0f, 0f, 0f),
            InputPackets.controllerBattery(0, BatteryState.FULL, 0),
        )
        for (packet in sunshinePackets) {
            // Little-endian 0x55______ puts the 0x55 last, at offset 7.
            assertEquals("magic of ${hex(packet)}", 0x55, packet[7].toInt() and 0xFF)
        }
    }

    @Test
    fun `every packet is exactly the size its constants declare`() {
        assertEquals(InputConstants.HEADER_SIZE + InputConstants.BODY_KEYBOARD, InputPackets.keyboard(0x41, true, 0).size)
        assertEquals(
            InputConstants.HEADER_SIZE + InputConstants.BODY_MULTI_CONTROLLER,
            InputPackets.multiController(ControllerState(), 1, true, true).size,
        )
        assertEquals(
            InputConstants.HEADER_SIZE + InputConstants.BODY_SS_TOUCH,
            InputPackets.touch(TouchEventType.MOVE, 0, 0f, 0f, 0f).size,
        )
        assertEquals(
            InputConstants.HEADER_SIZE + InputConstants.BODY_SS_PEN,
            InputPackets.pen(TouchEventType.MOVE, 0, 0, 0f, 0f, 0f).size,
        )
    }

    private fun everyPacket(): List<ByteArray> = listOf(
        InputPackets.keyboard(0x41, true, 0),
        InputPackets.utf8Text(byteArrayOf(0x41)),
        InputPackets.mouseMoveRelative(1, 1, true),
        InputPackets.mouseMoveAbsolute(1, 1, 2, 2),
        InputPackets.mouseButton(MouseButton.LEFT, true, true),
        InputPackets.scroll(120, true),
        InputPackets.horizontalScroll(120),
        InputPackets.controller(ControllerState()),
        InputPackets.multiController(ControllerState(), 1, true, true),
        InputPackets.controllerArrival(0, ControllerType.XBOX, 0, 0),
        InputPackets.controllerMotion(0, MotionType.GYROSCOPE, 0f, 0f, 0f),
        InputPackets.controllerTouch(0, TouchEventType.DOWN, 0, 0, 0f, 0f, 0f),
        InputPackets.controllerBattery(0, BatteryState.FULL, 0),
        InputPackets.touch(TouchEventType.DOWN, 0, 0f, 0f, 0f),
        InputPackets.pen(TouchEventType.DOWN, 0, 0, 0f, 0f, 0f),
        InputPackets.enableHaptics(true),
    )
}
