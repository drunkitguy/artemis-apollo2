package com.limelight.focus;

/**
 * What the host says its focused control wants.
 *
 * The client cannot work this out for itself: it receives encoded video, and
 * the kind of field under the cursor is not in the pixels in any form a
 * machine can read. Windows knows because the control declares an input scope,
 * but that is an in-process API on the host with no network-visible surface.
 * So a small program on the host reads it and says so, and this is what it
 * says.
 */
public final class FocusHint {

    /** Every datagram starts with this, so stray traffic is rejected cheaply. */
    public static final String MAGIC = "VLFOCUS1";

    /** Longest datagram worth reading. Anything larger is not ours. */
    public static final int MAX_BYTES = 96;

    public enum State {
        /** Nothing that takes typing has focus. */
        NONE,
        /** A field that takes text. */
        TEXT,
        /** A field that only takes digits. */
        DIGITS,
        /** The host looked and could not tell. */
        UNKNOWN
    }

    private FocusHint() {
    }

    /**
     * Parses one datagram.
     *
     * @param payload       the datagram body, trusted only after the token matches
     * @param expectedToken the secret this client generated and the host was given
     * @return the reported state, or null when the datagram is not ours or the
     *         token is wrong
     */
    public static State parse(String payload, String expectedToken) {
        if (payload == null || expectedToken == null || expectedToken.isEmpty()) {
            return null;
        }
        if (payload.length() > MAX_BYTES) {
            return null;
        }

        String[] parts = payload.trim().split("\\s+");
        if (parts.length != 3) {
            return null;
        }
        if (!MAGIC.equals(parts[0])) {
            return null;
        }

        // Compared in constant time. The token is only guarding which keyboard
        // is on screen, but a comparison that leaks its length by returning
        // early is a bad habit to build in regardless.
        if (!constantTimeEquals(parts[1], expectedToken)) {
            return null;
        }

        String state = parts[2].toLowerCase(java.util.Locale.US);
        if ("none".equals(state)) {
            return State.NONE;
        }
        if ("text".equals(state)) {
            return State.TEXT;
        }
        if ("digits".equals(state)) {
            return State.DIGITS;
        }
        if ("unknown".equals(state)) {
            return State.UNKNOWN;
        }
        return null;
    }

    static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < a.length(); i++) {
            diff |= a.charAt(i) ^ b.charAt(i);
        }
        return diff == 0;
    }

    /** A token to hand to the host. Not a secret worth much, but not guessable. */
    public static String newToken(java.util.Random random) {
        StringBuilder out = new StringBuilder(12);
        for (int i = 0; i < 12; i++) {
            out.append("0123456789abcdef".charAt(random.nextInt(16)));
        }
        return out.toString();
    }
}
