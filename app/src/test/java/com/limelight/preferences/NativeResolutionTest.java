package com.limelight.preferences;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Pure JVM tests for resolving a panel size into a streamable resolution. */
public class NativeResolutionTest {

    private static void assertResolution(int expectedW, int expectedH, int[] actual) {
        assertEquals("width", expectedW, actual[0]);
        assertEquals("height", expectedH, actual[1]);
    }

    @Test
    public void aLandscapePanelIsLeftAlone() {
        assertResolution(1920, 1080, NativeResolution.normalize(1920, 1080));
    }

    @Test
    public void aPortraitPanelIsTurnedLandscape() {
        // Handhelds report their physical orientation. The stream is always
        // laid out landscape, so 1080x2400 means a 2400x1080 stream.
        assertResolution(2400, 1080, NativeResolution.normalize(1080, 2400));
    }

    @Test
    public void theThorsMainPanelResolvesToItself() {
        assertResolution(1920, 1080, NativeResolution.normalize(1080, 1920));
    }

    @Test
    public void a4kDisplayIsAcceptedUnchanged() {
        assertResolution(3840, 2160, NativeResolution.normalize(3840, 2160));
    }

    @Test
    public void anythingLargerThan4kIsScaledDownKeepingItsShape() {
        int[] out = NativeResolution.normalize(7680, 4320);
        assertResolution(3840, 2160, out);
    }

    @Test
    public void anUltrawideIsFittedOnTheTighterAxisRatherThanStretched() {
        // 5120x2160 is wider than the limit but not taller. Fitting each axis
        // independently would leave the height untouched and squash the image.
        int[] out = NativeResolution.normalize(5120, 2160);
        assertEquals("width is clamped", 3840, out[0]);
        assertTrue("height must come down with it", out[1] < 2160);

        double before = 5120d / 2160d;
        double after = (double) out[0] / (double) out[1];
        assertEquals("aspect ratio preserved", before, after, 0.02);
    }

    @Test
    public void oddDimensionsAreRoundedDownToEven() {
        // Hardware encoders reject odd dimensions, and the failure surfaces at
        // stream setup with nothing useful to point at.
        int[] out = NativeResolution.normalize(1921, 1081);
        assertEquals(0, out[0] % 2);
        assertEquals(0, out[1] % 2);
        assertResolution(1920, 1080, out);
    }

    @Test
    public void everyResultIsEvenAcrossAWideRangeOfPanels() {
        int[][] panels = {
                {2400, 1080}, {2340, 1080}, {2412, 1080}, {3120, 1440},
                {2778, 1284}, {1600, 720}, {3200, 1440}, {2560, 1600},
                {1366, 768}, {960, 376}, {5120, 1440}, {7680, 2160},
        };
        for (int[] panel : panels) {
            int[] out = NativeResolution.normalize(panel[0], panel[1]);
            String label = panel[0] + "x" + panel[1] + " -> " + out[0] + "x" + out[1];
            assertEquals("width even: " + label, 0, out[0] % 2);
            assertEquals("height even: " + label, 0, out[1] % 2);
            assertTrue("within width limit: " + label, out[0] <= NativeResolution.MAX_WIDTH);
            assertTrue("within height limit: " + label, out[1] <= NativeResolution.MAX_HEIGHT);
            assertTrue("landscape: " + label, out[0] >= out[1]);
        }
    }

    @Test
    public void anImplausibleSizeFallsBackRatherThanBreakingTheStream() {
        assertResolution(NativeResolution.FALLBACK_WIDTH, NativeResolution.FALLBACK_HEIGHT,
                NativeResolution.normalize(0, 0));
        assertResolution(NativeResolution.FALLBACK_WIDTH, NativeResolution.FALLBACK_HEIGHT,
                NativeResolution.normalize(-1, 1080));
        assertResolution(NativeResolution.FALLBACK_WIDTH, NativeResolution.FALLBACK_HEIGHT,
                NativeResolution.normalize(64, 64));
    }

    @Test
    public void theSecondScreenOfAHandheldWouldStillProduceSomethingValid() {
        // Not that it should ever be chosen, but a tiny panel must not produce
        // a resolution that fails to negotiate.
        int[] out = NativeResolution.normalize(960, 376);
        assertResolution(960, 376, out);
    }

    @Test
    public void theStringFormMatchesThePreferenceFormat() {
        assertEquals("2400x1080",
                NativeResolution.toResolutionString(NativeResolution.normalize(1080, 2400)));
    }
}
