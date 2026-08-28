package com.limelight.focus;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Random;

/** Pure JVM tests for the focus datagram format. */
public class FocusHintTest {

    private static final String TOKEN = "a1b2c3d4e5f6";

    @Test
    public void aWellFormedDatagramParses() {
        assertEquals(FocusHint.State.DIGITS,
                FocusHint.parse("VLFOCUS1 " + TOKEN + " digits", TOKEN));
        assertEquals(FocusHint.State.TEXT,
                FocusHint.parse("VLFOCUS1 " + TOKEN + " text", TOKEN));
        assertEquals(FocusHint.State.NONE,
                FocusHint.parse("VLFOCUS1 " + TOKEN + " none", TOKEN));
        assertEquals(FocusHint.State.UNKNOWN,
                FocusHint.parse("VLFOCUS1 " + TOKEN + " unknown", TOKEN));
    }

    @Test
    public void surroundingWhitespaceIsTolerated() {
        assertEquals(FocusHint.State.TEXT,
                FocusHint.parse("  VLFOCUS1   " + TOKEN + "   text \n", TOKEN));
    }

    @Test
    public void theWrongTokenIsRejected() {
        assertNull(FocusHint.parse("VLFOCUS1 ffffffffffff digits", TOKEN));
    }

    @Test
    public void anyoneElsesTrafficIsRejected() {
        // The socket will hear broadcast noise and stray packets. None of it
        // should be able to move the panel.
        assertNull(FocusHint.parse("hello", TOKEN));
        assertNull(FocusHint.parse("", TOKEN));
        assertNull(FocusHint.parse("VLFOCUS9 " + TOKEN + " digits", TOKEN));
        assertNull(FocusHint.parse("VLFOCUS1 " + TOKEN, TOKEN));
        assertNull(FocusHint.parse("VLFOCUS1 " + TOKEN + " digits extra", TOKEN));
        assertNull(FocusHint.parse(null, TOKEN));
    }

    @Test
    public void anUnknownStateWordIsRejectedRatherThanGuessed() {
        assertNull(FocusHint.parse("VLFOCUS1 " + TOKEN + " numeric", TOKEN));
    }

    @Test
    public void anOversizedDatagramIsRejectedWithoutBeingParsed() {
        StringBuilder big = new StringBuilder("VLFOCUS1 " + TOKEN + " digits");
        while (big.length() <= FocusHint.MAX_BYTES) {
            big.append(' ');
        }
        assertNull(FocusHint.parse(big.toString(), TOKEN));
    }

    @Test
    public void anEmptyExpectedTokenNeverMatches() {
        // A client that has not generated a token must not accept everything.
        assertNull(FocusHint.parse("VLFOCUS1  digits", ""));
        assertNull(FocusHint.parse("VLFOCUS1 x digits", null));
    }

    @Test
    public void tokensAreTwelveHexCharacters() {
        String token = FocusHint.newToken(new Random(1));
        assertEquals(12, token.length());
        assertTrue(token.matches("[0-9a-f]{12}"));
    }

    @Test
    public void constantTimeCompareStillCompares() {
        assertTrue(FocusHint.constantTimeEquals("abc", "abc"));
        assertFalse(FocusHint.constantTimeEquals("abc", "abd"));
        assertFalse(FocusHint.constantTimeEquals("abc", "ab"));
        assertFalse(FocusHint.constantTimeEquals(null, "ab"));
    }
}
