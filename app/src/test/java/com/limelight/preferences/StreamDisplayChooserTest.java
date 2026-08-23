package com.limelight.preferences;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Pure JVM tests for which screen native resolution should describe. */
public class StreamDisplayChooserTest {

    private static StreamDisplayChooser.Candidate main(int id, int w, int h) {
        return new StreamDisplayChooser.Candidate(id, w, h, true, true);
    }

    private static StreamDisplayChooser.Candidate other(int id, int w, int h) {
        return new StreamDisplayChooser.Candidate(id, w, h, false, true);
    }

    /** A dual screen handheld: 1080p on top, a small strip underneath. */
    private static List<StreamDisplayChooser.Candidate> thor() {
        return Arrays.asList(main(0, 1920, 1080), other(2, 960, 376));
    }

    /** The same handheld docked to a 4K television. */
    private static List<StreamDisplayChooser.Candidate> thorDocked() {
        return Arrays.asList(main(0, 1920, 1080), other(2, 960, 376), other(5, 3840, 2160));
    }

    // ------------------------------------------------- the reported bug

    @Test
    public void theSmallSecondPanelIsNeverChosen() {
        // The bug: enumerating displays and taking the first non-default one
        // finds the built-in bottom panel, so the stream was sized 960x376.
        assertEquals(0, StreamDisplayChooser.choose(thor(), false));
        assertEquals("even with external display mode on, there is no monitor here",
                0, StreamDisplayChooser.choose(thor(), true));
    }

    @Test
    public void externalModeOnAHandheldWithNoMonitorStaysOnTheMainPanel() {
        for (boolean external : new boolean[] {false, true}) {
            int chosen = StreamDisplayChooser.choose(thor(), external);
            assertEquals("external=" + external, 0, chosen);
        }
    }

    // --------------------------------------------------------- docking

    @Test
    public void dockedWithExternalModeOnPicksTheTelevision() {
        assertEquals(5, StreamDisplayChooser.choose(thorDocked(), true));
    }

    @Test
    public void dockedWithExternalModeOffStaysOnTheHandheld() {
        // The picture is still being rendered on the device, so sizing the
        // stream to the television would encode pixels that get scaled away.
        assertEquals(0, StreamDisplayChooser.choose(thorDocked(), false));
    }

    // ----------------------------------------------------------- guards

    @Test
    public void aDefaultDisplaySmallerThanAnotherPanelIsNotTrusted() {
        // If a device reports its small panel as the default, sizing the
        // stream to it is never what was wanted.
        List<StreamDisplayChooser.Candidate> odd =
                Arrays.asList(main(0, 960, 376), other(1, 1920, 1080));
        assertEquals(1, StreamDisplayChooser.choose(odd, false));
    }

    @Test
    public void unusableScreensAreSkipped() {
        List<StreamDisplayChooser.Candidate> displays = Arrays.asList(
                main(0, 1920, 1080),
                new StreamDisplayChooser.Candidate(4, 3840, 2160, false, false));
        assertEquals("a screen that is off is not where the picture is going",
                0, StreamDisplayChooser.choose(displays, true));
    }

    @Test
    public void sizelessScreensAreSkipped() {
        List<StreamDisplayChooser.Candidate> displays = Arrays.asList(
                main(0, 1920, 1080), other(3, 0, 0));
        assertEquals(0, StreamDisplayChooser.choose(displays, true));
    }

    @Test
    public void aSingleScreenDeviceJustUsesIt() {
        List<StreamDisplayChooser.Candidate> one = Collections.singletonList(main(0, 2400, 1080));
        assertEquals(0, StreamDisplayChooser.choose(one, false));
        assertEquals(0, StreamDisplayChooser.choose(one, true));
    }

    @Test
    public void tiesGoToTheLowestIdSoTheAnswerIsStable() {
        List<StreamDisplayChooser.Candidate> displays = Arrays.asList(
                main(0, 1280, 720), other(7, 1920, 1080), other(3, 1920, 1080));
        assertEquals(3, StreamDisplayChooser.choose(displays, true));
    }

    @Test
    public void noDisplaysAtAllIsNotACrash() {
        assertEquals(StreamDisplayChooser.NO_DISPLAY, StreamDisplayChooser.choose(null, true));
        assertEquals(StreamDisplayChooser.NO_DISPLAY,
                StreamDisplayChooser.choose(Collections.<StreamDisplayChooser.Candidate>emptyList(), false));
    }

    @Test
    public void withNoDefaultReportedTheLargestIsUsed() {
        List<StreamDisplayChooser.Candidate> displays =
                Arrays.asList(other(1, 960, 376), other(2, 1920, 1080));
        assertEquals(2, StreamDisplayChooser.choose(displays, false));
    }
}
