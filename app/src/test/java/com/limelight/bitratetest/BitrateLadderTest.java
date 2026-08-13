package com.limelight.bitratetest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;

/** Pure JVM tests for the ladder: no Android, no Robolectric. */
public class BitrateLadderTest {

    @Test
    public void defaultBitrateMatchesTheKnownModes() {
        // These are the values PreferenceConfiguration.getDefaultBitrate() produces.
        assertEquals(10000, BitrateLadder.defaultBitrateKbps(1280, 720, 60));
        assertEquals(20000, BitrateLadder.defaultBitrateKbps(1920, 1080, 60));
        assertEquals(40000, BitrateLadder.defaultBitrateKbps(2560, 1440, 60));
        assertEquals(80000, BitrateLadder.defaultBitrateKbps(3840, 2160, 60));

        // 30 fps is half of 60 fps.
        assertEquals(10000, BitrateLadder.defaultBitrateKbps(1920, 1080, 30));
    }

    @Test
    public void aboveSixtyFpsScalesSublinearly() {
        int at60 = BitrateLadder.defaultBitrateKbps(1920, 1080, 60);
        int at120 = BitrateLadder.defaultBitrateKbps(1920, 1080, 120);

        assertTrue("120 fps must want more than 60 fps", at120 > at60);
        assertTrue("but not twice as much", at120 < at60 * 2);
    }

    @Test
    public void ceilingIsClamped() {
        // Tiny mode: the default is 1 Mbps, but the ladder still starts at the floor.
        assertEquals(BitrateLadder.MIN_CEILING_KBPS, BitrateLadder.ceilingKbps(640, 360, 30));

        // 4K120 wants far more than the cap allows.
        assertEquals(BitrateLadder.MAX_CEILING_KBPS, BitrateLadder.ceilingKbps(3840, 2160, 120));
    }

    @Test
    public void ladderIsAscendingBoundedAndNonEmpty() {
        int[][] modes = {
                {640, 360, 30}, {1280, 720, 60}, {1920, 1080, 60},
                {1920, 1080, 120}, {2560, 1440, 60}, {3840, 2160, 60},
        };

        for (int[] mode : modes) {
            int[] ladder = BitrateLadder.build(mode[0], mode[1], mode[2]);
            String label = Arrays.toString(mode) + " -> " + Arrays.toString(ladder);

            assertTrue("ladder must not be empty: " + label, ladder.length > 0);
            int ceiling = BitrateLadder.ceilingKbps(mode[0], mode[1], mode[2]);
            for (int i = 0; i < ladder.length; i++) {
                assertTrue("rung must be positive: " + label, ladder[i] > 0);
                assertTrue("rung must not exceed the ceiling: " + label, ladder[i] <= ceiling);
                if (i > 0) {
                    assertTrue("ladder must strictly ascend: " + label, ladder[i] > ladder[i - 1]);
                }
            }
            assertTrue("ladder must never exceed the hard cap: " + label,
                    ladder[ladder.length - 1] <= BitrateLadder.MAX_CEILING_KBPS);
        }
    }

    @Test
    public void ladderForKnownModes() {
        // 720p60: default 10 Mbps, ceiling 40 Mbps.
        assertArrayEqualsInt(new int[] { 10000, 20000, 30000, 40000 },
                BitrateLadder.build(1280, 720, 60));

        // 1080p60: default 20 Mbps, ceiling 80 Mbps, which is already a rung.
        assertArrayEqualsInt(new int[] { 10000, 20000, 30000, 50000, 80000 },
                BitrateLadder.build(1920, 1080, 60));

        // 4K60: default 80 Mbps, so the ceiling clamps to the full ladder.
        assertArrayEqualsInt(BitrateLadder.BASE_RUNGS_KBPS, BitrateLadder.build(3840, 2160, 60));
    }

    @Test
    public void tinyModeStillGetsOneRung() {
        int[] ladder = BitrateLadder.build(640, 360, 30);
        assertArrayEqualsInt(new int[] { BitrateLadder.MIN_CEILING_KBPS }, ladder);
    }

    @Test
    public void nonsenseInputDoesNotBlowUp() {
        int[] ladder = BitrateLadder.build(0, 0, 0);
        assertTrue(ladder.length > 0);
        assertTrue(ladder[0] > 0);
    }

    private static void assertArrayEqualsInt(int[] expected, int[] actual) {
        assertEquals(Arrays.toString(expected), Arrays.toString(actual));
    }
}
