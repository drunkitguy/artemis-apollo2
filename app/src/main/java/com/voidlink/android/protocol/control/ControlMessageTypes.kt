package com.voidlink.android.protocol.control

import com.voidlink.android.protocol.ProtocolLog

/**
 * The message slots of `docs/01-PROTOCOL.md` §9.3's type table.
 *
 * The table is indexed, not named, on the wire: every generation assigns different numbers to the
 * same twelve slots, so the slot is the stable identity and the number is a lookup
 * ([ControlMessageTable]). The index values are the spec's own, and are load-bearing — index 0 is
 * "Request IDR frame" on Gen 3/4 and on encrypted Gen 7, and "Start A" on Gen 5/7, which is the
 * same slot serving two purposes rather than two slots that happen to collide.
 *
 * @property index the row number in spec §9.3's table.
 * @property label human-readable name for logs.
 */
enum class ControlMessageIndex(val index: Int, val label: String) {

    /**
     * Start A (Gen 5/7) — and Request IDR frame (Gen 3/4, Gen 7 encrypted).
     *
     * One slot, two meanings, exactly as spec §9.3 lists it. On a Gen 5/7 unencrypted host there is
     * no separate IDR message: [ControlMessageTable.supportsIdrRequest] is false and an IDR is
     * asked for with a reference-frame invalidation instead.
     */
    START_A(0, "Start A / Request IDR"),

    /** Start B — the second half of the session-start sequence (spec §9.4). */
    START_B(1, "Start B"),

    /** Invalidate reference frames; also the IDR fallback on hosts with no IDR message. */
    INVALIDATE_REFERENCE_FRAMES(2, "Invalidate reference frames"),

    /** Loss statistics, sent periodically by pre-7.1.415 hosts' clients (spec §9.5). */
    LOSS_STATS(3, "Loss stats"),

    /** Frame statistics. Never sent; present so the table indices line up with the spec. */
    FRAME_STATS(4, "Frame stats"),

    /** Input data. Owned by `protocol/input/`, which is out of scope here. */
    INPUT_DATA(5, "Input data"),

    /** Rumble, host→client (spec §9.6). */
    RUMBLE(6, "Rumble"),

    /** Termination, host→client — the session is ending (spec §9.6). */
    TERMINATION(7, "Termination"),

    /** HDR mode change, host→client (spec §9.6). */
    HDR_MODE(8, "HDR mode"),

    /** Rumble triggers, host→client. Sunshine extension; ignored in v1. */
    RUMBLE_TRIGGERS(9, "Rumble triggers"),

    /** Motion event state, host→client. Sunshine extension; ignored in v1. */
    SET_MOTION_EVENT(10, "Set motion event"),

    /** RGB LED, host→client. Sunshine extension; ignored in v1. */
    SET_RGB_LED(11, "Set RGB LED"),

    /** DualSense adaptive triggers, host→client. Sunshine extension; ignored in v1. */
    DS_ADAPTIVE_TRIGGERS(12, "DualSense adaptive triggers"),
}

/**
 * One generation's column of spec §9.3's message-type table.
 *
 * Modelled as data rather than a `when` over the generation because the table *is* data: five
 * columns of twelve small integers, several of which are "this generation has no such message". A
 * table lookup that can answer "no such message" is what lets [ControlStream] fall back from an IDR
 * request to a reference-frame invalidation without a second branch on the host generation.
 *
 * @property label the column's name, for logs.
 * @property generation the `AppVersionQuad[0]` this column serves.
 */
class ControlMessageTable private constructor(
    val label: String,
    val generation: Int,
    private val types: IntArray,
) {

    /**
     * The wire type for [message], or `null` when this generation has no such message.
     *
     * `null` is a real answer, not an error: Gen 5 has no termination message, Gen 3 has no input
     * message, and callers branch on exactly that.
     */
    fun typeOf(message: ControlMessageIndex): Int? =
        types.getOrNull(message.index)?.takeIf { it != ABSENT }

    /**
     * The slot a received wire type belongs to, or `null` for a type this table does not name.
     *
     * Spec §9.3: "**v1: ignore unrecognized control message types** (log the type + length at
     * debug)", which is what a `null` here lets the caller do.
     */
    fun indexOf(type: Int): ControlMessageIndex? {
        for (candidate in ControlMessageIndex.entries) {
            if (types.getOrNull(candidate.index) == type) return candidate
        }
        return null
    }

    /**
     * Whether this host has a dedicated "request IDR frame" message.
     *
     * False on unencrypted Gen 5/7, where slot 0 is Start A. Spec §9.5 still requires us to ask for
     * a keyframe on unrecoverable loss; [ControlPayloads.invalidateReferenceFrames] is how that is
     * expressed there, which is what the reference client does too.
     */
    val supportsIdrRequest: Boolean
        get() = generation < GEN5_FIRST_START_A_GENERATION || this === GEN7_ENCRYPTED

    override fun toString(): String = "ControlMessageTable($label)"

    companion object {

        /** Marks a slot this generation does not define; spec §9.3 writes it as an em dash. */
        private const val ABSENT: Int = -1

        /** Gen 5 is the first generation whose slot 0 is Start A rather than Request IDR. */
        private const val GEN5_FIRST_START_A_GENERATION: Int = 5

        /** Spec §9.3, Gen 3 column. */
        val GEN3: ControlMessageTable = ControlMessageTable(
            label = "Gen 3",
            generation = 3,
            types = intArrayOf(
                0x1407, 0x1410, 0x1404, 0x140c, 0x1417,
                ABSENT, ABSENT, ABSENT, ABSENT, ABSENT, ABSENT, ABSENT, ABSENT,
            ),
        )

        /** Spec §9.3, Gen 4 column. */
        val GEN4: ControlMessageTable = ControlMessageTable(
            label = "Gen 4",
            generation = 4,
            types = intArrayOf(
                0x0606, 0x0609, 0x0604, 0x060a, 0x0611,
                ABSENT, ABSENT, ABSENT, ABSENT, ABSENT, ABSENT, ABSENT, ABSENT,
            ),
        )

        /** Spec §9.3, Gen 5 column. */
        val GEN5: ControlMessageTable = ControlMessageTable(
            label = "Gen 5",
            generation = 5,
            types = intArrayOf(
                0x0305, 0x0307, 0x0301, 0x0201, 0x0204,
                0x0207, ABSENT, ABSENT, ABSENT, ABSENT, ABSENT, ABSENT, ABSENT,
            ),
        )

        /** Spec §9.3, Gen 7 column — the primary target (spec §0.3). */
        val GEN7: ControlMessageTable = ControlMessageTable(
            label = "Gen 7",
            generation = 7,
            types = intArrayOf(
                0x0305, 0x0307, 0x0301, 0x0201, 0x0204,
                0x0206, 0x010b, 0x0100, 0x010e, ABSENT, ABSENT, ABSENT, ABSENT,
            ),
        )

        /**
         * Spec §9.3, Gen 7 encrypted column.
         *
         * The Sunshine controller-feedback extensions (indices 9–12) are the four types spec §9.3
         * marks UNVERIFIED: "We know they exist and what their payloads mean (§9.6) but not their
         * numeric ids." They are filled in from the reference client's `packetTypesGen7Enc`
         * (`0x5500`–`0x5503`) so that an inbound message of one of these types is *named* in the
         * log rather than reported as unknown; v1 ignores all four either way (spec §9.3, §9.6), so
         * a wrong id here costs a log line and nothing else.
         *
         * Note `0x5502` appears both here (host→client Set RGB LED) and as
         * [ControlConstants.TYPE_FRAME_FEC_STATUS] (client→host per-frame FEC status). Direction
         * disambiguates them; the collision is in the protocol, not in this table.
         */
        val GEN7_ENCRYPTED: ControlMessageTable = ControlMessageTable(
            label = "Gen 7 encrypted",
            generation = 7,
            types = intArrayOf(
                0x0302, 0x0307, 0x0301, 0x0201, 0x0204,
                0x0206, 0x010b, 0x0109, 0x010e, 0x5500, 0x5501, 0x5502, 0x5503,
            ),
        )

        /**
         * Picks the column for a host.
         *
         * @param generation `AppVersionQuad[0]` (spec §0.3).
         * @param encrypted whether `SS_ENC_CONTROL_V2` was negotiated. Always false in v1
         *   (spec §6.5), and the reason the encrypted column exists at all is that turning it on
         *   later must be a one-line change here rather than a new table.
         */
        fun forHost(generation: Int, encrypted: Boolean): ControlMessageTable {
            if (encrypted) return GEN7_ENCRYPTED
            return when {
                generation >= 7 -> GEN7
                generation == 5 || generation == 6 -> GEN5
                generation == 4 -> GEN4
                generation == 3 -> GEN3
                else -> {
                    ProtocolLog.w(
                        ControlConstants.TAG,
                        "unknown host generation $generation; using the Gen 7 control message " +
                            "table, which is what every modern host reports (spec §0.3)",
                    )
                    GEN7
                }
            }
        }
    }
}
