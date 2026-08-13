package com.limelight.preferences;

import android.content.Context;
import android.util.AttributeSet;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.DialogPreference;
import androidx.preference.PreferenceViewHolder;

import com.limelight.R;

/**
 * VoidLink-styled base for the confirmation dialog preferences. Only the row presentation
 * changes; the dialog itself is still driven by PreferenceDialogFragmentCompat.
 */
public class VlDialogPreference extends DialogPreference {
    private boolean summaryExpanded;

    public VlDialogPreference(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        initializeVlRow();
    }

    public VlDialogPreference(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        initializeVlRow();
    }

    public VlDialogPreference(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        initializeVlRow();
    }

    public VlDialogPreference(@NonNull Context context) {
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
