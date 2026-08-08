package com.limelight.preferences;

import android.content.Context;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.preference.ListPreference;
import androidx.preference.PreferenceViewHolder;

import com.limelight.R;

/**
 * A {@link ListPreference} rendered as a row of segmented buttons instead of a
 * dropdown dialog.
 *
 * <p>For a choice with three or four options — 30/60/120, H.264/HEVC/AV1 — a
 * dropdown costs a tap to open, hides the alternatives until it does, and puts a
 * modal in the way. A segmented row shows every option and every choice is one
 * tap. That is the entire argument; it does not apply to long lists, which is
 * why this is opt-in per preference rather than a global replacement.
 *
 * <p><b>This changes presentation only.</b> It extends {@code ListPreference}
 * and calls {@link #setValue}, so the key, the entry values, the default and the
 * persistence are all exactly as they were. A preference switched from
 * {@code ListPreference} to this class reads and writes the same stored string.
 *
 * <p>Thread ownership: main thread only, like all preference binding.
 */
public class SegmentedListPreference extends ListPreference {

    /** Above this many options a segmented row stops being readable. */
    private static final int MAX_SENSIBLE_SEGMENTS = 6;

    private LinearLayout container;

    public SegmentedListPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        setLayoutResource(R.layout.pref_segmented);
    }

    public SegmentedListPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        setLayoutResource(R.layout.pref_segmented);
    }

    @Override
    protected void onClick() {
        // Deliberately does nothing. The whole point is that the choice is made
        // on the row itself; falling through to super would open the dropdown
        // dialog we are replacing.
    }

    @Override
    public void onBindViewHolder(PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);

        TextView title = (TextView) holder.findViewById(R.id.segmented_title);
        if (title != null) {
            title.setText(getTitle());
        }

        TextView summary = (TextView) holder.findViewById(R.id.segmented_summary);
        if (summary != null) {
            CharSequence s = getSummary();
            if (s != null && s.length() > 0) {
                summary.setText(s);
                summary.setVisibility(View.VISIBLE);
            }
            else {
                summary.setVisibility(View.GONE);
            }
        }

        container = (LinearLayout) holder.findViewById(R.id.segmented_container);
        rebuildSegments(holder);
    }

    private void rebuildSegments(PreferenceViewHolder holder) {
        if (container == null) {
            return;
        }

        container.removeAllViews();

        CharSequence[] entries = getEntries();
        CharSequence[] values = getEntryValues();
        if (entries == null || values == null || entries.length != values.length) {
            // Malformed preference. Render nothing rather than a half row that
            // would write a value the caller never declared.
            return;
        }

        String current = getValue();
        TextView valueLabel = (TextView) holder.findViewById(R.id.segmented_value);

        // Long lists scroll horizontally instead of being crushed; the container
        // only stretches to fill when the options actually fit.
        boolean stretch = entries.length <= MAX_SENSIBLE_SEGMENTS;

        for (int i = 0; i < entries.length; i++) {
            final String value = values[i].toString();
            boolean selected = value.equals(current);

            if (selected && valueLabel != null) {
                valueLabel.setText(entries[i]);
            }

            TextView segment = new TextView(getContext());
            segment.setText(entries[i]);
            segment.setGravity(Gravity.CENTER);
            segment.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                    getContext().getResources().getDimension(R.dimen.settings_segment_text));
            segment.setMinHeight((int) getContext().getResources()
                    .getDimension(R.dimen.settings_segment_min_height));
            int padH = dp(14);
            segment.setPadding(padH, dp(8), padH, dp(8));
            segment.setSingleLine(true);

            applySegmentStyle(segment, selected);

            LinearLayout.LayoutParams lp = stretch
                    ? new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    : new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.gravity = Gravity.CENTER_VERTICAL;
            container.addView(segment, lp);

            if (isEnabled()) {
                segment.setOnClickListener(v -> onSegmentChosen(value));
            }
            else {
                segment.setEnabled(false);
                segment.setAlpha(0.45f);
            }
        }
    }

    private void onSegmentChosen(String value) {
        if (value.equals(getValue())) {
            return;
        }
        // callChangeListener first so a listener that vetoes the change is
        // honoured, exactly as ListPreference's own dialog path does.
        if (callChangeListener(value)) {
            setValue(value);
            notifyChanged();
        }
    }

    private void applySegmentStyle(TextView segment, boolean selected) {
        if (selected) {
            segment.setBackgroundResource(R.drawable.settings_segment_selected);
            segment.setTextColor(getContext().getResources().getColor(R.color.segment_text_selected));
        }
        else {
            segment.setBackgroundResource(R.drawable.settings_segment_unselected);
            segment.setTextColor(getContext().getResources().getColor(R.color.segment_text));
        }
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                getContext().getResources().getDisplayMetrics());
    }
}
