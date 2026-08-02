package com.limelight.utils;

import android.app.ActivityOptions;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.Bundle;
import android.view.Display;
import android.widget.Toast;

import androidx.annotation.RequiresApi;

import com.limelight.AppView;
import com.limelight.Game;
import com.limelight.LimeLog;
import com.limelight.R;
import com.limelight.ShortcutTrampoline;
import com.limelight.binding.PlatformBinding;
import com.limelight.computers.ComputerManagerService;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.HostHttpResponseException;
import com.limelight.nvstream.http.NvApp;
import com.limelight.nvstream.http.NvHTTP;
import com.limelight.nvstream.jni.MoonBridge;
import com.limelight.preferences.PreferenceConfiguration;

import org.xmlpull.v1.XmlPullParserException;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.UnknownHostException;
import java.security.cert.CertificateEncodingException;
import java.util.ArrayList;

public class ServerHelper {
    public static final String CONNECTION_TEST_SERVER = "android.conntest.moonlight-stream.org";

    public static ComputerDetails.AddressTuple getCurrentAddressFromComputer(ComputerDetails computer) throws IOException {
        if (computer.activeAddress == null) {
            throw new IOException("No active address for "+computer.name);
        }
        return computer.activeAddress;
    }

    public static Intent createPcShortcutIntent(Activity parent, ComputerDetails computer) {
        Intent i = new Intent(parent, ShortcutTrampoline.class);
        i.putExtra(AppView.NAME_EXTRA, computer.name);
        i.putExtra(AppView.UUID_EXTRA, computer.uuid);
        i.setAction(Intent.ACTION_DEFAULT);
        return i;
    }

    public static Intent createAppShortcutIntent(Activity parent, ComputerDetails computer, NvApp app) {
        Intent i = new Intent(parent, ShortcutTrampoline.class);
        i.putExtra(AppView.NAME_EXTRA, computer.name);
        i.putExtra(AppView.UUID_EXTRA, computer.uuid);
        i.putExtra(Game.EXTRA_APP_NAME, app.getAppName());
        i.putExtra(Game.EXTRA_APP_UUID, app.getAppUUID());
        i.putExtra(Game.EXTRA_APP_ID, ""+app.getAppId());
        i.putExtra(Game.EXTRA_APP_HDR, app.isHdrSupported());
        i.setAction(Intent.ACTION_DEFAULT);
        return i;
    }
    /**
     * Score used to rank displays: pixels multiplied by refresh rate.
     *
     * <p>Both terms matter for streaming — a bigger panel is worth more, and so
     * is a faster one — and multiplying them gives a single number with no
     * arbitrary weighting to defend. Returns 0 for a display we cannot measure,
     * so an unmeasurable display never wins a comparison.
     */
    private static long displayScore(Display display) {
        if (display == null) {
            return 0;
        }

        int width;
        int height;
        float refreshRate;

        Display.Mode mode = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? display.getMode() : null;
        if (mode != null) {
            // Physical size, so a display running a scaled mode is still judged
            // on what the panel can actually do.
            width = mode.getPhysicalWidth();
            height = mode.getPhysicalHeight();
            refreshRate = mode.getRefreshRate();
        }
        else {
            android.graphics.Point size = new android.graphics.Point();
            display.getRealSize(size);
            width = size.x;
            height = size.y;
            refreshRate = display.getRefreshRate();
        }

        if (width <= 0 || height <= 0) {
            return 0;
        }
        if (refreshRate < 1f) {
            // Some displays report 0; treat as 60 rather than scoring them zero.
            refreshRate = 60f;
        }

        return (long) width * (long) height * (long) refreshRate;
    }

    /**
     * True if the framework marks this display as a presentation display.
     *
     * <p><b>This is recorded and reported but no longer decides anything on its
     * own.</b> AOSP's {@code LocalDisplayAdapter} sets {@code FLAG_PRESENTATION}
     * for {@code TYPE_EXTERNAL} displays and not for built-in panels, so it
     * looks like the right way to tell "the monitor the user plugged in" from
     * "the handheld's own second screen". On the AYN Thor Pro that reasoning
     * appears not to hold: with this flag deciding the choice, the stream still
     * landed on the small bottom panel, which is what we would see if the OEM
     * marks that built-in panel as a presentation display.
     *
     * <p>It is now only a tiebreak between displays of near-equal capability.
     * See {@link #getStreamDisplay} for the argument.
     */
    private static boolean isPresentationDisplay(Display display) {
        return display != null && (display.getFlags() & Display.FLAG_PRESENTATION) != 0;
    }

    private static boolean isUsableDisplay(Display display) {
        return display != null && display.isValid() && display.getState() != Display.STATE_OFF;
    }

    /**
     * Two displays whose scores are within this fraction of each other are
     * treated as equally capable, and presentation-ness breaks the tie.
     */
    private static final double SCORE_TIE_FRACTION = 0.15;

    /**
     * Human-readable record of the last {@link #getStreamDisplay} decision.
     *
     * <p>Written on whatever thread resolves the display and read at session end
     * for the trace metadata and for the on-screen summary. It is a single
     * reference assignment of an immutable String, so a stale read is the worst
     * that can happen and it costs nothing to make it volatile.
     *
     * <p>This exists because <b>the target device has no working adb</b>:
     * {@code LimeLog} output is unreachable, so the only way anyone can see why
     * a display was chosen is to put it somewhere the user can retrieve — the
     * trace CSV, or a Toast. Diagnosing this by guesswork has already cost two
     * failed attempts.
     */
    private static volatile String lastDisplaySelection = "not resolved yet";

    /** Companion record for the control-surface decision. See lastDisplaySelection. */
    private static volatile String lastControlSelection = "not resolved yet";

    /**
     * Full description of the last display decision, for diagnostics.
     *
     * <p>Includes the control-surface half, so a single CSV field answers both
     * "where did the stream go" and "where were the controls sent" — the two
     * questions that have each cost a round to answer.
     */
    public static String getLastDisplaySelection() {
        return lastDisplaySelection + " || control: " + lastControlSelection;
    }

    /** Compact per-display description: id, size, refresh, flags, score. */
    private static String describeDisplay(Display d) {
        if (d == null) {
            return "none";
        }
        int w = 0;
        int h = 0;
        float hz = 0;
        Display.Mode mode = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? d.getMode() : null;
        if (mode != null) {
            w = mode.getPhysicalWidth();
            h = mode.getPhysicalHeight();
            hz = mode.getRefreshRate();
        }
        return "id=" + d.getDisplayId()
                + " " + w + "x" + h + "@" + Math.round(hz)
                + " presentation=" + (isPresentationDisplay(d) ? 1 : 0)
                + " state=" + d.getState()
                + " valid=" + (d.isValid() ? 1 : 0)
                + " flags=0x" + Integer.toHexString(d.getFlags())
                + " score=" + displayScore(d);
    }

    /**
     * Picks the display to stream on.
     *
     * <p>Replaces a "first display whose ID is not 0" heuristic, which assumed
     * phone-plus-monitor. On a dual-screen handheld it inverts: the AYN Thor
     * Pro reports its 6" 1080x1920 120 Hz panel as display 0 and its 3.92"
     * 1080x1240 60 Hz panel as the secondary, so "full external display mode"
     * streamed to the worse screen.
     *
     * <h3>Why capability decides, and presentation-ness only breaks ties</h3>
     * An earlier version made {@code FLAG_PRESENTATION} the primary signal, on
     * the reasoning that AOSP sets it for external connectors and not for
     * built-in panels. That version still put the stream on the Thor's bottom
     * panel, which is what we would see if this device marks its built-in second
     * screen as a presentation display. The flag is therefore not trustworthy
     * for the question we are actually asking.
     *
     * <p>So the score decides: physical pixels multiplied by refresh rate, the
     * two things that determine how good a stream target a panel is.
     * <ul>
     *   <li><b>Dual-screen handheld.</b> 1080x1920x120 is about 249 Mpx*Hz
     *       against 1080x1240x60 at about 80 Mpx*Hz. The good panel wins by 3x,
     *       whatever the flags say.</li>
     *   <li><b>Phone plus monitor.</b> A 1440p120 or 4K60 monitor beats a phone
     *       panel on score and is still chosen. A 1080p60 monitor loses to a
     *       1080p120 phone panel — and on the metric that is arguably correct,
     *       because that monitor genuinely is the worse stream target. Wanting
     *       the physically larger screen is a preference, not a capability, and
     *       preferences get a setting: "Screen to stream on" covers it.</li>
     * </ul>
     * Presentation-ness only separates displays within
     * {@link #SCORE_TIE_FRACTION} of each other, where capability genuinely does
     * not distinguish them and "the one you plugged in" is the better guess.
     *
     * <p>Every candidate and the reason for the choice are recorded in
     * {@link #getLastDisplaySelection()}, because this decision cannot be
     * observed any other way on a device without adb.
     *
     * <p>Never returns null: falls back to {@code DEFAULT_DISPLAY}.
     */
    public static Display getStreamDisplay(Context context, PreferenceConfiguration prefs) {
        DisplayManager displayManager = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
        Display defaultDisplay = displayManager.getDisplay(Display.DEFAULT_DISPLAY);
        StringBuilder log = new StringBuilder(256);

        Display[] displays = displayManager.getDisplays();
        int displayCount = displays != null ? displays.length : 0;
        log.append("mode=").append(prefs != null && prefs.enableFullExDisplay ? "on" : "off")
           .append(" target=").append(prefs != null ? prefs.displayTarget : "null")
           .append(" count=").append(displayCount);
        if (displays != null) {
            for (Display d : displays) {
                log.append(" | ").append(describeDisplay(d));
            }
        }

        Display chosen;
        String reason;

        if (prefs == null || !prefs.enableFullExDisplay) {
            chosen = defaultDisplay;
            reason = "mode-off";
        }
        else if (displayCount <= 1) {
            chosen = defaultDisplay;
            reason = "single-display";
        }
        else if (PreferenceConfiguration.DISPLAY_TARGET_MAIN.equals(prefs.displayTarget)) {
            // Explicit override. Must not fall through to the heuristic.
            chosen = defaultDisplay;
            reason = "override-main";
        }
        else if (PreferenceConfiguration.DISPLAY_TARGET_SECONDARY.equals(prefs.displayTarget)) {
            Display best = null;
            for (Display candidate : displays) {
                if (candidate.getDisplayId() == Display.DEFAULT_DISPLAY || !isUsableDisplay(candidate)) {
                    continue;
                }
                if (best == null || displayScore(candidate) > displayScore(best)) {
                    best = candidate;
                }
            }
            // A stale preference asking for a secondary display that no longer
            // exists must not leave us with nothing to stream to.
            chosen = best != null ? best : defaultDisplay;
            reason = best != null ? "override-secondary" : "override-secondary-missing-fallback-default";
        }
        else {
            Display best = null;
            for (Display candidate : displays) {
                if (!isUsableDisplay(candidate)) {
                    continue;
                }
                if (best == null) {
                    best = candidate;
                    continue;
                }

                long candidateScore = displayScore(candidate);
                long bestScore = displayScore(best);
                if (candidateScore > bestScore) {
                    best = candidate;
                }
                else if (isTie(candidateScore, bestScore)
                        && isPresentationDisplay(candidate) && !isPresentationDisplay(best)) {
                    // Equally capable; prefer the one that looks plugged in.
                    best = candidate;
                }
            }
            chosen = best != null ? best : defaultDisplay;
            reason = "auto-best-score";
        }

        if (chosen == null) {
            chosen = defaultDisplay;
            reason = reason + "+null-fallback";
        }

        log.append(" || chosen=").append(chosen != null ? chosen.getDisplayId() : -1)
           .append(" reason=").append(reason);
        lastDisplaySelection = log.toString();
        LimeLog.info("Display selection: " + lastDisplaySelection);

        return chosen;
    }

    private static boolean isTie(long a, long b) {
        long larger = Math.max(a, b);
        if (larger <= 0) {
            return true;
        }
        return Math.abs(a - b) <= (long) (larger * SCORE_TIE_FRACTION);
    }

    /**
     * Display the touch control surface belongs on: the best usable display that
     * the stream is <em>not</em> using. Null when there is no such display.
     *
     * <p>This has to be derived from {@link #getStreamDisplay} rather than
     * hardcoded, because the stream target is no longer always the secondary
     * display. When the stream runs on the main panel of a dual-screen handheld
     * the control surface must go to the other panel; pinning it to
     * {@code DEFAULT_DISPLAY} would put both on the same screen and hide the
     * stream behind the touchpad.
     */
    public static Display getControlSurfaceDisplay(Context context, PreferenceConfiguration prefs) {
        DisplayManager displayManager = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
        Display streamDisplay = getStreamDisplay(context, prefs);
        int streamDisplayId = streamDisplay != null ? streamDisplay.getDisplayId() : Display.DEFAULT_DISPLAY;

        Display best = null;
        Display[] displays = displayManager.getDisplays();
        if (displays == null) {
            return null;
        }

        for (Display candidate : displays) {
            if (candidate.getDisplayId() == streamDisplayId || !isUsableDisplay(candidate)) {
                continue;
            }
            if (best == null || displayScore(candidate) > displayScore(best)) {
                best = candidate;
            }
        }

        lastControlSelection = "stream=" + streamDisplayId
                + " control=" + (best != null ? String.valueOf(best.getDisplayId()) : "none")
                + (best != null ? " [" + describeDisplay(best) + "]" : "");
        return best;
    }

    /**
     * Launch options that place an activity on the control-surface display, or
     * null when there is no separate display to put it on.
     *
     * <p>This exists because {@code startActivity()} with no options puts the
     * activity on the <b>caller's</b> display. Every launcher of the control
     * activity used to rely on that being right, which it was only while the
     * stream always went to a secondary display and the launcher always sat on
     * the default one. With the stream now on display 0, inheriting the caller's
     * display puts the control surface on display 0 too — on top of the stream —
     * and the second panel gets nothing at all.
     */
    public static Bundle getControlSurfaceLaunchOptions(Context context, PreferenceConfiguration prefs) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return null;
        }
        Display controlDisplay = getControlSurfaceDisplay(context, prefs);
        if (controlDisplay == null) {
            return null;
        }
        return ActivityOptions.makeBasic()
                .setLaunchDisplayId(controlDisplay.getDisplayId())
                .toBundle();
    }

    public static Intent createStartIntent(Activity parent, NvApp app, ComputerDetails computer,
                                           ComputerManagerService.ComputerManagerBinder managerBinder,
                                           boolean withVDisplay) {
        Intent gameIntent = null;
        PreferenceConfiguration prefConfig = PreferenceConfiguration.readPreferences(parent);

        // Route through getStreamDisplay() so this and Game's own getStreamDisplay()
        // call cannot disagree. Previously this resolved the display twice itself
        // and Game asked getActiveDisplay() separately, so three independent
        // queries decided one thing; if they ever disagreed the display context
        // and the stream target would diverge. Resolve once, use the result.
        Display targetDisplay = getStreamDisplay(parent, prefConfig);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && targetDisplay != null
                && targetDisplay.getDisplayId() != Display.DEFAULT_DISPLAY) {
            Context displayContext = parent.createDisplayContext(targetDisplay);
            gameIntent = new Intent(displayContext, Game.class);
            gameIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        if(gameIntent == null) gameIntent = new Intent(parent, Game.class);
        gameIntent.putExtra(Game.EXTRA_HOST, computer.activeAddress.address);
        gameIntent.putExtra(Game.EXTRA_PORT, computer.activeAddress.port);
        gameIntent.putExtra(Game.EXTRA_HTTPS_PORT, computer.httpsPort);
        gameIntent.putExtra(Game.EXTRA_APP_NAME, app.getAppName());
        gameIntent.putExtra(Game.EXTRA_APP_UUID, app.getAppUUID());
        gameIntent.putExtra(Game.EXTRA_APP_ID, app.getAppId());
        gameIntent.putExtra(Game.EXTRA_APP_HDR, app.isHdrSupported());
        gameIntent.putExtra(Game.EXTRA_UNIQUEID, managerBinder.getUniqueId());
        gameIntent.putExtra(Game.EXTRA_PC_UUID, computer.uuid);
        gameIntent.putExtra(Game.EXTRA_PC_NAME, computer.name);
        gameIntent.putExtra(Game.EXTRA_VDISPLAY, withVDisplay);
        gameIntent.putExtra(Game.EXTRA_SERVER_COMMANDS, (ArrayList<String>) computer.serverCommands);

        try {
            if (computer.serverCert != null) {
                gameIntent.putExtra(Game.EXTRA_SERVER_CERT, computer.serverCert.getEncoded());
            }
        } catch (CertificateEncodingException e) {
            e.printStackTrace();
        }

        if (prefConfig.enableFullExDisplay) {
            // EXTRA_DISPLAY_ID must name the display the stream will actually run
            // on: Game reads it back to decide which display it is on. It used to
            // be set from a secondary-only lookup, which by construction can never
            // be DEFAULT_DISPLAY, so a user asking to stream on the main panel
            // was overruled here no matter what the preference said.
            //
            // The control surface then takes whichever display is left over. If
            // there is no display left over there is nowhere to put it, so launch
            // the game directly rather than wrapping it in a touchpad activity
            // that would land on top of the stream.
            Display controlDisplay = getControlSurfaceDisplay(parent, prefConfig);
            if (controlDisplay != null && targetDisplay != null) {
                gameIntent.putExtra(Game.EXTRA_DISPLAY_ID, targetDisplay.getDisplayId());
                Intent touchpadIntent = new Intent(parent, ExternalDisplayControlActivity.class);
                touchpadIntent.putExtra(ExternalDisplayControlActivity.EXTRA_LAUNCH_INTENT, gameIntent);
                return touchpadIntent;
            }
        }

        return gameIntent;
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    public static void doStart(
            Activity parent,
            NvApp app,
            ComputerDetails computer,
            ComputerManagerService.ComputerManagerBinder managerBinder,
            boolean withVDisplay
    ) {
        if (computer.state == ComputerDetails.State.OFFLINE || computer.activeAddress == null) {
            Toast.makeText(parent, parent.getString(R.string.pair_pc_offline), Toast.LENGTH_SHORT).show();
            return;
        }

        Intent intent = createStartIntent(parent, app, computer, managerBinder, withVDisplay);

        // When createStartIntent() wrapped the game in the control activity, that
        // activity must be placed on the control display explicitly. Launching it
        // bare puts it on the caller's display, which is where the stream now is.
        PreferenceConfiguration prefConfig = PreferenceConfiguration.readPreferences(parent);
        Bundle controlOptions = getControlSurfaceLaunchOptions(parent, prefConfig);
        if (controlOptions != null
                && ExternalDisplayControlActivity.class.getName().equals(
                        intent.getComponent() != null ? intent.getComponent().getClassName() : null)) {
            parent.startActivity(intent, controlOptions);
        }
        else {
            parent.startActivity(intent);
        }
    }

    public static void doNetworkTest(final Activity parent) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                SpinnerDialog spinnerDialog = SpinnerDialog.displayDialog(parent,
                        parent.getResources().getString(R.string.nettest_title_waiting),
                        parent.getResources().getString(R.string.nettest_text_waiting),
                        false);

                int ret = MoonBridge.testClientConnectivity(CONNECTION_TEST_SERVER, 443, MoonBridge.ML_PORT_FLAG_ALL);
                spinnerDialog.dismiss();

                String dialogSummary;
                if (ret == MoonBridge.ML_TEST_RESULT_INCONCLUSIVE) {
                    dialogSummary = parent.getResources().getString(R.string.nettest_text_inconclusive);
                }
                else if (ret == 0) {
                    dialogSummary = parent.getResources().getString(R.string.nettest_text_success);
                }
                else {
                    dialogSummary = parent.getResources().getString(R.string.nettest_text_failure);
                    dialogSummary += MoonBridge.stringifyPortFlags(ret, "\n");
                }

                Dialog.displayDialog(parent,
                        parent.getResources().getString(R.string.nettest_title_done),
                        dialogSummary,
                        false);
            }
        }).start();
    }

    public static void doQuit(final Activity parent,
                              final NvHTTP httpConn,
                              final String appName,
                              final Runnable onComplete,
                              final Runnable onFail
    ) {
        parent.runOnUiThread(() -> Toast.makeText(parent, parent.getResources().getString(R.string.applist_quit_app) + " " + appName + "...", Toast.LENGTH_SHORT).show());
        new Thread(new Runnable() {
            @Override
            public void run() {
                String message;
                boolean failed = false;
                try {
                    if (httpConn.quitApp()) {
                        message = parent.getResources().getString(R.string.applist_quit_success) + " " + appName;
                    } else {
                        message = parent.getResources().getString(R.string.applist_quit_fail) + " " + appName;
                    }
                } catch (HostHttpResponseException e) {
                    failed = true;
                    if (e.getErrorCode() == 599) {
                        message = "This session wasn't started by this device," +
                                " so it cannot be quit. End streaming on the original " +
                                "device or the PC itself. (Error code: "+e.getErrorCode()+")";
                    }
                    else {
                        message = e.getMessage();
                    }
                } catch (UnknownHostException e) {
                    failed = true;
                    message = parent.getResources().getString(R.string.error_unknown_host);
                } catch (FileNotFoundException e) {
                    failed = true;
                    message = parent.getResources().getString(R.string.error_404);
                } catch (IOException | XmlPullParserException e) {
                    failed = true;
                    message = e.getMessage();
                    e.printStackTrace();
                } finally {
                    if (failed) {
                        if (onFail != null) {
                            onFail.run();
                        }
                    } else {
                        if (onComplete != null) {
                            onComplete.run();
                        }
                    }
                }

                final String toastMessage = message;
                parent.runOnUiThread(() -> Toast.makeText(parent, toastMessage, Toast.LENGTH_LONG).show());
            }
        }).start();

    }

    public static void doQuit(final Activity parent,
                              final ComputerDetails computer,
                              final NvApp app,
                              final ComputerManagerService.ComputerManagerBinder managerBinder,
                              final Runnable onComplete
    ) {
        try {
            NvHTTP httpConn = new NvHTTP(
                    ServerHelper.getCurrentAddressFromComputer(computer),
                    computer.httpsPort,
                    managerBinder.getUniqueId(),
                    computer.serverCert,
                    PlatformBinding.getCryptoProvider(parent)
            );
            doQuit(
                    parent,
                    httpConn,
                    app.getAppName(),
                    onComplete,
                    null
            );
        } catch (Exception e) {
            e.printStackTrace();

            final String toastMessage = e.getMessage();
            parent.runOnUiThread(() -> Toast.makeText(parent, toastMessage, Toast.LENGTH_LONG).show());
        }
    }
}
