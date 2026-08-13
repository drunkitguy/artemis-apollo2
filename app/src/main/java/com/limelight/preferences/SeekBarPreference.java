package com.limelight.preferences;

import android.content.Context;
import android.util.AttributeSet;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;

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

    // Whether the inline row is currently revealing its summary (the circled-i affordance).
    private boolean summaryExpanded;

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

        setLayoutResource(R.layout.vl_settings_row_slider);
    }

    /** Formats a raw value the same way the dialog does, e.g. "23.0 Mbps" or "100%". */
    private String formatValue(int value) {
        String text;
        if (divisor != 1) {
            text = String.format((Locale)null, "%.1f", value / (float)divisor);
        }
        else {
            text = String.valueOf(value);
        }
        return suffix == null ? text : text.concat(suffix.length() > 1 ? " " + suffix : suffix);
    }

    private int roundToStep(int value) {
        int step = stepSize <= 0 ? 1 : stepSize;
        int rounded = Math.round((float)value / step) * step;
        if (rounded < minValue) {
            rounded = minValue;
        }
        else if (rounded > maxValue) {
            rounded = maxValue;
        }
        return rounded;
    }

    private int toSeekBarProgress(int value) {
        int progress = value - minValue;
        if (progress < 0) {
            return 0;
        }
        return Math.min(progress, seekbarMax);
    }

    private void commitInlineValue(int value, SeekBar inlineBar) {
        if (value == currentValue) {
            return;
        }
        if (!callChangeListener(value)) {
            inlineBar.setProgress(toSeekBarProgress(currentValue));
            return;
        }
        currentValue = value;
        if (shouldPersist()) {
            persistInt(value);
        }
    }

    @Override
    public void onBindViewHolder(@NonNull PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);

        if (shouldPersist()) {
            currentValue = getPersistedInt(defaultValue);
        }

        final TextView inlineValueText = (TextView) holder.findViewById(R.id.vl_settings_slider_value);
        if (inlineValueText != null) {
            inlineValueText.setText(formatValue(currentValue));
        }

        final SeekBar inlineBar = (SeekBar) holder.findViewById(R.id.vl_settings_seekbar);
        if (inlineBar != null) {
            // Detach first: this view may be recycled from another row.
            inlineBar.setOnSeekBarChangeListener(null);
            inlineBar.setMax(seekbarMax);
            if (keyStepSize != 0) {
                inlineBar.setKeyProgressIncrement(keyStepSize);
            }
            inlineBar.setProgress(toSeekBarProgress(currentValue));
            inlineBar.setEnabled(isEnabled());
            inlineBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                    int value = roundToStep(progress + minValue);
                    if (value - minValue != progress) {
                        bar.setProgress(toSeekBarProgress(value));
                        return;
                    }
                    if (inlineValueText != null) {
                        inlineValueText.setText(formatValue(value));
                    }
                }

                @Override
                public void onStartTrackingTouch(SeekBar bar) {}

                @Override
                public void onStopTrackingTouch(SeekBar bar) {
                    commitInlineValue(roundToStep(bar.getProgress() + minValue), bar);
                }
            });
        }

        VlSettingsRowBinder.bindRow(this, holder, summaryExpanded, v -> {
            summaryExpanded = !summaryExpanded;
            notifyChanged();
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

                // Refresh the inline slider row with the value chosen in the dialog.
                notifyChanged();
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
        super.onClick();
        showDialog();
    }
}
