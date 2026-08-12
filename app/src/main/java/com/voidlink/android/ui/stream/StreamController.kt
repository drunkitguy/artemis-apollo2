package com.voidlink.android.ui.stream

import android.view.Surface
import com.voidlink.android.data.HostRepository
import com.voidlink.android.data.SettingsRepository
import com.voidlink.android.data.StreamSettings
import com.voidlink.android.media.CodecSupport
import com.voidlink.android.media.DecoderChoice
import com.voidlink.android.media.DecoderEvent
import com.voidlink.android.media.DecoderProbe
import com.voidlink.android.media.DecoderSelectionResult
import com.voidlink.android.media.DecoderSelector
import com.voidlink.android.media.MediaCodecProbe
import com.voidlink.android.media.StreamFormatResolver
import com.voidlink.android.media.VideoDecoder
import com.voidlink.android.media.VideoFrame
import com.voidlink.android.media.VideoSourceFactory
import com.voidlink.android.media.VideoSourceRequest
import com.voidlink.android.media.VideoSourceResult
import com.voidlink.android.protocol.ProtocolLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What the stream screen was asked to stream.
 *
 * @property hostId the host's uuid, from [com.voidlink.android.ui.navigation.StreamLaunchContract].
 * @property appId the host-assigned application id.
 * @property appName the application's display name.
 * @property displayWidth the display's width in pixels, for `Native` resolution.
 * @property displayHeight the display's height in pixels, for `Native` resolution.
 */
data class StreamStartRequest(
    val hostId: String?,
    val appId: String?,
    val appName: String?,
    val displayWidth: Int,
    val displayHeight: Int,
)

/**
 * Drives one streaming session, from settings through decoder selection to a picture on screen.
 *
 * A plain class rather than a `ViewModel`: `StreamActivity` declares `configChanges` covering
 * orientation and size (UI spec §5.7 requires that rotation must not restart the session), so the
 * Activity instance already outlives every configuration change a stream can experience, and a
 * `ViewModel` would add a lifecycle indirection with nothing to protect against.
 *
 * ### The order of operations, and why it is that order
 *
 * 1. **Settings.** Global, overridden per-host.
 * 2. **Probe and select a decoder** — *before* talking to the host. A device that cannot decode
 *    4K should be told so in a second, not after a full RTSP handshake ends in a codec error.
 * 3. **Wait for the surface.** The `SurfaceView` is mounted as soon as step 2 fixes the stream's
 *    dimensions, so this is usually instant.
 * 4. **Open the video source.** This is where the session layer negotiates with the host.
 * 5. **Re-select** if the host negotiated something other than what was asked for.
 * 6. **Decode**, in a loop that survives the surface being destroyed and recreated.
 *
 * Every path out of every step ends either in a picture or in [StreamPhase.Failed] with a sentence
 * naming the cause. There is no path that leaves the screen black.
 *
 * @param scope the coroutine scope the session lives in — the Activity's `lifecycleScope`, which
 *   is confined to the main thread, so the surface handoff needs no extra synchronisation.
 * @param settingsRepository global settings.
 * @param hostRepository known hosts, for the per-host settings override.
 * @param probe decoder enumeration.
 * @param videoSourceFactory where frames come from.
 * @param decoderFactory builds a decoder for a chosen codec and surface; overridable for tests.
 */
class StreamController(
    private val scope: CoroutineScope,
    private val settingsRepository: SettingsRepository,
    private val hostRepository: HostRepository,
    private val probe: DecoderProbe,
    private val videoSourceFactory: VideoSourceFactory,
    private val decoderFactory: (DecoderChoice, Surface) -> VideoDecoder =
        { choice, surface -> VideoDecoder(choice, surface) },
) {

    private val mutableState = MutableStateFlow(StreamUiState())

    /** Everything the screen draws. */
    val state: StateFlow<StreamUiState> = mutableState.asStateFlow()

    private var sessionJob: Job? = null
    private var surfaceHandoff = CompletableDeferred<Surface>()
    private var activeDecoder: VideoDecoder? = null
    private var decodeJob: Job? = null
    private var firstFrameWatchdog: Job? = null
    private var streamEnded = false

    private var hostId: String? = null
    private var hostHasSettingsOverride = false

    /**
     * Starts the session. Safe to call more than once; later calls are ignored, which is what
     * makes a `singleTop` re-delivery harmless.
     */
    fun start(request: StreamStartRequest) {
        if (sessionJob != null) return
        hostId = request.hostId
        mutableState.update { it.copy(appName = request.appName) }
        observeStatsSetting()
        sessionJob = scope.launch { runSession(request) }
    }

    /**
     * Hands the decoder its render target.
     *
     * Called from `SurfaceHolder.Callback.surfaceCreated` on the main thread.
     */
    fun onSurfaceAvailable(surface: Surface) {
        if (!surfaceHandoff.isCompleted) {
            surfaceHandoff.complete(surface)
        }
    }

    /**
     * Takes the render target away.
     *
     * Called from `surfaceDestroyed`, which **must not return until the codec has stopped using
     * the surface** — so the decoder is released synchronously here rather than by cancelling a
     * coroutine and hoping. The decode loop then parks on a fresh handoff and rebuilds the decoder
     * when a surface comes back, which is what makes backgrounding and returning work.
     */
    fun onSurfaceDestroyed() {
        activeDecoder?.release()
        activeDecoder = null
        surfaceHandoff = CompletableDeferred()
        decodeJob?.cancel()
    }

    /**
     * Ends the session and releases everything.
     *
     * Idempotent, and safe from any state.
     */
    fun stop() {
        firstFrameWatchdog?.cancel()
        firstFrameWatchdog = null
        decodeJob?.cancel()
        decodeJob = null
        sessionJob?.cancel()
        sessionJob = null
        activeDecoder?.release()
        activeDecoder = null
    }

    /**
     * Shows or hides the stats chip, and persists the choice.
     *
     * UI spec §5.2: dismissing the chip writes the setting off, because that is the dismissal a
     * user expects to stick. The write goes to the per-host override when the host has one, and to
     * the global settings otherwise — writing globally for a host that has an override would
     * appear to do nothing.
     */
    fun setStatsOverlayVisible(visible: Boolean) {
        mutableState.update { it.copy(showStats = visible) }
        scope.launch {
            val target = hostId
            if (target != null && hostHasSettingsOverride) {
                val global = settingsRepository.settings.first()
                hostRepository.updateHost(target) { host ->
                    host.withOverride(global) { settings -> settings.copy(showStatsOverlay = visible) }
                }
            } else {
                settingsRepository.update { settings -> settings.copy(showStatsOverlay = visible) }
            }
        }
    }

    // ---- session ---------------------------------------------------------------------------

    private suspend fun runSession(request: StreamStartRequest) {
        val settings = loadSettings(request.hostId)

        step(StreamPreparationStep.PROBING_DECODER)
        val formatRequest = StreamFormatResolver.requestFor(
            settings = settings,
            displayWidth = request.displayWidth,
            displayHeight = request.displayHeight,
        )
        val candidates = withContext(Dispatchers.Default) { probe.probe(formatRequest) }
        val selection = DecoderSelector.select(candidates, formatRequest)
        if (selection is DecoderSelectionResult.NoDecoder) {
            mutableState.update { it.copy(codecSupport = selection.inventory) }
            fail(
                title = "This device cannot decode the stream",
                message = selection.summary,
                detail = describeInspected(selection),
            )
            return
        }
        var choice = (selection as DecoderSelectionResult.Selected).choice

        // Publishing the format mounts the SurfaceView, which is what produces the surface the
        // next step waits for.
        mutableState.update { it.copy(surfaceFormat = choice.format, codecSupport = choice.inventory) }
        step(StreamPreparationStep.WAITING_FOR_SURFACE)
        surfaceHandoff.await()

        step(StreamPreparationStep.STARTING_SESSION)
        val opened = try {
            videoSourceFactory.open(
                VideoSourceRequest(
                    hostId = request.hostId,
                    appId = request.appId,
                    appName = request.appName,
                    format = choice.format,
                    settings = settings,
                ),
            )
        } catch (error: Throwable) {
            ProtocolLog.e(MediaCodecProbe.TAG, "Opening the video source threw", error)
            VideoSourceResult.Unavailable(
                summary = "The streaming session could not be started.",
                detail = error.message ?: error.javaClass.simpleName,
            )
        }

        if (opened is VideoSourceResult.Unavailable) {
            fail(
                title = "Cannot start the stream",
                message = opened.summary,
                detail = listOfNotNull(
                    opened.detail,
                    "Selected decoder: " + decoderSummary(choice),
                    describeInventory(choice.inventory),
                ).joinToString("\n\n"),
            )
            return
        }
        val ready = opened as VideoSourceResult.Ready

        if (ready.format != choice.format) {
            val negotiated = StreamFormatResolver.requestForNegotiated(ready.format)
            val reprobed = withContext(Dispatchers.Default) { probe.probe(negotiated) }
            val reselected = DecoderSelector.select(reprobed, negotiated)
            if (reselected is DecoderSelectionResult.NoDecoder) {
                runCatching { ready.onClose.invoke() }
                fail(
                    title = "This device cannot decode the stream",
                    message = "The host negotiated ${ready.format.describe()}, which no decoder " +
                        "on this device supports. " + reselected.summary,
                    detail = describeInspected(reselected),
                )
                return
            }
            choice = (reselected as DecoderSelectionResult.Selected).choice
            mutableState.update { it.copy(surfaceFormat = choice.format) }
        }

        step(StreamPreparationStep.WAITING_FOR_VIDEO)
        startFirstFrameWatchdog()

        try {
            decodeLoop(ready.frames, choice)
        } finally {
            firstFrameWatchdog?.cancel()
            firstFrameWatchdog = null
            runCatching { ready.onClose.invoke() }
        }

        if (streamEnded && mutableState.value.phase !is StreamPhase.Failed) {
            fail(
                title = "The stream ended",
                message = "The host closed the session.",
                detail = failureDetail(choice),
            )
        }
    }

    /**
     * Decodes until the frame channel closes or the session is cancelled, rebuilding the decoder
     * each time the surface is replaced.
     *
     * The loop is the whole reason backgrounding does not kill a stream: the frames keep arriving
     * on the channel, the decoder is torn down with its surface, and a new one is built against
     * the new surface without the session noticing.
     */
    private suspend fun decodeLoop(frames: ReceiveChannel<VideoFrame>, choice: DecoderChoice) {
        coroutineScope {
            while (isActive && !streamEnded) {
                val surface = surfaceHandoff.await()
                val job = launch { decodeWithSurface(surface, frames, choice) }
                decodeJob = job
                job.join()
                decodeJob = null
                if (mutableState.value.phase is StreamPhase.Failed) return@coroutineScope
            }
        }
    }

    private suspend fun decodeWithSurface(
        surface: Surface,
        frames: ReceiveChannel<VideoFrame>,
        choice: DecoderChoice,
    ) {
        val decoder = decoderFactory(choice, surface)
        activeDecoder = decoder
        try {
            coroutineScope {
                val eventJob = launch { decoder.events.collect { event -> onDecoderEvent(event, choice) } }
                val statsJob = launch {
                    while (isActive) {
                        delay(STATS_INTERVAL_MILLIS)
                        val snapshot = decoder.stats()
                        mutableState.update { it.copy(stats = snapshot) }
                    }
                }
                try {
                    // Codec creation runs on the main thread deliberately: it is tens of
                    // milliseconds once per surface, and moving it off would open a window in which
                    // the surface could be destroyed while a codec was being configured against it.
                    if (decoder.start()) {
                        decoder.consume(frames)
                        // consume() only returns normally when the producer closed the channel.
                        streamEnded = true
                    } else if (mutableState.value.phase !is StreamPhase.Failed) {
                        fail(
                            title = "The video decoder could not start",
                            message = "${decoder.decoderName} refused every configuration we " +
                                "offered for ${decoder.format.describe()}. Try a lower " +
                                "resolution, or force H.264 in Settings.",
                            detail = failureDetail(choice),
                        )
                    }
                } finally {
                    eventJob.cancel()
                    statsJob.cancel()
                }
            }
        } finally {
            activeDecoder = null
            decoder.release()
        }
    }

    private fun onDecoderEvent(event: DecoderEvent, choice: DecoderChoice) {
        when (event) {
            is DecoderEvent.FirstFrameRendered -> {
                firstFrameWatchdog?.cancel()
                firstFrameWatchdog = null
                mutableState.update {
                    it.copy(
                        phase = StreamPhase.Streaming(
                            decoderName = choice.candidate.name,
                            notes = choice.notes,
                        ),
                    )
                }
            }

            is DecoderEvent.FatalError -> fail(
                title = "The video decoder failed",
                message = event.message,
                detail = failureDetail(choice),
            )

            else -> Unit
        }
    }

    /**
     * Fails the session if nothing has been decoded within
     * [FIRST_FRAME_TIMEOUT_MILLIS] of the host accepting it.
     *
     * Architecture §4.2 puts the timeout at 10 s. Without it, a host that accepts the session and
     * then sends nothing leaves the user watching a spinner forever — which is the same bug as a
     * black screen, wearing a different hat.
     */
    private fun startFirstFrameWatchdog() {
        firstFrameWatchdog?.cancel()
        firstFrameWatchdog = scope.launch {
            delay(FIRST_FRAME_TIMEOUT_MILLIS)
            if (mutableState.value.phase is StreamPhase.Preparing) {
                fail(
                    title = "No video arrived",
                    message = "The host accepted the session but no video frame arrived within " +
                        "${FIRST_FRAME_TIMEOUT_MILLIS / 1000} seconds. This is usually a " +
                        "firewall blocking the video port, or a host that is still starting the " +
                        "game.",
                    detail = null,
                )
                stop()
            }
        }
    }

    // ---- helpers ---------------------------------------------------------------------------

    private suspend fun loadSettings(hostId: String?): StreamSettings {
        val global = settingsRepository.settings.first()
        if (hostId == null) {
            mutableState.update { it.copy(showStats = global.showStatsOverlay, settings = global) }
            return global
        }
        val host = runCatching { hostRepository.snapshot() }
            .getOrDefault(emptyList())
            .firstOrNull { it.uuid == hostId }
        hostHasSettingsOverride = host?.settingsOverride != null
        val effective = host?.effectiveSettings(global) ?: global
        mutableState.update { it.copy(showStats = effective.showStatsOverlay, settings = effective) }
        return effective
    }

    /**
     * Keeps the chip in sync with the settings panel while the stream runs.
     *
     * Only the global value is observed: a host override is a snapshot taken at session start, and
     * re-reading the host list on every settings write would be a lot of work for a checkbox.
     */
    private fun observeStatsSetting() {
        scope.launch {
            settingsRepository.settings.collect { settings ->
                if (!hostHasSettingsOverride) {
                    // The whole object, not just the chip: the input surface reads `settings` live
                    // so that UI spec §5.3's "apply live" rows do.
                    mutableState.update {
                        it.copy(showStats = settings.showStatsOverlay, settings = settings)
                    }
                }
            }
        }
    }

    private fun step(step: StreamPreparationStep) {
        mutableState.update { current ->
            if (current.phase is StreamPhase.Failed) current else current.copy(phase = StreamPhase.Preparing(step))
        }
    }

    private fun fail(title: String, message: String, detail: String?) {
        ProtocolLog.w(MediaCodecProbe.TAG, "Stream failed: $title — $message")
        mutableState.update {
            it.copy(phase = StreamPhase.Failed(title = title, message = message, detail = detail))
        }
    }

    private fun describeInspected(result: DecoderSelectionResult.NoDecoder): String? {
        val inventory = describeInventory(result.inventory)
        val inspected = if (result.inspected.isEmpty()) {
            null
        } else {
            "Decoders inspected:\n" + result.inspected.joinToString(separator = "\n") {
                "• " + it.describe()
            }
        }
        val parts = listOfNotNull(inventory, inspected)
        return if (parts.isEmpty()) null else parts.joinToString("\n\n")
    }

    /**
     * The device's video capability report, one line per codec.
     *
     * Shown on every failure screen, not only the "no decoder" one. It is the only place a user
     * can find out whether their device has hardware AV1 — the question that decides whether the
     * codec setting is a real choice or a wish.
     */
    private fun describeInventory(inventory: List<CodecSupport>): String? {
        if (inventory.isEmpty()) return null
        return "This device's video decoders:\n" +
            inventory.joinToString(separator = "\n") { "• " + it.describe() }
    }

    private fun decoderSummary(choice: DecoderChoice): String {
        val acceleration = if (choice.candidate.hardwareAccelerated) "hardware" else "SOFTWARE"
        return "${choice.candidate.name} · ${choice.format.describe()} · $acceleration"
    }

    /** The small print for a failure once a decoder had already been chosen. */
    private fun failureDetail(choice: DecoderChoice): String = listOfNotNull(
        "Selected decoder: " + decoderSummary(choice),
        describeInventory(choice.inventory),
    ).joinToString("\n\n")

    private companion object {
        /** Stats refresh period. UI spec §5.2 asks for 2 Hz, not per frame. */
        const val STATS_INTERVAL_MILLIS: Long = 500L

        /** How long to wait for a first decoded frame before giving up (architecture §4.2). */
        const val FIRST_FRAME_TIMEOUT_MILLIS: Long = 10_000L
    }
}
