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
 * Mostly black, so the panel is dark next to the game rather than lit up by a
 * keyboard nobody is using. The two buttons are the concession to reality: the
 * host never says which kind of field has focus, so the choice between letters
 * and digits has to come from somewhere, and one tap here is cheaper than a
 * wrong keyboard plus a correction.
 */
public class SoftKeyboardLauncherView extends LinearLayout {

    public interface OnPickListener {
        void onPick(SoftKeyboardLayouts.Page page);
    }

    private OnPickListener listener;

    public SoftKeyboardLauncherView(Context context) {
        super(context);

        setOrientation(VERTICAL);
        setGravity(Gravity.CENTER);
        setBackgroundColor(Color.BLACK);

        TextView caption = new TextView(context);
        caption.setText(R.string.soft_keyboard_launcher_caption);
        caption.setTextColor(ContextCompat.getColor(context, R.color.vl_secondary_label));
        caption.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        caption.setGravity(Gravity.CENTER);
        LayoutParams captionParams = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        captionParams.bottomMargin = dp(18f);
        addView(caption, captionParams);

        LinearLayout row = new LinearLayout(context);
        row.setOrientation(HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        addView(row, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));

        row.addView(button(context, R.string.soft_keyboard_launcher_letters,
                SoftKeyboardLayouts.Page.LETTERS), buttonParams(false));
        row.addView(button(context, R.string.soft_keyboard_launcher_digits,
                SoftKeyboardLayouts.Page.PIN), buttonParams(true));
    }

    public void setOnPickListener(OnPickListener listener) {
        this.listener = listener;
    }

    private TextView button(Context context, int labelRes, final SoftKeyboardLayouts.Page page) {
        TextView view = new TextView(context);
        view.setText(labelRes);
        view.setGravity(Gravity.CENTER);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f);
        view.setTextColor(ContextCompat.getColor(context, R.color.vl_label));
        view.setPadding(dp(30f), dp(18f), dp(30f), dp(18f));

        GradientDrawable shape = new GradientDrawable();
        shape.setShape(GradientDrawable.RECTANGLE);
        shape.setCornerRadius(getResources().getDimension(R.dimen.vl_tile_radius));
        shape.setColor(ContextCompat.getColor(context, R.color.vl_neutral_fill));
        view.setBackground(shape);

        view.setClickable(true);
        view.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (listener != null) {
                    listener.onPick(page);
                }
            }
        });
        return view;
    }

    private LayoutParams buttonParams(boolean trailing) {
        LayoutParams params = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        if (trailing) {
            params.leftMargin = dp(16f);
        }
        return params;
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
