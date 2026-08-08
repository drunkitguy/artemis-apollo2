package com.limelight.preferences;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.app.Activity;
import android.content.res.Configuration;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;

import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.FragmentManager;

import com.limelight.R;

/**
 * Settings as a sheet that slides in over whatever is already on screen,
 * instead of a full-screen page that replaces it.
 *
 * <h3>Why a View and not an Activity</h3>
 * The panel has to work on top of a live stream. Launching a translucent
 * activity over {@code Game} would put {@code Game} through {@code onPause},
 * and an activity that is paused is an activity the platform may stop
 * rendering, throttle, or tear down under memory pressure. Adding a View to the
 * activity's own content root has no lifecycle effect whatsoever: the stream
 * never learns the panel exists.
 *
 * <h3>Cost while streaming</h3>
 * The container is {@code GONE} whenever the panel is closed, so it is not
 * measured, laid out, drawn or composited. That is deliberate rather than
 * incidental -- {@code INVISIBLE}, or a visible view at alpha 0, still costs a
 * compositing pass on every frame, which is precisely what this project exists
 * to avoid spending.
 *
 * <p>While the panel is <em>open</em> it does add one more layer for the
 * compositor, on top of the video surface. What that costs has not been
 * measured on the target device and no number is claimed for it here. The
 * mitigations that are in place: the sheet is opaque, so the region it covers
 * need not blend the video underneath; the slide animation moves the sheet by
 * {@code translationX}, which is a transform on an already-rendered layer
 * rather than a relayout; and nothing behind the panel is invalidated by it.
 *
 * <h3>Thread ownership</h3>
 * Everything here is main-thread only, like all View work. There is no shared
 * state and no background thread.
 */
public class SettingsPanel {

    /**
     * Fraction of the window the sheet occupies, by available width in dp.
     *
     * <p>Not a hardcoded 40%: the reference proportion is right for a handheld
     * in landscape and wrong for a docked 4K display, where 40% is an absurd
     * amount of settings and the underlying content stops being usable. Wide
     * canvases give the panel proportionally less, narrow ones proportionally
     * more, because on a small screen a too-narrow sheet cannot show a
     * segmented row without wrapping.
     */
    private static final int WIDE_CANVAS_DP = 900;
    private static final int NARROW_CANVAS_DP = 480;
    private static final float FRACTION_ON_WIDE = 0.32f;
    private static final float FRACTION_ON_MEDIUM = 0.40f;
    private static final float FRACTION_ON_NARROW = 0.86f;

    private static final long ANIM_MS = 200;

    private final Activity activity;
    private final boolean fromLeftEdge;

    private View root;
    private View sheet;
    private boolean open;
    private boolean fragmentAttached;

    public SettingsPanel(Activity activity, boolean fromLeftEdge) {
        this.activity = activity;
        this.fromLeftEdge = fromLeftEdge;
    }

    public boolean isOpen() {
        return open;
    }

    /** Computes the sheet width for the current window. */
    private int computeSheetWidth() {
        Configuration cfg = activity.getResources().getConfiguration();
        int availableDp = cfg.screenWidthDp;
        float density = activity.getResources().getDisplayMetrics().density;

        float fraction;
        if (availableDp >= WIDE_CANVAS_DP) {
            fraction = FRACTION_ON_WIDE;
        }
        else if (availableDp <= NARROW_CANVAS_DP) {
            fraction = FRACTION_ON_NARROW;
        }
        else {
            // Linear between the two anchors so there is no visible jump as a
            // window is resized or a device is folded.
            float t = (availableDp - NARROW_CANVAS_DP) / (float) (WIDE_CANVAS_DP - NARROW_CANVAS_DP);
            fraction = FRACTION_ON_NARROW + t * (FRACTION_ON_MEDIUM - FRACTION_ON_NARROW);
            if (availableDp > 700) {
                fraction = FRACTION_ON_MEDIUM;
            }
        }

        int widthPx = Math.round(availableDp * density * fraction);

        // Below this the segmented rows wrap and the panel stops being usable.
        int minPx = Math.round(320 * density);
        return Math.max(widthPx, Math.min(minPx, Math.round(availableDp * density)));
    }

    private void ensureInflated() {
        if (root != null) {
            return;
        }

        ViewGroup content = activity.findViewById(android.R.id.content);
        root = LayoutInflater.from(activity).inflate(R.layout.settings_panel, content, false);
        content.addView(root);

        sheet = root.findViewById(R.id.settings_panel_sheet);

        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) sheet.getLayoutParams();
        lp.width = computeSheetWidth();
        lp.gravity = (fromLeftEdge ? Gravity.START : Gravity.END) | Gravity.TOP;
        sheet.setLayoutParams(lp);

        root.findViewById(R.id.settings_panel_scrim).setOnClickListener(v -> close());
        root.findViewById(R.id.settings_panel_collapse).setOnClickListener(v -> close());
    }

    private void attachFragmentIfNeeded() {
        if (fragmentAttached || !(activity instanceof AppCompatActivity)) {
            return;
        }

        FragmentManager fm = ((AppCompatActivity) activity).getSupportFragmentManager();
        // Reuses the same fragment as the standalone settings screen, so the
        // collapsible sections, live header values, segmented rows and inline
        // sliders are identical. This changes the container, not the contents.
        fm.beginTransaction()
                .replace(R.id.settings_panel_content,
                        new StreamSettings.SettingsFragment(
                                PreferenceConfiguration.readPreferences(activity)))
                .commitAllowingStateLoss();
        fragmentAttached = true;
    }

    public void open() {
        if (open) {
            return;
        }
        ensureInflated();
        attachFragmentIfNeeded();

        open = true;
        root.setVisibility(View.VISIBLE);

        float hidden = fromLeftEdge ? -sheet.getLayoutParams().width : sheet.getLayoutParams().width;
        sheet.setTranslationX(hidden);
        sheet.animate()
                .translationX(0)
                .setDuration(ANIM_MS)
                .setInterpolator(new DecelerateInterpolator())
                .setListener(null)
                .start();
    }

    public void close() {
        if (!open || root == null) {
            return;
        }
        open = false;

        float hidden = fromLeftEdge ? -sheet.getLayoutParams().width : sheet.getLayoutParams().width;
        sheet.animate()
                .translationX(hidden)
                .setDuration(ANIM_MS)
                .setInterpolator(new DecelerateInterpolator())
                .setListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        // GONE rather than INVISIBLE: see the class comment.
                        // This is the difference between costing nothing and
                        // costing a compositing pass per frame for the rest of
                        // the session.
                        if (!open) {
                            root.setVisibility(View.GONE);
                        }
                    }
                })
                .start();
    }

    public void toggle() {
        if (open) {
            close();
        }
        else {
            open();
        }
    }

    /**
     * Handles a back press. Returns true if the panel consumed it, so the
     * caller does not also exit the stream.
     */
    public boolean onBackPressed() {
        if (open) {
            close();
            return true;
        }
        return false;
    }

    /** Re-measures the sheet after a configuration change. */
    public void onConfigurationChanged() {
        if (root == null) {
            return;
        }
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) sheet.getLayoutParams();
        lp.width = computeSheetWidth();
        sheet.setLayoutParams(lp);
        if (!open) {
            sheet.setTranslationX(fromLeftEdge ? -lp.width : lp.width);
        }
    }
}
