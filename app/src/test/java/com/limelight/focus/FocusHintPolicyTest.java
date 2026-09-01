package com.limelight.focus;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import com.limelight.binding.input.softkeyboard.HostFieldFocus;

import org.junit.Test;

/** Pure JVM tests for how focus reports drive the panel. */
public class FocusHintPolicyTest {

    private static FocusHint.Report of(FocusHint.State state) {
        return new FocusHint.Report(state, 0);
    }

    @Test
    public void aTextFieldIsHandedOn() {
        FocusHintPolicy p = new FocusHintPolicy();
        FocusHint.Report out = p.onHint(of(FocusHint.State.TEXT), 0);
        assertNotNull(out);
        assertEquals(HostFieldFocus.KIND_TEXT, out.hostKind());
    }

    @Test
    public void aNumericFieldIsHandedOn() {
        FocusHintPolicy p = new FocusHintPolicy();
        FocusHint.Report out = p.onHint(of(FocusHint.State.DIGITS), 0);
        assertNotNull(out);
        assertEquals(HostFieldFocus.KIND_NUMERIC, out.hostKind());
    }

    @Test
    public void repeatedReportsOfTheSameFieldChangeNothing() {
        // The host reports on a timer, so the same state arrives over and over.
        FocusHintPolicy p = new FocusHintPolicy();
        assertNotNull(p.onHint(of(FocusHint.State.TEXT), 0));
        for (long t = 150; t < 3000; t += 150) {
            assertNull("t=" + t, p.onHint(of(FocusHint.State.TEXT), t));
        }
    }

    @Test
    public void aChangeOfFlagsOnTheSameKindIsStillHandedOn() {
        // Tabbing from an ordinary password box to a PIN box is a change of
        // flags and nothing else, and it is the difference between QWERTY and
        // the keypad. Deciding that is the controller's job, so this must not
        // swallow it on the way.
        FocusHintPolicy p = new FocusHintPolicy();
        assertNotNull(p.onHint(new FocusHint.Report(FocusHint.State.PASSWORD, 0), 0));
        FocusHint.Report out = p.onHint(
                new FocusHint.Report(FocusHint.State.PASSWORD, HostFieldFocus.FLAG_NUMERIC), 150);
        assertNotNull(out);
        assertEquals(HostFieldFocus.FLAG_NUMERIC, out.flags);
    }

    @Test
    public void movingFromTextToDigitsSwitchesWithoutClosingFirst() {
        FocusHintPolicy p = new FocusHintPolicy();
        p.onHint(of(FocusHint.State.TEXT), 0);
        FocusHint.Report out = p.onHint(of(FocusHint.State.DIGITS), 150);
        assertNotNull(out);
        assertEquals(HostFieldFocus.KIND_NUMERIC, out.hostKind());
    }

    @Test
    public void unknownNeverChangesAnything() {
        // Browsers usually report unknown. Treating that as "no field" would
        // close the keyboard while someone is typing into a web page.
        FocusHintPolicy p = new FocusHintPolicy();
        p.onHint(of(FocusHint.State.TEXT), 0);
        for (long t = 150; t < 5000; t += 150) {
            assertNull(p.onHint(of(FocusHint.State.UNKNOWN), t));
        }
    }

    @Test
    public void aBriefLossOfFocusDoesNotCloseTheKeyboard() {
        // Clicking from one field to another passes through nothing focused.
        FocusHintPolicy p = new FocusHintPolicy();
        p.onHint(of(FocusHint.State.TEXT), 0);

        assertNull(p.onHint(of(FocusHint.State.NONE), 100));
        assertNull(p.onHint(of(FocusHint.State.NONE), 300));
        assertNull("focus came back before the delay expired",
                p.onHint(of(FocusHint.State.TEXT), 400));
    }

    @Test
    public void asustainedLossOfFocusClosesIt() {
        FocusHintPolicy p = new FocusHintPolicy();
        p.onHint(of(FocusHint.State.TEXT), 0);
        p.onHint(of(FocusHint.State.NONE), 100);
        assertNull(p.onHint(of(FocusHint.State.NONE),
                100 + FocusHintPolicy.CLOSE_DELAY_MS - 1));
        FocusHint.Report out = p.onHint(of(FocusHint.State.NONE),
                100 + FocusHintPolicy.CLOSE_DELAY_MS + 1);
        assertNotNull(out);
        assertEquals(HostFieldFocus.KIND_NONE, out.hostKind());
    }

    @Test
    public void theTimerClosesItEvenIfTheHostStopsReporting() {
        FocusHintPolicy p = new FocusHintPolicy();
        p.onHint(of(FocusHint.State.TEXT), 0);
        p.onHint(of(FocusHint.State.NONE), 100);

        assertNull(p.onTick(200));
        FocusHint.Report out = p.onTick(100 + FocusHintPolicy.CLOSE_DELAY_MS + 1);
        assertNotNull(out);
        assertEquals(HostFieldFocus.KIND_NONE, out.hostKind());
    }

    @Test
    public void theTimerDoesNotFireWhenAFieldStillHasFocus() {
        FocusHintPolicy p = new FocusHintPolicy();
        p.onHint(of(FocusHint.State.TEXT), 0);
        for (long t = 0; t < 10000; t += 500) {
            assertNull(p.onTick(t));
        }
    }

    @Test
    public void nothingHappensWhenThereIsNoKeyboardToClose() {
        FocusHintPolicy p = new FocusHintPolicy();
        for (long t = 0; t < 5000; t += 200) {
            assertNull(p.onHint(of(FocusHint.State.NONE), t));
        }
        assertNull(p.onTick(6000));
    }

    @Test
    public void resetForgetsEverything() {
        FocusHintPolicy p = new FocusHintPolicy();
        p.onHint(of(FocusHint.State.TEXT), 0);
        p.reset();
        assertNotNull(p.onHint(of(FocusHint.State.TEXT), 100));
    }
}
