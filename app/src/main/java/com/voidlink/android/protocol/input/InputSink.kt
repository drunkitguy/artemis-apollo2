package com.voidlink.android.protocol.input

import com.voidlink.android.protocol.ProtocolLog
import java.util.concurrent.atomic.AtomicReference

/**
 * Where a built input payload goes. **This is the seam the session layer fills.**
 *
 * One method, and it knows nothing about input: the payload handed over is the complete body of a
 * control-stream message of type index
 * [com.voidlink.android.protocol.control.ControlMessageIndex.INPUT_DATA] — already encrypted,
 * already carrying its big-endian length prefix (spec §10). All the session has to do is frame it
 * with the control header and put it on the urgent channel, reliably (spec §10.4):
 *
 * ```kotlin
 * val type = table.typeOf(ControlMessageIndex.INPUT_DATA) ?: return false
 * send(type, payload, urgent = true, EnetDelivery.RELIABLE)
 * ```
 *
 * The split is deliberate. Encryption lives on this side because the IV chaining rule (spec §10.1)
 * is an input concern, is the highest-risk guess in the path, and has to be switchable at runtime by
 * the input layer that logs it. Framing and delivery live on the session's side because channel
 * choice and reliability are control-stream concerns.
 *
 * Implementations must be safe to call from any thread: the sender is driven from the Android input
 * thread, a sensor callback and a coroutine flush tick.
 */
fun interface InputPacketTransport {

    /**
     * Sends one input payload.
     *
     * @return true when the payload was handed to the transport. A `false` is not fatal — a single
     *   lost input packet is a dropped keystroke, not a dead session — but it is counted, because a
     *   session where every send fails is worth surfacing.
     */
    fun sendInputPayload(payload: ByteArray): Boolean
}

/**
 * What the UI calls to make something happen on the host.
 *
 * The one interface the stream screen depends on. Everything above it — Android `MotionEvent`s,
 * `InputDevice` axes, `SensorManager` callbacks, gesture recognisers — turns into calls on this;
 * everything below it is packet layout and encryption. Coordinates and units are already
 * host-shaped by the time they arrive here: normalized 0..1 for touch, stream pixels for absolute
 * mouse, `int16` for sticks, deg/s for the gyro.
 *
 * **Every method is safe to call from any thread.**
 */
interface InputSink {

    /** Whether the host understands the Sunshine extension packets: native touch, motion, arrival. */
    val supportsSunshineExtensions: Boolean

    /**
     * Accumulates a relative mouse move (spec §10.3, §10.4).
     *
     * Deltas are coalesced and flushed at most once per
     * [InputConstants.MOUSE_BATCH_INTERVAL_MS]; a caller that wants the move out now calls [flush].
     */
    fun mouseMoveRelative(deltaX: Int, deltaY: Int)

    /**
     * Sets the absolute pointer position, in the reference frame the host scales against.
     *
     * @param x position in stream pixels.
     * @param y position in stream pixels.
     * @param referenceWidth the stream's width; the sender applies the reference client's off-by-one
     *   workaround before the packet is built.
     * @param referenceHeight the stream's height.
     */
    fun mouseMoveAbsolute(x: Int, y: Int, referenceWidth: Int, referenceHeight: Int)

    /** Presses or releases a mouse button (spec §10.3). */
    fun mouseButton(button: MouseButton, pressed: Boolean)

    /**
     * Scrolls vertically.
     *
     * @param clicks wheel clicks; fractions are allowed and become fractions of
     *   [InputConstants.WHEEL_DELTA], which is what "high-resolution scrolling" means here.
     */
    fun scroll(clicks: Float)

    /** Scrolls horizontally. Silently ignored on a host that is not Sunshine (spec §10.3). */
    fun horizontalScroll(clicks: Float)

    /**
     * Presses or releases a key.
     *
     * @param virtualKeyCode a **Windows** virtual-key code — see [WindowsKeyCodes].
     * @param modifiers a mask of [InputConstants.MODIFIER_SHIFT] and friends.
     */
    fun key(virtualKeyCode: Int, pressed: Boolean, modifiers: Int)

    /** Sends text, one code point per packet (spec §10.3). For IME and soft-keyboard input. */
    fun text(text: String)

    /**
     * Tells the host a pad appeared (spec §10.3).
     *
     * @param capabilities a mask of [InputConstants.CCAP_RUMBLE] and friends.
     * @param supportedButtonFlags every button this pad can produce.
     */
    fun controllerArrived(
        controllerNumber: Int,
        type: ControllerType,
        capabilities: Int,
        supportedButtonFlags: Int,
    )

    /** Tells the host a pad went away: an empty event with its mask bit cleared (spec §10.3). */
    fun controllerRemoved(controllerNumber: Int)

    /** Reports a pad's complete state. Sent only when it differs from the last one (spec §10.4). */
    fun controllerState(state: ControllerState)

    /**
     * Reports a motion sample.
     *
     * @param x accelerometer in m/s² including gravity, or gyroscope in **degrees** per second.
     */
    fun controllerMotion(controllerNumber: Int, type: MotionType, x: Float, y: Float, z: Float)

    /**
     * Reports one native touch pointer (spec §10.3).
     *
     * @param x normalized 0..1 across the **video rectangle**, not the view.
     * @param pressureOrDistance contact pressure, or hover distance for a hovering pointer.
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
    )

    /**
     * Releases everything held: touches, mouse buttons, keys, controller buttons.
     *
     * UI spec §5.4: *"Focus loss (app backgrounded, dialog opened) ⇒ send `LI_TOUCH_EVENT_CANCEL_ALL`
     * and release every held mouse/controller button. Stuck-key bugs are unforgivable here."*
     */
    fun releaseAll()

    /** Writes any batched state out now. Called on a tick and before anything order-sensitive. */
    fun flush()
}

/**
 * A sink that does nothing, for a screen with no session behind it yet.
 *
 * The stream screen mounts its input surface as soon as there is a picture, which can be before the
 * session has attached a transport. Substituting this rather than null-checking at forty call sites
 * keeps the UI free of "is input up yet" branches, and makes a touch during connect a no-op instead
 * of a crash.
 */
object NoOpInputSink : InputSink {
    override val supportsSunshineExtensions: Boolean = false
    override fun mouseMoveRelative(deltaX: Int, deltaY: Int) = Unit
    override fun mouseMoveAbsolute(x: Int, y: Int, referenceWidth: Int, referenceHeight: Int) = Unit
    override fun mouseButton(button: MouseButton, pressed: Boolean) = Unit
    override fun scroll(clicks: Float) = Unit
    override fun horizontalScroll(clicks: Float) = Unit
    override fun key(virtualKeyCode: Int, pressed: Boolean, modifiers: Int) = Unit
    override fun text(text: String) = Unit
    override fun controllerArrived(
        controllerNumber: Int,
        type: ControllerType,
        capabilities: Int,
        supportedButtonFlags: Int,
    ) = Unit
    override fun controllerRemoved(controllerNumber: Int) = Unit
    override fun controllerState(state: ControllerState) = Unit
    override fun controllerMotion(
        controllerNumber: Int,
        type: MotionType,
        x: Float,
        y: Float,
        z: Float,
    ) = Unit
    override fun touch(
        eventType: TouchEventType,
        pointerId: Int,
        x: Float,
        y: Float,
        pressureOrDistance: Float,
        contactAreaMajor: Float,
        contactAreaMinor: Float,
        rotation: Int,
    ) = Unit
    override fun releaseAll() = Unit
    override fun flush() = Unit
}

/**
 * Everything the input layer needs from a live session in order to start sending.
 *
 * @param remoteInputKey the 16-byte `riKey` sent in `/launch?rikey=` (spec §5). Not copied: the
 *   session generated it and nothing else holds it.
 * @param remoteInputKeyId the `riKeyId`, which seeds the IV (spec §10.1).
 * @param profile the host's generation and family (spec §10.1, §10.3).
 * @param transport where built payloads go.
 */
class InputConnection(
    val remoteInputKey: ByteArray,
    val remoteInputKeyId: Int,
    val profile: InputProfile,
    val transport: InputPacketTransport,
)

/** Something the host sent back that the input layer acts on (spec §9.6). */
sealed interface HostInputFeedback {

    /**
     * A rumble command.
     *
     * @property lowFrequencyMotor 0..65535, the heavy motor.
     * @property highFrequencyMotor 0..65535, the light motor.
     */
    class Rumble(
        val controllerNumber: Int,
        val lowFrequencyMotor: Int,
        val highFrequencyMotor: Int,
    ) : HostInputFeedback

    /** Trigger rumble, on a pad that has motors in its triggers. */
    class RumbleTriggers(
        val controllerNumber: Int,
        val leftTriggerMotor: Int,
        val rightTriggerMotor: Int,
    ) : HostInputFeedback

    /**
     * The host asking us to start or stop reporting motion (spec §10.3).
     *
     * @property reportRateHz the rate to report at; **zero means stop**. Spec §10.3: "Honor the
     *   requested rate by throttling; do not just dump every sensor callback."
     */
    class SetMotionEventState(
        val controllerNumber: Int,
        val motionType: MotionType,
        val reportRateHz: Int,
    ) : HostInputFeedback
}

/**
 * The input pipeline's rendezvous between the session layer and the stream screen.
 *
 * Modelled on [com.voidlink.android.media.VideoPipeline], which solves the identical problem for
 * frames: the session layer owns the control stream and the remote-input key, the stream screen owns
 * the Android event sources, and neither can hold a reference to the other without the layering
 * inverting. So the session **publishes** a connection here when its control stream comes up, and
 * the screen **observes** one.
 *
 * ### For the session layer
 *
 * ```kotlin
 * // once the control stream is up and the riKey is known:
 * InputPipeline.attach(
 *     InputConnection(
 *         remoteInputKey = parameters.remoteInputKey.key,
 *         remoteInputKeyId = parameters.remoteInputKey.keyId,
 *         profile = InputProfile(profile.generation, profile.isSunshineish),
 *         transport = { payload -> controlStream.sendInputPayload(payload) },
 *     ),
 * )
 *
 * // when a ControlEvent.Rumble arrives:
 * InputPipeline.publish(HostInputFeedback.Rumble(number, low, high))
 *
 * // in teardown, before the ENet disconnect (spec §9.7 step 1, "stop sending input"):
 * InputPipeline.detach()
 * ```
 *
 * [detach] releases every held button and touch first, so a session that ends while a key is down
 * does not leave the host holding it.
 *
 * Deliberately a process-wide singleton with one slot: there is exactly one stream at a time, and
 * `StreamActivity` is `singleTop`. A second [attach] replaces the first and logs it.
 */
object InputPipeline {

    private val connection = AtomicReference<InputConnection?>(null)

    private val sinkRef = AtomicReference<InputSink>(NoOpInputSink)

    private val listeners = mutableListOf<(HostInputFeedback) -> Unit>()

    private val lock = Any()

    /**
     * The live sink, or [NoOpInputSink] when no session is attached.
     *
     * Read on every input event, so it is a plain atomic get rather than a flow: the stream screen
     * does not need to recompose when input becomes available, it just needs the next touch to go
     * somewhere real.
     */
    val sink: InputSink get() = sinkRef.get()

    /** Whether a session is currently attached. */
    val isAttached: Boolean get() = connection.get() != null

    /**
     * Publishes a live session's input connection and builds the sink over it.
     *
     * @return the sink, which the caller may ignore — the stream screen reads [sink] instead.
     */
    fun attach(connection: InputConnection): InputSink {
        val previous = this.connection.getAndSet(connection)
        if (previous != null) {
            ProtocolLog.w(
                InputConstants.TAG,
                "a second input connection was attached while one was live; replacing it",
            )
            sinkRef.get().releaseAll()
        }
        val sender = InputSender(
            transport = connection.transport,
            profile = connection.profile,
            encryptor = InputEncryptor(
                key = connection.remoteInputKey,
                keyId = connection.remoteInputKeyId,
                profile = connection.profile,
            ),
        )
        sinkRef.set(sender)
        ProtocolLog.i(
            InputConstants.TAG,
            "input attached: generation ${connection.profile.generation}, " +
                "sunshine=${connection.profile.isSunshine}",
        )
        return sender
    }

    /**
     * Releases everything held and disconnects the sink (spec §9.7 step 1).
     *
     * Idempotent. Safe to call from a teardown path that may already be cancelled — it does no I/O
     * beyond the release packets, and those go through the same transport that is about to close.
     */
    fun detach() {
        val previous = connection.getAndSet(null) ?: return
        runCatching { sinkRef.get().releaseAll() }.onFailure {
            ProtocolLog.w(InputConstants.TAG, "releasing held input during detach failed: ${it.message}")
        }
        sinkRef.set(NoOpInputSink)
        ProtocolLog.i(
            InputConstants.TAG,
            "input detached (generation ${previous.profile.generation})",
        )
    }

    /** Registers a feedback listener — the stream screen's rumble player and motion pump. */
    fun addFeedbackListener(listener: (HostInputFeedback) -> Unit) {
        synchronized(lock) { listeners += listener }
    }

    /** Removes a listener registered with [addFeedbackListener]. */
    fun removeFeedbackListener(listener: (HostInputFeedback) -> Unit) {
        synchronized(lock) { listeners -= listener }
    }

    /**
     * Delivers something the host sent back.
     *
     * Called by the session layer from its control-event pump. A listener that throws is logged and
     * skipped rather than allowed to kill that pump: the control stream also carries the
     * termination message, and losing it would leave the session unable to end cleanly.
     */
    fun publish(feedback: HostInputFeedback) {
        // The motion rate is applied here rather than by a listener: spec §10.3 requires us to
        // honour it ("do not just dump every sensor callback"), and a request that only took effect
        // when some UI happened to be listening would be honoured on some screens and not others.
        if (feedback is HostInputFeedback.SetMotionEventState) {
            (sinkRef.get() as? InputSender)?.setMotionReportRate(
                controllerNumber = feedback.controllerNumber,
                type = feedback.motionType,
                reportRateHz = feedback.reportRateHz,
            )
        }
        val snapshot = synchronized(lock) { listeners.toList() }
        for (listener in snapshot) {
            runCatching { listener(feedback) }.onFailure {
                ProtocolLog.w(InputConstants.TAG, "an input feedback listener threw: ${it.message}")
            }
        }
    }

    /** Drops every listener and any attached connection. For tests. */
    fun resetForTesting() {
        connection.set(null)
        sinkRef.set(NoOpInputSink)
        synchronized(lock) { listeners.clear() }
    }
}
