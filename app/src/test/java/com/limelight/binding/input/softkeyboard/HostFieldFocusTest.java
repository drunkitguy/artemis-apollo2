package com.limelight.binding.input.softkeyboard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Pure JVM tests for the mapping from what the PC reports onto the keyboards
 * this app already has.
 *
 * The whole point of {@link HostFieldFocus} being a plain Java decision table
 * is that this can be run without a device, a second screen or a PC, so the
 * table is checked here rather than by clicking around Windows.
 */
public class HostFieldFocusTest {

    private static SoftKeyboardLayouts.Page page(int kind, int flags) {
        return HostFieldFocus.pageFor(kind, flags);
    }

    // ------------------------------------------------------------ every kind

    @Test
    public void noFieldMeansNoKeyboard() {
        assertNull(page(HostFieldFocus.KIND_NONE, 0));
    }

    @Test
    public void textOpensTheLetters() {
        assertEquals(SoftKeyboardLayouts.Page.LETTERS,
                page(HostFieldFocus.KIND_TEXT, 0));
    }

    @Test
    public void numericOpensTheKeypad() {
        assertEquals(SoftKeyboardLayouts.Page.PIN,
                page(HostFieldFocus.KIND_NUMERIC, 0));
    }

    @Test
    public void passwordOpensTheLettersByDefault() {
        assertEquals(SoftKeyboardLayouts.Page.LETTERS,
                page(HostFieldFocus.KIND_PASSWORD, 0));
    }

    @Test
    public void numericPasswordOpensTheKeypad() {
        // A masked box the host has told us takes digits is a PIN or a CVV,
        // and typing one of those on a QWERTY grid is miserable.
        assertEquals(SoftKeyboardLayouts.Page.PIN,
                page(HostFieldFocus.KIND_PASSWORD, HostFieldFocus.FLAG_NUMERIC));
    }

    @Test
    public void theHostNeverSelectsTheSymbolPage() {
        // The symbol page is a detour off the letters page. Nothing the host
        // can say should land the user in it.
        int[] kinds = {
                HostFieldFocus.KIND_NONE, HostFieldFocus.KIND_TEXT,
                HostFieldFocus.KIND_NUMERIC, HostFieldFocus.KIND_PASSWORD,
        };
        for (int kind : kinds) {
            for (int flags = 0; flags <= 0xFF; flags++) {
                assertFalse("kind " + kind + " flags " + flags,
                        page(kind, flags) == SoftKeyboardLayouts.Page.SYMBOLS);
            }
        }
    }

    // ------------------------------------------------------------- read only

    @Test
    public void readOnlyMeansNoKeyboardWhateverTheKind() {
        int flags = HostFieldFocus.FLAG_READ_ONLY;
        assertNull(page(HostFieldFocus.KIND_NONE, flags));
        assertNull(page(HostFieldFocus.KIND_TEXT, flags));
        assertNull(page(HostFieldFocus.KIND_NUMERIC, flags));
        assertNull(page(HostFieldFocus.KIND_PASSWORD, flags));
        assertNull(page(HostFieldFocus.KIND_PASSWORD,
                flags | HostFieldFocus.FLAG_NUMERIC));
    }

    // ----------------------------------------------------- flags that do not

    @Test
    public void multilineDoesNotChangeThePage() {
        assertEquals(SoftKeyboardLayouts.Page.LETTERS,
                page(HostFieldFocus.KIND_TEXT, HostFieldFocus.FLAG_MULTILINE));
    }

    @Test
    public void uiaSourceDoesNotChangeThePage() {
        assertEquals(SoftKeyboardLayouts.Page.LETTERS,
                page(HostFieldFocus.KIND_TEXT, HostFieldFocus.FLAG_SOURCE_UIA));
        assertEquals(SoftKeyboardLayouts.Page.PIN,
                page(HostFieldFocus.KIND_NUMERIC, HostFieldFocus.FLAG_SOURCE_UIA));
    }

    @Test
    public void lowConfidenceNumericIsStillHonoured() {
        // Not downgraded to letters. The host only guesses at all when its
        // operator turned that tier on, and L1/R1 is one button away when the
        // guess is wrong; silently ignoring the answer would make the setting
        // do nothing at all on exactly the fields it exists for.
        assertEquals(SoftKeyboardLayouts.Page.PIN,
                page(HostFieldFocus.KIND_NUMERIC, HostFieldFocus.FLAG_LOW_CONFIDENCE));
        assertEquals(SoftKeyboardLayouts.Page.PIN,
                page(HostFieldFocus.KIND_NUMERIC,
                        HostFieldFocus.FLAG_LOW_CONFIDENCE | HostFieldFocus.FLAG_SOURCE_UIA));
    }

    @Test
    public void unknownFlagBitsAreIgnoredNotRejected() {
        assertEquals(SoftKeyboardLayouts.Page.LETTERS, page(HostFieldFocus.KIND_TEXT, 0x80));
        assertEquals(SoftKeyboardLayouts.Page.PIN, page(HostFieldFocus.KIND_NUMERIC, 0x60));
        assertEquals(SoftKeyboardLayouts.Page.LETTERS, page(HostFieldFocus.KIND_TEXT, 0xE0));
    }

    @Test
    public void unknownKindsAreTreatedAsNoField() {
        assertNull(page(7, 0));
        assertNull(page(7, HostFieldFocus.FLAG_NUMERIC));
        assertNull(page(255, 0));
    }

    // --------------------------------------------------------------- masking

    @Test
    public void onlyPasswordFieldsMaskTheEcho() {
        assertFalse(HostFieldFocus.masksEcho(HostFieldFocus.KIND_NONE, 0));
        assertFalse(HostFieldFocus.masksEcho(HostFieldFocus.KIND_TEXT, 0));
        assertFalse(HostFieldFocus.masksEcho(HostFieldFocus.KIND_NUMERIC, 0));
        assertTrue(HostFieldFocus.masksEcho(HostFieldFocus.KIND_PASSWORD, 0));
    }

    @Test
    public void passwordMaskingSurvivesEveryFlagCombination() {
        // Masking is what stops the second screen displaying the password in
        // plain text at reading distance. No flag may switch it back off.
        for (int flags = 0; flags <= 0xFF; flags++) {
            assertTrue("flags " + flags,
                    HostFieldFocus.masksEcho(HostFieldFocus.KIND_PASSWORD, flags));
        }
    }

    @Test
    public void nonPasswordKindsNeverMask() {
        for (int flags = 0; flags <= 0xFF; flags++) {
            assertFalse("flags " + flags,
                    HostFieldFocus.masksEcho(HostFieldFocus.KIND_TEXT, flags));
            assertFalse("flags " + flags,
                    HostFieldFocus.masksEcho(HostFieldFocus.KIND_NUMERIC, flags));
        }
    }

    // ----------------------------------------------------------- signed wire

    @Test
    public void signedWireBytesAreWidenedByTheCaller() {
        // The wire carries signed bytes. (byte) 0x83 is -125 in Java, so a flag
        // test written without the widening would go wrong on every bit; the
        // callers widen with & 0xFF first, which is what is exercised here.
        // 0x83 is read only plus multiline plus an unknown top bit, and the
        // read-only bit is the one that decides.
        byte text = (byte) 0x01;
        byte readOnlyMultilineAndUnknown = (byte) 0x83;
        assertNull(page(text & 0xFF, readOnlyMultilineAndUnknown & 0xFF));

        // Same top bit, without read only: still an ordinary text field.
        byte multilineAndUnknown = (byte) 0x82;
        assertEquals(SoftKeyboardLayouts.Page.LETTERS,
                page(text & 0xFF, multilineAndUnknown & 0xFF));
        assertFalse(HostFieldFocus.masksEcho(text & 0xFF, multilineAndUnknown & 0xFF));

        byte password = (byte) 0x03;
        byte topBitAndNumeric = (byte) 0x90;
        assertEquals(SoftKeyboardLayouts.Page.PIN,
                page(password & 0xFF, topBitAndNumeric & 0xFF));
        assertTrue(HostFieldFocus.masksEcho(password & 0xFF, topBitAndNumeric & 0xFF));
    }

    @Test
    public void widenedReadOnlyStillSuppressesTheKeyboard() {
        byte flags = (byte) 0x81;
        assertNull(page(HostFieldFocus.KIND_TEXT, flags & 0xFF));
        assertNull(page(HostFieldFocus.KIND_PASSWORD, flags & 0xFF));
    }

    // ------------------------------------------------------------- reporting

    @Test
    public void describeNamesTheKindTheNotesAndTheOutcome() {
        String numeric = HostFieldFocus.describe(HostFieldFocus.KIND_NUMERIC,
                HostFieldFocus.FLAG_SOURCE_UIA | HostFieldFocus.FLAG_LOW_CONFIDENCE);
        assertEquals("numeric (UIA, low confidence) -> number pad", numeric);

        assertEquals("nothing -> nothing, the panel rests",
                HostFieldFocus.describe(HostFieldFocus.KIND_NONE, 0));

        assertEquals("text (read only) -> nothing, the panel rests",
                HostFieldFocus.describe(HostFieldFocus.KIND_TEXT,
                        HostFieldFocus.FLAG_READ_ONLY));
    }

    @Test
    public void describeSaysWhenTypingIsHidden() {
        assertTrue(HostFieldFocus.describe(HostFieldFocus.KIND_PASSWORD, 0)
                .contains("typing hidden"));
        assertFalse(HostFieldFocus.describe(HostFieldFocus.KIND_TEXT, 0)
                .contains("typing hidden"));
    }

    @Test
    public void describeNeverThrowsOnAnythingTheWireCanCarry() {
        for (int kind = 0; kind <= 0xFF; kind++) {
            for (int flags = 0; flags <= 0xFF; flags += 17) {
                assertTrue(HostFieldFocus.describe(kind, flags).length() > 0);
            }
        }
    }
}
