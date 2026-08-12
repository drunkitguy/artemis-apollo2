package com.voidlink.android.ui.hosts

import com.voidlink.android.data.HostReachability
import com.voidlink.android.data.HostStatus
import com.voidlink.android.data.KnownHost
import com.voidlink.android.protocol.pairing.PairResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the decision table behind a host card: which badge it wears and which action its footer
 * button offers.
 *
 * This is the piece of UI logic most likely to be got subtly wrong — a host that forgot us still
 * reports itself as unpaired, and an offline host must never be offered "Connect" — so it is pulled
 * out into a pure value type and tested here rather than being read off a screenshot.
 */
class HostCardStateTest {

    private fun host(paired: Boolean = false, mac: String? = null) = KnownHost(
        uuid = "uuid-1",
        name = "BATTLESTATION",
        addresses = listOf("192.168.1.24"),
        macAddress = mac,
        paired = paired,
    )

    private val online = HostStatus(reachability = HostReachability.ONLINE, paired = true)
    private val onlineButForgotUs = HostStatus(reachability = HostReachability.ONLINE, paired = false)

    @Test
    fun `an online paired host offers Connect`() {
        val card = HostCardState(host = host(paired = true), status = online)

        assertTrue(card.isOnline)
        assertFalse(card.needsPairing)
        assertEquals(HostAction.CONNECT, card.primaryAction)
    }

    @Test
    fun `an online host we have never paired with offers pairing`() {
        val card = HostCardState(host = host(paired = false), status = onlineButForgotUs)

        assertTrue(card.needsPairing)
        assertEquals(HostAction.PAIR, card.primaryAction)
    }

    @Test
    fun `a host that forgot this client is offered pairing again even though we stored paired`() {
        val card = HostCardState(host = host(paired = true), status = onlineButForgotUs)

        assertTrue(card.needsPairing)
        assertEquals(HostAction.PAIR, card.primaryAction)
    }

    @Test
    fun `an offline host offers wake regardless of pairing`() {
        assertEquals(
            HostAction.WAKE,
            HostCardState(host = host(paired = true), status = HostStatus.Offline).primaryAction,
        )
        assertEquals(
            HostAction.WAKE,
            HostCardState(host = host(paired = false), status = HostStatus.Offline).primaryAction,
        )
    }

    @Test
    fun `a host that has never been probed is checking, not offline`() {
        // The cold-start case: claiming a PC is offline before anyone has asked it makes a working
        // app look broken for as long as the first probe takes.
        val card = HostCardState(host = host(paired = true))

        assertFalse(card.isOnline)
        assertTrue(card.isChecking)
        assertEquals(HostAction.CHECKING, card.primaryAction)
        assertFalse(card.isActionable)
    }

    @Test
    fun `a probed host is no longer checking, whichever way the probe went`() {
        assertFalse(HostCardState(host = host(), status = HostStatus.Offline).isChecking)
        assertFalse(HostCardState(host = host(paired = true), status = online).isChecking)
    }

    @Test
    fun `wake is only actionable when the host has a MAC on record`() {
        val withMac = HostCardState(host = host(mac = "aa:bb:cc:dd:ee:ff"), status = HostStatus.Offline)
        val withoutMac = HostCardState(host = host(mac = null), status = HostStatus.Offline)

        assertEquals(HostAction.WAKE, withMac.primaryAction)
        assertEquals(HostAction.WAKE, withoutMac.primaryAction)
        assertTrue(withMac.isActionable)
        // Still shown, still explains itself when tapped — just not a button that pretends to work.
        assertFalse(withoutMac.isActionable)
    }

    @Test
    fun `pairing and connecting are always actionable`() {
        assertTrue(HostCardState(host = host(paired = false), status = onlineButForgotUs).isActionable)
        assertTrue(HostCardState(host = host(paired = true), status = online).isActionable)
    }

    @Test
    fun `the running app name is only reported for an online host`() {
        val running = HostStatus(
            reachability = HostReachability.ONLINE,
            paired = true,
            runningAppId = "42",
            runningAppName = "Hades II",
        )

        assertEquals(
            "Hades II",
            HostCardState(host = host(paired = true), status = running).runningAppName,
        )
        // A stale name from the last sighting must not be shown next to an offline card.
        assertNull(
            HostCardState(
                host = host(paired = true),
                status = running.copy(reachability = HostReachability.OFFLINE),
            ).runningAppName,
        )
        assertNull(HostCardState(host = host(paired = true), status = online).runningAppName)
    }

    @Test
    fun `a blank running app name is treated as no app`() {
        val blank = HostStatus(
            reachability = HostReachability.ONLINE,
            paired = true,
            runningAppName = "   ",
        )

        assertNull(HostCardState(host = host(paired = true), status = blank).runningAppName)
    }

    @Test
    fun `an offline unpaired host still shows the not-paired badge`() {
        // The padlock reflects the stored pairing state, not reachability: a sleeping PC we have
        // never paired with must not look ready to stream.
        val card = HostCardState(host = host(paired = false), status = HostStatus.Offline)

        assertTrue(card.needsPairing)
    }

    @Test
    fun `the stored record knows whether a wake packet can be addressed`() {
        assertFalse(host(mac = null).canWakeOnLan)
        assertTrue(host(mac = "aa:bb:cc:dd:ee:ff").canWakeOnLan)
    }

    @Test
    fun `the empty grid is reported as empty`() {
        assertTrue(HostsUiState().isEmpty)
        assertFalse(HostsUiState(hosts = listOf(HostCardState(host = host()))).isEmpty)
    }

    @Test
    fun `pairing waits for the engine to produce the PIN before showing anything to type`() {
        // The dialog opens before the handshake has generated a PIN; showing an empty PIN box in
        // that gap would invite the user to type nothing into their PC.
        val opening = PairingUiState(host = host())

        assertFalse(opening.isAwaitingPin)
        assertFalse(opening.isFinished)
    }

    @Test
    fun `the PIN stays on screen for the whole of the blocking first phase`() {
        val showing = PairingUiState(host = host(), pin = "0420", phase = 1)

        assertTrue(showing.isAwaitingPin)
        assertFalse(showing.isFinished)
    }

    @Test
    fun `once the handshake moves past phase one the dialog stops asking for the PIN`() {
        val working = PairingUiState(host = host(), pin = "0420", phase = 3)

        assertFalse(working.isAwaitingPin)
        assertFalse(working.isFinished)
    }

    @Test
    fun `a terminal outcome finishes the dialog whichever way it went`() {
        PairingOutcome.entries.forEach { outcome ->
            val state = PairingUiState(host = host(), pin = "0420", phase = 5, outcome = outcome)

            assertTrue(state.isFinished)
            // A finished attempt never re-offers the PIN, not even a cancelled one.
            assertFalse(state.isAwaitingPin)
        }
    }

    @Test
    fun `every protocol pairing result maps to a distinct UI outcome`() {
        // The mapping itself is an exhaustive `when`, so the compiler catches a new protocol
        // result. This pins the other direction: no UI outcome without a protocol counterpart.
        assertEquals(PairResult.entries.size, PairingOutcome.entries.size)
        PairResult.entries.forEach { result ->
            assertTrue(
                "no UI outcome named after $result",
                PairingOutcome.entries.any { it.name == result.name },
            )
        }
    }
}
