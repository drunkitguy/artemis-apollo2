package com.limelight.binding.input.softkeyboard;

import android.view.KeyEvent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The key grids the gamepad keyboard can show.
 *
 * Only the integer values of {@code KeyEvent.KEYCODE_*} are used, and javac
 * inlines those, so nothing here loads an Android class at runtime.
 */
public final class SoftKeyboardLayouts {

    /** Which grid is on screen. */
    public enum Page {
        /** Letters, with the symbol page one key away. */
        LETTERS,
        /** Digits, punctuation and the navigation keys. */
        SYMBOLS,
        /** Digits only, laid out as a phone keypad. Has no other page. */
        PIN
    }

    private SoftKeyboardLayouts() {
    }

    // Faces for keys that are not a letter. Kept here so the view never invents one.
    public static final String FACE_SHIFT = "⇧";        // upwards white arrow
    public static final String FACE_BACKSPACE = "⌫";    // erase to the left
    public static final String FACE_ENTER = "↵";        // carriage return arrow
    public static final String FACE_SPACE = "␣";        // open box
    public static final String FACE_CLOSE = "✕";        // multiplication x
    public static final String FACE_SYMBOLS = "?123";
    public static final String FACE_LETTERS = "ABC";

    private static SoftKey shiftKey() {
        return new SoftKey(FACE_SHIFT, FACE_SHIFT, KeyEvent.KEYCODE_SHIFT_LEFT, 1.5f, SoftKey.Action.SHIFT);
    }

    private static SoftKey backspaceKey() {
        return new SoftKey(FACE_BACKSPACE, FACE_BACKSPACE, KeyEvent.KEYCODE_DEL, 1.5f, SoftKey.Action.CHAR);
    }

    private static SoftKey clipboardKey() {
        // A glyph rather than an emoji: emoji render at wildly different sizes
        // across the handhelds this runs on and break the row's baseline.
        return new SoftKey("⎘", "⎘", 0, 1f, SoftKey.Action.CLIPBOARD);
    }

    private static SoftKey closeKey(float weight) {
        return new SoftKey(FACE_CLOSE, FACE_CLOSE, 0, weight, SoftKey.Action.CLOSE);
    }

    private static SoftKey pageKey(String face) {
        return new SoftKey(face, face, 0, 1.5f, SoftKey.Action.PAGE);
    }

    /** The bottom row, which is the same shape on the letter and symbol pages. */
    private static List<SoftKey> utilityRow(String pageFace) {
        return Arrays.asList(
                pageKey(pageFace),
                clipboardKey(),
                SoftKey.shifted(",", "<", KeyEvent.KEYCODE_COMMA),
                new SoftKey(FACE_SPACE, FACE_SPACE, KeyEvent.KEYCODE_SPACE, 3f, SoftKey.Action.CHAR),
                SoftKey.shifted(".", ">", KeyEvent.KEYCODE_PERIOD),
                new SoftKey(FACE_ENTER, FACE_ENTER, KeyEvent.KEYCODE_ENTER, 1.5f, SoftKey.Action.CHAR),
                closeKey(1f));
    }

    private static SoftKey letter(char lower, int keyCode) {
        return SoftKey.shifted(String.valueOf(lower), String.valueOf(Character.toUpperCase(lower)), keyCode);
    }

    /** QWERTY, lower case with a shift key. Four rows. */
    public static List<List<SoftKey>> letters() {
        List<List<SoftKey>> rows = new ArrayList<>();

        rows.add(Arrays.asList(
                letter('q', KeyEvent.KEYCODE_Q), letter('w', KeyEvent.KEYCODE_W),
                letter('e', KeyEvent.KEYCODE_E), letter('r', KeyEvent.KEYCODE_R),
                letter('t', KeyEvent.KEYCODE_T), letter('y', KeyEvent.KEYCODE_Y),
                letter('u', KeyEvent.KEYCODE_U), letter('i', KeyEvent.KEYCODE_I),
                letter('o', KeyEvent.KEYCODE_O), letter('p', KeyEvent.KEYCODE_P)));

        rows.add(Arrays.asList(
                letter('a', KeyEvent.KEYCODE_A), letter('s', KeyEvent.KEYCODE_S),
                letter('d', KeyEvent.KEYCODE_D), letter('f', KeyEvent.KEYCODE_F),
                letter('g', KeyEvent.KEYCODE_G), letter('h', KeyEvent.KEYCODE_H),
                letter('j', KeyEvent.KEYCODE_J), letter('k', KeyEvent.KEYCODE_K),
                letter('l', KeyEvent.KEYCODE_L)));

        rows.add(Arrays.asList(
                shiftKey(),
                letter('z', KeyEvent.KEYCODE_Z), letter('x', KeyEvent.KEYCODE_X),
                letter('c', KeyEvent.KEYCODE_C), letter('v', KeyEvent.KEYCODE_V),
                letter('b', KeyEvent.KEYCODE_B), letter('n', KeyEvent.KEYCODE_N),
                letter('m', KeyEvent.KEYCODE_M),
                backspaceKey()));

        rows.add(utilityRow(FACE_SYMBOLS));

        return freeze(rows);
    }

    /**
     * Digits, punctuation and the navigation keys that a game or a text field
     * actually needs. Shift gives the US shifted face of each punctuation key,
     * which is why the shifted labels are spelled out rather than derived.
     */
    public static List<List<SoftKey>> symbols() {
        List<List<SoftKey>> rows = new ArrayList<>();

        rows.add(Arrays.asList(
                SoftKey.shifted("1", "!", KeyEvent.KEYCODE_1),
                SoftKey.shifted("2", "@", KeyEvent.KEYCODE_2),
                SoftKey.shifted("3", "#", KeyEvent.KEYCODE_3),
                SoftKey.shifted("4", "$", KeyEvent.KEYCODE_4),
                SoftKey.shifted("5", "%", KeyEvent.KEYCODE_5),
                SoftKey.shifted("6", "^", KeyEvent.KEYCODE_6),
                SoftKey.shifted("7", "&", KeyEvent.KEYCODE_7),
                SoftKey.shifted("8", "*", KeyEvent.KEYCODE_8),
                SoftKey.shifted("9", "(", KeyEvent.KEYCODE_9),
                SoftKey.shifted("0", ")", KeyEvent.KEYCODE_0)));

        rows.add(Arrays.asList(
                SoftKey.shifted("-", "_", KeyEvent.KEYCODE_MINUS),
                SoftKey.shifted("=", "+", KeyEvent.KEYCODE_EQUALS),
                SoftKey.shifted("[", "{", KeyEvent.KEYCODE_LEFT_BRACKET),
                SoftKey.shifted("]", "}", KeyEvent.KEYCODE_RIGHT_BRACKET),
                SoftKey.shifted("\\", "|", KeyEvent.KEYCODE_BACKSLASH),
                SoftKey.shifted(";", ":", KeyEvent.KEYCODE_SEMICOLON),
                SoftKey.shifted("'", "\"", KeyEvent.KEYCODE_APOSTROPHE),
                SoftKey.shifted("`", "~", KeyEvent.KEYCODE_GRAVE),
                SoftKey.shifted("/", "?", KeyEvent.KEYCODE_SLASH),
                SoftKey.plain("Esc", KeyEvent.KEYCODE_ESCAPE)));

        rows.add(Arrays.asList(
                shiftKey(),
                SoftKey.plain("Tab", KeyEvent.KEYCODE_TAB),
                SoftKey.plain("←", KeyEvent.KEYCODE_DPAD_LEFT),
                SoftKey.plain("↑", KeyEvent.KEYCODE_DPAD_UP),
                SoftKey.plain("↓", KeyEvent.KEYCODE_DPAD_DOWN),
                SoftKey.plain("→", KeyEvent.KEYCODE_DPAD_RIGHT),
                SoftKey.plain("Home", KeyEvent.KEYCODE_MOVE_HOME),
                SoftKey.plain("End", KeyEvent.KEYCODE_MOVE_END),
                backspaceKey()));

        rows.add(utilityRow(FACE_LETTERS));

        return freeze(rows);
    }

    /**
     * A phone style keypad for fields that only take digits.
     *
     * The digits are the top row key codes rather than the numpad ones on
     * purpose: numpad digits only produce characters when the host has NumLock
     * on, and a PIN box is exactly where that failure is hardest to diagnose.
     */
    public static List<List<SoftKey>> pinPad() {
        List<List<SoftKey>> rows = new ArrayList<>();

        rows.add(Arrays.asList(
                SoftKey.plain("1", KeyEvent.KEYCODE_1),
                SoftKey.plain("2", KeyEvent.KEYCODE_2),
                SoftKey.plain("3", KeyEvent.KEYCODE_3)));
        rows.add(Arrays.asList(
                SoftKey.plain("4", KeyEvent.KEYCODE_4),
                SoftKey.plain("5", KeyEvent.KEYCODE_5),
                SoftKey.plain("6", KeyEvent.KEYCODE_6)));
        rows.add(Arrays.asList(
                SoftKey.plain("7", KeyEvent.KEYCODE_7),
                SoftKey.plain("8", KeyEvent.KEYCODE_8),
                SoftKey.plain("9", KeyEvent.KEYCODE_9)));
        rows.add(Arrays.asList(
                new SoftKey(FACE_BACKSPACE, FACE_BACKSPACE, KeyEvent.KEYCODE_DEL, 1f, SoftKey.Action.CHAR),
                SoftKey.plain("0", KeyEvent.KEYCODE_0),
                new SoftKey(FACE_ENTER, FACE_ENTER, KeyEvent.KEYCODE_ENTER, 1f, SoftKey.Action.CHAR)));
        rows.add(Collections.singletonList(closeKey(3f)));

        return freeze(rows);
    }

    public static List<List<SoftKey>> forPage(Page page) {
        switch (page) {
            case SYMBOLS:
                return symbols();
            case PIN:
                return pinPad();
            case LETTERS:
            default:
                return letters();
        }
    }

    private static List<List<SoftKey>> freeze(List<List<SoftKey>> rows) {
        List<List<SoftKey>> out = new ArrayList<>(rows.size());
        for (List<SoftKey> row : rows) {
            out.add(Collections.unmodifiableList(new ArrayList<>(row)));
        }
        return Collections.unmodifiableList(out);
    }
}
