package com.limelight.preferences;

import android.content.Context;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.preference.ListPreference;
import androidx.preference.PreferenceViewHolder;

import com.limelight.R;

/**
 * VoidLink segmented control row: an iOS-style track with the selected option shown as a
 * solid accent pill. Used for the list preferences with few options (codec, touch mode,
 * resolution, frame rate and similar).
 *
 * <p>This is a plain {@link ListPreference} underneath - same key, same entries, same
 * change listeners - so options that are added or removed at runtime (native resolutions,
 * native frame rates) are picked up automatically. The segments are laid out inside a
 * horizontal scroller so a long entry list still fits. Tapping the row label still opens
 * the regular list dialog.</p>
 */
public class VlSegmentedPreference extends ListPreference {
    private boolean summaryExpanded;

    public VlSegmentedPreference(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        initializeVlRow();
    }

    public VlSegmentedPreference(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        initializeVlRow();
    }

    public VlSegmentedPreference(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        initializeVlRow();
    }

    public VlSegmentedPreference(@NonNull Context context) {
        super(context);
        initializeVlRow();
    }

    private void initializeVlRow() {
        setLayoutResource(R.layout.vl_settings_row_segmented);
    }

    @Override
    public void onBindViewHolder(@NonNull PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);

        View container = holder.findViewById(R.id.vl_settings_segments);
        if (container instanceof LinearLayout) {
            bindSegments((LinearLayout) container);
        }

        VlSettingsRowBinder.bindRow(this, holder, summaryExpanded, v -> {
            summaryExpanded = !summaryExpanded;
            notifyChanged();
        });
    }

    private void bindSegments(LinearLayout container) {
        container.removeAllViews();

        CharSequence[] entries = getEntries();
        CharSequence[] entryValues = getEntryValues();
        if (entries == null || entryValues == null) {
            container.setVisibility(View.GONE);
            return;
        }
        container.setVisibility(View.VISIBLE);

        Context context = container.getContext();
        LayoutInflater inflater = LayoutInflater.from(context);
        String currentValue = getValue();
        int count = Math.min(entries.length, entryValues.length);

        for (int i = 0; i < count; i++) {
            final String value = entryValues[i].toString();
            boolean selected = value.equals(currentValue);

            TextView segment = (TextView) inflater.inflate(R.layout.vl_settings_segment_item, container, false);
            segment.setText(entries[i]);
            segment.setBackgroundResource(selected
                    ? R.drawable.vl_settings_segment_selected
                    : R.drawable.vl_settings_segment_unselected);
            segment.setTextColor(ContextCompat.getColor(context,
                    selected ? android.R.color.white : R.color.vl_secondary_label));
            segment.setEnabled(isEnabled());
            segment.setOnClickListener(v -> onSegmentClicked(value));

            container.addView(segment);
        }
    }

    private void onSegmentClicked(String value) {
        if (!isEnabled() || value.equals(getValue())) {
            return;
        }
        // Same contract as picking the option in the list dialog: the change listener gets
        // the final say (it is what resets the bitrate and warns about native modes).
        if (callChangeListener(value)) {
            setValue(value);
            notifyChanged();
        }
    }
}
