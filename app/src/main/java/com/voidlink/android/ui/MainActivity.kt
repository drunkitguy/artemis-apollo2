package com.voidlink.android.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.voidlink.android.ui.navigation.StreamLaunchContract
import com.voidlink.android.ui.navigation.VoidLinkApp
import com.voidlink.android.ui.theme.VoidLinkTheme

/**
 * The app's only non-streaming activity.
 *
 * It does nothing but host the Compose navigation graph inside [VoidLinkTheme] and translate a
 * "stream this app" intent from the UI into an actual activity launch, which keeps the streaming
 * session — a very different beast, with its own lifecycle and window flags — cleanly separated.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            VoidLinkTheme {
                VoidLinkApp(
                    onLaunchStream = { hostId, app ->
                        startActivity(
                            StreamLaunchContract.intent(
                                context = this,
                                hostId = hostId,
                                appId = app.id,
                                appName = app.name,
                            ),
                        )
                    },
                )
            }
        }
    }
}
