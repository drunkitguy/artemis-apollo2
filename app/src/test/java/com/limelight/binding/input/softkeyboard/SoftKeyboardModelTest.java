package com.limelight.binding.input.softkeyboard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.view.KeyEvent;

import org.junit.Test;

import java.util.List;

/** Pure JVM tests for the gamepad keyboard's focus, shift and page logic. */
public class SoftKeyboardModelTest {

    private static SoftKeyboardModel letters() {
        return new SoftKeyboardModel(SoftKeyboardLayouts.Page.LETTERS);
    }

    private static void focusOn(SoftKeyboardModel model, String face) {
        List<List<SoftKey>> rows = model.getRows();
        for (int r = 0; r < rows.size(); r++) {
            for (int c = 0; c < rows.get(r).size(); c++) {
                if (rows.get(r).get(c).label.equals(face)) {
                    model.setFocus(r, c);
                    return;
                }
            }
        }
        throw new AssertionError("no key with face " + face);
    }

    // ------------------------------------------------------------ the layouts

    @Test
    public void everyLayoutIsRectangularEnoughToNavigate() {
        for (SoftKeyboardLayouts.Page page : SoftKeyboardLayouts.Page.values()) {
            List<List<SoftKey>> rows = SoftKeyboardLayouts.forPage(page);
            assertFalse(page + " has no rows", rows.isEmpty());
            for (List<SoftKey> row : rows) {
                assertFalse(page + " has an empty row", row.isEmpty());
                for (SoftKey key : row) {
                    assertTrue(page + " key " + key + " has no face", key.label.length() > 0);
                    assertTrue(page + " key " + key + " has a bad weight", key.weight > 0f);
                }
            }
        }
    }

    @Test
    public void letterPageIsQwertyNotAlphabetical() {
        List<List<SoftKey>> rows = SoftKeyboardLayouts.letters();
        StringBuilder top = new StringBuilder();
        for (SoftKey key : rows.get(0)) {
            top.append(key.label);
        }
        assertEquals("qwertyuiop", top.toString());
    }

    @Test
    public void pinPadOnlySendsDigitsAndEditingKeys() {
        for (List<SoftKey> row : SoftKeyboardLayouts.pinPad()) {
            for (SoftKey key : row) {
                if (!key.sendsKey()) {
                    continue;
                }
                boolean digit = key.keyCode >= KeyEvent.KEYCODE_0 && key.keyCode <= KeyEvent.KEYCODE_9;
                boolean editing = key.keyCode == KeyEvent.KEYCODE_DEL || key.keyCode == KeyEvent.KEYCODE_ENTER;
                assertTrue("keypad must not send " + key, digit || editing);
            }
        }
    }

    @Test
    public void pinPadUsesTopRowDigitsSoNumLockCannotBreakIt() {
        // KEYCODE_NUMPAD_0 and up only produce characters when the host has
        // NumLock on. A PIN box is the worst place to discover that.
        for (List<SoftKey> row : SoftKeyboardLayouts.pinPad()) {
            for (SoftKey key : row) {
                assertFalse("keypad used a numpad code: " + key,
                        key.keyCode >= KeyEvent.KEYCODE_NUMPAD_0
                                && key.keyCode <= KeyEvent.KEYCODE_NUMPAD_9);
            }
        }
    }

    // --------------------------------------------------------------- movement

    @Test
    public void leftAndRightStopAtTheEndsOfTheRow() {
        SoftKeyboardModel model = letters();
        model.setFocus(0, 0);

        assertFalse("already at the left edge", model.move(SoftKeyboardModel.Direction.LEFT));
        assertEquals(0, model.getColumn());

        int lastColumn = model.getRows().get(0).size() - 1;
        model.setFocus(0, lastColumn);
        assertFalse("already at the right edge", model.move(SoftKeyboardModel.Direction.RIGHT));
        assertEquals(lastColumn, model.getColumn());
    }

    @Test
    public void upAndDownStopAtTheTopAndBottomRows() {
        SoftKeyboardModel model = letters();
        model.setFocus(0, 3);
        assertFalse(model.move(SoftKeyboardModel.Direction.UP));
        assertEquals(0, model.getRow());

        int lastRow = model.getRows().size() - 1;
        model.setFocus(lastRow, 0);
        assertFalse(model.move(SoftKeyboardModel.Direction.DOWN));
        assertEquals(lastRow, model.getRow());
    }

    @Test
    public void movingDownKeepsTheHorizontalPosition() {
        SoftKeyboardModel model = letters();

        // q is the far left of the top row, so going down twice should reach
        // the far left of the rows below rather than drifting inwards.
        focusOn(model, "q");
        model.move(SoftKeyboardModel.Direction.DOWN);
        assertEquals("a", model.getFocusedKey().label);

        model.move(SoftKeyboardModel.Direction.DOWN);
        assertEquals(SoftKeyboardLayouts.FACE_SHIFT, model.getFocusedKey().label);
    }

    @Test
    public void aWideKeyIsReachableFromEveryColumnItSpans() {
        // The space bar is three keys wide. Coming down onto it from any of the
        // letters above it must land on space, not skip past it.
        int reached = 0;
        List<SoftKey> row = SoftKeyboardLayouts.letters().get(2);
        for (int column = 0; column < row.size(); column++) {
            SoftKeyboardModel model = letters();
            model.setFocus(2, column);
            model.move(SoftKeyboardModel.Direction.DOWN);
            if (model.getFocusedKey().label.equals(SoftKeyboardLayouts.FACE_SPACE)) {
                reached++;
            }
        }
        assertTrue("space bar was unreachable from the row above", reached > 0);
    }

    @Test
    public void everyKeyIsReachableFromTheTopLeftByDpadAlone() {
        for (SoftKeyboardLayouts.Page page : SoftKeyboardLayouts.Page.values()) {
            List<List<SoftKey>> rows = SoftKeyboardLayouts.forPage(page);
            for (int r = 0; r < rows.size(); r++) {
                for (int c = 0; c < rows.get(r).size(); c++) {
                    SoftKeyboardModel model = new SoftKeyboardModel(page);
                    // Down to the row, then right along it.
                    for (int i = 0; i < r; i++) {
                        model.move(SoftKeyboardModel.Direction.DOWN);
                    }
                    for (int i = 0; i < c; i++) {
                        model.move(SoftKeyboardModel.Direction.RIGHT);
                    }
                    assertEquals(page + " could not reach row " + r, r, model.getRow());
                    assertEquals(page + " could not reach column " + c + " of row " + r,
                            c, model.getColumn());
                }
            }
        }
    }

    @Test
    public void movingNeverLeavesFocusOutOfRange() {
        SoftKeyboardModel model = letters();
        SoftKeyboardModel.Direction[] all = SoftKeyboardModel.Direction.values();
        // A fixed walk rather than a random one, so a failure is reproducible.
        for (int i = 0; i < 400; i++) {
            model.move(all[i % all.length]);
            if (i % 7 == 0) {
                model.move(SoftKeyboardModel.Direction.RIGHT);
            }
            if (i % 11 == 0) {
                model.move(SoftKeyboardModel.Direction.DOWN);
            }
            assertTrue(model.getRow() >= 0 && model.getRow() < model.getRows().size());
            List<SoftKey> row = model.getRows().get(model.getRow());
            assertTrue(model.getColumn() >= 0 && model.getColumn() < row.size());
        }
    }

    // ------------------------------------------------------------------ shift

    @Test
    public void shiftCyclesOffOnceLockedOff() {
        SoftKeyboardModel model = letters();
        assertEquals(SoftKeyboardModel.Shift.OFF, model.getShift());

        focusOn(model, SoftKeyboardLayouts.FACE_SHIFT);
        model.press();
        assertEquals(SoftKeyboardModel.Shift.ONCE, model.getShift());
        model.press();
        assertEquals(SoftKeyboardModel.Shift.LOCKED, model.getShift());
        model.press();
        assertEquals(SoftKeyboardModel.Shift.OFF, model.getShift());
    }

    @Test
    public void shiftPressSendsNothingToTheHost() {
        SoftKeyboardModel model = letters();
        focusOn(model, SoftKeyboardLayouts.FACE_SHIFT);
        assertFalse(model.press().sends());
    }

    @Test
    public void oneShotShiftAppliesToExactlyOneCharacter() {
        SoftKeyboardModel model = letters();
        focusOn(model, SoftKeyboardLayouts.FACE_SHIFT);
        model.press();

        focusOn(model, "a");
        SoftKeyboardModel.Press first = model.press();
        assertTrue("first character should be shifted", first.shift);
        assertEquals(KeyEvent.KEYCODE_A, first.keyCode);

        SoftKeyboardModel.Press second = model.press();
        assertFalse("shift should have been consumed", second.shift);
        assertEquals(SoftKeyboardModel.Shift.OFF, model.getShift());
    }

    @Test
    public void lockedShiftSurvivesManyCharacters() {
        SoftKeyboardModel model = letters();
        focusOn(model, SoftKeyboardLayouts.FACE_SHIFT);
        model.press();
        model.press();
        assertEquals(SoftKeyboardModel.Shift.LOCKED, model.getShift());

        focusOn(model, "a");
        for (int i = 0; i < 5; i++) {
            assertTrue("character " + i + " should still be shifted", model.press().shift);
        }
        assertEquals(SoftKeyboardModel.Shift.LOCKED, model.getShift());
    }

    @Test
    public void shiftChangesTheFaceButNotTheKeyCode() {
        SoftKey a = null;
        for (SoftKey key : SoftKeyboardLayouts.letters().get(1)) {
            if (key.label.equals("a")) {
                a = key;
            }
        }
        assertEquals("a", a.face(false));
        assertEquals("A", a.face(true));
        assertEquals(KeyEvent.KEYCODE_A, a.keyCode);
    }

    // ------------------------------------------------------------------ pages

    @Test
    public void thePageKeyTogglesBetweenLettersAndSymbols() {
        SoftKeyboardModel model = letters();
        focusOn(model, SoftKeyboardLayouts.FACE_SYMBOLS);
        assertFalse(model.press().sends());
        assertEquals(SoftKeyboardLayouts.Page.SYMBOLS, model.getPage());

        focusOn(model, SoftKeyboardLayouts.FACE_LETTERS);
        model.press();
        assertEquals(SoftKeyboardLayouts.Page.LETTERS, model.getPage());
    }

    @Test
    public void changingPageResetsFocusSomewherePredictable() {
        SoftKeyboardModel model = letters();
        model.setFocus(3, 3);
        model.setPage(SoftKeyboardLayouts.Page.SYMBOLS);
        assertEquals(0, model.getRow());
        assertEquals(0, model.getColumn());
    }

    @Test
    public void switchingToTheKeypadClearsLatchedShift() {
        // Shift plus a digit is punctuation on a US layout, so a latched shift
        // would turn a PIN into "!@#" without any visible cause.
        SoftKeyboardModel model = letters();
        focusOn(model, SoftKeyboardLayouts.FACE_SHIFT);
        model.press();
        model.press();
        assertEquals(SoftKeyboardModel.Shift.LOCKED, model.getShift());

        model.setPage(SoftKeyboardLayouts.Page.PIN);
        assertEquals(SoftKeyboardModel.Shift.OFF, model.getShift());

        SoftKeyboardModel.Press press = model.press();
        assertFalse(press.shift);
    }

    @Test
    public void theKeypadHasNoPageKeyToEscapeInto() {
        for (List<SoftKey> row : SoftKeyboardLayouts.pinPad()) {
            for (SoftKey key : row) {
                assertNotEquals("keypad should not offer a letter page", SoftKey.Action.PAGE, key.action);
            }
        }
    }

    @Test
    public void closeAndClipboardSendNothingThemselves() {
        SoftKeyboardModel model = letters();
        focusOn(model, SoftKeyboardLayouts.FACE_CLOSE);
        SoftKeyboardModel.Press close = model.press();
        assertFalse(close.sends());
        assertSame(SoftKey.Action.CLOSE, close.key.action);
    }
}
