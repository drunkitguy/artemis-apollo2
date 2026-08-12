package com.voidlink.android.protocol.pairing

import com.voidlink.android.protocol.ProtocolConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the timeout budget of the pairing handshake.
 *
 * A real AYN Thor reported `Phase 5 (https pairchallenge): Read timed out` for a pairing the host
 * had already completed, because every phase shared one 10-second read timeout. Phase 5 is not like
 * the others: it opens the first TLS connection ever made to the host, and on `HttpURLConnection`
 * the read timeout covers the handshake too. These assertions exist so that budget cannot silently
 * collapse back to a single shared number.
 */
class PairingTimeoutsTest {

    @Test
    fun `phase 1 waits forever, because it blocks on a human typing a PIN`() {
        // 0 means "no read timeout" to HttpURLConnection; cancellation is what ends phase 1.
        assertEquals(0, ProtocolConstants.PAIRING_PHASE1_READ_TIMEOUT_MS)
    }

    @Test
    fun `phase 5 is given far longer than a plaintext phase`() {
        assertTrue(
            "phase 5 must out-wait the plaintext phases: it includes a TLS handshake",
            ProtocolConstants.PAIRING_PHASE5_READ_TIMEOUT_MS >
                ProtocolConstants.PAIRING_PHASE2_READ_TIMEOUT_MS * 2,
        )
        assertTrue(
            ProtocolConstants.PAIRING_PHASE5_READ_TIMEOUT_MS >
                ProtocolConstants.PAIRING_PHASE4_READ_TIMEOUT_MS,
        )
        assertTrue(
            ProtocolConstants.PAIRING_PHASE5_CONNECT_TIMEOUT_MS >=
                ProtocolConstants.PAIRING_CONNECT_TIMEOUT_MS,
        )
    }

    @Test
    fun `phase 4 outlasts phases 2 and 3, because the host rewrites its client list`() {
        assertTrue(
            ProtocolConstants.PAIRING_PHASE4_READ_TIMEOUT_MS >=
                ProtocolConstants.PAIRING_PHASE3_READ_TIMEOUT_MS,
        )
    }

    @Test
    fun `the confirmation fallback retries more than once and is bounded`() {
        // One attempt would make the fallback a coin toss on a host that is still reloading the
        // client database it rewrote during phase 4.
        assertTrue(ProtocolConstants.PAIRING_CONFIRM_ATTEMPTS >= 2)
        // And it must stay bounded: the dialog is on screen while this runs.
        assertTrue(ProtocolConstants.PAIRING_CONFIRM_ATTEMPTS <= 5)
        assertTrue(ProtocolConstants.PAIRING_CONFIRM_RETRY_DELAY_MS > 0L)
    }

    @Test
    fun `the whole phase-5 budget stays inside a tolerable wait`() {
        val worstCase = ProtocolConstants.PAIRING_PHASE5_CONNECT_TIMEOUT_MS +
            ProtocolConstants.PAIRING_PHASE5_READ_TIMEOUT_MS +
            ProtocolConstants.PAIRING_CONFIRM_ATTEMPTS *
            (ProtocolConstants.PAIRING_CONFIRM_TIMEOUT_MS * 2)

        assertTrue("phase 5 must not be able to hang the dialog for minutes", worstCase < 180_000)
    }
}
