package com.limelight.focus;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** Pure JVM tests for how focus reports drive the panel. */
public class FocusHintPolicyTest {

    @Test
    public void aTextFieldOpensTheLetterKeyboard() {
        FocusHintPolicy p = new FocusHintPolicy();
        assertEquals(FocusHintPolicy.Action.SHOW_TEXT, p.onHint(FocusHint.State.TEXT, 0));
    }

    @Test
    public void aNumericFieldOpensTheKeypad() {
        FocusHintPolicy p = new FocusHintPolicy();
        assertEquals(FocusHintPolicy.Action.SHOW_DIGITS, p.onHint(FocusHint.State.DIGITS, 0));
    }

    @Test
    public void repeatedReportsOfTheSameFieldChangeNothing() {
        // The host reports on a timer, so the same state arrives over and over.
        FocusHintPolicy p = new FocusHintPolicy();
        assertEquals(FocusHintPolicy.Action.SHOW_TEXT, p.onHint(FocusHint.State.TEXT, 0));
        for (long t = 150; t < 3000; t += 150) {
            assertEquals("t=" + t, FocusHintPolicy.Action.NOTHING, p.onHint(FocusHint.State.TEXT, t));
        }
    }

    @Test
    public void movingFromTextToDigitsSwitchesWithoutClosingFirst() {
        FocusHintPolicy p = new FocusHintPolicy();
        p.onHint(FocusHint.State.TEXT, 0);
        assertEquals(FocusHintPolicy.Action.SHOW_DIGITS, p.onHint(FocusHint.State.DIGITS, 150));
    }

    @Test
    public void unknownNeverChangesAnything() {
        // Browsers usually report unknown. Treating that as "no field" would
        // close the keyboard while someone is typing into a web page.
        FocusHintPolicy p = new FocusHintPolicy();
        p.onHint(FocusHint.State.TEXT, 0);
        for (long t = 150; t < 5000; t += 150) {
            assertEquals(FocusHintPolicy.Action.NOTHING, p.onHint(FocusHint.State.UNKNOWN, t));
        }
    }

    @Test
    public void aBriefLossOfFocusDoesNotCloseTheKeyboard() {
        // Clicking from one field to another passes through nothing focused.
        FocusHintPolicy p = new FocusHintPolicy();
        p.onHint(FocusHint.State.TEXT, 0);

        assertEquals(FocusHintPolicy.Action.NOTHING, p.onHint(FocusHint.State.NONE, 100));
        assertEquals(FocusHintPolicy.Action.NOTHING, p.onHint(FocusHint.State.NONE, 300));
        assertEquals("focus came back before the delay expired",
                FocusHintPolicy.Action.NOTHING, p.onHint(FocusHint.State.TEXT, 400));
    }

    @Test
    public void asustainedLossOfFocusClosesIt() {
        FocusHintPolicy p = new FocusHintPolicy();
        p.onHint(FocusHint.State.TEXT, 0);
        p.onHint(FocusHint.State.NONE, 100);
        assertEquals(FocusHintPolicy.Action.NOTHING,
                p.onHint(FocusHint.State.NONE, 100 + FocusHintPolicy.CLOSE_DELAY_MS - 1));
        assertEquals(FocusHintPolicy.Action.REST,
                p.onHint(FocusHint.State.NONE, 100 + FocusHintPolicy.CLOSE_DELAY_MS + 1));
    }

    @Test
    public void theTimerClosesItEvenIfTheHostStopsReporting() {
        FocusHintPolicy p = new FocusHintPolicy();
        p.onHint(FocusHint.State.TEXT, 0);
        p.onHint(FocusHint.State.NONE, 100);

        assertEquals(FocusHintPolicy.Action.NOTHING, p.onTick(200));
        assertEquals(FocusHintPolicy.Action.REST, p.onTick(100 + FocusHintPolicy.CLOSE_DELAY_MS + 1));
    }

    @Test
    public void nothingHappensWhenThereIsNoKeyboardToClose() {
        FocusHintPolicy p = new FocusHintPolicy();
        for (long t = 0; t < 5000; t += 200) {
            assertEquals(FocusHintPolicy.Action.NOTHING, p.onHint(FocusHint.State.NONE, t));
        }
    }

    @Test
    public void openingTheKeyboardByHandIsRespected() {
        // The user pressed the stick chord. A later report of the same kind of
        // field must not fight them by reopening it.
        FocusHintPolicy p = new FocusHintPolicy();
        p.syncOpenState(true, false);
        assertEquals(FocusHintPolicy.Action.NOTHING, p.onHint(FocusHint.State.TEXT, 0));
    }

    @Test
    public void closingTheKeyboardByHandIsRespected() {
        FocusHintPolicy p = new FocusHintPolicy();
        p.onHint(FocusHint.State.TEXT, 0);
        p.syncOpenState(false, false);
        // The field still has focus, so the next report reopens it. That is
        // correct: the host says a field is focused and the panel is empty.
        assertEquals(FocusHintPolicy.Action.SHOW_TEXT, p.onHint(FocusHint.State.TEXT, 500));
    }

    @Test
    public void resetForgetsEverything() {
        FocusHintPolicy p = new FocusHintPolicy();
        p.onHint(FocusHint.State.TEXT, 0);
        p.reset();
        assertEquals(FocusHintPolicy.Action.SHOW_TEXT, p.onHint(FocusHint.State.TEXT, 100));
    }
}
