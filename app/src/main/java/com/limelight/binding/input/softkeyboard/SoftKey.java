package com.limelight.binding.input.softkeyboard;

/**
 * One key on a gamepad navigable soft keyboard.
 *
 * Pure data. The only Android dependency is the integer value of a
 * {@code KeyEvent.KEYCODE_*} constant, which javac inlines, so this class and
 * everything built on it runs in a plain JVM test.
 */
public final class SoftKey {

    /** What pressing the key does, beyond sending its key code. */
    public enum Action {
        /** Sends {@link #keyCode}, shifted when the model says so. */
        CHAR,
        /** Cycles the shift state instead of sending anything. */
        SHIFT,
        /** Swaps the letter page for the symbol page and back. */
        PAGE,
        /** Pastes the Android clipboard into the host as text. */
        CLIPBOARD,
        /** Dismisses the keyboard. */
        CLOSE
    }

    /** Face of the key with shift off. */
    public final String label;

    /** Face of the key with shift on. Equal to {@link #label} when shift does nothing. */
    public final String shiftedLabel;

    /** Android key code to translate and send, or {@code 0} for keys that send nothing. */
    public final int keyCode;

    /** Width relative to the other keys in the same row. A plain letter is 1. */
    public final float weight;

    public final Action action;

    public SoftKey(String label, String shiftedLabel, int keyCode, float weight, Action action) {
        if (label == null) {
            throw new IllegalArgumentException("label");
        }
        if (weight <= 0f) {
            throw new IllegalArgumentException("weight must be positive: " + weight);
        }
        this.label = label;
        this.shiftedLabel = shiftedLabel == null ? label : shiftedLabel;
        this.keyCode = keyCode;
        this.weight = weight;
        this.action = action == null ? Action.CHAR : action;
    }

    /** A one-wide key whose face and key code do not change with shift. */
    public static SoftKey plain(String label, int keyCode) {
        return new SoftKey(label, label, keyCode, 1f, Action.CHAR);
    }

    /** A letter or digit: same key code either way, different face. */
    public static SoftKey shifted(String label, String shiftedLabel, int keyCode) {
        return new SoftKey(label, shiftedLabel, keyCode, 1f, Action.CHAR);
    }

    /** A key that sends its code but is wider than a letter. */
    public static SoftKey wide(String label, int keyCode, float weight) {
        return new SoftKey(label, label, keyCode, weight, Action.CHAR);
    }

    /** The face to draw, given the current shift state. */
    public String face(boolean shift) {
        return shift ? shiftedLabel : label;
    }

    /** True when the key sends something to the host rather than only changing local state. */
    public boolean sendsKey() {
        return action == Action.CHAR && keyCode != 0;
    }

    @Override
    public String toString() {
        return "SoftKey(" + label + ")";
    }
}
