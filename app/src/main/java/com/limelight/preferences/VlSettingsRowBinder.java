package com.limelight.preferences;

import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.preference.Preference;
import androidx.preference.PreferenceGroup;
import androidx.preference.PreferenceViewHolder;

import com.limelight.R;

/**
 * Shared binding helpers for the VoidLink-styled preference rows.
 *
 * <p>Every row lives inside a rounded "card" that is drawn by the section header plus its
 * rows, so the background of a row depends on whether it is the last visible row of its
 * section. Rows also hide their summary behind a trailing circled-i affordance: the summary
 * text already present in preferences.xml is what gets revealed, no new copy is invented.</p>
 */
final class VlSettingsRowBinder {
    private static final float DISABLED_ALPHA = 0.4f;

    private VlSettingsRowBinder() {
    }

    /**
     * Applies the card background, the enabled/disabled dimming and the info affordance.
     * Must be called <b>after</b> {@code super.onBindViewHolder()} so that the summary
     * visibility computed by the framework can be reinterpreted here.
     */
    static void bindRow(Preference preference, PreferenceViewHolder holder,
                        boolean summaryExpanded, View.OnClickListener infoClickListener) {
        View itemView = holder.itemView;
        itemView.setBackgroundResource(isLastVisibleInGroup(preference)
                ? R.drawable.vl_settings_bg_card_bottom
                : R.drawable.vl_settings_bg_card_middle);

        // The framework has just made the summary visible if (and only if) there is
        // summary text to show. Use that as the signal for "this row has help text".
        View summaryView = holder.findViewById(android.R.id.summary);
        boolean hasSummary = summaryView != null && summaryView.getVisibility() == View.VISIBLE;
        if (summaryView != null) {
            summaryView.setVisibility(hasSummary && summaryExpanded ? View.VISIBLE : View.GONE);
        }

        View infoButton = holder.findViewById(R.id.vl_settings_info);
        if (infoButton != null) {
            infoButton.setVisibility(hasSummary ? View.VISIBLE : View.GONE);
            if (hasSummary) {
                infoButton.setOnClickListener(infoClickListener);
            } else {
                infoButton.setOnClickListener(null);
                infoButton.setClickable(false);
            }
            infoButton.setSelected(summaryExpanded);
        }

        dimContent(itemView, preference.isEnabled());
    }

    /** Shows the current value of a row in the accent color, or hides it when empty. */
    static void bindValue(PreferenceViewHolder holder, CharSequence value) {
        View view = holder.findViewById(R.id.vl_settings_value);
        if (!(view instanceof TextView)) {
            return;
        }
        TextView valueView = (TextView) view;
        if (TextUtils.isEmpty(value)) {
            valueView.setText("");
            valueView.setVisibility(View.GONE);
        } else {
            valueView.setText(value);
            valueView.setVisibility(View.VISIBLE);
        }
    }

    /**
     * Dims the row content (not its background) when the preference is disabled by a
     * dependency, keeping the card surface itself opaque.
     */
    static void dimContent(View itemView, boolean enabled) {
        float alpha = enabled ? 1f : DISABLED_ALPHA;
        if (itemView instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) itemView;
            for (int i = 0; i < group.getChildCount(); i++) {
                group.getChildAt(i).setAlpha(alpha);
            }
        } else {
            itemView.setAlpha(alpha);
        }
    }

    /** True when this preference is the last currently visible row of its section. */
    static boolean isLastVisibleInGroup(Preference preference) {
        PreferenceGroup parent = preference.getParent();
        if (parent == null) {
            return true;
        }
        for (int i = parent.getPreferenceCount() - 1; i >= 0; i--) {
            Preference candidate = parent.getPreference(i);
            if (candidate.isVisible()) {
                return candidate == preference;
            }
        }
        return true;
    }
}
