package com.limelight.focus;

import com.limelight.binding.input.softkeyboard.HostFieldFocus;

/**
 * What the host says its focused control wants.
 *
 * The client cannot work this out for itself: it receives encoded video, and
 * the kind of field under the cursor is not in the pixels in any form a
 * machine can read. Windows knows because the control declares an input scope,
 * but that is an in-process API on the host with no network-visible surface.
 * So a small program on the host reads it and says so, and this is what it
 * says.
 *
 * <p>This is the out-of-stream twin of the 0x3003 control packet. A host that
 * speaks 0x3003 needs none of this; a host that cannot be rebuilt - anyone
 * running a stock Sunshine, Apollo or Vibepollo install - runs
 * {@code focus_reporter.exe} instead and it arrives here over UDP. Both ends
 * up in the same place, {@code SoftKeyboardController.applyHostFieldFocus},
 * with the same {@link HostFieldFocus} vocabulary, because there is no reason
 * for the client to have two opinions about what a numeric field is.
 *
 * <p>Wire format, ASCII, one datagram per report, at most {@link #MAX_BYTES}:
 * <pre>
 *   host -&gt; client   VLFOCUS2 &lt;token&gt; &lt;kind&gt; &lt;flags&gt;
 *   client -&gt; host   VLFOCUS2 &lt;token&gt; hello
 * </pre>
 * where {@code kind} is one of {@code none text digits password unknown} and
 * {@code flags} is a hexadecimal mask of the {@code HostFieldFocus.FLAG_*}
 * bits. The hello is what wakes the reporter up; see {@link FocusHintListener}.
 */
public final class FocusHint {

    /** Every datagram starts with this, so stray traffic is rejected cheaply. */
    public static final String MAGIC = "VLFOCUS2";

    /** The word a client sends to say it is streaming and would like reports. */
    public static final String HELLO = "hello";

    /** Longest datagram worth reading. Anything larger is not ours. */
    public static final int MAX_BYTES = 96;

    /** Returned by {@link State#hostKind()} for a state the keyboard cannot act on. */
    public static final int NO_KIND = -1;

    public enum State {
        /** Nothing that takes typing has focus. */
        NONE(HostFieldFocus.KIND_NONE),
        /** A field that takes text. */
        TEXT(HostFieldFocus.KIND_TEXT),
        /** A field that only takes digits. */
        DIGITS(HostFieldFocus.KIND_NUMERIC),
        /** A field whose contents are masked as they are typed. */
        PASSWORD(HostFieldFocus.KIND_PASSWORD),
        /**
         * The host looked and could not tell.
         *
         * Deliberately not a {@code HostFieldFocus} kind. The keyboard only
         * understands states it can act on, and "I do not know" is not one:
         * see {@link FocusHintPolicy}, which swallows it.
         */
        UNKNOWN(NO_KIND);

        private final int hostKind;

        State(int hostKind) {
            this.hostKind = hostKind;
        }

        /**
         * The {@code HostFieldFocus.KIND_*} value this state means, or
         * {@link #NO_KIND} for {@link #UNKNOWN}.
         */
        public int hostKind() {
            return hostKind;
        }
    }

    /** One parsed report: exactly the pair {@code applyHostFieldFocus} takes. */
    public static final class Report {
        public final State state;
        public final int flags;

        public Report(State state, int flags) {
            this.state = state;
            this.flags = flags;
        }

        public int hostKind() {
            return state.hostKind();
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof Report)) {
                return false;
            }
            Report that = (Report) other;
            return state == that.state && flags == that.flags;
        }

        @Override
        public int hashCode() {
            return state.hashCode() * 31 + flags;
        }

        @Override
        public String toString() {
            return state + "/" + Integer.toHexString(flags);
        }
    }

    private FocusHint() {
    }

    /**
     * Parses one datagram.
     *
     * @param payload       the datagram body, trusted only after the token matches
     * @param expectedToken the secret this client generated and the host was given
     * @return the report, or null when the datagram is not ours or the token is
     *         wrong
     */
    public static Report parse(String payload, String expectedToken) {
        if (payload == null || expectedToken == null || expectedToken.isEmpty()) {
            return null;
        }
        if (payload.length() > MAX_BYTES) {
            return null;
        }

        String[] parts = payload.trim().split("\\s+");
        if (parts.length != 4) {
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

        State state = stateFor(parts[2]);
        if (state == null) {
            return null;
        }

        int flags = parseFlags(parts[3]);
        if (flags < 0) {
            return null;
        }

        return new Report(state, flags);
    }

    /** The datagram a client sends to ask the host to start reporting. */
    public static String hello(String token) {
        return MAGIC + " " + token + " " + HELLO;
    }

    private static State stateFor(String word) {
        String state = word.toLowerCase(java.util.Locale.US);
        if ("none".equals(state)) {
            return State.NONE;
        }
        if ("text".equals(state)) {
            return State.TEXT;
        }
        if ("digits".equals(state)) {
            return State.DIGITS;
        }
        if ("password".equals(state)) {
            return State.PASSWORD;
        }
        if ("unknown".equals(state)) {
            return State.UNKNOWN;
        }
        return null;
    }

    /**
     * The flag mask, or -1 when the field is not a small hexadecimal number.
     *
     * Unknown bits are kept rather than rejected. A newer host setting a bit
     * this build has never heard of is describing a field more precisely, not
     * speaking a different protocol, and {@code HostFieldFocus} already ignores
     * what it does not recognise.
     */
    private static int parseFlags(String word) {
        if (word.isEmpty() || word.length() > 2) {
            return -1;
        }
        int value = 0;
        for (int i = 0; i < word.length(); i++) {
            int digit = Character.digit(word.charAt(i), 16);
            if (digit < 0) {
                return -1;
            }
            value = (value << 4) | digit;
        }
        return value;
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
