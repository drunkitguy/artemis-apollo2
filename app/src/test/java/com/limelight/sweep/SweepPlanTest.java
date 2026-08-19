package com.limelight.sweep;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Pure JVM tests for the shape of the sweep plan. */
public class SweepPlanTest {

    private static List<SweepPlan.Codec> threeCodecs() {
        return Arrays.asList(
                new SweepPlan.Codec(1, "H264"),
                new SweepPlan.Codec(2, "HEVC"),
                new SweepPlan.Codec(4, "AV1"));
    }

    private static List<SweepPlan.Pacing> twoPacings() {
        return Arrays.asList(new SweepPlan.Pacing(0, "latency"), new SweepPlan.Pacing(1, "balanced"));
    }

    @Test
    public void quickVariesOnlyTheCodec() {
        List<SweepVariant> plan = SweepPlan.build(threeCodecs(), 20000,
                twoPacings(), true, SweepPlan.Depth.QUICK);

        Set<String> configs = new HashSet<>();
        for (SweepVariant v : plan) {
            configs.add(v.key());
            assertFalse("quick must not pin", v.pinCores);
        }
        assertEquals(3, configs.size());
        assertEquals(3 * SweepPlan.REPEATS, plan.size());
    }

    @Test
    public void standardAddsPinningWhenTheDeviceHasFastCores() {
        List<SweepVariant> plan = SweepPlan.build(threeCodecs(), 20000,
                twoPacings(), true, SweepPlan.Depth.STANDARD);

        Set<String> configs = new HashSet<>();
        for (SweepVariant v : plan) {
            configs.add(v.key());
        }
        assertEquals("three codecs against pinned and unpinned", 6, configs.size());
    }

    @Test
    public void pinningIsSkippedOnDevicesThatCannotDoIt() {
        // Offering a choice the device cannot make would double the runtime to
        // measure the same thing twice.
        List<SweepVariant> plan = SweepPlan.build(threeCodecs(), 20000,
                twoPacings(), false, SweepPlan.Depth.STANDARD);

        for (SweepVariant v : plan) {
            assertFalse(v.pinCores);
        }
        Set<String> configs = new HashSet<>();
        for (SweepVariant v : plan) {
            configs.add(v.key());
        }
        assertEquals(3, configs.size());
    }

    @Test
    public void thoroughAddsPacing() {
        List<SweepVariant> plan = SweepPlan.build(threeCodecs(), 20000,
                twoPacings(), true, SweepPlan.Depth.THOROUGH);

        Set<String> configs = new HashSet<>();
        for (SweepVariant v : plan) {
            configs.add(v.key());
        }
        assertEquals("3 codecs x 2 pinning x 2 pacing", 12, configs.size());
    }

    @Test
    public void everyConfigurationIsRunTheSameNumberOfTimes() {
        List<SweepVariant> plan = SweepPlan.build(threeCodecs(), 20000,
                Collections.<SweepPlan.Pacing>emptyList(), true, SweepPlan.Depth.STANDARD);

        java.util.Map<String, Integer> counts = new java.util.HashMap<>();
        for (SweepVariant v : plan) {
            Integer seen = counts.get(v.key());
            counts.put(v.key(), seen == null ? 1 : seen + 1);
        }
        for (Integer count : counts.values()) {
            assertEquals(SweepPlan.REPEATS, count.intValue());
        }
    }

    @Test
    public void repeatsAreSpreadAcrossTheSweepRatherThanRunBackToBack() {
        // A handheld heats up as the sweep proceeds. Running one configuration's
        // repeats consecutively measures a single moment three times.
        List<SweepVariant> plan = SweepPlan.build(threeCodecs(), 20000,
                Collections.<SweepPlan.Pacing>emptyList(), false, SweepPlan.Depth.QUICK);

        int consecutive = 0;
        for (int i = 1; i < plan.size(); i++) {
            if (plan.get(i).key().equals(plan.get(i - 1).key())) {
                consecutive++;
            }
        }
        assertEquals("no repeat should run immediately after itself", 0, consecutive);
    }

    @Test
    public void eachRoundStartsWithADifferentConfiguration() {
        List<SweepVariant> cells = new ArrayList<>();
        cells.add(new SweepVariant(1, "A", 1, false, -1, ""));
        cells.add(new SweepVariant(2, "B", 1, false, -1, ""));
        cells.add(new SweepVariant(4, "C", 1, false, -1, ""));

        List<SweepVariant> out = SweepPlan.interleave(cells, 3);
        assertEquals(9, out.size());

        // Rotation: A B C / B C A / C A B. No cell touches itself, and each
        // one gets a turn at being measured on the coldest device.
        assertEquals("A", out.get(0).codecName);
        assertEquals("B", out.get(3).codecName);
        assertEquals("C", out.get(6).codecName);

        for (int i = 1; i < out.size(); i++) {
            assertFalse("adjacent repeat at " + i,
                    out.get(i).codecName.equals(out.get(i - 1).codecName));
        }
    }

    @Test
    public void bitrateIsHeldConstantAcrossTheWholePlan() {
        List<SweepVariant> plan = SweepPlan.build(threeCodecs(), 33500,
                twoPacings(), true, SweepPlan.Depth.THOROUGH);
        for (SweepVariant v : plan) {
            assertEquals(33500, v.bitrateKbps);
        }
    }

    @Test
    public void exhaustiveMakesBitrateItsOwnAxis() {
        // A codec's packet loss threshold is not the same as another's, so a
        // single fixed bitrate can flatter whichever codec it happens to suit.
        List<Integer> bitrates = Arrays.asList(20000, 40000, 80000);
        List<SweepVariant> plan = SweepPlan.build(threeCodecs(), bitrates,
                twoPacings(), true, SweepPlan.Depth.EXHAUSTIVE);

        Set<String> configs = new HashSet<>();
        for (SweepVariant v : plan) {
            configs.add(v.key());
        }
        assertEquals("3 codecs x 2 pinning x 2 pacing x 3 bitrates", 36, configs.size());
        assertEquals(36 * SweepPlan.REPEATS, plan.size());
    }

    @Test
    public void everyDepthBelowExhaustiveHoldsBitrateConstant() {
        List<Integer> bitrates = Arrays.asList(20000, 40000, 80000);
        for (SweepPlan.Depth depth : new SweepPlan.Depth[] {
                SweepPlan.Depth.QUICK, SweepPlan.Depth.STANDARD, SweepPlan.Depth.THOROUGH}) {
            List<SweepVariant> plan = SweepPlan.build(threeCodecs(), bitrates,
                    twoPacings(), true, depth);
            for (SweepVariant v : plan) {
                assertEquals(depth + " should pin bitrate to the first value",
                        20000, v.bitrateKbps);
            }
        }
    }

    @Test
    public void exhaustiveStillNeverRunsARepeatBackToBack() {
        List<SweepVariant> plan = SweepPlan.build(threeCodecs(),
                Arrays.asList(20000, 40000), twoPacings(), true, SweepPlan.Depth.EXHAUSTIVE);
        for (int i = 1; i < plan.size(); i++) {
            assertFalse("adjacent repeat at " + i,
                    plan.get(i).key().equals(plan.get(i - 1).key()));
        }
    }

    @Test
    public void aSingleCodecStillProducesAValidPlan() {
        List<SweepVariant> plan = SweepPlan.build(
                Collections.singletonList(new SweepPlan.Codec(1, "H264")),
                20000, Collections.<SweepPlan.Pacing>emptyList(), false, SweepPlan.Depth.STANDARD);
        assertEquals(SweepPlan.REPEATS, plan.size());
    }
}
