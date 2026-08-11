package com.voidlink.android.ui.hosts

import com.voidlink.android.data.HostReachability
import com.voidlink.android.data.HostStatus
import com.voidlink.android.data.KnownHost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `a host that has never been probed is not treated as reachable`() {
        val card = HostCardState(host = host(paired = true))

        assertFalse(card.isOnline)
        assertEquals(HostAction.WAKE, card.primaryAction)
    }

    @Test
    fun `an offline unpaired host still shows the not-paired badge`() {
        // The padlock reflects the stored pairing state, not reachability: a sleeping PC we have
        // never paired with must not look ready to stream.
        val card = HostCardState(host = host(paired = false), status = HostStatus.Offline)

        assertTrue(card.needsPairing)
    }

    @Test
    fun `wake is only actionable when a MAC is on record`() {
        assertFalse(host(mac = null).canWakeOnLan)
        assertTrue(host(mac = "aa:bb:cc:dd:ee:ff").canWakeOnLan)
    }

    @Test
    fun `the empty grid is reported as empty`() {
        assertTrue(HostsUiState().isEmpty)
        assertFalse(HostsUiState(hosts = listOf(HostCardState(host = host()))).isEmpty)
    }
}
