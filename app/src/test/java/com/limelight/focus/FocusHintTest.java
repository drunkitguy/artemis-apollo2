package com.limelight.focus;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.limelight.binding.input.softkeyboard.HostFieldFocus;

import org.junit.Test;

import java.util.Random;

/** Pure JVM tests for the focus datagram format. */
public class FocusHintTest {

    private static final String TOKEN = "a1b2c3d4e5f6";

    private static FocusHint.Report parse(String kind, String flags) {
        return FocusHint.parse("VLFOCUS2 " + TOKEN + " " + kind + " " + flags, TOKEN);
    }

    @Test
    public void aWellFormedDatagramParses() {
        assertEquals(FocusHint.State.DIGITS, parse("digits", "0").state);
        assertEquals(FocusHint.State.TEXT, parse("text", "0").state);
        assertEquals(FocusHint.State.NONE, parse("none", "0").state);
        assertEquals(FocusHint.State.PASSWORD, parse("password", "0").state);
        assertEquals(FocusHint.State.UNKNOWN, parse("unknown", "0").state);
    }

    @Test
    public void everyStateExceptUnknownCarriesAHostFieldKind() {
        // The whole point of the second wire format is that it lands in the
        // same place the in-stream one does, so the words have to mean the
        // constants the keyboard already understands rather than new ones.
        assertEquals(HostFieldFocus.KIND_NONE, FocusHint.State.NONE.hostKind());
        assertEquals(HostFieldFocus.KIND_TEXT, FocusHint.State.TEXT.hostKind());
        assertEquals(HostFieldFocus.KIND_NUMERIC, FocusHint.State.DIGITS.hostKind());
        assertEquals(HostFieldFocus.KIND_PASSWORD, FocusHint.State.PASSWORD.hostKind());
        assertEquals(FocusHint.NO_KIND, FocusHint.State.UNKNOWN.hostKind());
    }

    @Test
    public void flagsAreReadAsHexadecimal() {
        assertEquals(0, parse("text", "0").flags);
        assertEquals(HostFieldFocus.FLAG_READ_ONLY, parse("text", "1").flags);
        assertEquals(HostFieldFocus.FLAG_NUMERIC, parse("password", "10").flags);
        assertEquals(HostFieldFocus.FLAG_SOURCE_UIA | HostFieldFocus.FLAG_LOW_CONFIDENCE,
                parse("digits", "c").flags);
        assertEquals(HostFieldFocus.FLAG_SOURCE_UIA | HostFieldFocus.FLAG_LOW_CONFIDENCE,
                parse("digits", "C").flags);
    }

    @Test
    public void anUnrecognisedFlagBitIsKeptRatherThanRejected() {
        // A newer host describing a field more precisely is not speaking a
        // different protocol, and HostFieldFocus already ignores bits it does
        // not know about.
        FocusHint.Report report = parse("text", "80");
        assertNotNull(report);
        assertEquals(0x80, report.flags);
    }

    @Test
    public void surroundingWhitespaceIsTolerated() {
        assertEquals(FocusHint.State.TEXT,
                FocusHint.parse("  VLFOCUS2   " + TOKEN + "   text   0 \n", TOKEN).state);
    }

    @Test
    public void theWrongTokenIsRejected() {
        assertNull(FocusHint.parse("VLFOCUS2 ffffffffffff digits 0", TOKEN));
    }

    @Test
    public void anyoneElsesTrafficIsRejected() {
        // The socket will hear broadcast noise and stray packets. None of it
        // should be able to move the panel.
        assertNull(FocusHint.parse("hello", TOKEN));
        assertNull(FocusHint.parse("", TOKEN));
        assertNull(FocusHint.parse("VLFOCUS9 " + TOKEN + " digits 0", TOKEN));
        assertNull(FocusHint.parse("VLFOCUS1 " + TOKEN + " digits", TOKEN));
        assertNull(FocusHint.parse("VLFOCUS2 " + TOKEN, TOKEN));
        assertNull(FocusHint.parse("VLFOCUS2 " + TOKEN + " digits", TOKEN));
        assertNull(FocusHint.parse("VLFOCUS2 " + TOKEN + " digits 0 extra", TOKEN));
        assertNull(FocusHint.parse(null, TOKEN));
    }

    @Test
    public void ourOwnHelloIsNotAReport() {
        // The hellos go out on the same socket the reports come back on, so a
        // reflected or looped-back one must not be mistaken for a verdict.
        assertNull(FocusHint.parse(FocusHint.hello(TOKEN), TOKEN));
    }

    @Test
    public void anUnknownStateWordIsRejectedRatherThanGuessed() {
        assertNull(parse("numeric", "0"));
        assertNull(parse("tel", "0"));
    }

    @Test
    public void aFlagFieldThatIsNotHexIsRejected() {
        assertNull(parse("text", "zz"));
        assertNull(parse("text", "0x2"));
        assertNull(parse("text", "-1"));
    }

    @Test
    public void anOversizedDatagramIsRejectedWithoutBeingParsed() {
        StringBuilder big = new StringBuilder("VLFOCUS2 " + TOKEN + " digits 0");
        while (big.length() <= FocusHint.MAX_BYTES) {
            big.append(' ');
        }
        assertNull(FocusHint.parse(big.toString(), TOKEN));
    }

    @Test
    public void anEmptyExpectedTokenNeverMatches() {
        // A client that has not generated a token must not accept everything.
        assertNull(FocusHint.parse("VLFOCUS2  digits 0", ""));
        assertNull(FocusHint.parse("VLFOCUS2 x digits 0", null));
    }

    @Test
    public void theHelloCarriesTheTokenSoTheReporterCanRefuseStrangers() {
        assertEquals("VLFOCUS2 " + TOKEN + " hello", FocusHint.hello(TOKEN));
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
