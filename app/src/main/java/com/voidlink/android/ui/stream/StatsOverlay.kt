package com.voidlink.android.ui.stream

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.voidlink.android.media.DecoderStats
import com.voidlink.android.media.VideoStreamFormat
import java.util.Locale

/**
 * The stats chip: decode time first and large, then the rest.
 *
 * UI spec §5.2 defines the shape (top-start, black at 55%, `radiusMd`, mono), the cadence (2 Hz,
 * not per frame) and the behaviour — **tapping it dismisses it**, which writes the
 * `showStatsOverlay` setting off, a dismissal the user expects to stick.
 *
 * Decode time gets the top line and the largest type because it is the acceptance criterion for
 * a codec choice. The honest way to decide between AV1 and HEVC on a given device is to measure
 * decode latency, not to reason about it, so the chip shows the measurement against the frame
 * budget (16.6 ms at 60 fps) and colours it: green well inside budget, amber approaching it, red
 * over. A decode time creeping towards the budget is a device that cannot keep up, and it is
 * indistinguishable from a network problem unless the number is on screen.
 *
 * The spec's line 2 also lists `rtt` and `loss`. Those come from the control and RTP layers, which
 * this build does not have yet; rather than print a plausible-looking zero, the chip shows the
 * numbers the decoder genuinely knows and says nothing about the ones it does not.
 *
 * @param stats the latest decode metrics.
 * @param format the negotiated stream format, for the resolution and frame-budget lines.
 * @param decoderName the platform codec name — the single most useful field in a bug report from
 *   an unfamiliar device, and the way to tell a hardware decoder from a software one at a glance.
 * @param onDismiss called when the chip is tapped.
 * @param modifier layout modifier, typically carrying the safe-area inset.
 */
@Composable
fun StatsOverlay(
    stats: DecoderStats,
    format: VideoStreamFormat,
    decoderName: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val budgetMs = frameBudgetMs(format.frameRate)

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable(
                interactionSource = interactionSource,
                // UI spec §6: no Material ripples anywhere.
                indication = null,
                onClick = onDismiss,
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = "decode ",
                color = Color.White.copy(alpha = 0.7f),
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
            )
            Text(
                text = "${oneDecimal(stats.averageDecodeTimeMs)} ms",
                color = budgetColor(stats.averageDecodeTimeMs, budgetMs),
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp,
            )
        }
        StatsLine(
            text = "peak ${oneDecimal(stats.peakDecodeTimeMs)} ms   " +
                "budget ${oneDecimal(budgetMs)} ms @ ${format.frameRate} fps",
        )
        StatsLine(
            text = "${format.width}×${format.height}  " +
                "${oneDecimal(stats.renderedFps)} fps  " +
                "${oneDecimal(stats.bitrateMbps)} Mbps",
        )
        StatsLine(text = "dropped ${stats.framesDropped}  decoded ${stats.framesDecoded}")
        StatsLine(text = "${format.codec.label} · $decoderName")
    }
}

@Composable
private fun StatsLine(text: String) {
    Text(
        text = text,
        color = Color.White,
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
    )
}

/** Milliseconds available per frame at [frameRate]; 16.6 ms at 60 fps. */
internal fun frameBudgetMs(frameRate: Int): Float =
    if (frameRate <= 0) 0f else 1000f / frameRate.toFloat()

/**
 * Green well inside the frame budget, amber approaching it, red over it.
 *
 * Half the budget is the "comfortable" line: decode is only one stage of the pipeline, so a codec
 * consuming most of a frame interval on its own leaves nothing for receive, reassembly and
 * composition.
 */
internal fun budgetColor(decodeTimeMs: Float, budgetMs: Float): Color = when {
    budgetMs <= 0f || decodeTimeMs <= 0f -> Color.White
    decodeTimeMs < budgetMs * 0.5f -> Color(0xFF30D158)
    decodeTimeMs < budgetMs -> Color(0xFFFFD60A)
    else -> Color(0xFFFF453A)
}

/** One decimal place, in a fixed locale so the chip does not switch separators between devices. */
private fun oneDecimal(value: Float): String {
    val safe = if (value.isNaN() || value.isInfinite()) 0f else value
    return String.format(Locale.US, "%.1f", safe)
}
