package com.limelight.binding.input.softkeyboard;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Pure JVM tests for which screen the keyboard lands on. */
public class KeyboardDisplayChooserTest {

    private static KeyboardDisplayChooser.Candidate display(int id, int w, int h) {
        return new KeyboardDisplayChooser.Candidate(id, w, h, true);
    }

    private static KeyboardDisplayChooser.Candidate unusable(int id, int w, int h) {
        return new KeyboardDisplayChooser.Candidate(id, w, h, false);
    }

    /** The AYN Thor Pro: a 1080p main panel and a smaller secondary one. */
    private static List<KeyboardDisplayChooser.Candidate> thor() {
        return Arrays.asList(display(0, 1920, 1080), display(2, 960, 376));
    }

    @Test
    public void onADualScreenHandheldTheKeyboardTakesTheSmallScreen() {
        assertEquals(2, KeyboardDisplayChooser.choose(thor(), 0));
    }

    @Test
    public void withOnlyOneScreenThereIsNowhereToPutIt() {
        List<KeyboardDisplayChooser.Candidate> one = Collections.singletonList(display(0, 1920, 1080));
        assertEquals(KeyboardDisplayChooser.NO_DISPLAY, KeyboardDisplayChooser.choose(one, 0));
    }

    @Test
    public void theScreenTheStreamIsOnIsNeverChosen() {
        for (KeyboardDisplayChooser.Candidate candidate : thor()) {
            int chosen = KeyboardDisplayChooser.choose(thor(), candidate.displayId);
            assertEquals("must not cover the stream", true, chosen != candidate.displayId);
        }
    }

    @Test
    public void externalDisplayModeSendsTheKeyboardBackToTheHandheld() {
        // Artemis can put the stream on the external screen. The same rule then
        // has to send the keyboard the other way, onto the device panel.
        List<KeyboardDisplayChooser.Candidate> displays =
                Arrays.asList(display(0, 1080, 2400), display(5, 3840, 2160));
        assertEquals(0, KeyboardDisplayChooser.choose(displays, 5));
    }

    @Test
    public void theSmallestUsableScreenWinsWhenThereAreSeveral() {
        List<KeyboardDisplayChooser.Candidate> displays = Arrays.asList(
                display(0, 1920, 1080),
                display(1, 1280, 720),
                display(2, 800, 480),
                display(3, 2560, 1440));
        assertEquals(2, KeyboardDisplayChooser.choose(displays, 0));
    }

    @Test
    public void tiesGoToTheLowestIdSoTheChoiceIsStable() {
        List<KeyboardDisplayChooser.Candidate> displays = Arrays.asList(
                display(0, 1920, 1080),
                display(7, 800, 480),
                display(3, 800, 480));
        assertEquals(3, KeyboardDisplayChooser.choose(displays, 0));

        // Order of the list must not change the answer.
        List<KeyboardDisplayChooser.Candidate> shuffled = new ArrayList<>(displays);
        Collections.reverse(shuffled);
        assertEquals(3, KeyboardDisplayChooser.choose(shuffled, 0));
    }

    @Test
    public void screensThatAreOffOrSizelessAreSkipped() {
        List<KeyboardDisplayChooser.Candidate> displays = Arrays.asList(
                display(0, 1920, 1080),
                unusable(1, 640, 480),
                display(2, 0, 0),
                display(3, 960, 376));
        assertEquals(3, KeyboardDisplayChooser.choose(displays, 0));
    }

    @Test
    public void noUsableSecondScreenFallsBackToTheOverlay() {
        List<KeyboardDisplayChooser.Candidate> displays = Arrays.asList(
                display(0, 1920, 1080),
                unusable(1, 960, 376));
        assertEquals(KeyboardDisplayChooser.NO_DISPLAY, KeyboardDisplayChooser.choose(displays, 0));
    }

    @Test
    public void aNullOrEmptyListIsNotACrash() {
        assertEquals(KeyboardDisplayChooser.NO_DISPLAY, KeyboardDisplayChooser.choose(null, 0));
        assertEquals(KeyboardDisplayChooser.NO_DISPLAY,
                KeyboardDisplayChooser.choose(Collections.<KeyboardDisplayChooser.Candidate>emptyList(), 0));
    }
}
