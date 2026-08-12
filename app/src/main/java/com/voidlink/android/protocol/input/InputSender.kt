package com.voidlink.android.protocol.input

import com.voidlink.android.protocol.ProtocolLog

/**
 * Counters the sender keeps, for the stats overlay and for bug reports.
 *
 * @property packetsSent input packets that reached the transport.
 * @property packetsFailed payloads the transport refused, plus any the cipher could not build.
 * @property mouseMovesCoalesced relative moves that were folded into an existing batch rather than
 *   sent — the number that says whether batching is doing anything (spec §10.4).
 * @property controllerUpdatesSuppressed controller states identical to the last one sent.
 * @property motionSamplesDropped motion samples the rate throttle discarded (spec §10.3).
 */
class InputSenderStats(
    val packetsSent: Long,
    val packetsFailed: Long,
    val mouseMovesCoalesced: Long,
    val controllerUpdatesSuppressed: Long,
    val motionSamplesDropped: Long,
)

/**
 * The [InputSink] that actually builds, encrypts and sends packets (`docs/01-PROTOCOL.md` §10.3,
 * §10.4).
 *
 * Everything between "the user did something" and "bytes leave the process" that is not packet
 * layout ([InputPackets]) or encryption ([InputEncryptor]) is here:
 *
 * * **Batching** (spec §10.4). Relative mouse deltas are accumulated and flushed at most once per
 *   [InputConstants.MOUSE_BATCH_INTERVAL_MS]; absolute positions keep only the newest; controller
 *   state is sent only when it differs from what the host was last told; motion samples are
 *   throttled to the rate the host asked for. Touch down and up events are never coalesced, which
 *   spec §10.4 states explicitly and which matters more than the rest put together — a swallowed
 *   "up" is a finger the host thinks is still down.
 * * **Held state**, so [releaseAll] can let go of exactly what is held. UI spec §5.4: *"Stuck-key
 *   bugs are unforgivable here."*
 * * **Host quirks** that the reference client carries and spec §10 does not mention: the GFE
 *   modifier fixups that otherwise leave a right-hand Shift stuck down, folding `MISC` onto
 *   `SPECIAL` on a host with no `MISC` button, clamping pad numbers to what the host exposes, and
 *   the absolute-mouse off-by-one.
 *
 * ### Threading
 *
 * Every public method may be called from any thread — the Android input thread, a sensor callback,
 * a coroutine flush tick and the UI thread all reach it. State is guarded by one lock, which is held
 * only while *building* a packet and never while sending: the transport enqueues onto ENet's own
 * loop, and holding a lock across that would let a slow link stall the input thread.
 *
 * @param transport the session's seam (see [InputPacketTransport]).
 * @param profile the host's generation and family.
 * @param encryptor the AES envelope. Owned: one per session, and it carries the IV chain.
 * @param clock monotonic nanosecond source, injectable so batching is testable without sleeping.
 */
class InputSender(
    private val transport: InputPacketTransport,
    private val profile: InputProfile,
    private val encryptor: InputEncryptor,
    private val clock: () -> Long = { System.nanoTime() },
) : InputSink {

    private val lock = Any()

    // ---- Batched state (spec §10.4) ------------------------------------------------------------

    private var pendingDeltaX: Int = 0
    private var pendingDeltaY: Int = 0
    private var lastMouseFlushNanos: Long = Long.MIN_VALUE

    private var pendingAbsolute: IntArray? = null

    /** Fractional wheel remainder, so a slow trackpad scroll is not rounded away to nothing. */
    private var scrollRemainder: Float = 0f
    private var horizontalScrollRemainder: Float = 0f

    // ---- Held state, for releaseAll ------------------------------------------------------------

    private val heldMouseButtons = LinkedHashSet<MouseButton>()
    private val heldKeys = LinkedHashMap<Int, Int>()
    private val activeTouchPointers = LinkedHashSet<Int>()

    /** The last state the host was told, per pad. Absent means the pad is not connected. */
    private val controllerStates = LinkedHashMap<Int, ControllerState>()

    private var activeGamepadMask: Int = 0

    /** Minimum spacing between motion samples, per pad and per sensor. */
    private val motionIntervalNanos = LinkedHashMap<Int, Long>()
    private val lastMotionNanos = LinkedHashMap<Int, Long>()

    // ---- Counters -------------------------------------------------------------------------------

    @Volatile private var packetsSent: Long = 0L
    @Volatile private var packetsFailed: Long = 0L
    @Volatile private var movesCoalesced: Long = 0L
    @Volatile private var controllerSuppressed: Long = 0L
    @Volatile private var motionDropped: Long = 0L

    init {
        UnverifiedInputConstants.announce()
    }

    override val supportsSunshineExtensions: Boolean get() = profile.isSunshine

    /** A snapshot of the counters. Safe from any thread. */
    fun stats(): InputSenderStats = InputSenderStats(
        packetsSent = packetsSent,
        packetsFailed = packetsFailed,
        mouseMovesCoalesced = movesCoalesced,
        controllerUpdatesSuppressed = controllerSuppressed,
        motionSamplesDropped = motionDropped,
    )

    // ---- Mouse ----------------------------------------------------------------------------------

    override fun mouseMoveRelative(deltaX: Int, deltaY: Int) {
        if (deltaX == 0 && deltaY == 0) return
        val due: Boolean
        synchronized(lock) {
            pendingDeltaX += deltaX
            pendingDeltaY += deltaY
            val now = clock()
            due = lastMouseFlushNanos == Long.MIN_VALUE ||
                now - lastMouseFlushNanos >= InputConstants.MOUSE_BATCH_INTERVAL_MS * NANOS_PER_MILLI
            if (!due) movesCoalesced++
        }
        if (due) flush()
    }

    override fun mouseMoveAbsolute(x: Int, y: Int, referenceWidth: Int, referenceHeight: Int) {
        val due: Boolean
        synchronized(lock) {
            pendingAbsolute = intArrayOf(x, y, referenceWidth, referenceHeight)
            val now = clock()
            due = lastMouseFlushNanos == Long.MIN_VALUE ||
                now - lastMouseFlushNanos >= InputConstants.MOUSE_BATCH_INTERVAL_MS * NANOS_PER_MILLI
            if (!due) movesCoalesced++
        }
        if (due) flush()
    }

    override fun mouseButton(button: MouseButton, pressed: Boolean) {
        // Position first: a click must land where the finger is, not where the last flush left the
        // pointer. Spec §10.4 allows moves to be coalesced but says nothing about reordering them
        // past a button, and a host that receives down-then-move clicks in the wrong place.
        flush()
        synchronized(lock) {
            if (pressed) heldMouseButtons += button else heldMouseButtons -= button
        }
        send(InputPackets.mouseButton(button, pressed, profile.isGen5OrLater))
    }

    override fun scroll(clicks: Float) {
        if (clicks == 0f) return
        val amount = synchronized(lock) {
            scrollRemainder += clicks * InputConstants.WHEEL_DELTA
            val whole = scrollRemainder.toInt()
            scrollRemainder -= whole.toFloat()
            whole
        }
        if (amount == 0) return
        send(InputPackets.scroll(clampToInt16(amount), profile.isGen5OrLater))
    }

    override fun horizontalScroll(clicks: Float) {
        if (clicks == 0f) return
        if (!profile.isSunshine) return // SS_HSCROLL_MAGIC is a Sunshine extension (spec §10.3).
        val amount = synchronized(lock) {
            horizontalScrollRemainder += clicks * InputConstants.WHEEL_DELTA
            val whole = horizontalScrollRemainder.toInt()
            horizontalScrollRemainder -= whole.toFloat()
            whole
        }
        if (amount == 0) return
        send(InputPackets.horizontalScroll(clampToInt16(amount)))
    }

    // ---- Keyboard -------------------------------------------------------------------------------

    override fun key(virtualKeyCode: Int, pressed: Boolean, modifiers: Int) {
        val effective = KeyboardFixups.modifiersFor(virtualKeyCode, modifiers, profile.isSunshine)
        synchronized(lock) {
            if (pressed) heldKeys[virtualKeyCode] = effective else heldKeys.remove(virtualKeyCode)
        }
        send(InputPackets.keyboard(virtualKeyCode, pressed, effective))
    }

    override fun text(text: String) {
        if (text.isEmpty()) return
        // One code point per packet, exactly as the reference client does: a code point split
        // across two packets is a parsing error on the host, and a surrogate pair split across two
        // packets is a character the host renders as two broken ones.
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            val chars = Character.charCount(codePoint)
            val bytes = text.substring(index, index + chars).toByteArray(Charsets.UTF_8)
            if (bytes.size in 1..InputConstants.UTF8_TEXT_MAX_BYTES) {
                send(InputPackets.utf8Text(bytes))
            }
            index += chars
        }
    }

    // ---- Controllers ----------------------------------------------------------------------------

    override fun controllerArrived(
        controllerNumber: Int,
        type: ControllerType,
        capabilities: Int,
        supportedButtonFlags: Int,
    ) {
        val number = clampControllerNumber(controllerNumber)
        val neutral: ControllerState
        val mask: Int
        synchronized(lock) {
            activeGamepadMask = activeGamepadMask or (1 shl number)
            mask = maskedGamepadMask()
            neutral = ControllerState(controllerNumber = number)
            controllerStates[number] = neutral
        }

        // Spec §10.3 prefers the arrival packet on Sunshine because it is what makes the host
        // emulate the right pad type. The reference client sends the multi-controller event too,
        // "just in case the host software doesn't support arrival events", and so do we — on GFE
        // that empty event is the only arrival notice there is.
        if (profile.isSunshine) {
            send(
                InputPackets.controllerArrival(
                    controllerNumber = number,
                    type = type,
                    capabilities = capabilities,
                    supportedButtonFlags = supportedButtonFlags,
                ),
            )
        }
        send(InputPackets.multiController(neutral, mask, profile.isGen5OrLater, profile.isSunshine))
        ProtocolLog.i(
            InputConstants.TAG,
            "controller $number arrived as ${type.name} (caps=0x${capabilities.toString(16)}, " +
                "mask=0x${mask.toString(16)})",
        )
    }

    override fun controllerRemoved(controllerNumber: Int) {
        val number = clampControllerNumber(controllerNumber)
        val neutral: ControllerState
        val mask: Int
        synchronized(lock) {
            activeGamepadMask = activeGamepadMask and (1 shl number).inv()
            controllerStates.remove(number)
            motionIntervalNanos.remove(motionKey(number, MotionType.ACCELEROMETER))
            motionIntervalNanos.remove(motionKey(number, MotionType.GYROSCOPE))
            mask = maskedGamepadMask()
            neutral = ControllerState(controllerNumber = number)
        }
        // Removal is the same empty event with the bit cleared (spec §10.3). Sent neutral so a pad
        // yanked mid-press does not leave the host holding a button.
        send(InputPackets.multiController(neutral, mask, profile.isGen5OrLater, profile.isSunshine))
        ProtocolLog.i(InputConstants.TAG, "controller $number removed (mask=0x${mask.toString(16)})")
    }

    override fun controllerState(state: ControllerState) {
        val number = clampControllerNumber(state.controllerNumber)
        val adjusted = state.copy(
            controllerNumber = number,
            buttonFlags = ControllerFixups.buttonFlagsFor(state.buttonFlags, profile.isSunshine),
        )
        val mask: Int
        synchronized(lock) {
            if (controllerStates[number] == adjusted) {
                controllerSuppressed++
                return
            }
            // A state for a pad that never announced itself still implies its presence; without
            // this a controller that missed its arrival event sends input the host discards.
            activeGamepadMask = activeGamepadMask or (1 shl number)
            controllerStates[number] = adjusted
            mask = maskedGamepadMask()
        }
        send(InputPackets.multiController(adjusted, mask, profile.isGen5OrLater, profile.isSunshine))
    }

    override fun controllerMotion(
        controllerNumber: Int,
        type: MotionType,
        x: Float,
        y: Float,
        z: Float,
    ) {
        if (!profile.isSunshine) return // SS_CONTROLLER_MOTION_MAGIC is a Sunshine extension.
        val number = clampControllerNumber(controllerNumber)
        val key = motionKey(number, type)
        synchronized(lock) {
            val interval = motionIntervalNanos[key] ?: DEFAULT_MOTION_INTERVAL_NANOS
            if (interval <= 0L) {
                motionDropped++
                return
            }
            val now = clock()
            val last = lastMotionNanos[key]
            if (last != null && now - last < interval) {
                motionDropped++
                return
            }
            lastMotionNanos[key] = now
        }
        send(InputPackets.controllerMotion(number, type, x, y, z))
    }

    /**
     * Applies the host's set-motion-event request (spec §10.3, §9.6).
     *
     * @param reportRateHz the requested rate; **zero stops reporting**, which is the host saying the
     *   game does not want motion any more — continuing to send it would waste a packet every few
     *   milliseconds for the rest of the session.
     */
    fun setMotionReportRate(controllerNumber: Int, type: MotionType, reportRateHz: Int) {
        val number = clampControllerNumber(controllerNumber)
        val key = motionKey(number, type)
        synchronized(lock) {
            motionIntervalNanos[key] = if (reportRateHz <= 0) 0L else NANOS_PER_SECOND / reportRateHz
            lastMotionNanos.remove(key)
        }
        ProtocolLog.i(
            InputConstants.TAG,
            "host set ${type.name} reporting on controller $number to ${reportRateHz}Hz",
        )
    }

    // ---- Touch ----------------------------------------------------------------------------------

    override fun touch(
        eventType: TouchEventType,
        pointerId: Int,
        x: Float,
        y: Float,
        pressureOrDistance: Float,
        contactAreaMajor: Float,
        contactAreaMinor: Float,
        rotation: Int,
    ) {
        if (!profile.isSunshine) return // SS_TOUCH_MAGIC is Sunshine-only (spec §10.3).
        synchronized(lock) {
            when (eventType) {
                TouchEventType.DOWN -> activeTouchPointers += pointerId
                TouchEventType.UP, TouchEventType.CANCEL -> activeTouchPointers -= pointerId
                TouchEventType.CANCEL_ALL -> activeTouchPointers.clear()
                else -> Unit
            }
        }
        send(
            InputPackets.touch(
                eventType = eventType,
                pointerId = pointerId,
                x = x,
                y = y,
                pressureOrDistance = pressureOrDistance,
                contactAreaMajor = contactAreaMajor,
                contactAreaMinor = contactAreaMinor,
                rotation = rotation,
            ),
        )
    }

    // ---- Lifecycle ------------------------------------------------------------------------------

    override fun flush() {
        val moves: List<ByteArray>
        val absolute: ByteArray?
        synchronized(lock) {
            moves = drainRelativeMoves()
            absolute = drainAbsolute()
            if (moves.isNotEmpty() || absolute != null) lastMouseFlushNanos = clock()
        }
        for (packet in moves) send(packet)
        if (absolute != null) send(absolute)
    }

    override fun releaseAll() {
        val buttons: List<MouseButton>
        val keys: List<Map.Entry<Int, Int>>
        val pads: List<ControllerState>
        val hadTouches: Boolean
        val mask: Int
        synchronized(lock) {
            buttons = heldMouseButtons.toList()
            heldMouseButtons.clear()
            keys = heldKeys.entries.toList()
            heldKeys.clear()
            hadTouches = activeTouchPointers.isNotEmpty()
            activeTouchPointers.clear()
            pendingDeltaX = 0
            pendingDeltaY = 0
            pendingAbsolute = null
            mask = maskedGamepadMask()
            pads = controllerStates.values.filterNot { it.isNeutral }
                .map { ControllerState(controllerNumber = it.controllerNumber) }
            for (pad in pads) controllerStates[pad.controllerNumber] = pad
        }

        // Touches first: a cancel-all with a mouse button still held would leave the host with a
        // dragging cursor and no finger (UI spec §5.4).
        if (hadTouches && profile.isSunshine) {
            send(
                InputPackets.touch(
                    eventType = TouchEventType.CANCEL_ALL,
                    pointerId = 0,
                    x = 0f,
                    y = 0f,
                    pressureOrDistance = 0f,
                ),
            )
        }
        for (button in buttons) {
            send(InputPackets.mouseButton(button, pressed = false, gen5OrLater = profile.isGen5OrLater))
        }
        for ((code, modifiers) in keys) {
            send(InputPackets.keyboard(code, pressed = false, modifiers = modifiers))
        }
        for (pad in pads) {
            send(InputPackets.multiController(pad, mask, profile.isGen5OrLater, profile.isSunshine))
        }
        if (buttons.isNotEmpty() || keys.isNotEmpty() || pads.isNotEmpty() || hadTouches) {
            ProtocolLog.i(
                InputConstants.TAG,
                "released held input: ${buttons.size} mouse buttons, ${keys.size} keys, " +
                    "${pads.size} controllers, touches=$hadTouches",
            )
        }
    }

    // ---- Internals ------------------------------------------------------------------------------

    /**
     * Splits the accumulated delta into as many `int16` packets as it takes.
     *
     * A fling on a high-DPI screen with the pointer velocity turned up genuinely exceeds 32767 in a
     * 4 ms window; clamping instead of splitting would silently shorten the fastest movements,
     * which is the reference client's reason for the same loop.
     */
    private fun drainRelativeMoves(): List<ByteArray> {
        if (pendingDeltaX == 0 && pendingDeltaY == 0) return emptyList()
        val packets = ArrayList<ByteArray>(1)
        while (pendingDeltaX != 0 || pendingDeltaY != 0) {
            val x = takeDeltaChunk(pendingDeltaX)
            val y = takeDeltaChunk(pendingDeltaY)
            pendingDeltaX -= x
            pendingDeltaY -= y
            packets += InputPackets.mouseMoveRelative(x, y, profile.isGen5OrLater)
        }
        return packets
    }

    private fun takeDeltaChunk(value: Int): Int = when {
        value > InputConstants.MOUSE_DELTA_MAX -> InputConstants.MOUSE_DELTA_MAX
        value < InputConstants.MOUSE_DELTA_MIN -> InputConstants.MOUSE_DELTA_MIN
        else -> value
    }

    /**
     * Builds the pending absolute-position packet, applying the reference client's off-by-one.
     *
     * See [UnverifiedInputConstants.absoluteMouseReferenceAdjustment]: GFE's scaling arithmetic
     * cannot reach the far edge of the screen unless the reference dimensions are one short.
     */
    private fun drainAbsolute(): ByteArray? {
        val pending = pendingAbsolute ?: return null
        pendingAbsolute = null
        val adjustment = UnverifiedInputConstants.absoluteMouseReferenceAdjustment
        return InputPackets.mouseMoveAbsolute(
            x = pending[0],
            y = pending[1],
            referenceWidth = (pending[2] - adjustment).coerceAtLeast(1),
            referenceHeight = (pending[3] - adjustment).coerceAtLeast(1),
        )
    }

    /** GFE looks at four bits of the mask and Sunshine at sixteen (spec §10.3). */
    private fun maskedGamepadMask(): Int =
        if (profile.isSunshine) activeGamepadMask and SUNSHINE_MASK
        else activeGamepadMask and GFE_MASK

    private fun clampControllerNumber(number: Int): Int {
        val max = profile.maxGamepads
        val wrapped = ((number % max) + max) % max
        if (wrapped != number) {
            ProtocolLog.w(
                InputConstants.TAG,
                "controller $number is beyond this host's $max pads; using $wrapped instead",
            )
        }
        return wrapped
    }

    private fun motionKey(controllerNumber: Int, type: MotionType): Int =
        controllerNumber * MOTION_TYPES + type.code

    private fun send(packet: ByteArray) {
        val payload = encryptor.seal(packet)
        if (payload == null) {
            packetsFailed++
            return
        }
        if (transport.sendInputPayload(payload)) packetsSent++ else packetsFailed++
    }

    private fun clampToInt16(value: Int): Int =
        value.coerceIn(InputConstants.MOUSE_DELTA_MIN, InputConstants.MOUSE_DELTA_MAX)

    private companion object {
        const val NANOS_PER_MILLI: Long = 1_000_000L
        const val NANOS_PER_SECOND: Long = 1_000_000_000L
        const val GFE_MASK: Int = 0xF
        const val SUNSHINE_MASK: Int = 0xFFFF
        const val MOTION_TYPES: Int = 4

        /**
         * The motion rate assumed before the host says otherwise.
         *
         * Spec §10.3 says the host asks for a rate with the set-motion-event control message, and
         * spec §9.3 marks that message's wire type UNVERIFIED for every generation — so in practice
         * we may never be told. Reporting nothing until asked would mean gyro never works; the
         * compromise is to start at 100 Hz, which spec §12.5's `SENSOR_DELAY_GAME` is close to, and
         * to obey the request the moment one arrives.
         */
        const val DEFAULT_MOTION_RATE_HZ: Int = 100
        const val DEFAULT_MOTION_INTERVAL_NANOS: Long = NANOS_PER_SECOND / DEFAULT_MOTION_RATE_HZ
    }
}

/**
 * Button-mask adjustments a host needs but the spec does not mention.
 *
 * Split out of [InputSender] so it can be tested without a transport: these are pure functions on a
 * bitmask, and each of them is a behaviour difference a user would report as "this button does
 * nothing".
 */
object ControllerFixups {

    /**
     * Adapts a 32-bit button mask to what the host understands (spec §10.3).
     *
     * GFE has no button beyond an Xbox 360 pad's, so the Sunshine extension bits are dropped — and
     * `MISC` (Share/Capture/Mute) is folded onto `SPECIAL` (Guide) first, which is what the
     * reference client does so that an otherwise dead button still reaches the host. Sunshine keeps
     * the full mask.
     */
    fun buttonFlagsFor(buttonFlags: Int, sunshine: Boolean): Int {
        if (sunshine) return buttonFlags
        val folded = if (buttonFlags and InputConstants.BUTTON_MISC != 0) {
            buttonFlags or InputConstants.BUTTON_SPECIAL
        } else {
            buttonFlags
        }
        return folded and LOW_16
    }

    private const val LOW_16: Int = 0xFFFF
}

/**
 * Keyboard modifier fixups for GFE, transcribed from the reference client.
 *
 * None of this is in spec §10.3, and every line of it is a stuck-key bug that has already happened
 * to someone. GFE synthesises key events from the modifier mask as well as from the key code, so:
 *
 * * A **right-hand** modifier key-down that also sets its modifier bit makes GFE synthesise a
 *   *left*-hand key-down as well, and that left key is never released — the user's next keystroke
 *   arrives shifted, forever.
 * * A **left-hand** modifier key-down that does *not* set its bit has the mirror problem.
 * * Any event carrying `MODIFIER_META` is dropped outright by every known GFE version, which would
 *   make the Windows key itself unusable. Clearing the bit on the Win key's own event gets the key
 *   through; chords involving it remain impossible, which is GFE's limitation and not ours.
 *
 * Sunshine needs none of this and gets the mask unchanged.
 */
object KeyboardFixups {

    /** The virtual-key codes this fixup table is keyed on. */
    private const val VK_LWIN: Int = 0x5B
    private const val VK_RWIN: Int = 0x5C
    private const val VK_LSHIFT: Int = 0xA0
    private const val VK_RSHIFT: Int = 0xA1
    private const val VK_LCONTROL: Int = 0xA2
    private const val VK_RCONTROL: Int = 0xA3
    private const val VK_LMENU: Int = 0xA4
    private const val VK_RMENU: Int = 0xA5

    /**
     * The modifier mask to send with [virtualKeyCode].
     *
     * @param modifiers the mask the UI computed from the physical keyboard's state.
     * @param sunshine whether the host is Sunshine, in which case nothing is adjusted.
     */
    fun modifiersFor(virtualKeyCode: Int, modifiers: Int, sunshine: Boolean): Int {
        if (sunshine) return modifiers
        return when (virtualKeyCode and BYTE_MASK) {
            VK_LWIN, VK_RWIN -> modifiers and InputConstants.MODIFIER_META.inv()
            VK_LSHIFT -> modifiers or InputConstants.MODIFIER_SHIFT
            VK_RSHIFT -> modifiers and InputConstants.MODIFIER_SHIFT.inv()
            VK_LCONTROL -> modifiers or InputConstants.MODIFIER_CTRL
            VK_RCONTROL -> modifiers and InputConstants.MODIFIER_CTRL.inv()
            VK_LMENU -> modifiers or InputConstants.MODIFIER_ALT
            VK_RMENU -> modifiers and InputConstants.MODIFIER_ALT.inv()
            else -> modifiers
        }
    }

    private const val BYTE_MASK: Int = 0xFF
}
