package com.voidlink.android.protocol.input

/**
 * A fixed-size packet buffer with one method per byte order.
 *
 * `ByteBuffer` is the wrong tool for these packets: a single input packet mixes a big-endian `size`
 * with a little-endian `magic` four bytes later, then a body that is big-endian for mouse deltas and
 * little-endian for everything else. Switching a `ByteBuffer`'s order mid-packet works but makes the
 * order of a field depend on a statement several lines above it, which is exactly how the bug spec
 * §0.1 calls "the number-one bug source" gets written.
 *
 * Here the order is part of the call: `be16` and `le16` cannot be confused for each other while
 * reading, and the field-by-field transcription can be diffed against `Input.h` line by line.
 *
 * Bounds are enforced by [ByteArray]'s own indexing — writing past the declared size throws rather
 * than silently truncating, and every packet's size is a counted constant in [InputConstants].
 */
internal class PacketWriter(size: Int) {

    val bytes: ByteArray = ByteArray(size)
    private var offset: Int = 0

    /** Bytes written so far. */
    val position: Int get() = offset

    fun u8(value: Int): PacketWriter {
        bytes[offset++] = value.toByte()
        return this
    }

    /** Writes [count] zero bytes — the reserved fields spec §10.3 spells out. */
    fun zeros(count: Int): PacketWriter {
        offset += count
        return this
    }

    fun be16(value: Int): PacketWriter {
        bytes[offset++] = (value ushr 8).toByte()
        bytes[offset++] = value.toByte()
        return this
    }

    fun le16(value: Int): PacketWriter {
        bytes[offset++] = value.toByte()
        bytes[offset++] = (value ushr 8).toByte()
        return this
    }

    /** Writes a 16-bit field in whichever order [littleEndian] selects. */
    fun i16(value: Int, littleEndian: Boolean): PacketWriter =
        if (littleEndian) le16(value) else be16(value)

    fun be32(value: Int): PacketWriter {
        bytes[offset++] = (value ushr 24).toByte()
        bytes[offset++] = (value ushr 16).toByte()
        bytes[offset++] = (value ushr 8).toByte()
        bytes[offset++] = value.toByte()
        return this
    }

    fun le32(value: Int): PacketWriter {
        bytes[offset++] = value.toByte()
        bytes[offset++] = (value ushr 8).toByte()
        bytes[offset++] = (value ushr 16).toByte()
        bytes[offset++] = (value ushr 24).toByte()
        return this
    }

    /** Writes a 32-bit field in whichever order [littleEndian] selects. */
    fun i32(value: Int, littleEndian: Boolean): PacketWriter =
        if (littleEndian) le32(value) else be32(value)

    /** A `netfloat`: an IEEE-754 float, always little-endian, on every host (spec §10.3). */
    fun netfloat(value: Float): PacketWriter = le32(value.toRawBits())

    fun raw(source: ByteArray, from: Int = 0, count: Int = source.size - from): PacketWriter {
        System.arraycopy(source, from, bytes, offset, count)
        offset += count
        return this
    }
}

/**
 * Every input packet this client builds, as pure `ByteArray` builders (`docs/01-PROTOCOL.md` §10.2,
 * §10.3).
 *
 * Deliberately shaped like [com.voidlink.android.protocol.control.ControlPayloads]: a builder takes
 * numbers and returns bytes, opens nothing, holds nothing, and can be pinned byte for byte by a test
 * with a hex fixture. Encryption, batching, dead zones and Android event handling all live
 * elsewhere, because this file has exactly one way to be wrong — a byte in the wrong place — and
 * that is the one thing a test without a host can prove.
 *
 * ### Byte order, which is the whole job
 *
 * The header is two adjacent 32-bit fields in *opposite* orders: `size` big-endian, `magic`
 * little-endian (spec §10.2). The body is where spec §10.3 and the reference client part company.
 * Spec §10.3 says "multi-byte body fields are **big-endian** unless the type is `netfloat`". The
 * reference client — which is what every host in existence actually parses — writes:
 *
 * | Packet | Body order |
 * |---|---|
 * | Relative mouse move, absolute mouse move, scroll, horizontal scroll | **big**-endian |
 * | Keyboard `keyCode` | little-endian |
 * | Controller and multi-controller, every field | little-endian |
 * | Sunshine `SS_*` integers (`pointerId`, `rotation`, `capabilities`, …) | little-endian |
 * | `netfloat` anywhere | little-endian |
 *
 * So the spec's rule holds for the mouse and for nothing else. This file follows the reference and
 * routes each disputed field through a switch in [UnverifiedInputConstants], so that a session
 * against a host that disagrees is one flag away rather than one rebuild away. The switches are read
 * per call rather than cached, which costs a field read per packet and makes them work at runtime.
 *
 * Every builder is transcribed field by field from `Input.h`'s `#pragma pack(1)` structs; spec §0.2
 * applies throughout, so nothing is aligned or padded.
 */
object InputPackets {

    // ---- Header (spec §10.2) -------------------------------------------------------------------

    /**
     * Opens a packet: `uint32 size` big-endian, then `uint32 magic` little-endian.
     *
     * @param bodySize bytes that follow the header. `size` is `4 + bodySize`, because it counts the
     *   magic but not itself.
     */
    private fun open(magic: Int, bodySize: Int): PacketWriter =
        PacketWriter(InputConstants.HEADER_SIZE + bodySize)
            .be32(InputConstants.SIZE_FIELD_BYTES + bodySize)
            .le32(magic)

    // ---- Keyboard (spec §10.3) -----------------------------------------------------------------

    /**
     * A key press or release.
     *
     * @param keyCode a **Windows virtual-key code**, not an Android keycode. [WindowsKeyCodes] does
     *   that translation; there is no shortcut and the table is explicit.
     * @param pressed down or up, which selects the magic rather than a field.
     * @param modifiers a mask of [InputConstants.MODIFIER_SHIFT] and friends.
     * @param flags Sunshine's `SS_KBE_FLAG_*`; must be zero for GFE, which the sender enforces.
     */
    fun keyboard(keyCode: Int, pressed: Boolean, modifiers: Int, flags: Int = 0): ByteArray {
        val magic = if (pressed) InputConstants.MAGIC_KEY_DOWN else InputConstants.MAGIC_KEY_UP
        val code = if (UnverifiedInputConstants.keyCodeHighBit) keyCode or HIGH_BIT else keyCode
        return open(magic, InputConstants.BODY_KEYBOARD)
            .u8(flags)
            .i16(code, UnverifiedInputConstants.keyCodeLittleEndian)
            .u8(modifiers)
            .zeros(2)
            .bytes
    }

    /**
     * One UTF-8 text event (spec §10.3).
     *
     * The reference client splits text into **single code points** before sending, because a code
     * point straddling a packet boundary is a parsing error on the host; [InputSender.text] does
     * that splitting, so this builder takes whatever bytes it is given and sizes the packet to them.
     *
     * @throws IllegalArgumentException if the text exceeds `UTF8_TEXT_EVENT_MAX_COUNT` bytes.
     */
    fun utf8Text(utf8: ByteArray): ByteArray {
        require(utf8.size in 1..InputConstants.UTF8_TEXT_MAX_BYTES) {
            "UTF-8 text event must be 1..${InputConstants.UTF8_TEXT_MAX_BYTES} bytes, " +
                "was ${utf8.size}"
        }
        return open(InputConstants.MAGIC_UTF8_TEXT, utf8.size).raw(utf8).bytes
    }

    // ---- Mouse (spec §10.3) --------------------------------------------------------------------

    /**
     * A relative mouse move. **Big-endian deltas** — one of the few places spec §10.3's blanket
     * rule is right.
     *
     * @param gen5OrLater picks between the two magics Gen 5 renumbered.
     */
    fun mouseMoveRelative(deltaX: Int, deltaY: Int, gen5OrLater: Boolean): ByteArray {
        val magic = if (gen5OrLater) InputConstants.MAGIC_MOUSE_MOVE_REL_GEN5
        else InputConstants.MAGIC_MOUSE_MOVE_REL
        return open(magic, InputConstants.BODY_MOUSE_MOVE_REL)
            .be16(deltaX)
            .be16(deltaY)
            .bytes
    }

    /**
     * An absolute mouse position, in the reference frame the host scales against (spec §10.3).
     *
     * Writes exactly what it is given: the reference client's `-1` workaround for GFE's scaling
     * rounding error is applied by [InputSender], not here, so this stays a faithful transcription
     * and the workaround stays visible.
     */
    fun mouseMoveAbsolute(x: Int, y: Int, referenceWidth: Int, referenceHeight: Int): ByteArray =
        open(InputConstants.MAGIC_MOUSE_MOVE_ABS, InputConstants.BODY_MOUSE_MOVE_ABS)
            .be16(x)
            .be16(y)
            .be16(0)
            .be16(referenceWidth)
            .be16(referenceHeight)
            .bytes

    /** A mouse button press or release (spec §10.3). */
    fun mouseButton(button: MouseButton, pressed: Boolean, gen5OrLater: Boolean): ByteArray {
        val magic = when {
            pressed && gen5OrLater -> InputConstants.MAGIC_MOUSE_BUTTON_DOWN_GEN5
            pressed -> InputConstants.MAGIC_MOUSE_BUTTON_DOWN
            gen5OrLater -> InputConstants.MAGIC_MOUSE_BUTTON_UP_GEN5
            else -> InputConstants.MAGIC_MOUSE_BUTTON_UP
        }
        return open(magic, InputConstants.BODY_MOUSE_BUTTON).u8(button.code).bytes
    }

    /**
     * A vertical scroll, in high-resolution units where one wheel click is
     * [InputConstants.WHEEL_DELTA] (spec §10.3).
     *
     * `scrollAmt2` duplicates `scrollAmt1`; the reference client assigns the already-byte-swapped
     * first field to the second, so both are big-endian copies of the same number.
     */
    fun scroll(amount: Int, gen5OrLater: Boolean): ByteArray {
        val magic = if (gen5OrLater) InputConstants.MAGIC_SCROLL_GEN5 else InputConstants.MAGIC_SCROLL
        return open(magic, InputConstants.BODY_SCROLL)
            .be16(amount)
            .be16(amount)
            .be16(0)
            .bytes
    }

    /** A horizontal scroll. Sunshine only (spec §10.3). */
    fun horizontalScroll(amount: Int): ByteArray =
        open(InputConstants.MAGIC_HSCROLL, InputConstants.BODY_HSCROLL).be16(amount).bytes

    // ---- Controller (spec §10.3) ---------------------------------------------------------------

    /**
     * The Gen 3 single-controller packet.
     *
     * Only reachable on a Gen 3 host, which this client is unlikely ever to meet; built and pinned
     * anyway because it is fully specified and because discovering its layout later, from a bug
     * report, would cost far more than transcribing it now.
     */
    fun controller(state: ControllerState): ByteArray {
        val le = UnverifiedInputConstants.controllerFieldsLittleEndian
        return open(InputConstants.MAGIC_CONTROLLER, InputConstants.BODY_CONTROLLER)
            .i16(InputConstants.C_HEADER_B, le)
            .i16(state.buttonFlags and LOW_16, le)
            .u8(state.leftTrigger)
            .u8(state.rightTrigger)
            .i16(state.leftStickX, le)
            .i16(state.leftStickY, le)
            .i16(state.rightStickX, le)
            .i16(state.rightStickY, le)
            .i32(InputConstants.C_TAIL_A, le)
            .i16(InputConstants.C_TAIL_B, le)
            .bytes
    }

    /**
     * The multi-controller packet — the one this client actually sends (spec §10.3).
     *
     * @param activeGamepadMask one bit per connected pad. An arrival is this packet with the pad's
     *   bit **set** and everything else zero; a removal is the same with the bit **cleared**.
     * @param sunshine whether the high 16 button bits may travel in `buttonFlags2`. On GFE that
     *   field must be zero, and the sender additionally folds `MISC` onto `SPECIAL` there so an
     *   otherwise-unusable button still does something.
     */
    fun multiController(
        state: ControllerState,
        activeGamepadMask: Int,
        gen5OrLater: Boolean,
        sunshine: Boolean,
    ): ByteArray {
        val magic = if (gen5OrLater) InputConstants.MAGIC_MULTI_CONTROLLER_GEN5
        else InputConstants.MAGIC_MULTI_CONTROLLER
        val le = UnverifiedInputConstants.controllerFieldsLittleEndian
        val high = if (sunshine) (state.buttonFlags ushr 16) and LOW_16 else 0
        return open(magic, InputConstants.BODY_MULTI_CONTROLLER)
            .i16(InputConstants.MC_HEADER_B, le)
            .i16(state.controllerNumber, le)
            .i16(activeGamepadMask, le)
            .i16(InputConstants.MC_MID_B, le)
            .i16(state.buttonFlags and LOW_16, le)
            .u8(state.leftTrigger)
            .u8(state.rightTrigger)
            .i16(state.leftStickX, le)
            .i16(state.leftStickY, le)
            .i16(state.rightStickX, le)
            .i16(state.rightStickY, le)
            .i16(InputConstants.MC_TAIL_A, le)
            .i16(high, le)
            .i16(InputConstants.MC_TAIL_B, le)
            .bytes
    }

    /**
     * A Sunshine controller-arrival event, which tells the host *what kind* of pad to emulate
     * (spec §10.3).
     *
     * Preferred over a bare multi-controller arrival on Sunshine because it is what makes the
     * "Emulated Controller Type" setting mean anything. The reference client sends **both** — this
     * packet, then a neutral multi-controller event — in case the host does not understand arrival
     * events; [InputSender.controllerArrived] does the same.
     *
     * `LI_CCAP_DUAL_TOUCHPAD` implies `LI_CCAP_TOUCHPAD`, which the reference client sets for the
     * caller and so does this builder: a host that sees the newer bit without the older one may
     * conclude the pad has no touchpad at all.
     */
    fun controllerArrival(
        controllerNumber: Int,
        type: ControllerType,
        capabilities: Int,
        supportedButtonFlags: Int,
    ): ByteArray {
        val le = UnverifiedInputConstants.sunshineFieldsLittleEndian
        val caps = if (capabilities and InputConstants.CCAP_DUAL_TOUCHPAD != 0) {
            capabilities or InputConstants.CCAP_TOUCHPAD
        } else {
            capabilities
        }
        return open(InputConstants.MAGIC_SS_CONTROLLER_ARRIVAL, InputConstants.BODY_SS_CONTROLLER_ARRIVAL)
            .u8(controllerNumber)
            .u8(type.code)
            .i16(caps, le)
            .i32(supportedButtonFlags, le)
            .bytes
    }

    /**
     * A controller motion sample (spec §10.3).
     *
     * Units are the caller's responsibility and are load-bearing: m/s² including gravity for the
     * accelerometer, **degrees** per second for the gyroscope. [MotionSampler] converts.
     */
    fun controllerMotion(
        controllerNumber: Int,
        type: MotionType,
        x: Float,
        y: Float,
        z: Float,
    ): ByteArray = open(
        InputConstants.MAGIC_SS_CONTROLLER_MOTION,
        InputConstants.BODY_SS_CONTROLLER_MOTION,
    )
        .u8(controllerNumber)
        .u8(type.code)
        .zeros(2)
        .netfloat(x)
        .netfloat(y)
        .netfloat(z)
        .bytes

    /** A touch on a controller's own touchpad (spec §10.3). Coordinates are normalized 0..1. */
    fun controllerTouch(
        controllerNumber: Int,
        eventType: TouchEventType,
        touchpadIndex: Int,
        pointerId: Int,
        x: Float,
        y: Float,
        pressure: Float,
    ): ByteArray = open(
        InputConstants.MAGIC_SS_CONTROLLER_TOUCH,
        InputConstants.BODY_SS_CONTROLLER_TOUCH,
    )
        .u8(controllerNumber)
        .u8(eventType.code)
        .zeros(1)
        .u8(touchpadIndex)
        .i32(pointerId, UnverifiedInputConstants.sunshineFieldsLittleEndian)
        .netfloat(x)
        .netfloat(y)
        .netfloat(pressure)
        .bytes

    /** A controller battery report (spec §10.3). */
    fun controllerBattery(
        controllerNumber: Int,
        state: BatteryState,
        percentage: Int,
    ): ByteArray = open(
        InputConstants.MAGIC_SS_CONTROLLER_BATTERY,
        InputConstants.BODY_SS_CONTROLLER_BATTERY,
    )
        .u8(controllerNumber)
        .u8(state.code)
        .u8(percentage)
        .zeros(1)
        .bytes

    // ---- Native touch and pen (spec §10.3) -----------------------------------------------------

    /**
     * A native touch event — the "Native Touch" mode of UI spec §5.4 (spec §10.3).
     *
     * Coordinates are normalized across the **video surface**, not the view: a touch in a letterbox
     * band has no meaning here, which is why [TouchRouter] drops those rather than clamping them.
     *
     * @param rotation 0..359, or [InputConstants.ROTATION_UNKNOWN] when the touch has no orientation.
     */
    fun touch(
        eventType: TouchEventType,
        pointerId: Int,
        x: Float,
        y: Float,
        pressureOrDistance: Float,
        contactAreaMajor: Float = 0f,
        contactAreaMinor: Float = 0f,
        rotation: Int = InputConstants.ROTATION_UNKNOWN,
    ): ByteArray {
        val le = UnverifiedInputConstants.sunshineFieldsLittleEndian
        return open(InputConstants.MAGIC_SS_TOUCH, InputConstants.BODY_SS_TOUCH)
            .u8(eventType.code)
            .zeros(1)
            .i16(rotation, le)
            .i32(pointerId, le)
            .netfloat(x)
            .netfloat(y)
            .netfloat(pressureOrDistance)
            .netfloat(contactAreaMajor)
            .netfloat(contactAreaMinor)
            .bytes
    }

    /**
     * A pen or stylus event (spec §10.3).
     *
     * Not driven by anything in v1 — the stream screen has no stylus path — but the layout is fully
     * specified, it is the packet most likely to be wanted next, and pinning it now costs one test.
     */
    fun pen(
        eventType: TouchEventType,
        toolType: Int,
        penButtons: Int,
        x: Float,
        y: Float,
        pressureOrDistance: Float,
        rotation: Int = InputConstants.ROTATION_UNKNOWN,
        tilt: Int = 0,
        contactAreaMajor: Float = 0f,
        contactAreaMinor: Float = 0f,
    ): ByteArray = open(InputConstants.MAGIC_SS_PEN, InputConstants.BODY_SS_PEN)
        .u8(eventType.code)
        .u8(toolType)
        .u8(penButtons)
        .zeros(1)
        .netfloat(x)
        .netfloat(y)
        .netfloat(pressureOrDistance)
        .i16(rotation, UnverifiedInputConstants.sunshineFieldsLittleEndian)
        .u8(tilt)
        .zeros(1)
        .netfloat(contactAreaMajor)
        .netfloat(contactAreaMinor)
        .bytes

    // ---- Haptics (spec §10.3) ------------------------------------------------------------------

    /**
     * The haptics-enable message (spec §10.3).
     *
     * **Not sent in v1**: spec §10.3 marks it UNVERIFIED and its magic collides with
     * `MULTI_CONTROLLER_MAGIC` on Gen < 5. The reference client does send it, and says GFE will not
     * send rumble events without it, so this is where that experiment starts if rumble never
     * arrives from a GFE host. The `enable` field is little-endian in the reference, which is one
     * more contradiction of spec §10.3's blanket big-endian rule.
     */
    fun enableHaptics(enable: Boolean): ByteArray =
        open(InputConstants.MAGIC_ENABLE_HAPTICS, InputConstants.BODY_ENABLE_HAPTICS)
            .le16(if (enable) 1 else 0)
            .bytes

    private const val LOW_16: Int = 0xFFFF
    private const val HIGH_BIT: Int = 0x8000
}
