package com.limelight.binding.input.softkeyboard;

/**
 * Turns what the PC says about the focused text field into a keyboard page.
 *
 * The host sends one control packet whenever the focused field changes, and it
 * describes ABSOLUTE STATE: the most recent report is always the current truth.
 * All this class does is decide which of the keyboards the user already has is
 * the right one to show, and whether what gets typed may be echoed back onto
 * the second screen.
 *
 * It is plain Java with no Android imports on purpose, the same way
 * {@link KeyboardDisplayChooser} and {@link SoftKeyboardModel} are: the
 * interesting part is a decision table, and a decision table is worth testing
 * on a JVM rather than on a handheld with a PC attached.
 *
 * <p>The constants are duplicated from {@code MoonBridge.TEXT_FIELD_*}, which
 * in turn mirror {@code ML_TEXT_FIELD_*} in moonlight-common-c's Limelight.h.
 * Duplicating them is deliberate: it keeps this class loadable, and therefore
 * testable, without the native bridge being initialised.
 */
public final class HostFieldFocus {

    // Field kinds. Mirrors MoonBridge.TEXT_FIELD_*.
    public static final int KIND_NONE = 0;
    public static final int KIND_TEXT = 1;
    public static final int KIND_NUMERIC = 2;
    public static final int KIND_PASSWORD = 3;

    // Field flags. Mirrors MoonBridge.TEXT_FIELD_FLAG_*.
    /** The field cannot be typed into, so there is nothing to raise a keyboard for. */
    public static final int FLAG_READ_ONLY = 0x01;
    /** More than one line. Does not change which keyboard is right. */
    public static final int FLAG_MULTILINE = 0x02;
    /** Classified through UI Automation rather than from a Win32 window class. */
    public static final int FLAG_SOURCE_UIA = 0x04;
    /** The host guessed from a label rather than from anything the app published. */
    public static final int FLAG_LOW_CONFIDENCE = 0x08;
    /** Positive numeric evidence. On a password field it means a PIN or a CVV. */
    public static final int FLAG_NUMERIC = 0x10;

    private HostFieldFocus() {
    }

    /**
     * Which keyboard the host is asking for.
     *
     * @param kind  one of the {@code KIND_*} values, already widened out of the
     *              signed wire byte with {@code & 0xFF}
     * @param flags a mask of the {@code FLAG_*} values, likewise widened
     * @return the page to show, or {@code null} when the host is not reporting
     *         a field that can be typed into. Null means "go back to resting",
     *         never "hide the panel".
     */
    public static SoftKeyboardLayouts.Page pageFor(int kind, int flags) {
        // A read-only field takes no input, so raising a keyboard over it would
        // cost the trackpad for nothing. The flag still travels on the wire so
        // the report in the game menu can explain what happened.
        if ((flags & FLAG_READ_ONLY) != 0) {
            return null;
        }

        switch (kind) {
            case KIND_TEXT:
                return SoftKeyboardLayouts.Page.LETTERS;

            case KIND_NUMERIC:
                // Honoured even when the host says it is a low confidence guess.
                // The host only guesses at all when its operator turned that
                // tier on, and L1/R1 is one button away if it guessed wrong.
                return SoftKeyboardLayouts.Page.PIN;

            case KIND_PASSWORD:
                // A masked field is usually a password, but a PIN entry box and
                // a CVV box are masked too, and typing those on a QWERTY grid
                // is miserable. The host says which by setting FLAG_NUMERIC.
                return (flags & FLAG_NUMERIC) != 0
                        ? SoftKeyboardLayouts.Page.PIN
                        : SoftKeyboardLayouts.Page.LETTERS;

            case KIND_NONE:
            default:
                // An unknown kind from a newer host is treated as no field
                // rather than rejected. Doing nothing is always safe here.
                return null;
        }
    }

    /**
     * Whether the on-screen echo must be replaced with bullets.
     *
     * This branch's keyboard keeps a local mirror of everything typed and paints
     * it across the second screen so the user can see what they sent without
     * looking up at the TV. That is a good feature and a terrible one to point
     * at a password box: the handheld ends up displaying the password in plain
     * text, at reading distance, to whoever is in the room.
     *
     * Deliberately keyed on the kind alone. A read-only password field will not
     * raise a keyboard at all, but if anything else ever consults this the
     * answer that leaks nothing is the right default.
     */
    public static boolean masksEcho(int kind, int flags) {
        return kind == KIND_PASSWORD;
    }

    /**
     * One line of plain English for the game menu's keyboard report, so that a
     * "it opened the wrong keyboard" complaint can be diagnosed without a log.
     */
    public static String describe(int kind, int flags) {
        StringBuilder out = new StringBuilder();
        out.append(kindName(kind));

        StringBuilder notes = new StringBuilder();
        appendNote(notes, (flags & FLAG_READ_ONLY) != 0, "read only");
        appendNote(notes, (flags & FLAG_MULTILINE) != 0, "multiline");
        appendNote(notes, (flags & FLAG_SOURCE_UIA) != 0, "UIA");
        appendNote(notes, (flags & FLAG_LOW_CONFIDENCE) != 0, "low confidence");
        appendNote(notes, (flags & FLAG_NUMERIC) != 0, "digits only");
        if (notes.length() > 0) {
            out.append(" (").append(notes).append(')');
        }

        SoftKeyboardLayouts.Page page = pageFor(kind, flags);
        out.append(" -> ");
        if (page == SoftKeyboardLayouts.Page.PIN) {
            out.append("number pad");
        } else if (page == null) {
            out.append("nothing, the panel rests");
        } else {
            out.append("letters");
        }

        if (masksEcho(kind, flags)) {
            out.append(", typing hidden");
        }
        return out.toString();
    }

    private static void appendNote(StringBuilder notes, boolean present, String note) {
        if (!present) {
            return;
        }
        if (notes.length() > 0) {
            notes.append(", ");
        }
        notes.append(note);
    }

    private static String kindName(int kind) {
        switch (kind) {
            case KIND_NONE:
                return "nothing";
            case KIND_TEXT:
                return "text";
            case KIND_NUMERIC:
                return "numeric";
            case KIND_PASSWORD:
                return "password";
            default:
                return "an unknown kind (" + kind + ")";
        }
    }
}
