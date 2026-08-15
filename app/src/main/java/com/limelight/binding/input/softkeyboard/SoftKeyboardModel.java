package com.limelight.binding.input.softkeyboard;

import java.util.List;

/**
 * Focus, shift and page state for the gamepad keyboard.
 *
 * All of it is plain Java on purpose. Navigating a grid whose keys are
 * different widths is the part that is easy to get subtly wrong, so it lives
 * where a JVM test can drive it directly instead of needing a device.
 */
public final class SoftKeyboardModel {

    /** Shift behaves like a phone keyboard: one shot, then locked, then off. */
    public enum Shift {
        OFF,
        /** Applies to the next character key only. */
        ONCE,
        /** Applies until it is turned off. */
        LOCKED
    }

    public enum Direction { UP, DOWN, LEFT, RIGHT }

    /** What the caller should do as a result of a press. */
    public static final class Press {
        /** The key that was pressed. Never null. */
        public final SoftKey key;
        /** Android key code to send, or {@code 0} when the press only changed local state. */
        public final int keyCode;
        /** True when the host should see shift held down with {@link #keyCode}. */
        public final boolean shift;

        Press(SoftKey key, int keyCode, boolean shift) {
            this.key = key;
            this.keyCode = keyCode;
            this.shift = shift;
        }

        /** True when something must actually be sent to the host. */
        public boolean sends() {
            return keyCode != 0;
        }
    }

    private SoftKeyboardLayouts.Page page;
    private List<List<SoftKey>> rows;
    private Shift shift = Shift.OFF;
    private int row;
    private int column;

    public SoftKeyboardModel(SoftKeyboardLayouts.Page page) {
        setPage(page);
    }

    // ------------------------------------------------------------------ state

    public SoftKeyboardLayouts.Page getPage() {
        return page;
    }

    public List<List<SoftKey>> getRows() {
        return rows;
    }

    public Shift getShift() {
        return shift;
    }

    /** True when the next character key should be sent shifted. */
    public boolean isShiftActive() {
        return shift != Shift.OFF;
    }

    public int getRow() {
        return row;
    }

    public int getColumn() {
        return column;
    }

    public SoftKey getFocusedKey() {
        return rows.get(row).get(column);
    }

    /**
     * Swaps the grid. Focus is placed on the first key rather than carried
     * over: the pages have different shapes and landing somewhere unrelated is
     * worse than always landing somewhere predictable.
     */
    public void setPage(SoftKeyboardLayouts.Page page) {
        this.page = page;
        this.rows = SoftKeyboardLayouts.forPage(page);
        this.row = 0;
        this.column = 0;
        if (page == SoftKeyboardLayouts.Page.PIN) {
            // Nothing on the keypad is shifted, and leaving shift latched here
            // would send digits the host reads as punctuation.
            this.shift = Shift.OFF;
        }
    }

    /** Places focus directly, clamping into range. Used when a finger taps a key. */
    public void setFocus(int row, int column) {
        this.row = clamp(row, 0, rows.size() - 1);
        this.column = clamp(column, 0, rows.get(this.row).size() - 1);
    }

    // ------------------------------------------------------------- navigation

    /**
     * Left and right walk the row and stop at its ends. Up and down keep the
     * horizontal position: the target is the key in the next row that covers
     * the centre of the current one, which is what makes a wide space bar or a
     * 1.5 wide shift feel right instead of skipping keys.
     *
     * @return true when focus actually moved
     */
    public boolean move(Direction direction) {
        int startRow = row;
        int startColumn = column;

        switch (direction) {
            case LEFT:
                column = Math.max(0, column - 1);
                break;
            case RIGHT:
                column = Math.min(rows.get(row).size() - 1, column + 1);
                break;
            case UP:
            case DOWN: {
                int target = direction == Direction.UP ? row - 1 : row + 1;
                if (target < 0 || target >= rows.size()) {
                    break;
                }
                float centre = centreOf(rows.get(row), column);
                row = target;
                column = columnCovering(rows.get(row), centre);
                break;
            }
        }

        return row != startRow || column != startColumn;
    }

    /** Fraction of the row's width, 0 to 1, at the middle of the given key. */
    static float centreOf(List<SoftKey> row, int index) {
        float total = 0f;
        for (SoftKey key : row) {
            total += key.weight;
        }
        float start = 0f;
        for (int i = 0; i < index; i++) {
            start += row.get(i).weight;
        }
        return (start + row.get(index).weight / 2f) / total;
    }

    /** Index of the key in the row whose span contains the given fraction. */
    static int columnCovering(List<SoftKey> row, float fraction) {
        float total = 0f;
        for (SoftKey key : row) {
            total += key.weight;
        }
        float edge = 0f;
        for (int i = 0; i < row.size(); i++) {
            edge += row.get(i).weight;
            if (fraction < edge / total) {
                return i;
            }
        }
        return row.size() - 1;
    }

    // ------------------------------------------------------------------ press

    /** Presses the focused key. */
    public Press press() {
        return press(getFocusedKey());
    }

    /**
     * Presses a key, applying and then consuming the shift state.
     *
     * Shift itself and the page key report a press that sends nothing, so the
     * caller can redraw without having to know which keys are local only.
     */
    public Press press(SoftKey key) {
        switch (key.action) {
            case SHIFT:
                shift = nextShift(shift);
                return new Press(key, 0, false);

            case PAGE:
                setPage(page == SoftKeyboardLayouts.Page.LETTERS
                        ? SoftKeyboardLayouts.Page.SYMBOLS
                        : SoftKeyboardLayouts.Page.LETTERS);
                return new Press(key, 0, false);

            case CLIPBOARD:
            case CLOSE:
                return new Press(key, 0, false);

            case CHAR:
            default: {
                boolean withShift = isShiftActive();
                if (shift == Shift.ONCE) {
                    shift = Shift.OFF;
                }
                return new Press(key, key.keyCode, withShift);
            }
        }
    }

    static Shift nextShift(Shift current) {
        switch (current) {
            case OFF:
                return Shift.ONCE;
            case ONCE:
                return Shift.LOCKED;
            case LOCKED:
            default:
                return Shift.OFF;
        }
    }

    private static int clamp(int value, int low, int high) {
        return Math.max(low, Math.min(high, value));
    }
}
