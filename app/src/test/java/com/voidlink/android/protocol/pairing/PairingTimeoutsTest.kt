package com.voidlink.android.protocol.pairing

import com.voidlink.android.protocol.ProtocolConstants
import com.voidlink.android.protocol.http.TlsProbe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the wait budget of the pairing handshake.
 *
 * Two real failures shaped these numbers, in opposite directions. First, one shared 10-second read
 * timeout made a `pairchallenge` give up on a host that was merely slow. Then a long phase-5 timeout
 * plus retries made the dialog sit apparently frozen for over a minute against a host whose HTTPS
 * service was not answering at all — and a user who cancels a frozen dialog used to lose a pairing
 * that had already succeeded.
 *
 * The settled design: phases before the point of no return get room to breathe; everything after it
 * is short, because by then the pairing is safe and we are only confirming it.
 */
class PairingTimeoutsTest {

    @Test
    fun `phase 1 waits forever, because it blocks on a human typing a PIN`() {
        // 0 means "no read timeout" to HttpURLConnection; cancellation is what ends phase 1.
        assertEquals(0, ProtocolConstants.PAIRING_PHASE1_READ_TIMEOUT_MS)
    }

    @Test
    fun `phase 4 outlasts phases 2 and 3, because the host rewrites its client list`() {
        assertTrue(
            ProtocolConstants.PAIRING_PHASE4_READ_TIMEOUT_MS >=
                ProtocolConstants.PAIRING_PHASE3_READ_TIMEOUT_MS,
        )
        assertTrue(
            ProtocolConstants.PAIRING_PHASE4_READ_TIMEOUT_MS >=
                ProtocolConstants.PAIRING_PHASE2_READ_TIMEOUT_MS,
        )
    }

    @Test
    fun `phase 5 is deliberately short, because we can answer its question another way`() {
        // Counter-intuitive on purpose. Waiting longer on pairchallenge buys nothing: the pairing is
        // already safe, and a host that is going to go quiet on this leg goes quiet for the whole
        // timeout. Anything longer only makes the dialog look frozen.
        assertTrue(
            "phase 5 must not out-wait the phase that actually commits the pairing",
            ProtocolConstants.PAIRING_PHASE5_READ_TIMEOUT_MS <
                ProtocolConstants.PAIRING_PHASE4_READ_TIMEOUT_MS,
        )
        assertTrue(ProtocolConstants.PAIRING_PHASE5_READ_TIMEOUT_MS > 0)
        assertTrue(ProtocolConstants.PAIRING_PHASE5_CONNECT_TIMEOUT_MS > 0)
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
    fun `one hard deadline covers everything after the host has accepted us`() {
        // The per-step values are advisory; this is the number that decides how long a user waits
        // in front of a dialog they cannot help, so it is the one with a ceiling on it.
        assertTrue(
            "the verification budget must exceed a single phase-5 attempt or it can never retry",
            ProtocolConstants.PAIRING_VERIFY_BUDGET_MS >
                (
                    ProtocolConstants.PAIRING_PHASE5_CONNECT_TIMEOUT_MS +
                        ProtocolConstants.PAIRING_PHASE5_READ_TIMEOUT_MS
                    ).toLong(),
        )
        assertTrue(
            "verification must not be able to hang the dialog for minutes",
            ProtocolConstants.PAIRING_VERIFY_BUDGET_MS <= 30_000L,
        )
    }

    @Test
    fun `the transport self-test is bounded too, and finishes fast in the common failure`() {
        assertTrue(
            "self-test must not be able to hang the dialog for minutes",
            ProtocolConstants.PAIRING_DIAGNOSE_BUDGET_MS <= 30_000L,
        )
        // The reported failure — a port that accepts a connection and then says nothing — is
        // settled by the raw poke alone, so it must fit inside the budget several times over.
        val rawPoke = (TlsProbe.CONNECT_TIMEOUT_MS + TlsProbe.HANDSHAKE_TIMEOUT_MS).toLong()
        assertTrue(rawPoke * 2 < ProtocolConstants.PAIRING_DIAGNOSE_BUDGET_MS)
    }

    @Test
    fun `the whole post-acceptance wait stays inside a tolerable window`() {
        val worstCase = ProtocolConstants.PAIRING_VERIFY_BUDGET_MS +
            ProtocolConstants.PAIRING_DIAGNOSE_BUDGET_MS

        assertTrue("the user must never wait minutes", worstCase <= 60_000L)
    }
}
