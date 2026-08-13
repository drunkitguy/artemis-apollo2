package com.limelight.reconnect;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Pure JVM tests for the reconnect offer: no Android, no Robolectric. */
public class ReconnectPromptPolicyTest {

    // ------------------------------------------------------------ the reduced bitrate

    @Test
    public void reductionIsAboutSixtyPercent() {
        // The worked example from the feature description: 20 Mbps -> "Reconnect at 12 Mbps?"
        assertEquals(12000, ReconnectPromptPolicy.reducedBitrateKbps(20000));

        assertEquals(6000, ReconnectPromptPolicy.reducedBitrateKbps(10000));
        assertEquals(24000, ReconnectPromptPolicy.reducedBitrateKbps(40000));
    }

    @Test
    public void reductionRoundsDownOntoASeekBarStep() {
        // 12000 * 0.6 = 7200, which is not a 500 kbps step, so it rounds down.
        assertEquals(7000, ReconnectPromptPolicy.reducedBitrateKbps(12000));

        // 3000 * 0.6 = 1800 -> 1500.
        assertEquals(1500, ReconnectPromptPolicy.reducedBitrateKbps(3000));

        for (int current = 500; current <= 300000; current += 500) {
            int reduced = ReconnectPromptPolicy.reducedBitrateKbps(current);
            String label = current + " -> " + reduced;
            assertEquals("must land on a seek bar step: " + label,
                    0, reduced % ReconnectPromptPolicy.ROUNDING_KBPS);
            assertTrue("must never go below the seek bar minimum: " + label,
                    reduced >= ReconnectPromptPolicy.MIN_BITRATE_KBPS);
            assertTrue("must never go up: " + label, reduced <= current);
        }
    }

    @Test
    public void reductionClampsAtTheSeekBarMinimum() {
        assertEquals(ReconnectPromptPolicy.MIN_BITRATE_KBPS,
                ReconnectPromptPolicy.reducedBitrateKbps(ReconnectPromptPolicy.MIN_BITRATE_KBPS));
        assertEquals(ReconnectPromptPolicy.MIN_BITRATE_KBPS,
                ReconnectPromptPolicy.reducedBitrateKbps(0));
        assertEquals(ReconnectPromptPolicy.MIN_BITRATE_KBPS,
                ReconnectPromptPolicy.reducedBitrateKbps(-1));
    }

    @Test
    public void aReductionIsOnlyWorthwhileWhenThereIsSomethingToGiveUp() {
        assertTrue(ReconnectPromptPolicy.isReductionWorthwhile(20000));
        assertTrue(ReconnectPromptPolicy.isReductionWorthwhile(
                ReconnectPromptPolicy.MIN_OFFERABLE_BITRATE_KBPS));

        // Already down at a couple of Mbps: cutting further will not fix anything.
        assertFalse(ReconnectPromptPolicy.isReductionWorthwhile(2500));
        assertFalse(ReconnectPromptPolicy.isReductionWorthwhile(500));
        assertFalse(ReconnectPromptPolicy.isReductionWorthwhile(0));
    }

    @Test
    public void anyWorthwhileReductionSavesAtLeastTheMinimum() {
        for (int current = 0; current <= 300000; current += 500) {
            if (ReconnectPromptPolicy.isReductionWorthwhile(current)) {
                int saving = current - ReconnectPromptPolicy.reducedBitrateKbps(current);
                assertTrue("saving too small at " + current,
                        saving >= ReconnectPromptPolicy.MIN_SAVING_KBPS);
            }
        }
    }

    // -------------------------------------------------------------- the corroboration

    @Test
    public void lossOverTheWindowCorroboratesDegradation() {
        // 1% loss over a healthy number of frames.
        assertTrue(ReconnectPromptPolicy.degradationCorroborated(297, 3));

        // A clean window: the host said "poor" but the renderer disagrees, so no prompt.
        assertFalse(ReconnectPromptPolicy.degradationCorroborated(300, 0));
        assertFalse(ReconnectPromptPolicy.degradationCorroborated(100000, 1));
    }

    @Test
    public void aStalledWindowCorroboratesWithoutNeedingLoss() {
        assertTrue(ReconnectPromptPolicy.degradationCorroborated(0, 0));
        assertTrue(ReconnectPromptPolicy.degradationCorroborated(5, 0));
    }

    @Test
    public void negativeCountersAreTreatedAsZero() {
        // Counters are read without locking, so a difference can come back slightly negative.
        assertTrue(ReconnectPromptPolicy.degradationCorroborated(-10, -10));
        assertFalse(ReconnectPromptPolicy.degradationCorroborated(500, -10));
    }

    // ----------------------------------------------------------------- the state machine

    private static ReconnectPromptPolicy started(long atMs) {
        ReconnectPromptPolicy policy = new ReconnectPromptPolicy();
        policy.sessionStarted(atMs);
        return policy;
    }

    @Test
    public void nothingIsOfferedBeforeTheSessionHasStarted() {
        ReconnectPromptPolicy policy = new ReconnectPromptPolicy();
        assertFalse(policy.connectionPoor(0));
        assertFalse(policy.shouldOffer(60000));
    }

    @Test
    public void nothingIsOfferedDuringTheStartupGracePeriod() {
        ReconnectPromptPolicy policy = started(0);
        assertTrue(policy.connectionPoor(1000));

        // Poor for well over the sustained window, but the session is only 9s old.
        assertFalse(policy.shouldOffer(9000));

        // Past the grace period, still poor.
        assertTrue(policy.shouldOffer(ReconnectPromptPolicy.STARTUP_GRACE_MS));
    }

    @Test
    public void aBlipDoesNotProduceAnOffer() {
        ReconnectPromptPolicy policy = started(0);
        assertTrue(policy.connectionPoor(20000));

        // One second later it recovers.
        policy.connectionOkay(21000);
        assertFalse(policy.isPoor());
        assertFalse(policy.shouldOffer(30000));
    }

    @Test
    public void sustainedDegradationProducesAnOffer() {
        ReconnectPromptPolicy policy = started(0);
        assertTrue(policy.connectionPoor(20000));

        assertFalse("not yet sustained", policy.shouldOffer(24000));
        assertTrue(policy.shouldOffer(20000 + ReconnectPromptPolicy.SUSTAINED_POOR_MS));
    }

    @Test
    public void theOfferIsMadeAtMostOncePerSession() {
        ReconnectPromptPolicy policy = started(0);
        policy.connectionPoor(20000);
        assertTrue(policy.shouldOffer(26000));

        policy.offerMade();

        assertTrue(policy.isOfferSpent());
        assertFalse(policy.shouldOffer(26000));

        // A later episode -- dismissed, accepted or ignored, it makes no difference.
        policy.connectionOkay(30000);
        assertFalse("must not re-arm", policy.connectionPoor(40000));
        assertFalse(policy.shouldOffer(60000));
    }

    @Test
    public void disablingTheFeatureSuppressesEverything() {
        ReconnectPromptPolicy policy = started(0);
        policy.setEnabled(false);

        assertFalse(policy.connectionPoor(20000));
        assertFalse(policy.shouldOffer(60000));
    }

    @Test
    public void onlyTheLeadingEdgeOfAnEpisodeArmsTheCheck() {
        ReconnectPromptPolicy policy = started(0);

        assertTrue("first poor report arms the check", policy.connectionPoor(20000));
        assertFalse("repeats must not re-arm", policy.connectionPoor(21000));
        assertFalse(policy.connectionPoor(22000));

        // The episode is still timed from the first report, not the last.
        assertTrue(policy.shouldOffer(25000));
    }

    @Test
    public void recoveryRestartsTheClock() {
        ReconnectPromptPolicy policy = started(0);
        policy.connectionPoor(20000);
        policy.connectionOkay(22000);

        assertTrue("a new episode arms again", policy.connectionPoor(30000));
        assertFalse(policy.shouldOffer(33000));
        assertTrue(policy.shouldOffer(35000));
    }

    @Test
    public void theEvaluationDelayWaitsForWhicheverGuardIsLater() {
        ReconnectPromptPolicy policy = started(0);
        policy.connectionPoor(1000);

        // The startup grace period is what we are waiting on here, not the poor window.
        assertEquals(ReconnectPromptPolicy.STARTUP_GRACE_MS - 1000, policy.evaluationDelayMs(1000));

        ReconnectPromptPolicy later = started(0);
        later.connectionPoor(30000);
        assertEquals(ReconnectPromptPolicy.SUSTAINED_POOR_MS, later.evaluationDelayMs(30000));
    }

    @Test
    public void theEvaluationDelayIsNeverNegative() {
        ReconnectPromptPolicy policy = started(0);
        policy.connectionPoor(20000);
        assertEquals(0, policy.evaluationDelayMs(600000));
    }

    @Test
    public void whenTheDelayExpiresTheOfferIsDue() {
        // The delay the policy asks for must be exactly long enough: no early wake-ups
        // that would silently drop the offer, and no waiting longer than necessary.
        long[][] cases = { {0, 1000}, {0, 12000}, {0, 20000}, {5000, 5001}, {0, 15000} };
        for (long[] c : cases) {
            ReconnectPromptPolicy policy = started(c[0]);
            long poorAt = c[1];
            policy.connectionPoor(poorAt);
            long due = poorAt + policy.evaluationDelayMs(poorAt);
            String label = "session " + c[0] + ", poor at " + poorAt;
            assertTrue("offer must be due when the delay expires: " + label, policy.shouldOffer(due));
            if (due > poorAt) {
                assertFalse("offer must not be due before that: " + label, policy.shouldOffer(due - 1));
            }
        }
    }
}
