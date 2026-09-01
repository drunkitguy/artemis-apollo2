package com.limelight.binding.input.softkeyboard;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.limelight.R;

/**
 * What the second screen shows when nobody is typing.
 *
 * Black, so the panel reads as off next to the game rather than lit up by a
 * keyboard nobody is using. The two buttons are the concession to hosts that
 * cannot say which kind of field has focus, so the choice between letters and
 * digits has to come from the person typing. Where the PC does say, the choice
 * is already made and the buttons are only in the way, so they come off and
 * the trackpad takes the space back.
 *
 * The buttons are deliberately half a row each rather than wrapped to their
 * text: on a handheld's small panel a miss costs more than the space saved.
 * With them gone the trackpad takes the whole panel below the banner, and the
 * keyboard is reached by the PC raising it, by the game menu, or by the both
 * stick chord, none of which need a touchscreen.
 */
public class SoftKeyboardLauncherView extends LinearLayout {

    public interface OnPickListener {
        void onPick(SoftKeyboardLayouts.Page page);
    }

    private OnPickListener listener;
    private final SoftKeyboardLayouts.Page lastUsed;
    private final com.limelight.binding.input.trackpad.SoftTrackpadView trackpad;
    private final com.limelight.metrics.StreamMetricsBanner banner;
    private final TextView reporterStatus;

    /**
     * @param lastUsed        the keyboard that gets the filled button, i.e. the
     *                        one the user is most likely to want
     * @param hostPicksLayout true when the PC is reporting which field has
     *                        focus, in which case the letters/digits row is
     *                        left off entirely
     */
    public SoftKeyboardLauncherView(Context context, SoftKeyboardLayouts.Page lastUsed,
                                    boolean padShortcutEnabled, float trackpadSensitivity,
                                    boolean hostPicksLayout) {
        super(context);
        this.lastUsed = lastUsed;

        setOrientation(VERTICAL);
        setGravity(Gravity.CENTER);
        setBackgroundColor(Color.BLACK);

        // The panel is otherwise doing nothing while nobody types, so most of
        // it is a trackpad. The keyboard buttons become a strip along the
        // bottom rather than the whole screen.
        banner = new com.limelight.metrics.StreamMetricsBanner(context);
        addView(banner, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        trackpad = new com.limelight.binding.input.trackpad.SoftTrackpadView(context, trackpadSensitivity);
        LayoutParams padParams = new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f);
        addView(trackpad, padParams);

        // Hidden unless the PC-side reporter has something to say. When the
        // automatic switching does not work, this is the difference between a
        // report of "it didn't work" and one that names the cause. Added on
        // both paths on purpose: the case where the buttons are gone is
        // exactly the case where the reporter going quiet is worth knowing
        // about, because then there is nothing on the panel picking a
        // keyboard at all.
        reporterStatus = new TextView(context);
        reporterStatus.setGravity(Gravity.CENTER);
        reporterStatus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        reporterStatus.setTextColor(ContextCompat.getColor(context, R.color.vl_label));
        reporterStatus.setPadding(dp(8f), 0, dp(8f), dp(6f));
        reporterStatus.setVisibility(GONE);
        addView(reporterStatus, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        if (hostPicksLayout) {
            // The PC has already said which keyboard the focused field wants,
            // so asking again would be asking the user to answer a question
            // that has an answer. The trackpad grows into the space.
            return;
        }

        LinearLayout row = new LinearLayout(context);
        row.setOrientation(HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        LayoutParams rowParams = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        rowParams.leftMargin = dp(16f);
        rowParams.rightMargin = dp(16f);
        rowParams.bottomMargin = dp(12f);
        rowParams.topMargin = dp(8f);
        addView(row, rowParams);

        row.addView(button(context, R.string.soft_keyboard_launcher_letters,
                        SoftKeyboardLayouts.Page.LETTERS),
                buttonParams(false));
        row.addView(button(context, R.string.soft_keyboard_launcher_digits,
                        SoftKeyboardLayouts.Page.PIN),
                buttonParams(true));

    }

    /** Pass null to hide the line entirely. */
    public void setReporterStatus(String text) {
        if (text == null) {
            reporterStatus.setVisibility(GONE);
            return;
        }
        reporterStatus.setText(text);
        reporterStatus.setVisibility(VISIBLE);
    }

    public com.limelight.binding.input.trackpad.SoftTrackpadView getTrackpad() {
        return trackpad;
    }

    public com.limelight.metrics.StreamMetricsBanner getBanner() {
        return banner;
    }

    public void setOnPickListener(OnPickListener listener) {
        this.listener = listener;
    }

    private void pick(SoftKeyboardLayouts.Page page) {
        if (listener != null) {
            listener.onPick(page);
        }
    }

    private TextView button(Context context, int labelRes, final SoftKeyboardLayouts.Page page) {
        boolean preferred = page == lastUsed;

        TextView view = new TextView(context);
        view.setText(labelRes);
        view.setGravity(Gravity.CENTER);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f);
        view.setTextColor(preferred
                ? Color.WHITE
                : ContextCompat.getColor(context, R.color.vl_label));
        view.setPadding(dp(12f), dp(14f), dp(12f), dp(14f));

        GradientDrawable shape = new GradientDrawable();
        shape.setShape(GradientDrawable.RECTANGLE);
        shape.setCornerRadius(getResources().getDimension(R.dimen.vl_tile_radius));
        // The one used last is filled rather than outlined, so the common case
        // is the one the eye lands on first.
        shape.setColor(ContextCompat.getColor(context,
                preferred ? R.color.vl_accent : R.color.vl_neutral_fill));
        view.setBackground(shape);

        view.setClickable(true);
        view.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                pick(page);
            }
        });
        return view;
    }

    private LayoutParams buttonParams(boolean trailing) {
        // Equal halves of the row rather than wrapped text: a miss on a
        // handheld's small panel costs more than the space saved.
        LayoutParams params = new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f);
        if (trailing) {
            params.leftMargin = dp(16f);
        }
        return params;
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
