package com.limelight.metrics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Pure JVM tests for the live metrics readout. */
public class StreamMetricsWindowTest {

    @Test
    public void theFirstReadingOnlyAnchorsAndReportsNothing() {
        StreamMetricsWindow w = new StreamMetricsWindow();
        w.update(1000, 500, 4000);

        assertFalse(w.hasReading());
        assertEquals(StreamMetricsWindow.UNKNOWN, w.getFps());
    }

    @Test
    public void fpsIsMeasuredOverTheWindowNotTheWholeSession() {
        StreamMetricsWindow w = new StreamMetricsWindow();
        // A long session that averaged 30 fps so far.
        w.update(0, 30000, 100000);
        // The last second delivered 60.
        w.update(1000, 30060, 100500);

        assertEquals("must describe the last second, not the session", 60, w.getFps());
    }

    @Test
    public void decodeTimeIsAveragedPerFrameInTheWindow() {
        StreamMetricsWindow w = new StreamMetricsWindow();
        w.update(0, 0, 0);
        // 60 frames took 504 ms of decoding: 8.4 ms each.
        w.update(1000, 60, 504);

        assertEquals(84, w.getDecodeTimeTenthsMs());
        assertEquals("8.4", StreamMetricsWindow.formatTenths(w.getDecodeTimeTenthsMs()));
    }

    @Test
    public void aWindowWithNoFramesReportsNothingRatherThanZero() {
        StreamMetricsWindow w = new StreamMetricsWindow();
        w.update(0, 100, 800);
        w.update(1000, 100, 800);

        assertEquals("no frames means no fps", 0, w.getFps());
        assertEquals("and no decode time to average",
                StreamMetricsWindow.UNKNOWN, w.getDecodeTimeTenthsMs());
        assertEquals("—", StreamMetricsWindow.formatTenths(w.getDecodeTimeTenthsMs()));
    }

    @Test
    public void countersGoingBackwardsReanchorInsteadOfReportingNonsense() {
        // The session was torn down and restarted, so the totals reset.
        StreamMetricsWindow w = new StreamMetricsWindow();
        w.update(0, 5000, 40000);
        w.update(1000, 5060, 40500);
        assertEquals(60, w.getFps());

        w.update(2000, 12, 90);
        assertEquals("the stale reading is kept rather than a negative one", 60, w.getFps());

        // And the next window measures cleanly from the new baseline.
        w.update(3000, 72, 590);
        assertEquals(60, w.getFps());
    }

    @Test
    public void aZeroLengthWindowIsIgnored() {
        StreamMetricsWindow w = new StreamMetricsWindow();
        w.update(1000, 0, 0);
        w.update(1000, 60, 500);
        assertFalse("no time passed, so there is no rate", w.hasReading());
    }

    @Test
    public void unevenPollIntervalsStillGiveTheRightRate() {
        // Handlers do not fire on exact intervals.
        StreamMetricsWindow w = new StreamMetricsWindow();
        w.update(0, 0, 0);
        w.update(1487, 89, 712);
        assertEquals("89 frames in 1.487s", 60, w.getFps());
    }

    @Test
    public void formattingCoversTheEdges() {
        assertEquals("—", StreamMetricsWindow.formatTenths(StreamMetricsWindow.UNKNOWN));
        assertEquals("0.0", StreamMetricsWindow.formatTenths(0));
        assertEquals("12.7", StreamMetricsWindow.formatTenths(127));
        assertEquals("100.0", StreamMetricsWindow.formatTenths(1000));
    }

    @Test
    public void resetClearsTheReadingAndTheAnchor() {
        StreamMetricsWindow w = new StreamMetricsWindow();
        w.update(0, 0, 0);
        w.update(1000, 60, 500);
        assertTrue(w.hasReading());

        w.reset();
        assertFalse(w.hasReading());

        // The next reading anchors again rather than measuring against stale state.
        w.update(2000, 500, 4000);
        assertFalse(w.hasReading());
    }
}
