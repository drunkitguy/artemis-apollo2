package com.voidlink.android.ui.stream

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.voidlink.android.ui.navigation.StreamLaunchContract
import com.voidlink.android.ui.theme.VoidLinkTheme

/**
 * Placeholder for the streaming session.
 *
 * The streaming pipeline — RTSP negotiation, the ENet control channel, RTP receive, forward error
 * correction, `MediaCodec` decode, audio playback and input forwarding — is not implemented in
 * this build. Until it is, this screen exists to say so.
 *
 * That matters more than it looks: without it this activity showed a black fullscreen window with
 * no text and no affordance, which is indistinguishable from a hang or a crash. An honest screen
 * is better than a blank one.
 */
class StreamActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val appName = intent?.getStringExtra(StreamLaunchContract.EXTRA_APP_NAME)

        setContent {
            VoidLinkTheme {
                StreamUnavailableScreen(
                    appName = appName,
                    onClose = { finish() },
                )
            }
        }
    }
}

/**
 * Explains that streaming is not available, naming the app the user tried to launch so the screen
 * is clearly a response to their action rather than a generic error.
 */
@Composable
private fun StreamUnavailableScreen(
    appName: String?,
    onClose: () -> Unit,
) {
    val colors = VoidLinkTheme.colors

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 420.dp)
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = appName ?: "Streaming",
                color = colors.label,
                fontSize = 22.sp,
                textAlign = TextAlign.Center,
            )
            Text(
                text = "Streaming is not implemented in this build. Pairing, your app library " +
                    "and host controls all work, but there is no video pipeline yet, so there " +
                    "is nothing to show here.",
                color = colors.secondaryLabel,
                fontSize = 15.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 12.dp),
            )
            Button(
                onClick = onClose,
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.accent,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 28.dp),
            ) {
                Text(text = "Back to library", fontSize = 17.sp)
            }
        }
    }
}
