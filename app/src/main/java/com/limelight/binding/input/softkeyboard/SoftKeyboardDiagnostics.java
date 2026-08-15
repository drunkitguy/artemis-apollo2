package com.limelight.binding.input.softkeyboard;

import android.content.Context;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.view.Display;

import java.util.ArrayList;
import java.util.List;

/**
 * Reports what the device actually says about its screens.
 *
 * Dual screen handhelds are not consistent about this. Some expose the second
 * panel as a real {@link Display}, which is what the keyboard needs; others
 * drive both panels from one oversized framebuffer and Android only ever sees
 * a single screen. Guessing which one a given device does is how you end up
 * shipping a fix for the wrong problem, so this prints the raw truth and the
 * decision that was made from it.
 */
public final class SoftKeyboardDiagnostics {

    private SoftKeyboardDiagnostics() {
    }

    /**
     * @param streamDisplayId the screen the stream is rendering on
     * @param preferSecond    the user's second screen preference
     * @param lastOutcome     what the last attempt to show the keyboard did
     */
    public static String report(Context context, int streamDisplayId,
                                boolean preferSecond, String lastOutcome) {
        StringBuilder out = new StringBuilder();

        DisplayManager manager = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
        if (manager == null) {
            return "No DisplayManager available.";
        }

        Display[] all = manager.getDisplays();
        Display[] presentable = manager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION);

        out.append("Device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append("  (Android ").append(Build.VERSION.RELEASE).append(")\n\n");

        out.append("Screens Android reports: ").append(all == null ? 0 : all.length).append('\n');
        out.append("Usable for a second screen window: ")
                .append(presentable == null ? 0 : presentable.length).append("\n\n");

        List<KeyboardDisplayChooser.Candidate> candidates = new ArrayList<>();
        if (all != null) {
            for (Display display : all) {
                Point size = sizeOf(display);
                boolean usable = display.getState() != Display.STATE_OFF;
                candidates.add(new KeyboardDisplayChooser.Candidate(
                        display.getDisplayId(), size.x, size.y, usable));

                out.append("  [").append(display.getDisplayId()).append("] ")
                        .append(display.getName()).append('\n');
                out.append("      ").append(size.x).append('x').append(size.y)
                        .append("   state=").append(stateName(display.getState()));
                if (display.getDisplayId() == streamDisplayId) {
                    out.append("   <- stream is here");
                }
                out.append('\n');
                out.append("      can host a window: ")
                        .append(contains(presentable, display.getDisplayId()) ? "yes" : "no")
                        .append('\n');
            }
        }

        out.append('\n');
        out.append("Second screen setting: ").append(preferSecond ? "on" : "off").append('\n');

        int chosen = KeyboardDisplayChooser.choose(candidates, streamDisplayId);
        out.append("Keyboard would use: ")
                .append(chosen == KeyboardDisplayChooser.NO_DISPLAY
                        ? "an overlay (no separate second screen found)"
                        : ("screen " + chosen))
                .append('\n');

        out.append("Last time it was opened: ")
                .append(lastOutcome == null ? "not opened yet this session" : lastOutcome)
                .append('\n');

        if (all != null && all.length < 2) {
            out.append("\nOnly one screen is reported. On this device the second panel is\n")
                    .append("not a separate Android screen, so no window can be sent to it.\n")
                    .append("The keyboard will dock over the stream instead.\n");
        }

        return out.toString();
    }

    private static boolean contains(Display[] displays, int displayId) {
        if (displays == null) {
            return false;
        }
        for (Display display : displays) {
            if (display.getDisplayId() == displayId) {
                return true;
            }
        }
        return false;
    }

    private static String stateName(int state) {
        switch (state) {
            case Display.STATE_OFF:
                return "off";
            case Display.STATE_ON:
                return "on";
            case Display.STATE_DOZE:
                return "doze";
            case Display.STATE_DOZE_SUSPEND:
                return "doze-suspend";
            default:
                return "unknown(" + state + ")";
        }
    }

    @SuppressWarnings("deprecation")
    private static Point sizeOf(Display display) {
        Point size = new Point();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Display.Mode mode = display.getMode();
            if (mode != null && mode.getPhysicalWidth() > 0) {
                size.set(mode.getPhysicalWidth(), mode.getPhysicalHeight());
                return size;
            }
        }
        display.getRealSize(size);
        return size;
    }
}
