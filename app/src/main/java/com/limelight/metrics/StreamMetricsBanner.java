package com.limelight.metrics;

import android.content.Context;
import android.graphics.Color;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.limelight.R;

/**
 * A strip along the top of the second screen showing what the stream is doing.
 *
 * Three figures, because a second screen is glanced at rather than read: what
 * resolution is being sent, how many frames are arriving, and how long the
 * decoder is taking with each one.
 */
public class StreamMetricsBanner extends LinearLayout {

    private final TextView resolutionView;
    private final TextView fpsView;
    private final TextView decodeView;

    public StreamMetricsBanner(Context context) {
        super(context);

        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setBackgroundColor(Color.BLACK);
        int padH = dp(14f);
        int padV = dp(8f);
        setPadding(padH, padV, padH, padV);

        resolutionView = addCell(context, 0f);
        fpsView = addCell(context, 1f);
        decodeView = addCell(context, 1f);

        resolutionView.setGravity(Gravity.START);
        fpsView.setGravity(Gravity.CENTER);
        decodeView.setGravity(Gravity.END);

        setResolution(0, 0);
        setRates(StreamMetricsWindow.UNKNOWN, StreamMetricsWindow.UNKNOWN);
    }

    private TextView addCell(Context context, float weight) {
        TextView view = new TextView(context);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        view.setTextColor(ContextCompat.getColor(context, R.color.vl_secondary_label));
        view.setSingleLine(true);
        // Digits that change every second must not shuffle the layout around.
        view.setTypeface(android.graphics.Typeface.MONOSPACE);
        addView(view, new LayoutParams(
                weight > 0 ? 0 : LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, weight));
        return view;
    }

    public void setResolution(int width, int height) {
        resolutionView.setText(width > 0 && height > 0 ? (width + "×" + height) : "—");
    }

    /**
     * @param fps          frames in the last window, or {@link StreamMetricsWindow#UNKNOWN}
     * @param decodeTenths decode milliseconds x10, or {@link StreamMetricsWindow#UNKNOWN}
     */
    public void setRates(int fps, int decodeTenths) {
        fpsView.setText(fps == StreamMetricsWindow.UNKNOWN
                ? "— fps"
                : (fps + " fps"));

        decodeView.setText(StreamMetricsWindow.formatTenths(decodeTenths) + " ms decode");

        // Amber once decoding eats a meaningful slice of a 60 Hz frame budget,
        // red once it exceeds the budget entirely. A number alone does not tell
        // anyone whether it is a good number.
        int colour;
        if (decodeTenths == StreamMetricsWindow.UNKNOWN) {
            colour = ContextCompat.getColor(getContext(), R.color.vl_secondary_label);
        } else if (decodeTenths >= 167) {
            // vl_offline is grey, which reads as "no data" rather than "bad".
            colour = 0xFFFF453A;
        } else if (decodeTenths >= 100) {
            colour = 0xFFFFB020;
        } else {
            colour = ContextCompat.getColor(getContext(), R.color.vl_online);
        }
        decodeView.setTextColor(colour);
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
