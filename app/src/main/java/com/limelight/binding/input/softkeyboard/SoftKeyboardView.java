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

import java.util.ArrayList;
import java.util.List;

/**
 * Draws a {@link SoftKeyboardModel} and reports presses.
 *
 * The rows are built from the model rather than from XML so that the focus
 * ring, the shifted faces and the page swap all have exactly one source of
 * truth. Touch and gamepad end up in the same place: a tap moves focus to the
 * key and presses it, which is what keeps the highlight honest when the user
 * mixes the two.
 */
public class SoftKeyboardView extends LinearLayout {

    /** Told when a key is pressed, by finger or by pad. */
    public interface OnKeyPressListener {
        void onKeyPress(int row, int column);
    }

    private static final float ROW_HEIGHT_DP = 46f;
    private static final float PIN_ROW_HEIGHT_DP = 76f;
    private static final float KEY_GAP_DP = 4f;

    private final SoftKeyboardModel model;
    private final boolean keypad;
    private OnKeyPressListener listener;

    private final List<List<TextView>> keyViews = new ArrayList<>();
    private TextView hintView;
    private TextView echoView;

    private final int colourKey;
    private final int colourKeyFocused;
    private final int colourKeyActive;
    private final int colourLabel;
    private final int colourLabelFocused;
    private final int colourSecondary;

    public SoftKeyboardView(Context context, SoftKeyboardModel model) {
        super(context);
        this.model = model;
        this.keypad = model.getPage() == SoftKeyboardLayouts.Page.PIN;

        this.colourKey = ContextCompat.getColor(context, R.color.vl_neutral_fill);
        this.colourKeyFocused = ContextCompat.getColor(context, R.color.vl_accent);
        this.colourKeyActive = ContextCompat.getColor(context, R.color.vl_accent_fill);
        this.colourLabel = ContextCompat.getColor(context, R.color.vl_label);
        this.colourLabelFocused = Color.WHITE;
        this.colourSecondary = ContextCompat.getColor(context, R.color.vl_secondary_label);

        setOrientation(VERTICAL);
        setBackground(panelBackground(ContextCompat.getColor(context, R.color.vl_card)));
        int pad = dp(KEY_GAP_DP * 2);
        setPadding(pad, pad, pad, pad);

        buildHeader(context);
        buildRows(context);
        refresh();
    }

    public void setOnKeyPressListener(OnKeyPressListener listener) {
        this.listener = listener;
    }

    // ----------------------------------------------------------------- layout

    private void buildHeader(Context context) {
        hintView = new TextView(context);
        hintView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        hintView.setTextColor(colourSecondary);
        hintView.setPadding(dp(6f), 0, dp(6f), dp(4f));
        addView(hintView, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        echoView = new TextView(context);
        echoView.setTextSize(TypedValue.COMPLEX_UNIT_SP, keypad ? 26f : 18f);
        echoView.setTextColor(colourLabel);
        echoView.setBackground(panelBackground(colourKey));
        echoView.setPadding(dp(12f), dp(10f), dp(12f), dp(10f));
        echoView.setGravity(keypad ? Gravity.CENTER : Gravity.CENTER_VERTICAL);
        echoView.setSingleLine(true);
        LayoutParams echoParams = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        echoParams.bottomMargin = dp(KEY_GAP_DP * 2);
        addView(echoView, echoParams);
    }

    private void buildRows(Context context) {
        List<List<SoftKey>> rows = model.getRows();
        int rowHeight = dp(keypad ? PIN_ROW_HEIGHT_DP : ROW_HEIGHT_DP);

        for (int r = 0; r < rows.size(); r++) {
            List<SoftKey> row = rows.get(r);

            LinearLayout rowView = new LinearLayout(context);
            rowView.setOrientation(HORIZONTAL);

            LayoutParams rowParams = new LayoutParams(LayoutParams.MATCH_PARENT, rowHeight);
            rowParams.topMargin = dp(KEY_GAP_DP);
            addView(rowView, rowParams);

            List<TextView> viewsInRow = new ArrayList<>(row.size());
            for (int c = 0; c < row.size(); c++) {
                SoftKey key = row.get(c);

                TextView keyView = new TextView(context);
                keyView.setGravity(Gravity.CENTER);
                keyView.setTextSize(TypedValue.COMPLEX_UNIT_SP, keypad ? 24f : 16f);
                keyView.setSingleLine(true);
                keyView.setClickable(true);
                keyView.setFocusable(false);

                final int pressRow = r;
                final int pressColumn = c;
                keyView.setOnClickListener(new OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (listener != null) {
                            listener.onKeyPress(pressRow, pressColumn);
                        }
                    }
                });

                LayoutParams keyParams = new LayoutParams(0, LayoutParams.MATCH_PARENT, key.weight);
                keyParams.leftMargin = c == 0 ? 0 : dp(KEY_GAP_DP);
                rowView.addView(keyView, keyParams);
                viewsInRow.add(keyView);
            }
            keyViews.add(viewsInRow);
        }
    }

    // ------------------------------------------------------------------ paint

    /** Repaints faces, the focus ring and the shift state from the model. */
    public void refresh() {
        boolean shift = model.isShiftActive();
        List<List<SoftKey>> rows = model.getRows();

        for (int r = 0; r < rows.size(); r++) {
            for (int c = 0; c < rows.get(r).size(); c++) {
                SoftKey key = rows.get(r).get(c);
                TextView view = keyViews.get(r).get(c);

                view.setText(key.face(shift));

                boolean focused = r == model.getRow() && c == model.getColumn();
                boolean latched = key.action == SoftKey.Action.SHIFT
                        && model.getShift() != SoftKeyboardModel.Shift.OFF;

                int fill = focused ? colourKeyFocused : (latched ? colourKeyActive : colourKey);
                view.setBackground(keyBackground(fill, focused));
                view.setTextColor(focused ? colourLabelFocused : colourLabel);
            }
        }
    }

    /**
     * Fades the keyboard while it is not holding the pad, so a glance at the
     * second screen says whether typing or the game has the controller.
     */
    public void setDimmed(boolean dimmed) {
        setAlpha(dimmed ? 0.45f : 1f);
    }

    public void setHint(CharSequence hint) {
        hintView.setText(hint);
    }

    /** Local echo of what has been typed. It is a mirror, not the host's field. */
    public void setEcho(CharSequence echo) {
        echoView.setText(echo);
    }

    // ---------------------------------------------------------------- drawing

    private GradientDrawable keyBackground(int fill, boolean focused) {
        GradientDrawable shape = new GradientDrawable();
        shape.setShape(GradientDrawable.RECTANGLE);
        shape.setCornerRadius(getResources().getDimension(R.dimen.vl_control_radius));
        shape.setColor(fill);
        if (focused) {
            shape.setStroke(dp(2f), colourLabelFocused);
        }
        return shape;
    }

    private GradientDrawable panelBackground(int fill) {
        GradientDrawable shape = new GradientDrawable();
        shape.setShape(GradientDrawable.RECTANGLE);
        shape.setCornerRadius(getResources().getDimension(R.dimen.vl_tile_radius));
        shape.setColor(fill);
        return shape;
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
