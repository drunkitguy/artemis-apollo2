package com.limelight.preferences;

import android.content.Context;
import android.util.AttributeSet;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.CheckBoxPreference;
import androidx.preference.PreferenceViewHolder;

import com.limelight.R;

/**
 * VoidLink toggle row: label plus an accent-tinted switch. Behaviour is unchanged from
 * {@link CheckBoxPreference} - only the row and widget layouts differ.
 */
public class VlTogglePreference extends CheckBoxPreference {
    private boolean summaryExpanded;

    public VlTogglePreference(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        initializeVlRow();
    }

    public VlTogglePreference(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        initializeVlRow();
    }

    public VlTogglePreference(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        initializeVlRow();
    }

    public VlTogglePreference(@NonNull Context context) {
        super(context);
        initializeVlRow();
    }

    private void initializeVlRow() {
        setLayoutResource(R.layout.vl_settings_row_toggle);
        setWidgetLayoutResource(R.layout.vl_settings_widget_switch);
    }

    @Override
    public void onBindViewHolder(@NonNull PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        VlSettingsRowBinder.bindRow(this, holder, summaryExpanded, v -> {
            summaryExpanded = !summaryExpanded;
            notifyChanged();
        });
    }
}
