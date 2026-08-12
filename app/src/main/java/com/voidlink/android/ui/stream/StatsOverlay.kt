package com.voidlink.android.ui.stream

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.voidlink.android.media.DecoderStats
import com.voidlink.android.media.VideoStreamFormat
import java.util.Locale

/**
 * The stats chip: two lines of monospaced text over a translucent black pill.
 *
 * UI spec §5.2 defines the shape (top-start, black at 55%, `radiusMd`, mono) and the behaviour:
 * it updates at 2 Hz rather than per frame, and **tapping it dismisses it**, which writes the
 * `showStatsOverlay` setting off — a dismissal the user expects to stick.
 *
 * The spec's line 2 also lists `rtt` and `loss`. Those come from the control and RTP layers, which
 * this build does not have yet; rather than print a plausible-looking zero, the chip shows the
 * numbers the decoder genuinely knows and says nothing about the ones it does not.
 *
 * @param stats the latest decode metrics.
 * @param format the negotiated stream format, for the resolution line.
 * @param decoderName the platform codec name — the single most useful field in a bug report from
 *   an unfamiliar device.
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
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        StatsLine(
            text = "${format.width}×${format.height}  " +
                "${oneDecimal(stats.renderedFps)} fps  " +
                "${oneDecimal(stats.bitrateMbps)} Mbps",
        )
        StatsLine(
            text = "decode ${oneDecimal(stats.averageDecodeTimeMs)} ms  " +
                "peak ${oneDecimal(stats.peakDecodeTimeMs)} ms  " +
                "dropped ${stats.framesDropped}",
        )
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

/** One decimal place, in a fixed locale so the chip does not switch separators between devices. */
private fun oneDecimal(value: Float): String {
    val safe = if (value.isNaN() || value.isInfinite()) 0f else value
    return String.format(Locale.US, "%.1f", safe)
}
