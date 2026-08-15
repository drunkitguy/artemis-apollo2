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
 * keyboard nobody is using. The two buttons are the concession to reality: the
 * host never says which kind of field has focus, so the choice between letters
 * and digits has to come from the person typing.
 *
 * Every part of it is a target. The whole panel opens whichever keyboard was
 * used last, and the buttons are only needed to pick the other one, because on
 * a handheld the surest tap is the one that cannot miss.
 */
public class SoftKeyboardLauncherView extends LinearLayout {

    public interface OnPickListener {
        void onPick(SoftKeyboardLayouts.Page page);
    }

    private OnPickListener listener;
    private final SoftKeyboardLayouts.Page lastUsed;

    public SoftKeyboardLauncherView(Context context, SoftKeyboardLayouts.Page lastUsed,
                                    boolean padShortcutEnabled) {
        super(context);
        this.lastUsed = lastUsed;

        setOrientation(VERTICAL);
        setGravity(Gravity.CENTER);
        setBackgroundColor(Color.BLACK);

        // Anywhere that is not a button reopens what was used last.
        setClickable(true);
        setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                pick(SoftKeyboardLauncherView.this.lastUsed);
            }
        });

        TextView caption = new TextView(context);
        caption.setText(padShortcutEnabled
                ? R.string.soft_keyboard_launcher_caption_pad
                : R.string.soft_keyboard_launcher_caption);
        caption.setTextColor(ContextCompat.getColor(context, R.color.vl_secondary_label));
        caption.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        caption.setGravity(Gravity.CENTER);
        caption.setPadding(dp(16f), 0, dp(16f), 0);
        LayoutParams captionParams = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        captionParams.bottomMargin = dp(20f);
        addView(caption, captionParams);

        LinearLayout row = new LinearLayout(context);
        row.setOrientation(HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        LayoutParams rowParams = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        rowParams.leftMargin = dp(24f);
        rowParams.rightMargin = dp(24f);
        addView(row, rowParams);

        row.addView(button(context, R.string.soft_keyboard_launcher_letters,
                        SoftKeyboardLayouts.Page.LETTERS),
                buttonParams(false));
        row.addView(button(context, R.string.soft_keyboard_launcher_digits,
                        SoftKeyboardLayouts.Page.PIN),
                buttonParams(true));
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
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f);
        view.setTextColor(preferred
                ? Color.WHITE
                : ContextCompat.getColor(context, R.color.vl_label));
        view.setPadding(dp(12f), dp(30f), dp(12f), dp(30f));

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
