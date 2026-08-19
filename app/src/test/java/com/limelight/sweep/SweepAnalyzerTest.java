package com.limelight.sweep;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Pure JVM tests for how the sweep reduces and ranks its runs. */
public class SweepAnalyzerTest {

    private static SweepVariant variant(String codec, boolean pinned) {
        return new SweepVariant(1, codec, 20000, pinned, -1, "");
    }

    private static SweepAnalyzer.Run run(SweepVariant v, double decodeMs) {
        return new SweepAnalyzer.Run(v, decodeMs, 0d, 3d, false);
    }

    // -------------------------------------------------------------- reduction

    @Test
    public void repeatsAreReducedWithAMedianNotAMean() {
        SweepVariant v = variant("HEVC", false);
        // One run ruined by a burst of contention must not move the answer.
        List<SweepAnalyzer.Run> runs = Arrays.asList(
                run(v, 5.0), run(v, 5.2), run(v, 40.0));

        List<SweepAnalyzer.Summary> summaries = SweepAnalyzer.summarize(runs);
        assertEquals(1, summaries.size());
        assertEquals(5.2, summaries.get(0).medianDecodeMs, 0.001);
    }

    @Test
    public void repeatsOfOneConfigurationGroupTogether() {
        SweepVariant hevc = variant("HEVC", false);
        SweepVariant av1 = variant("AV1", false);
        List<SweepAnalyzer.Run> runs = Arrays.asList(
                run(hevc, 5), run(av1, 7), run(hevc, 6), run(av1, 8));

        List<SweepAnalyzer.Summary> summaries = SweepAnalyzer.summarize(runs);
        assertEquals(2, summaries.size());
        for (SweepAnalyzer.Summary s : summaries) {
            assertEquals("each configuration ran twice", 2, s.runs);
        }
    }

    @Test
    public void pinnedAndUnpinnedAreDifferentConfigurations() {
        List<SweepAnalyzer.Run> runs = Arrays.asList(
                run(variant("HEVC", false), 5),
                run(variant("HEVC", true), 4));
        assertEquals(2, SweepAnalyzer.summarize(runs).size());
    }

    @Test
    public void spreadIsHalfTheRange() {
        SweepVariant v = variant("HEVC", false);
        List<SweepAnalyzer.Summary> s = SweepAnalyzer.summarize(
                Arrays.asList(run(v, 4.0), run(v, 5.0), run(v, 6.0)));
        assertEquals(1.0, s.get(0).decodeSpreadMs, 0.001);
    }

    @Test
    public void aSingleRunHasNoSpreadToReport() {
        SweepVariant v = variant("HEVC", false);
        List<SweepAnalyzer.Summary> s = SweepAnalyzer.summarize(
                Collections.singletonList(run(v, 4.0)));
        assertEquals(0.0, s.get(0).decodeSpreadMs, 0.001);
    }

    // ---------------------------------------------------------- qualification

    @Test
    public void aFastCodecThatLosesPacketsIsNotTheWinner() {
        SweepVariant lossy = variant("AV1", false);
        SweepVariant clean = variant("HEVC", false);

        List<SweepAnalyzer.Run> runs = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            // Decodes faster, but drops packets: broken, not better.
            runs.add(new SweepAnalyzer.Run(lossy, 2.0, 3.0, 3d, false));
            runs.add(new SweepAnalyzer.Run(clean, 8.0, 0.0, 3d, false));
        }

        SweepAnalyzer.Summary best = SweepAnalyzer.best(SweepAnalyzer.summarize(runs));
        assertNotNull(best);
        assertEquals("HEVC", best.variant.codecName);
    }

    @Test
    public void aConfigurationThatFailedAnyRunIsDisqualified() {
        SweepVariant v = variant("AV1", false);
        List<SweepAnalyzer.Run> runs = Arrays.asList(
                run(v, 2.0),
                new SweepAnalyzer.Run(v, 0, 0, 0, true));

        List<SweepAnalyzer.Summary> summaries = SweepAnalyzer.summarize(runs);
        assertTrue(summaries.get(0).isDisqualified());
        assertNull(SweepAnalyzer.best(summaries));
    }

    @Test
    public void everythingDisqualifiedYieldsNoWinnerRatherThanAWrongOne() {
        SweepVariant a = variant("H264", false);
        SweepVariant b = variant("HEVC", false);
        List<SweepAnalyzer.Run> runs = Arrays.asList(
                new SweepAnalyzer.Run(a, 3, 5.0, 3, false),
                new SweepAnalyzer.Run(b, 4, 6.0, 3, false));
        assertNull(SweepAnalyzer.best(SweepAnalyzer.summarize(runs)));
    }

    // -------------------------------------------------------------- confidence

    @Test
    public void aGapSmallerThanTheNoiseIsNotCalledAWinner() {
        SweepVariant a = variant("HEVC", false);
        SweepVariant b = variant("AV1", false);

        // Medians differ by 0.2ms while each configuration's own repeats vary
        // by more than that. Reporting a winner here would be reporting noise.
        List<SweepAnalyzer.Run> runs = Arrays.asList(
                run(a, 4.0), run(a, 6.0), run(a, 5.0),
                run(b, 4.2), run(b, 6.2), run(b, 5.2));

        List<SweepAnalyzer.Summary> summaries = SweepAnalyzer.summarize(runs);
        assertNotNull("a best still exists", SweepAnalyzer.best(summaries));
        assertFalse("but it must not be called conclusive",
                SweepAnalyzer.isConclusive(summaries));
    }

    @Test
    public void aGapLargerThanTheNoiseIsConclusive() {
        SweepVariant fast = variant("AV1", false);
        SweepVariant slow = variant("H264", false);

        List<SweepAnalyzer.Run> runs = Arrays.asList(
                run(fast, 3.0), run(fast, 3.1), run(fast, 3.2),
                run(slow, 9.0), run(slow, 9.1), run(slow, 9.2));

        List<SweepAnalyzer.Summary> summaries = SweepAnalyzer.summarize(runs);
        assertTrue(SweepAnalyzer.isConclusive(summaries));
        assertEquals("AV1", SweepAnalyzer.best(summaries).variant.codecName);
    }

    @Test
    public void oneSurvivingConfigurationIsNeverConclusive() {
        // Nothing to compare against is not the same as a clear result.
        SweepVariant only = variant("HEVC", false);
        List<SweepAnalyzer.Run> runs = Arrays.asList(run(only, 4.0), run(only, 4.1));
        assertFalse(SweepAnalyzer.isConclusive(SweepAnalyzer.summarize(runs)));
    }

    @Test
    public void medianHandlesEvenCounts() {
        assertEquals(4.5, SweepAnalyzer.median(Arrays.asList(4.0, 5.0)), 0.001);
        assertEquals(0.0, SweepAnalyzer.median(new ArrayList<Double>()), 0.001);
    }
}
