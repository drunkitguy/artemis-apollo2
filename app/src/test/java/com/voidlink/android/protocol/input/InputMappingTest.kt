package com.voidlink.android.protocol.input

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The translation tables and the axis arithmetic (`docs/01-PROTOCOL.md` §10.3).
 *
 * Everything here is a pure function whose failure mode is *quiet wrongness*: a key that types the
 * wrong character, a stick that will not reach full deflection, a gyro reading 57 times too small
 * because radians were forwarded as degrees. None of it throws, none of it logs, and all of it is
 * provable without a device.
 */
class InputMappingTest {

    @After
    fun restoreDefaults() {
        UnverifiedInputConstants.invertMotionX = false
        UnverifiedInputConstants.invertMotionY = false
        UnverifiedInputConstants.invertMotionZ = false
    }

    // ---- Key codes (spec §10.3) -----------------------------------------------------------------

    @Test
    fun `the letter and digit rows map onto their contiguous virtual-key ranges`() {
        assertEquals(0x41, WindowsKeyCodes.forAndroidKeyCode(29)) // KEYCODE_A -> VK_A
        assertEquals(0x5A, WindowsKeyCodes.forAndroidKeyCode(54)) // KEYCODE_Z -> VK_Z
        assertEquals(0x30, WindowsKeyCodes.forAndroidKeyCode(7)) // KEYCODE_0 -> VK_0
        assertEquals(0x39, WindowsKeyCodes.forAndroidKeyCode(16)) // KEYCODE_9 -> VK_9
        assertEquals(0x70, WindowsKeyCodes.forAndroidKeyCode(131)) // KEYCODE_F1 -> VK_F1
        assertEquals(0x7B, WindowsKeyCodes.forAndroidKeyCode(142)) // KEYCODE_F12 -> VK_F12
        assertEquals(0x60, WindowsKeyCodes.forAndroidKeyCode(144)) // KEYCODE_NUMPAD_0
        assertEquals(0x69, WindowsKeyCodes.forAndroidKeyCode(153)) // KEYCODE_NUMPAD_9
    }

    @Test
    fun `backspace and delete are not swapped`() {
        // Android's DEL is Backspace and its FORWARD_DEL is Delete. Transposing them deletes the
        // character on the wrong side of the caret, forever, and reads as a host-side bug.
        assertEquals(0x08, WindowsKeyCodes.forAndroidKeyCode(67)) // KEYCODE_DEL -> VK_BACK
        assertEquals(0x2E, WindowsKeyCodes.forAndroidKeyCode(112)) // KEYCODE_FORWARD_DEL -> VK_DELETE
    }

    @Test
    fun `left and right modifiers keep their sides`() {
        assertEquals(0xA0, WindowsKeyCodes.forAndroidKeyCode(59)) // SHIFT_LEFT
        assertEquals(0xA1, WindowsKeyCodes.forAndroidKeyCode(60)) // SHIFT_RIGHT
        assertEquals(0xA2, WindowsKeyCodes.forAndroidKeyCode(113)) // CTRL_LEFT
        assertEquals(0xA3, WindowsKeyCodes.forAndroidKeyCode(114)) // CTRL_RIGHT
        assertEquals(0xA4, WindowsKeyCodes.forAndroidKeyCode(57)) // ALT_LEFT
        assertEquals(0xA5, WindowsKeyCodes.forAndroidKeyCode(58)) // ALT_RIGHT
        assertEquals(0x5B, WindowsKeyCodes.forAndroidKeyCode(117)) // META_LEFT -> VK_LWIN
        assertEquals(0x5C, WindowsKeyCodes.forAndroidKeyCode(118)) // META_RIGHT -> VK_RWIN
    }

    @Test
    fun `punctuation lands on the OEM keys a US layout expects`() {
        assertEquals(0xBA, WindowsKeyCodes.forAndroidKeyCode(74)) // ; -> VK_OEM_1
        assertEquals(0xBB, WindowsKeyCodes.forAndroidKeyCode(70)) // = -> VK_OEM_PLUS
        assertEquals(0xBC, WindowsKeyCodes.forAndroidKeyCode(55)) // , -> VK_OEM_COMMA
        assertEquals(0xBD, WindowsKeyCodes.forAndroidKeyCode(69)) // - -> VK_OEM_MINUS
        assertEquals(0xBE, WindowsKeyCodes.forAndroidKeyCode(56)) // . -> VK_OEM_PERIOD
        assertEquals(0xBF, WindowsKeyCodes.forAndroidKeyCode(76)) // / -> VK_OEM_2
        assertEquals(0xC0, WindowsKeyCodes.forAndroidKeyCode(68)) // ` -> VK_OEM_3
        assertEquals(0xDB, WindowsKeyCodes.forAndroidKeyCode(71)) // [ -> VK_OEM_4
        assertEquals(0xDC, WindowsKeyCodes.forAndroidKeyCode(73)) // \ -> VK_OEM_5
        assertEquals(0xDD, WindowsKeyCodes.forAndroidKeyCode(72)) // ] -> VK_OEM_6
        assertEquals(0xDE, WindowsKeyCodes.forAndroidKeyCode(75)) // ' -> VK_OEM_7
    }

    @Test
    fun `device keys and gamepad buttons are deliberately absent`() {
        assertNull(WindowsKeyCodes.forAndroidKeyCode(3)) // HOME
        assertNull(WindowsKeyCodes.forAndroidKeyCode(4)) // BACK
        assertNull(WindowsKeyCodes.forAndroidKeyCode(24)) // VOLUME_UP
        assertNull(WindowsKeyCodes.forAndroidKeyCode(26)) // POWER
        assertNull(WindowsKeyCodes.forAndroidKeyCode(96)) // BUTTON_A: the pad path owns this
    }

    @Test
    fun `no two Android keys map onto the same virtual key by accident`() {
        // The two intentional collisions are the numpad Enter and numpad Equals, which Windows does
        // not distinguish from their main-keyboard counterparts.
        val intentional = setOf(160, 161)
        val seen = mutableMapOf<Int, Int>()
        for (androidKey in 0..300) {
            if (androidKey in intentional) continue
            val vk = WindowsKeyCodes.forAndroidKeyCode(androidKey) ?: continue
            val previous = seen.put(vk, androidKey)
            assertNull("VK 0x${vk.toString(16)} claimed by $androidKey and $previous", previous)
        }
    }

    @Test
    fun `the meta state becomes the protocol's four modifier bits`() {
        assertEquals(
            InputConstants.MODIFIER_SHIFT or InputConstants.MODIFIER_CTRL,
            WindowsKeyCodes.modifiersFromMetaState(
                WindowsKeyCodes.META_SHIFT_ON or WindowsKeyCodes.META_CTRL_ON,
            ),
        )
        assertEquals(
            InputConstants.MODIFIER_ALT or InputConstants.MODIFIER_META,
            WindowsKeyCodes.modifiersFromMetaState(
                WindowsKeyCodes.META_ALT_ON or WindowsKeyCodes.META_META_ON,
            ),
        )
        assertEquals(0, WindowsKeyCodes.modifiersFromMetaState(0))
    }

    // ---- Gamepad buttons (spec §10.3) -----------------------------------------------------------

    @Test
    fun `face buttons map straight through, and swap in pairs when asked`() {
        assertEquals(InputConstants.BUTTON_A, GamepadButtons.flagFor(96, swapFaceButtons = false))
        assertEquals(InputConstants.BUTTON_B, GamepadButtons.flagFor(97, swapFaceButtons = false))
        assertEquals(InputConstants.BUTTON_X, GamepadButtons.flagFor(99, swapFaceButtons = false))
        assertEquals(InputConstants.BUTTON_Y, GamepadButtons.flagFor(100, swapFaceButtons = false))

        assertEquals(InputConstants.BUTTON_B, GamepadButtons.flagFor(96, swapFaceButtons = true))
        assertEquals(InputConstants.BUTTON_A, GamepadButtons.flagFor(97, swapFaceButtons = true))
        assertEquals(InputConstants.BUTTON_Y, GamepadButtons.flagFor(99, swapFaceButtons = true))
        assertEquals(InputConstants.BUTTON_X, GamepadButtons.flagFor(100, swapFaceButtons = true))
    }

    @Test
    fun `swapping never disturbs anything but the four face buttons`() {
        for (keyCode in 0..200) {
            val plain = GamepadButtons.flagFor(keyCode, swapFaceButtons = false)
            val swapped = GamepadButtons.flagFor(keyCode, swapFaceButtons = true)
            if (keyCode in 96..100 && keyCode != 98) continue
            assertEquals("key $keyCode", plain, swapped)
        }
    }

    @Test
    fun `shoulders, sticks, start and select land on their flags`() {
        assertEquals(InputConstants.BUTTON_LB, GamepadButtons.flagFor(102, false))
        assertEquals(InputConstants.BUTTON_RB, GamepadButtons.flagFor(103, false))
        assertEquals(InputConstants.BUTTON_LS_CLK, GamepadButtons.flagFor(106, false))
        assertEquals(InputConstants.BUTTON_RS_CLK, GamepadButtons.flagFor(107, false))
        assertEquals(InputConstants.BUTTON_PLAY, GamepadButtons.flagFor(108, false))
        assertEquals(InputConstants.BUTTON_BACK, GamepadButtons.flagFor(109, false))
        assertEquals(InputConstants.BUTTON_SPECIAL, GamepadButtons.flagFor(110, false))
    }

    @Test
    fun `analogue shoulder triggers are not also reported as buttons`() {
        // L2/R2 arrive as axes on every pad that has analogue triggers; mapping the key form as
        // well would send each pull twice.
        assertEquals(0, GamepadButtons.flagFor(104, false) and InputConstants.BUTTON_LB)
        assertEquals(0, GamepadButtons.flagFor(105, false) and InputConstants.BUTTON_RB)
    }

    // ---- Axis maths -------------------------------------------------------------------------------

    @Test
    fun `the stick dead zone is radial, not square`() {
        // 0.08 on each axis is 0.113 of deflection: inside a square dead zone of 0.10, outside a
        // radial one. A square dead zone is what makes diagonal movement feel notchy.
        val diagonal = AxisMath.applyStickDeadZone(0.08f, 0.08f, 0.10f)
        assertTrue(abs(diagonal[0]) > 0f)

        val axial = AxisMath.applyStickDeadZone(0.08f, 0f, 0.10f)
        assertEquals(0f, axial[0], 0f)
        assertEquals(0f, axial[1], 0f)
    }

    @Test
    fun `a stick at rest reports exactly zero`() {
        val rest = AxisMath.applyStickDeadZone(0.03f, -0.02f, AxisMath.DEFAULT_DEAD_ZONE)
        assertEquals(0f, rest[0], 0f)
        assertEquals(0f, rest[1], 0f)
    }

    @Test
    fun `full deflection survives the dead zone rescale`() {
        // Without rescaling, a 10% dead zone would cap the stick at 0.9 and the character would
        // never reach full speed.
        val full = AxisMath.applyStickDeadZone(1f, 0f, 0.10f)
        assertEquals(1f, full[0], 1e-4f)
        assertEquals(InputConstants.STICK_MAX, AxisMath.toStickAxis(full[0]))
    }

    @Test
    fun `the Y axis is flipped from Android's convention to the protocol's`() {
        // Android reports "up" as negative; the protocol's sticks are positive up.
        val up = AxisMath.applyStickDeadZone(0f, -1f, 0.10f)
        assertTrue(up[1] > 0f)
    }

    @Test
    fun `stick values are clamped rather than wrapped`() {
        // A pad reporting 1.02 at the corners must not become full deflection the other way.
        assertEquals(InputConstants.STICK_MAX, AxisMath.toStickAxis(1.02f))
        // Full negative deflection is -32767, not -32768: the range is scaled symmetrically about
        // zero, so a stick cannot report one unit further left than it can right.
        assertEquals(-InputConstants.STICK_MAX, AxisMath.toStickAxis(-1.5f))
    }

    @Test
    fun `triggers scale to a byte, with a resting trigger reading zero`() {
        assertEquals(0, AxisMath.toTrigger(0f))
        assertEquals(0, AxisMath.toTrigger(AxisMath.TRIGGER_DEAD_ZONE / 2f))
        assertEquals(InputConstants.TRIGGER_MAX, AxisMath.toTrigger(1f))
        assertEquals(127, AxisMath.toTrigger(0.5f))
    }

    @Test
    fun `the hat switch produces D-pad flags with Android's inverted Y`() {
        assertEquals(InputConstants.BUTTON_UP, AxisMath.hatToButtonFlags(0f, -1f))
        assertEquals(InputConstants.BUTTON_DOWN, AxisMath.hatToButtonFlags(0f, 1f))
        assertEquals(InputConstants.BUTTON_LEFT, AxisMath.hatToButtonFlags(-1f, 0f))
        assertEquals(InputConstants.BUTTON_RIGHT, AxisMath.hatToButtonFlags(1f, 0f))
        assertEquals(
            InputConstants.BUTTON_UP or InputConstants.BUTTON_LEFT,
            AxisMath.hatToButtonFlags(-1f, -1f),
        )
        assertEquals(0, AxisMath.hatToButtonFlags(0f, 0f))
    }

    @Test
    fun `stick jitter below the change epsilon is not a change`() {
        assertFalse(AxisMath.axisChanged(1000, 1000 + AxisMath.AXIS_CHANGE_EPSILON))
        assertTrue(AxisMath.axisChanged(1000, 1000 + AxisMath.AXIS_CHANGE_EPSILON + 1))
    }

    // ---- Motion (spec §10.3) ---------------------------------------------------------------------

    @Test
    fun `the gyroscope is converted from radians to degrees per second`() {
        // Spec §10.3: Android reports rad/s, the host wants deg/s. Forwarding the raw value sends a
        // reading 57 times too small, which reads as "gyro does not work".
        val sample = MotionMath.gyroscope(1f, 0f, 0f, sensitivityPercent = 100)
        assertEquals(InputConstants.RADIANS_TO_DEGREES, sample.x, 1e-3f)
    }

    @Test
    fun `gyro sensitivity is a plain multiplier`() {
        val doubled = MotionMath.gyroscope(1f, 0f, 0f, sensitivityPercent = 200)
        assertEquals(InputConstants.RADIANS_TO_DEGREES * 2f, doubled.x, 1e-3f)
    }

    @Test
    fun `the accelerometer passes through unscaled, because gravity is not a preference`() {
        val sample = MotionMath.accelerometer(0f, 9.81f, 0f)
        assertEquals(9.81f, sample.y, 1e-4f)
    }

    @Test
    fun `a landscape display rotates the sensor frame with it`() {
        // Android's sensor axes are pinned to the device's natural orientation. Held in landscape,
        // an unrotated gyro swaps pitch and yaw: the player tilts to look up and the view pans.
        val portrait = MotionMath.accelerometer(1f, 2f, 3f, rotationDegrees = 0)
        assertEquals(1f, portrait.x, 0f)
        assertEquals(2f, portrait.y, 0f)

        val landscape = MotionMath.accelerometer(1f, 2f, 3f, rotationDegrees = 90)
        assertEquals(-2f, landscape.x, 0f)
        assertEquals(1f, landscape.y, 0f)
        assertEquals(3f, landscape.z, 0f) // the display never rotates about its own normal
    }

    @Test
    fun `the debug axis inversions apply after the rotation`() {
        UnverifiedInputConstants.invertMotionY = true
        val sample = MotionMath.accelerometer(1f, 2f, 3f, rotationDegrees = 0)
        assertEquals(-2f, sample.y, 0f)
    }

    // ---- Host feedback (spec §9.6, §10.3) --------------------------------------------------------

    @Test
    fun `a rumble payload is read past its four leading bytes`() {
        // controller 1, low 0x2000, high 0x4000, behind the four bytes spec §9.6 describes.
        val payload = byteArrayOf(0, 0, 0, 0, 1, 0, 0, 0x20, 0, 0x40)
        val rumble = requireNotNull(HostFeedbackParser.rumble(payload))
        assertEquals(1, rumble.controllerNumber)
        assertEquals(0x2000, rumble.lowFrequencyMotor)
        assertEquals(0x4000, rumble.highFrequencyMotor)
    }

    @Test
    fun `a short rumble payload is read from the start instead`() {
        val payload = byteArrayOf(2, 0, 0x11, 0x11, 0x22, 0x22)
        val rumble = requireNotNull(HostFeedbackParser.rumble(payload))
        assertEquals(2, rumble.controllerNumber)
        assertEquals(0x1111, rumble.lowFrequencyMotor)
    }

    @Test
    fun `rumble triggers have no leading bytes, unlike rumble`() {
        // The asymmetry is real and is exactly the kind of thing that gets "tidied up" into a bug.
        val triggers = requireNotNull(
            HostFeedbackParser.rumbleTriggers(byteArrayOf(0, 0, 0x34, 0x12, 0x78, 0x56)),
        )
        assertEquals(0, triggers.controllerNumber)
        assertEquals(0x1234, triggers.leftTriggerMotor)
        assertEquals(0x5678, triggers.rightTriggerMotor)
    }

    @Test
    fun `set-motion-event puts the rate before the type, not after it`() {
        // Spec §10.3 lists the fields as "controller number, motion type, report rate", which reads
        // as (number, type, rate); the reference client parses (number, rate, type). Reading it the
        // spec's way sets the rate from the motion type — 2 Hz for a gyro — which is unusable and
        // looks fine in a log.
        val payload = byteArrayOf(1, 0, 100, 0, MotionType.GYROSCOPE.code.toByte())
        val state = requireNotNull(HostFeedbackParser.setMotionEventState(payload))
        assertEquals(1, state.controllerNumber)
        assertEquals(100, state.reportRateHz)
        assertEquals(MotionType.GYROSCOPE, state.motionType)
    }

    @Test
    fun `an unknown motion type is refused rather than guessed at`() {
        assertNull(HostFeedbackParser.setMotionEventState(byteArrayOf(0, 0, 60, 0, 9)))
        assertNull(HostFeedbackParser.setMotionEventState(byteArrayOf(0, 0)))
        assertNull(HostFeedbackParser.rumble(byteArrayOf(0, 0)))
    }

    @Test
    fun `the input profile answers the three questions anything branches on`() {
        val gen7Sunshine = InputProfile(generation = 7, isSunshine = true)
        assertTrue(gen7Sunshine.isGen5OrLater)
        assertTrue(gen7Sunshine.usesGcm)
        assertEquals(InputConstants.MAX_GAMEPADS_SUNSHINE, gen7Sunshine.maxGamepads)

        val gen5Gfe = InputProfile(generation = 5, isSunshine = false)
        assertTrue(gen5Gfe.isGen5OrLater)
        assertFalse(gen5Gfe.usesGcm)
        assertEquals(InputConstants.MAX_GAMEPADS_GFE, gen5Gfe.maxGamepads)

        assertNotEquals(gen7Sunshine, gen5Gfe)
    }
}
