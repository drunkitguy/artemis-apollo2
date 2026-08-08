package com.limelight.preferences;

import android.content.Context;
import android.util.AttributeSet;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.preference.Preference;

import com.limelight.R;

import java.util.Locale;

// Based on a Stack Overflow example: http://stackoverflow.com/questions/1974193/slider-on-my-preferencescreen
public class SeekBarPreference extends Preference
{
    private static final String ANDROID_SCHEMA_URL = "http://schemas.android.com/apk/res/android";
    private static final String SEEKBAR_SCHEMA_URL = "http://schemas.moonlight-stream.com/apk/res/seekbar";

    private AlertDialog dialog;
    private SeekBar seekBar;
    private TextView valueText;
    private final Context context;

    private final String dialogMessage;
    private final String suffix;
    private final int defaultValue;
    private final int maxValue;
    private final int minValue;
    private final int stepSize;
    private final int keyStepSize;
    private final int divisor;
    private int currentValue;

    private final int seekbarMax;

    // Inline presentation extras. Read once from XML, never mutated.
    private final String explanation;
    private final String minLabel;
    private final String maxLabel;

    public SeekBarPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        this.context = context;

        // Read the message from XML
        int dialogMessageId = attrs.getAttributeResourceValue(ANDROID_SCHEMA_URL, "dialogMessage", 0);
        if (dialogMessageId == 0) {
            dialogMessage = attrs.getAttributeValue(ANDROID_SCHEMA_URL, "dialogMessage");
        }
        else {
            dialogMessage = context.getString(dialogMessageId);
        }

        // Get the suffix for the number displayed in the dialog
        int suffixId = attrs.getAttributeResourceValue(ANDROID_SCHEMA_URL, "text", 0);
        if (suffixId == 0) {
            suffix = attrs.getAttributeValue(ANDROID_SCHEMA_URL, "text");
        }
        else {
            suffix = context.getString(suffixId);
        }

        // Get default, min, and max seekbar values
        defaultValue = attrs.getAttributeIntValue(ANDROID_SCHEMA_URL, "defaultValue", PreferenceConfiguration.getDefaultBitrate(context));
        maxValue = attrs.getAttributeIntValue(ANDROID_SCHEMA_URL, "max", 100);
        minValue = attrs.getAttributeIntValue(SEEKBAR_SCHEMA_URL, "min", 1);
        stepSize = attrs.getAttributeIntValue(SEEKBAR_SCHEMA_URL, "step", 1);
        divisor = attrs.getAttributeIntValue(SEEKBAR_SCHEMA_URL, "divisor", 1);
        keyStepSize = attrs.getAttributeIntValue(SEEKBAR_SCHEMA_URL, "keyStep", 0);
        seekbarMax = maxValue - minValue;

        android.content.res.TypedArray a =
                context.obtainStyledAttributes(attrs, R.styleable.InlineSeekBar);
        try {
            explanation = a.getString(R.styleable.InlineSeekBar_explanation);
            minLabel = a.getString(R.styleable.InlineSeekBar_minLabel);
            maxLabel = a.getString(R.styleable.InlineSeekBar_maxLabel);
        }
        finally {
            a.recycle();
        }

        // Render in the list rather than behind a dialog. Every existing usage
        // picks this up without touching its XML, and the persisted value, key,
        // range and step are all unchanged -- this is the same preference, drawn
        // differently.
        setLayoutResource(R.layout.pref_inline_seekbar);
    }

    /** Formats a raw slider value the way the old dialog did, suffix included. */
    private String formatValue(int value) {
        String t;
        if (divisor != 1) {
            t = String.format((Locale) null, "%.1f", value / (float) divisor);
        }
        else {
            t = String.valueOf(value);
        }
        if (suffix == null) {
            return t;
        }
        return t.concat(suffix.length() > 1 ? " " + suffix : suffix);
    }

    @Override
    public void onBindViewHolder(androidx.preference.PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);

        TextView titleView = (TextView) holder.findViewById(R.id.seekbar_title);
        if (titleView != null) {
            titleView.setText(getTitle());
        }

        final TextView valueView = (TextView) holder.findViewById(R.id.seekbar_value);
        TextView minView = (TextView) holder.findViewById(R.id.seekbar_min);
        TextView maxView = (TextView) holder.findViewById(R.id.seekbar_max);
        TextView explainView = (TextView) holder.findViewById(R.id.seekbar_explain);

        if (minView != null) {
            minView.setText(minLabel != null ? minLabel : formatValue(minValue));
        }
        if (maxView != null) {
            maxView.setText(maxLabel != null ? maxLabel : formatValue(maxValue));
        }

        if (explainView != null) {
            // An explicit explanation wins; otherwise fall back to whatever the
            // preference already had to say, so nothing that used dialogMessage
            // or a summary loses its text in the move off the dialog.
            CharSequence text = explanation != null ? explanation : getSummary();
            if (text == null || text.length() == 0) {
                text = dialogMessage;
            }
            if (text != null && text.length() > 0) {
                explainView.setText(text);
                explainView.setVisibility(android.view.View.VISIBLE);
            }
            else {
                explainView.setVisibility(android.view.View.GONE);
            }
        }

        final SeekBar bar = (SeekBar) holder.findViewById(R.id.seekbar_bar);
        if (bar == null) {
            return;
        }

        if (shouldPersist()) {
            currentValue = getPersistedInt(defaultValue);
        }

        bar.setMax(seekbarMax);
        if (keyStepSize != 0) {
            bar.setKeyProgressIncrement(keyStepSize);
        }
        bar.setProgress(currentValue - minValue);
        bar.setEnabled(isEnabled());

        if (valueView != null) {
            valueView.setText(formatValue(currentValue));
        }

        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int value = progress + minValue;

                // Snap to the declared step. Same rounding the dialog did, so a
                // preference with step=5 still cannot land on a value of 3.
                int roundedValue = Math.round((float) value / stepSize) * stepSize;
                if (roundedValue != value && fromUser) {
                    seekBar.setProgress(roundedValue - minValue);
                    return;
                }

                if (valueView != null) {
                    valueView.setText(formatValue(roundedValue));
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                // Persist on release, not on every pixel of drag. Writing on each
                // progress tick would fire the change listener hundreds of times
                // per gesture, and some of those listeners rebuild the UI.
                int value = seekBar.getProgress() + minValue;
                int roundedValue = Math.round((float) value / stepSize) * stepSize;

                if (!shouldPersist() || roundedValue == currentValue) {
                    return;
                }
                if (callChangeListener(roundedValue)) {
                    currentValue = roundedValue;
                    persistInt(currentValue);
                }
                else {
                    seekBar.setProgress(currentValue - minValue);
                }
            }
        });
    }

    protected AlertDialog getDialog() {
        if (dialog != null) {
            return dialog;
        }

        LinearLayout.LayoutParams params;
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(6, 6, 6, 6);

        TextView splashText = new TextView(context);
        splashText.setPadding(30, 10, 30, 10);
        if (dialogMessage != null) {
            splashText.setText(dialogMessage);
        }
        layout.addView(splashText);

        valueText = new TextView(context);
        valueText.setGravity(Gravity.CENTER_HORIZONTAL);
        valueText.setTextSize(32);
        // Default text for value; hides bug where OnSeekBarChangeListener isn't called when opacity is 0%
        valueText.setText("0%");
        params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        layout.addView(valueText, params);

        seekBar = new SeekBar(context);
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int value, boolean b) {
                value += minValue;
                if (value < minValue) {
                    seekBar.setProgress(0);
                    return;
                }

                int roundedValue = Math.round((float)value / stepSize) * stepSize;
                if (roundedValue != value) {
                    seekBar.setProgress(roundedValue - minValue);
                    return;
                }

                String t;
                if (divisor != 1) {
                    float floatValue = roundedValue / (float)divisor;
                    t = String.format((Locale)null, "%.1f", floatValue);
                }
                else {
                    t = String.valueOf(value);
                }
                valueText.setText(suffix == null ? t : t.concat(suffix.length() > 1 ? " "+suffix : suffix));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        layout.addView(seekBar, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        if (shouldPersist()) {
            currentValue = getPersistedInt(defaultValue);
        }

        seekBar.setMax(seekbarMax);
        if (keyStepSize != 0) {
            seekBar.setKeyProgressIncrement(keyStepSize);
        }
        seekBar.setProgress(currentValue - minValue);

        AlertDialog.Builder dialogBuilder = new AlertDialog.Builder(context);
        dialogBuilder.setTitle(getTitle());
        dialogBuilder.setView(layout);

        dialogBuilder.setPositiveButton("OK", (dialog, which) -> {
            if (shouldPersist()) {
                currentValue = seekBar.getProgress() + minValue;
                persistInt(currentValue);
                callChangeListener(currentValue);
            }

            dialog.dismiss();
        });
        dialogBuilder.setNegativeButton(context.getString(R.string.cancel), (dialog, which) -> dialog.dismiss());

        dialog = dialogBuilder.create();
        return dialog;
    }

    protected void updateSeekbar() {
        seekBar.setMax(seekbarMax);
        if (keyStepSize != 0) {
            seekBar.setKeyProgressIncrement(keyStepSize);
        }
        seekBar.setProgress(currentValue - minValue);
    }

    @Override
    protected void onSetInitialValue(boolean restore, Object defaultValue)
    {
        super.onSetInitialValue(restore, defaultValue);
        if (restore) {
            currentValue = shouldPersist() ? getPersistedInt(this.defaultValue) : 0;
        }
        else {
            currentValue = (Integer) defaultValue;
        }
    }

    public void setProgress(int progress) {
        this.currentValue = progress;
        if (seekBar != null) {
            seekBar.setProgress(progress - minValue);
        }
    }
    public int getProgress() {
        return currentValue + minValue;
    }

    public void showDialog() {
        AlertDialog dialog = getDialog();
        updateSeekbar();
        dialog.show();
    }

    @Override
    protected void onClick() {
        // The slider is on the row now. Opening the old dialog on top of it
        // would give two ways to set the same value, which is how they drift.
    }
}
