package com.voidlink.android.ui.diagnostics

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.voidlink.android.data.BitrateAdvice
import com.voidlink.android.data.BitrateAdvisor
import com.voidlink.android.data.LinkGrade
import com.voidlink.android.data.LinkQuality
import com.voidlink.android.data.SettingsFormat
import com.voidlink.android.data.StreamSettings
import com.voidlink.android.data.ThroughputEvidence
import com.voidlink.android.data.ThroughputFailure
import com.voidlink.android.data.ThroughputMode
import com.voidlink.android.ui.components.HairlineDivider
import com.voidlink.android.ui.components.SegmentedControl
import com.voidlink.android.ui.components.VoidLinkIcons
import com.voidlink.android.ui.theme.VoidLinkShapeTokens
import com.voidlink.android.ui.theme.VoidLinkTheme
import java.util.Locale

/**
 * The connection test, bound to its view model.
 *
 * @param hostId the [com.voidlink.android.data.KnownHost.uuid] to measure the path to.
 * @param settings the settings the recommendation is for — already resolved, so an override scope
 *   passes that host's effective values.
 * @param applyScopeLabel names where an applied bitrate would be written, e.g. "all PCs" or
 *   "BATTLESTATION only". Shown next to Apply so the write is never a surprise.
 * @param onApply writes the recommended value into whichever settings scope the panel is in.
 * @param onClose dismisses the screen.
 * @param modifier layout modifier.
 */
@Composable
fun ConnectionTestRoute(
    hostId: String,
    settings: StreamSettings,
    applyScopeLabel: String,
    onApply: (Int) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: ConnectionTestViewModel =
        viewModel(factory = ConnectionTestViewModel.factory(hostId), key = "connection-test-$hostId")
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    ConnectionTestScreen(
        state = state,
        settings = settings,
        applyScopeLabel = applyScopeLabel,
        onRetryLink = viewModel::measureLink,
        onSelectMode = viewModel::setMode,
        onSetPort = viewModel::setPort,
        onRunThroughput = viewModel::measureThroughput,
        onCancel = viewModel::cancel,
        onApply = onApply,
        onClose = onClose,
        modifier = modifier,
    )
}

/**
 * The connection test as a full-screen overlay: measured figures, a recommendation, and the
 * reasoning behind it.
 *
 * Presented over the current screen rather than as a route, because it is a detour from whatever
 * the user was doing — a host card's menu or the Video settings — and it should hand them straight
 * back where they came from.
 *
 * Purely presentational; every action is reported upward, so the previews below drive the same
 * composable the app does.
 */
@Composable
fun ConnectionTestScreen(
    state: ConnectionTestUiState,
    settings: StreamSettings,
    applyScopeLabel: String,
    onRetryLink: () -> Unit,
    onSelectMode: (ThroughputMode) -> Unit,
    onSetPort: (Int) -> Unit,
    onRunThroughput: (Double) -> Unit,
    onCancel: () -> Unit,
    onApply: (Int) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = VoidLinkTheme.colors
    val spacing = VoidLinkTheme.spacing
    val scrimSource = remember { MutableInteractionSource() }
    val cardSource = remember { MutableInteractionSource() }

    // What the settings alone ask for, with no link evidence applied. This is the rate the paced
    // UDP test is driven at, unchanged: the bitrate setting is already the whole session's budget
    // on the network, so it is the right number to ask the link to carry.
    val intent = remember(settings) { BitrateAdvisor.recommend(settings, null, null) }
    val advice = remember(settings, state.link, state.throughput) {
        BitrateAdvisor.recommend(settings, state.link, state.throughput)
    }

    Box(modifier = modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.scrim)
                .clickable(
                    interactionSource = scrimSource,
                    indication = null,
                    onClick = onClose,
                ),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(spacing.lg),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                shape = RoundedCornerShape(VoidLinkShapeTokens.CardRadius),
                color = colors.card,
                contentColor = colors.label,
                shadowElevation = if (colors.isDark) 0.dp else CARD_ELEVATION,
                tonalElevation = 0.dp,
                modifier = Modifier
                    .widthIn(max = MAX_CARD_WIDTH)
                    .fillMaxWidth()
                    // Swallows taps so a press inside the card does not reach the dismissing scrim
                    // underneath it.
                    .clickable(interactionSource = cardSource, indication = null) { },
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    TestHeader(hostName = state.hostName, onClose = onClose)
                    HairlineDivider()
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = spacing.xl, vertical = spacing.lg),
                    ) {
                        LinkSection(state = state, onRetry = onRetryLink)
                        Spacer(modifier = Modifier.height(spacing.xl))
                        ThroughputSection(
                            state = state,
                            targetMbps = intent.recommendedKbps / KBPS_PER_MBPS,
                            onSelectMode = onSelectMode,
                            onSetPort = onSetPort,
                            onRun = onRunThroughput,
                            onCancel = onCancel,
                        )
                        Spacer(modifier = Modifier.height(spacing.xl))
                        RecommendationSection(advice = advice)
                    }
                    HairlineDivider()
                    TestFooter(
                        advice = advice,
                        applyScopeLabel = applyScopeLabel,
                        busy = state.isBusy,
                        onApply = { onApply(advice.recommendedKbps) },
                        onClose = onClose,
                    )
                }
            }
        }
    }
}

@Composable
private fun TestHeader(hostName: String, onClose: () -> Unit) {
    val colors = VoidLinkTheme.colors
    val spacing = VoidLinkTheme.spacing
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = spacing.xl, end = spacing.sm, top = spacing.md, bottom = spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Test Connection",
                style = VoidLinkTheme.cardTitle,
                color = colors.label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (hostName.isNotBlank()) {
                Text(
                    text = hostName,
                    style = VoidLinkTheme.footnote,
                    color = colors.secondaryLabel,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        IconButton(onClick = onClose) {
            Icon(
                imageVector = VoidLinkIcons.Close,
                contentDescription = "Close the connection test",
                tint = colors.secondaryLabel,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Tier 1
// ---------------------------------------------------------------------------------------------

@Composable
private fun LinkSection(state: ConnectionTestUiState, onRetry: () -> Unit) {
    val colors = VoidLinkTheme.colors
    val spacing = VoidLinkTheme.spacing
    val link = state.link

    SectionTitle(
        title = "Link quality",
        trailing = {
            if (link != null && link.isUsable) GradePill(link.grade)
        },
    )
    Text(
        text = "Timed round trips to this PC's control port. Jitter — how much that time varies — " +
            "is what makes a stream stutter, and it matters more than raw bandwidth.",
        style = VoidLinkTheme.footnote,
        color = colors.secondaryLabel,
        modifier = Modifier.padding(top = spacing.xs, bottom = spacing.md),
    )

    when (state.linkPhase) {
        LinkPhase.IDLE -> {
            InlineButton(label = "Measure the link", prominent = true, onClick = onRetry)
        }

        LinkPhase.RUNNING -> {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp)),
                color = colors.accent,
                trackColor = colors.fill,
            )
            Spacer(modifier = Modifier.height(spacing.sm))
            Text(
                text = buildString {
                    append("Sampling… ${state.samplesDone} of ${state.samplesTotal}")
                    val last = state.lastRoundTripMs
                    if (last != null) append(" · last $last ms")
                },
                style = VoidLinkTheme.footnote,
                color = colors.secondaryLabel,
            )
        }

        LinkPhase.FAILED -> {
            Text(
                text = state.linkError ?: "The link could not be measured.",
                style = VoidLinkTheme.body,
                color = colors.destructive,
            )
            Spacer(modifier = Modifier.height(spacing.md))
            InlineButton(label = "Try again", prominent = false, onClick = onRetry)
        }

        LinkPhase.DONE -> {
            if (link == null) return
            MetricRow("Latency (median)", millis(link.medianMs))
            MetricRow("Best / slowest 5%", "${millis(link.minMs)} / ${millis(link.p95Ms)}")
            MetricRow("Jitter", millis(link.jitterMs))
            MetricRow("Requests that failed", percent(link.lossPercent))
            MetricRow("Over the window", link.stabilityLabel)
            Spacer(modifier = Modifier.height(spacing.md))
            InlineButton(label = "Measure again", prominent = false, onClick = onRetry)
        }
    }
}

@Composable
private fun GradePill(grade: LinkGrade) {
    val colors = VoidLinkTheme.colors
    val spacing = VoidLinkTheme.spacing
    val tint = when (grade) {
        LinkGrade.EXCELLENT, LinkGrade.GOOD -> colors.online
        LinkGrade.FAIR -> colors.accent
        LinkGrade.POOR -> colors.destructive
    }
    Text(
        text = grade.label,
        style = VoidLinkTheme.footnote.copy(fontWeight = FontWeight.SemiBold),
        color = tint,
        modifier = Modifier
            .clip(RoundedCornerShape(VoidLinkShapeTokens.SegmentPillRadius))
            .background(tint.copy(alpha = PILL_FILL_ALPHA))
            .padding(horizontal = spacing.sm, vertical = 2.dp),
    )
}

// ---------------------------------------------------------------------------------------------
// Tier 2
// ---------------------------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThroughputSection(
    state: ConnectionTestUiState,
    targetMbps: Double,
    onSelectMode: (ThroughputMode) -> Unit,
    onSetPort: (Int) -> Unit,
    onRun: (Double) -> Unit,
    onCancel: () -> Unit,
) {
    val colors = VoidLinkTheme.colors
    val spacing = VoidLinkTheme.spacing
    val modes = remember { listOf(ThroughputMode.PACED_UDP, ThroughputMode.SUSTAINED_TCP) }
    var portText by remember(state.port) { mutableStateOf(state.port.toString()) }
    val running = state.throughputPhase == ThroughputPhase.RUNNING ||
        state.throughputPhase == ThroughputPhase.CONNECTING

    SectionTitle(title = "Throughput", trailing = {})
    Text(
        text = "Needs iperf3 running on the PC. Nothing here touches the streaming host itself — " +
            "that service is fragile under repeated connections, which is exactly why this is a " +
            "separate, opt-in step.",
        style = VoidLinkTheme.footnote,
        color = colors.secondaryLabel,
        modifier = Modifier.padding(top = spacing.xs, bottom = spacing.md),
    )

    SegmentedControl(
        options = modes.map { it.label },
        selectedIndex = modes.indexOf(state.mode),
        onSelect = { index -> onSelectMode(modes[index]) },
        enabled = !running,
    )
    Spacer(modifier = Modifier.height(spacing.sm))
    Text(
        text = when (state.mode) {
            ThroughputMode.PACED_UDP ->
                "Asks the PC to send UDP at ${mbps(targetMbps)} — the budget your current settings " +
                    "ask the network for — and reports what arrives. This is the same traffic " +
                    "shape as the stream, so it measures the thing that actually breaks."
            ThroughputMode.SUSTAINED_TCP ->
                "Asks the PC to send TCP as fast as the link allows. Answers \"how much room is " +
                    "there\", which is useful once the paced test passes easily."
        },
        style = VoidLinkTheme.footnote,
        color = colors.secondaryLabel,
        modifier = Modifier.padding(bottom = spacing.md),
    )

    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = portText,
            onValueChange = { typed ->
                portText = typed.filter { it.isDigit() }.take(MAX_PORT_DIGITS)
                portText.toIntOrNull()?.let(onSetPort)
            },
            label = { Text("iperf3 port") },
            singleLine = true,
            enabled = !running,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.width(PORT_FIELD_WIDTH),
        )
        Spacer(modifier = Modifier.width(spacing.md))
        if (running) {
            InlineButton(label = "Cancel", prominent = false, onClick = onCancel)
        } else {
            InlineButton(
                label = "Run test",
                prominent = true,
                onClick = { onRun(targetMbps) },
            )
        }
    }

    Spacer(modifier = Modifier.height(spacing.md))

    when (state.throughputPhase) {
        ThroughputPhase.IDLE -> IperfHint()

        ThroughputPhase.CONNECTING -> Text(
            text = "Connecting to iperf3 on port ${state.port}…",
            style = VoidLinkTheme.footnote,
            color = colors.secondaryLabel,
        )

        ThroughputPhase.RUNNING -> {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp)),
                color = colors.accent,
                trackColor = colors.fill,
            )
            Spacer(modifier = Modifier.height(spacing.sm))
            Text(
                text = String.format(
                    Locale.US,
                    "%.0f of %d s · %s so far",
                    state.elapsedSeconds,
                    state.totalSeconds,
                    mbps(state.liveMegabitsPerSecond),
                ),
                style = VoidLinkTheme.footnote,
                color = colors.secondaryLabel,
            )
        }

        ThroughputPhase.DONE -> ThroughputResult(state.throughput)

        ThroughputPhase.FAILED -> ThroughputFailureBody(
            failure = state.throughputFailure,
            detail = state.throughputDetail,
            port = state.port,
        )
    }
}

@Composable
private fun ThroughputResult(evidence: ThroughputEvidence?) {
    if (evidence == null) return
    when (evidence) {
        is ThroughputEvidence.Sustained -> {
            MetricRow("Sustained throughput", mbps(evidence.megabitsPerSecond))
            MetricRow("Transferred", String.format(Locale.US, "%.1f MB", evidence.bytes / MB))
        }

        is ThroughputEvidence.Loaded -> {
            MetricRow("Sent at", mbps(evidence.targetMbps))
            MetricRow("Arrived at", mbps(evidence.receivedMbps))
            MetricRow("Packet loss", percent(evidence.lossPercent))
            MetricRow("Jitter under load", millis(evidence.jitterMs))
            MetricRow("Datagrams", evidence.packets.toString())
        }
    }
}

/** The one thing a user needs to be told when the port is empty: the command to run. */
@Composable
private fun IperfHint() {
    val colors = VoidLinkTheme.colors
    val spacing = VoidLinkTheme.spacing
    Column {
        Text(
            text = "Not measured yet. On the PC, open a terminal and run:",
            style = VoidLinkTheme.footnote,
            color = colors.secondaryLabel,
        )
        Spacer(modifier = Modifier.height(spacing.sm))
        Text(
            text = "iperf3 -s",
            style = VoidLinkTheme.footnote.copy(fontFamily = FontFamily.Monospace),
            color = colors.label,
            modifier = Modifier
                .clip(RoundedCornerShape(VoidLinkShapeTokens.ButtonRadius))
                .background(colors.fill)
                .padding(horizontal = spacing.md, vertical = spacing.sm),
        )
    }
}

@Composable
private fun ThroughputFailureBody(
    failure: ThroughputFailure?,
    detail: String?,
    port: Int,
) {
    val colors = VoidLinkTheme.colors
    val spacing = VoidLinkTheme.spacing
    val headline = when (failure) {
        // The common case by a wide margin, and it has a one-line fix — so say the fix rather than
        // reporting a connection error the user has to interpret.
        ThroughputFailure.SERVER_NOT_RUNNING ->
            "iperf3 isn't running on this PC. Nothing answered on port $port."
        ThroughputFailure.SERVER_BUSY ->
            "The iperf3 server is already busy with another test. Wait for it to finish."
        ThroughputFailure.UNREACHABLE ->
            "Couldn't reach this PC at all. Check it is awake and on the same network."
        ThroughputFailure.PROTOCOL_MISMATCH ->
            "Something answered on port $port, but it isn't iperf3."
        ThroughputFailure.SERVER_ERROR ->
            "The iperf3 server reported an error."
        ThroughputFailure.TIMED_OUT ->
            "The test stalled before it finished."
        ThroughputFailure.CANCELLED ->
            "Test cancelled."
        null -> "The test did not produce a result."
    }

    Column {
        Text(text = headline, style = VoidLinkTheme.body, color = colors.destructive)
        if (failure == ThroughputFailure.SERVER_NOT_RUNNING) {
            Spacer(modifier = Modifier.height(spacing.sm))
            IperfHint()
        }
        if (!detail.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(spacing.xs))
            Text(text = detail, style = VoidLinkTheme.footnote, color = colors.tertiaryLabel)
        }
    }
}

// ---------------------------------------------------------------------------------------------
// The point of the whole screen
// ---------------------------------------------------------------------------------------------

@Composable
private fun RecommendationSection(advice: BitrateAdvice) {
    val colors = VoidLinkTheme.colors
    val spacing = VoidLinkTheme.spacing

    SectionTitle(
        title = "Recommendation",
        trailing = {
            Text(
                text = advice.limitedBy.label,
                style = VoidLinkTheme.footnote,
                color = colors.secondaryLabel,
                maxLines = 1,
            )
        },
    )
    Spacer(modifier = Modifier.height(spacing.sm))
    Text(
        text = SettingsFormat.bitrate(advice.recommendedKbps),
        style = VoidLinkTheme.largeTitle,
        color = colors.accent,
    )
    Text(
        text = advice.headline,
        style = VoidLinkTheme.body,
        color = colors.label,
        modifier = Modifier.padding(top = spacing.xs),
    )
    if (!advice.confident) {
        Spacer(modifier = Modifier.height(spacing.sm))
        Text(
            text = "No throughput has been measured, so this rests on the codec's appetite alone.",
            style = VoidLinkTheme.footnote.copy(fontWeight = FontWeight.Medium),
            color = colors.offline,
        )
    }
    Spacer(modifier = Modifier.height(spacing.md))
    advice.reasons.forEach { reason ->
        Row(modifier = Modifier.padding(bottom = spacing.sm)) {
            Text(text = "•", style = VoidLinkTheme.footnote, color = colors.tertiaryLabel)
            Spacer(modifier = Modifier.width(spacing.sm))
            Text(text = reason, style = VoidLinkTheme.footnote, color = colors.secondaryLabel)
        }
    }
}

@Composable
private fun TestFooter(
    advice: BitrateAdvice,
    applyScopeLabel: String,
    busy: Boolean,
    onApply: () -> Unit,
    onClose: () -> Unit,
) {
    val colors = VoidLinkTheme.colors
    val spacing = VoidLinkTheme.spacing
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.xl, vertical = spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Applies to $applyScopeLabel",
            style = VoidLinkTheme.footnote,
            color = colors.secondaryLabel,
            modifier = Modifier.weight(1f),
        )
        InlineButton(label = "Close", prominent = false, onClick = onClose)
        Spacer(modifier = Modifier.width(spacing.sm))
        InlineButton(
            label = "Apply ${SettingsFormat.bitrate(advice.recommendedKbps)}",
            prominent = true,
            enabled = !busy,
            onClick = onApply,
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Small parts
// ---------------------------------------------------------------------------------------------

@Composable
private fun SectionTitle(title: String, trailing: @Composable () -> Unit) {
    val colors = VoidLinkTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = title,
            style = VoidLinkTheme.body.copy(fontWeight = FontWeight.SemiBold),
            color = colors.label,
        )
        trailing()
    }
}

/** Label on the left, the figure in accent blue on the right — the app's settings-row convention. */
@Composable
private fun MetricRow(label: String, value: String) {
    val colors = VoidLinkTheme.colors
    val spacing = VoidLinkTheme.spacing
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = VoidLinkTheme.body,
            color = colors.label,
            modifier = Modifier.weight(1f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = value,
            style = VoidLinkTheme.body.copy(fontWeight = FontWeight.SemiBold),
            color = colors.accent,
            maxLines = 1,
        )
    }
}

/** The tinted text button the rest of the app uses; no Material ripple anywhere. */
@Composable
private fun InlineButton(
    label: String,
    prominent: Boolean,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    val colors = VoidLinkTheme.colors
    val spacing = VoidLinkTheme.spacing
    Text(
        text = label,
        style = VoidLinkTheme.body.copy(fontWeight = FontWeight.SemiBold),
        color = if (prominent) colors.accent else colors.secondaryLabel,
        maxLines = 1,
        modifier = Modifier
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .clip(RoundedCornerShape(VoidLinkShapeTokens.ButtonRadius))
            .background(if (prominent) colors.accentFill else Color.Transparent)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = spacing.lg, vertical = spacing.sm),
    )
}

private fun millis(value: Double): String = String.format(Locale.US, "%.1f ms", value)

private fun percent(value: Double): String = String.format(Locale.US, "%.1f%%", value)

private fun mbps(value: Double): String = String.format(Locale.US, "%.1f Mbps", value)

private val MAX_CARD_WIDTH = 560.dp
private val PORT_FIELD_WIDTH = 140.dp
private val CARD_ELEVATION = 12.dp
private const val DISABLED_ALPHA = 0.4f
private const val PILL_FILL_ALPHA = 0.15f
private const val MAX_PORT_DIGITS = 5
private const val KBPS_PER_MBPS = 1_000.0
private const val MB = 1_000_000.0

// ---------------------------------------------------------------------------------------------
// Previews
// ---------------------------------------------------------------------------------------------

private val PreviewLink = LinkQuality(
    requested = 20,
    succeeded = 20,
    minMs = 6.0,
    medianMs = 9.0,
    p95Ms = 21.0,
    jitterMs = 3.4,
    lossPercent = 0.0,
    driftMs = 1.0,
)

@Preview(name = "Connection test", widthDp = 640, heightDp = 900)
@Composable
private fun ConnectionTestPreview() {
    VoidLinkTheme(darkTheme = false) {
        ConnectionTestPreviewContent(
            ConnectionTestUiState(
                hostName = "BATTLESTATION",
                linkPhase = LinkPhase.DONE,
                link = PreviewLink,
                throughputPhase = ThroughputPhase.DONE,
                throughput = ThroughputEvidence.Loaded(
                    targetMbps = 37.5,
                    receivedMbps = 37.2,
                    lossPercent = 0.02,
                    jitterMs = 1.4,
                    packets = 33_000,
                ),
            ),
        )
    }
}

@Preview(name = "Connection test — dark", widthDp = 640, heightDp = 900)
@Composable
private fun ConnectionTestDarkPreview() {
    VoidLinkTheme(darkTheme = true) {
        ConnectionTestPreviewContent(
            ConnectionTestUiState(
                hostName = "BATTLESTATION",
                linkPhase = LinkPhase.DONE,
                link = PreviewLink,
                throughputPhase = ThroughputPhase.DONE,
                throughput = ThroughputEvidence.Sustained(
                    megabitsPerSecond = 118.0,
                    bytes = 147_500_000L,
                    seconds = 10.0,
                ),
                mode = ThroughputMode.SUSTAINED_TCP,
            ),
        )
    }
}

/** The state a user sees first: link measured, iperf3 never started. */
@Preview(name = "Connection test — no iperf3", widthDp = 640, heightDp = 900)
@Composable
private fun ConnectionTestNoServerPreview() {
    VoidLinkTheme(darkTheme = false) {
        ConnectionTestPreviewContent(
            ConnectionTestUiState(
                hostName = "Living Room PC",
                linkPhase = LinkPhase.DONE,
                link = PreviewLink.copy(jitterMs = 18.0, lossPercent = 5.0, driftMs = 22.0),
                throughputPhase = ThroughputPhase.FAILED,
                throughputFailure = ThroughputFailure.SERVER_NOT_RUNNING,
                throughputDetail = "Nothing is listening on 192.168.1.31:5201.",
            ),
        )
    }
}

@Composable
private fun ConnectionTestPreviewContent(state: ConnectionTestUiState) {
    ConnectionTestScreen(
        state = state,
        settings = StreamSettings(),
        applyScopeLabel = "all PCs",
        onRetryLink = {},
        onSelectMode = {},
        onSetPort = {},
        onRunThroughput = {},
        onCancel = {},
        onApply = {},
        onClose = {},
    )
}
