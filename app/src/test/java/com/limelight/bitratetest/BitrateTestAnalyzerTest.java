package com.limelight.bitratetest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Pure JVM tests for the recommendation: no Android, no Robolectric. */
public class BitrateTestAnalyzerTest {

    private static final double FPS_60 = 60;
    private static final double FPS_120 = 120;

    /** 600 received frames over a 6 second window, shaped to the requested readings. */
    private static BitrateStepMeasurement step(int kbps, double lossPercent, double decodeMs, double hostMs) {
        long received = 600;
        long lost = Math.round(received * lossPercent / (100.0 - lossPercent));
        long decoderTimeMs = Math.round(decodeMs * received);
        long hostTenths = hostMs >= 0 ? Math.round(hostMs * 10 * received) : 0;
        long framesWithHost = hostMs >= 0 ? received : 0;
        long receivedBytes = (long) kbps * 750; // exactly kbps over 6000 ms

        return BitrateStepMeasurement.measured(kbps, received, lost, lost > 0 ? 1 : 0,
                decoderTimeMs, hostTenths, framesWithHost, receivedBytes, 6000);
    }

    // ------------------------------------------------------------------
    // The measurement itself
    // ------------------------------------------------------------------

    @Test
    public void measurementDerivesTheOverlayFigures() {
        BitrateStepMeasurement s = step(30000, 2.0, 7.5, 4.2);

        assertEquals(2.0, s.getFrameLossPercent(), 0.2);
        assertEquals(7.5, s.getAverageDecodeTimeMs(), 0.01);
        assertEquals(4.2, s.getAverageHostProcessingLatencyMs(), 0.01);
        assertEquals(30000, s.getReceivedKbps());
        assertTrue(s.hasVideo());
        assertFalse(s.isFailed());
    }

    @Test
    public void receivedBitrateIsMinusOneWhenUnavailable() {
        BitrateStepMeasurement s = BitrateStepMeasurement.measured(20000, 600, 0, 0,
                3000, 0, 0, -1, 6000);
        assertEquals(-1, s.getReceivedKbps());
        assertFalse(s.hasHostProcessingLatency());
        assertEquals(0.0, s.getAverageHostProcessingLatencyMs(), 0.0001);
    }

    @Test
    public void failedStepReportsItself() {
        BitrateStepMeasurement s = BitrateStepMeasurement.failed(50000, "No video received from host.");
        assertTrue(s.isFailed());
        assertFalse(s.hasVideo());
        assertEquals("No video received from host.", s.getFailureReason());
    }

    // ------------------------------------------------------------------
    // The frame budget
    // ------------------------------------------------------------------

    @Test
    public void frameBudgetFollowsTheFrameRate() {
        assertEquals(16.67, BitrateTestAnalyzer.frameBudgetMs(FPS_60), 0.01);
        assertEquals(8.33, BitrateTestAnalyzer.frameBudgetMs(FPS_120), 0.01);
        // A nonsense frame rate falls back to 60.
        assertEquals(16.67, BitrateTestAnalyzer.frameBudgetMs(0), 0.01);
    }

    @Test
    public void decodeTimeIsJudgedAgainstTheFrameRate() {
        BitrateStepMeasurement s = step(30000, 0, 6.0, 4.0);

        // 6 ms is comfortable inside a 16.6 ms frame but not inside an 8.3 ms one.
        assertTrue(BitrateTestAnalyzer.isClean(s, FPS_60));
        assertFalse(BitrateTestAnalyzer.isClean(s, FPS_120));
    }

    @Test
    public void aStepWithAlmostNoFramesIsNeverClean() {
        BitrateStepMeasurement s = BitrateStepMeasurement.measured(20000, 5, 0, 0, 10, 0, 0, 100, 6000);
        assertFalse(BitrateTestAnalyzer.isClean(s, FPS_60));
    }

    // ------------------------------------------------------------------
    // The verdict
    // ------------------------------------------------------------------

    @Test
    public void nothingBreaksMeansNoLimitAndTheTopRung() {
        List<BitrateStepMeasurement> steps = Arrays.asList(
                step(10000, 0, 4.0, 3.0),
                step(20000, 0, 4.5, 3.1),
                step(30000, 0, 5.0, 3.2),
                step(50000, 0, 5.5, 3.4));

        BitrateRecommendation r = BitrateTestAnalyzer.analyze(steps, FPS_60);

        assertEquals(LimitingFactor.NONE, r.getLimitingFactor());
        assertEquals(50000, r.getCleanCeilingKbps());
        assertEquals(0, r.getLimitingStepKbps());
        assertEquals(50000, r.getRecommendedKbps());
        assertTrue(r.isApplicable());
        assertTrue(r.getExplanation().length() > 0);
    }

    @Test
    public void lossNamesTheNetwork() {
        List<BitrateStepMeasurement> steps = Arrays.asList(
                step(10000, 0, 4.0, 3.0),
                step(20000, 0, 4.2, 3.0),
                step(30000, 0, 4.4, 3.1),
                step(50000, 3.0, 4.5, 3.2));

        BitrateRecommendation r = BitrateTestAnalyzer.analyze(steps, FPS_60);

        assertEquals(LimitingFactor.NETWORK, r.getLimitingFactor());
        assertEquals(30000, r.getCleanCeilingKbps());
        assertEquals(50000, r.getLimitingStepKbps());
        // 85% of 30 Mbps, landing on a 500 kbps seek bar step.
        assertEquals(25500, r.getRecommendedKbps());
        assertTrue(r.getExplanation().toLowerCase().contains("network"));
    }

    @Test
    public void decodeTimeNamesTheDecoder() {
        List<BitrateStepMeasurement> steps = Arrays.asList(
                step(10000, 0, 4.0, 3.0),
                step(20000, 0, 6.0, 3.0),
                step(30000, 0, 14.0, 3.1));

        BitrateRecommendation r = BitrateTestAnalyzer.analyze(steps, FPS_60);

        assertEquals(LimitingFactor.DECODER, r.getLimitingFactor());
        assertEquals(20000, r.getCleanCeilingKbps());
        assertEquals(30000, r.getLimitingStepKbps());
        assertEquals(17000, r.getRecommendedKbps());
        assertTrue(r.getExplanation().toLowerCase().contains("decoder"));
    }

    @Test
    public void hostLatencyNamesTheHost() {
        List<BitrateStepMeasurement> steps = Arrays.asList(
                step(10000, 0, 4.0, 3.0),
                step(20000, 0, 4.2, 4.0),
                step(30000, 0, 4.4, 22.0));

        BitrateRecommendation r = BitrateTestAnalyzer.analyze(steps, FPS_60);

        assertEquals(LimitingFactor.HOST, r.getLimitingFactor());
        assertEquals(20000, r.getCleanCeilingKbps());
        assertEquals(17000, r.getRecommendedKbps());
        assertTrue(r.getExplanation().toLowerCase().contains("host"));
    }

    @Test
    public void theWorstOffenderWins() {
        // Loss is 12x its threshold; decode is only marginally over.
        List<BitrateStepMeasurement> steps = Arrays.asList(
                step(10000, 0, 4.0, 3.0),
                step(20000, 3.0, 10.5, 3.0));

        BitrateRecommendation r = BitrateTestAnalyzer.analyze(steps, FPS_60);
        assertEquals(LimitingFactor.NETWORK, r.getLimitingFactor());
    }

    @Test
    public void aDeadSessionIsReportedAsSuch() {
        List<BitrateStepMeasurement> steps = Arrays.asList(
                step(10000, 0, 4.0, 3.0),
                step(20000, 0, 4.2, 3.0),
                BitrateStepMeasurement.failed(30000, "No video received from host."));

        BitrateRecommendation r = BitrateTestAnalyzer.analyze(steps, FPS_60);

        assertEquals(LimitingFactor.STREAM_FAILURE, r.getLimitingFactor());
        assertEquals(20000, r.getCleanCeilingKbps());
        assertEquals(30000, r.getLimitingStepKbps());
        assertEquals(17000, r.getRecommendedKbps());
        assertTrue(r.getExplanation().contains("No video received from host."));
    }

    @Test
    public void everythingDirtyFallsBackBelowTheLowestRung() {
        List<BitrateStepMeasurement> steps = Collections.singletonList(step(10000, 5.0, 4.0, 3.0));

        BitrateRecommendation r = BitrateTestAnalyzer.analyze(steps, FPS_60);

        assertEquals(LimitingFactor.NETWORK, r.getLimitingFactor());
        assertEquals(0, r.getCleanCeilingKbps());
        assertEquals(5000, r.getRecommendedKbps());
        assertTrue(r.isApplicable());
    }

    @Test
    public void noStepsMeansNoRecommendation() {
        BitrateRecommendation r = BitrateTestAnalyzer.analyze(new ArrayList<BitrateStepMeasurement>(), FPS_60);

        assertEquals(LimitingFactor.NO_DATA, r.getLimitingFactor());
        assertEquals(0, r.getRecommendedKbps());
        assertFalse(r.isApplicable());

        BitrateRecommendation nullResult = BitrateTestAnalyzer.analyze(null, FPS_60);
        assertEquals(LimitingFactor.NO_DATA, nullResult.getLimitingFactor());
    }

    @Test
    public void recommendationsAlwaysLandOnASeekBarStep() {
        List<BitrateStepMeasurement> steps = Arrays.asList(
                step(10000, 0, 4.0, 3.0),
                step(20000, 0, 4.2, 3.0),
                step(30000, 0, 4.4, 3.0),
                step(50000, 0, 4.6, 3.0),
                step(80000, 4.0, 4.8, 3.0));

        BitrateRecommendation r = BitrateTestAnalyzer.analyze(steps, FPS_60);

        assertEquals(0, r.getRecommendedKbps() % BitrateTestAnalyzer.ROUNDING_KBPS);
        assertTrue(r.getRecommendedKbps() < r.getCleanCeilingKbps());
    }

    @Test
    public void hostLatencyIsIgnoredWhenTheHostNeverReportedIt() {
        // hostMs < 0 means "the host sent no processing latency at all".
        List<BitrateStepMeasurement> steps = Arrays.asList(
                step(10000, 0, 4.0, -1),
                step(20000, 0, 4.2, -1));

        BitrateRecommendation r = BitrateTestAnalyzer.analyze(steps, FPS_60);
        assertEquals(LimitingFactor.NONE, r.getLimitingFactor());
        assertEquals(20000, r.getRecommendedKbps());
    }

    @Test
    public void mbpsReadsLikeAPersonWouldSayIt() {
        assertEquals("20 Mbps", BitrateTestAnalyzer.mbps(20000));
        assertEquals("25.5 Mbps", BitrateTestAnalyzer.mbps(25500));
    }
}
