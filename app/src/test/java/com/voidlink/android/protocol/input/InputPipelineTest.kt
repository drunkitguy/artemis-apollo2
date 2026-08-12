package com.voidlink.android.protocol.input

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The seam between the session layer and the input layer (`docs/02-ARCHITECTURE.md` §2.3's swap
 * pattern, as [com.voidlink.android.media.VideoPipeline] does it for frames).
 *
 * This file is the contract. The session layer cannot edit the `protocol.input` package and the input
 * layer cannot edit `protocol.session`, so what each side may assume about the other has to be written
 * down somewhere executable, and this is it:
 *
 * * Before [InputPipeline.attach], the sink is a no-op — a touch during the connecting phase must
 *   not crash and must not queue up to be replayed later.
 * * After [InputPipeline.attach], every call reaches the transport the session supplied.
 * * [InputPipeline.detach] releases everything held **before** it disconnects, because it is called
 *   from spec §9.7 step 1 ("stop sending input") while the control stream is still open — a key
 *   released after the ENet disconnect is a key never released.
 */
class InputPipelineTest {

    private val key = ByteArray(InputConstants.KEY_BYTES)

    @After
    fun reset() {
        InputPipeline.resetForTesting()
    }

    private fun connection(sent: MutableList<ByteArray>) = InputConnection(
        remoteInputKey = key,
        remoteInputKeyId = 1,
        profile = InputProfile(generation = 7, isSunshine = true),
        transport = { payload ->
            sent += payload
            true
        },
    )

    @Test
    fun `before a session attaches, the sink is the no-op`() {
        assertSame(NoOpInputSink, InputPipeline.sink)
        assertFalse(InputPipeline.isAttached)
        InputPipeline.sink.mouseMoveRelative(10, 10)
        InputPipeline.sink.key(0x41, pressed = true, modifiers = 0)
    }

    @Test
    fun `attaching routes input to the session's transport`() {
        val sent = mutableListOf<ByteArray>()
        InputPipeline.attach(connection(sent))

        assertTrue(InputPipeline.isAttached)
        InputPipeline.sink.key(0x41, pressed = true, modifiers = 0)
        assertEquals(1, sent.size)
        // What the session receives is the complete INPUT_DATA payload: a big-endian length prefix
        // plus the sealed blob. Nothing else is expected of it but framing and delivery.
        val declared = ((sent[0][0].toInt() and 0xFF) shl 24) or
            ((sent[0][1].toInt() and 0xFF) shl 16) or
            ((sent[0][2].toInt() and 0xFF) shl 8) or
            (sent[0][3].toInt() and 0xFF)
        assertEquals(sent[0].size - InputConstants.ENCRYPTED_LENGTH_PREFIX_BYTES, declared)
    }

    @Test
    fun `detaching releases what is held and then stops sending`() {
        val sent = mutableListOf<ByteArray>()
        InputPipeline.attach(connection(sent))
        InputPipeline.sink.key(0x41, pressed = true, modifiers = 0)
        InputPipeline.sink.mouseButton(MouseButton.LEFT, pressed = true)
        sent.clear()

        InputPipeline.detach()

        // A key up and a mouse button up, sent while the control stream is still open.
        assertEquals(2, sent.size)
        assertSame(NoOpInputSink, InputPipeline.sink)
        assertFalse(InputPipeline.isAttached)

        sent.clear()
        InputPipeline.sink.key(0x41, pressed = false, modifiers = 0)
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `detaching twice is harmless`() {
        InputPipeline.attach(connection(mutableListOf()))
        InputPipeline.detach()
        InputPipeline.detach()
        assertFalse(InputPipeline.isAttached)
    }

    @Test
    fun `feedback reaches every listener`() {
        val seen = mutableListOf<HostInputFeedback>()
        val listener: (HostInputFeedback) -> Unit = { seen += it }
        InputPipeline.addFeedbackListener(listener)

        InputPipeline.publish(HostInputFeedback.Rumble(0, 0x8000, 0x4000))
        assertEquals(1, seen.size)

        InputPipeline.removeFeedbackListener(listener)
        InputPipeline.publish(HostInputFeedback.Rumble(0, 0, 0))
        assertEquals(1, seen.size)
    }

    @Test
    fun `a listener that throws does not stop the others or the caller`() {
        // The session publishes from its control-event pump, which also carries the termination
        // message; losing that pump to a UI bug would leave the session unable to end.
        val seen = mutableListOf<HostInputFeedback>()
        InputPipeline.addFeedbackListener { error("this listener is broken") }
        InputPipeline.addFeedbackListener { seen += it }

        InputPipeline.publish(HostInputFeedback.Rumble(0, 1, 1))
        assertEquals(1, seen.size)
    }

    @Test
    fun `a motion-rate request is applied to the sender without any listener`() {
        val sent = mutableListOf<ByteArray>()
        InputPipeline.attach(connection(sent))

        InputPipeline.publish(
            HostInputFeedback.SetMotionEventState(
                controllerNumber = 0,
                motionType = MotionType.GYROSCOPE,
                reportRateHz = 0,
            ),
        )
        sent.clear()

        // A zero rate means stop; the sender must honour it whether or not the UI is listening.
        repeat(5) { InputPipeline.sink.controllerMotion(0, MotionType.GYROSCOPE, 1f, 1f, 1f) }
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `attaching a second session replaces the first`() {
        val first = mutableListOf<ByteArray>()
        val second = mutableListOf<ByteArray>()
        InputPipeline.attach(connection(first))
        InputPipeline.attach(connection(second))

        InputPipeline.sink.key(0x41, pressed = true, modifiers = 0)
        assertTrue(first.isEmpty())
        assertEquals(1, second.size)
    }
}
