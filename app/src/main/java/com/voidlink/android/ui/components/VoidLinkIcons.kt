package com.voidlink.android.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Every glyph the app uses, named for what it means rather than what it looks like.
 *
 * Keeping the mapping in one place means a change of visual metaphor is a one-line edit and the
 * screens stay readable ("VoidLinkIcons.Online" rather than "Icons.Filled.Wifi").
 */
object VoidLinkIcons {
    /** A host PC. */
    val Host: ImageVector = Icons.Filled.DesktopWindows

    /** A reachable host. */
    val Online: ImageVector = Icons.Filled.Wifi

    /** An unreachable host. */
    val Offline: ImageVector = Icons.Filled.Warning

    /** A host that still needs pairing. */
    val Locked: ImageVector = Icons.Filled.Lock

    /** The pairing action. */
    val Unlocked: ImageVector = Icons.Filled.LockOpen

    /** Wake-on-LAN. */
    val Power: ImageVector = Icons.Filled.PowerSettingsNew

    /** Start streaming. */
    val Connect: ImageVector = Icons.Filled.PlayArrow

    /** Quit the running app. */
    val Quit: ImageVector = Icons.Filled.Stop

    /** Add a host manually. */
    val Add: ImageVector = Icons.Filled.Add

    /** Re-run discovery. */
    val Refresh: ImageVector = Icons.Filled.Refresh

    /** Show or hide the settings sidebar. */
    val Sidebar: ImageVector = Icons.Filled.Menu

    /** Overflow menu. */
    val Overflow: ImageVector = Icons.Filled.MoreVert

    /** Per-host settings. */
    val Settings: ImageVector = Icons.Filled.Settings

    /** Rename. */
    val Rename: ImageVector = Icons.Filled.Edit

    /** Delete / forget. */
    val Delete: ImageVector = Icons.Filled.Delete

    /** Dismiss. */
    val Close: ImageVector = Icons.Filled.Close

    /** Return to the previous screen — a chevron, matching the reference rather than Material. */
    val Back: ImageVector = Icons.Filled.KeyboardArrowLeft

    /** External display options. */
    val Display: ImageVector = Icons.Filled.Tv

    /** Video settings section. */
    val Video: ImageVector = Icons.Filled.Videocam

    /** Touch & controller settings section. */
    val Touch: ImageVector = Icons.Filled.TouchApp

    /** Gesture settings section. */
    val Gestures: ImageVector = Icons.Filled.Gesture

    /** Peripheral settings section. */
    val Peripherals: ImageVector = Icons.Filled.Keyboard

    /** Audio settings section. */
    val Audio: ImageVector = Icons.Filled.VolumeUp

    /** Starred settings rows. */
    val Favorite: ImageVector = Icons.Filled.Star
}
