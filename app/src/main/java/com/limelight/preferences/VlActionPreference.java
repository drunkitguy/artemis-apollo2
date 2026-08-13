package com.limelight.preferences;

import android.content.Context;
import android.util.AttributeSet;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;

import com.limelight.R;

/**
 * VoidLink action row: a label with a trailing chevron, used for preferences that open
 * something (a file picker, a web page, another screen) rather than holding a value.
 */
public class VlActionPreference extends Preference {
    private boolean summaryExpanded;

    public VlActionPreference(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        initializeVlRow();
    }

    public VlActionPreference(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        initializeVlRow();
    }

    public VlActionPreference(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        initializeVlRow();
    }

    public VlActionPreference(@NonNull Context context) {
        super(context);
        initializeVlRow();
    }

    private void initializeVlRow() {
        setLayoutResource(R.layout.vl_settings_row_value);
    }

    @Override
    public void onBindViewHolder(@NonNull PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        VlSettingsRowBinder.bindValue(holder, null);
        VlSettingsRowBinder.bindRow(this, holder, summaryExpanded, v -> {
            summaryExpanded = !summaryExpanded;
            notifyChanged();
        });
    }
}
