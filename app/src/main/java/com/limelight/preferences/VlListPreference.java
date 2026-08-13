package com.limelight.preferences;

import android.content.Context;
import android.util.AttributeSet;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.ListPreference;
import androidx.preference.PreferenceViewHolder;

import com.limelight.R;

/**
 * VoidLink list row for preferences with too many options for a segmented control: the
 * current entry is shown in the accent color on the right with a chevron, and tapping the
 * row opens the regular list dialog.
 */
public class VlListPreference extends ListPreference {
    private boolean summaryExpanded;

    public VlListPreference(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        initializeVlRow();
    }

    public VlListPreference(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        initializeVlRow();
    }

    public VlListPreference(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        initializeVlRow();
    }

    public VlListPreference(@NonNull Context context) {
        super(context);
        initializeVlRow();
    }

    private void initializeVlRow() {
        setLayoutResource(R.layout.vl_settings_row_value);
    }

    @Override
    public void onBindViewHolder(@NonNull PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        VlSettingsRowBinder.bindValue(holder, getEntry());
        VlSettingsRowBinder.bindRow(this, holder, summaryExpanded, v -> {
            summaryExpanded = !summaryExpanded;
            notifyChanged();
        });
    }
}
