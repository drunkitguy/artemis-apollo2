package com.limelight.preferences;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceViewHolder;

import com.limelight.R;

import java.util.HashMap;
import java.util.Map;

/**
 * A collapsible VoidLink settings section.
 *
 * <p>Visually this is a card header with a leading glyph ({@code android:icon}), a title and
 * a chevron that rotates when the section is open. Tapping the header hides or shows the
 * section's children; no preference is ever removed, only made temporarily invisible, and
 * each child's own visibility is restored when the section is reopened.</p>
 *
 * <p>An {@code expanded="true"} attribute makes a section start open. Expansion state is kept
 * in a process-wide map so it survives the fragment recreation that
 * {@link StreamSettings#reloadSettings()} performs; nothing is written to SharedPreferences.</p>
 */
public class VlSettingsSection extends PreferenceCategory {
    private static final Map<String, Boolean> EXPANDED_STATE = new HashMap<>();

    private final Map<Preference, Boolean> savedChildVisibility = new HashMap<>();
    private boolean defaultExpanded;

    public VlSettingsSection(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        initialize(attrs);
    }

    public VlSettingsSection(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        initialize(attrs);
    }

    public VlSettingsSection(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        initialize(attrs);
    }

    public VlSettingsSection(@NonNull Context context) {
        super(context);
        initialize(null);
    }

    private void initialize(@Nullable AttributeSet attrs) {
        defaultExpanded = attrs != null && attrs.getAttributeBooleanValue(null, "expanded", false);
        setLayoutResource(R.layout.vl_settings_section_header);
    }

    // PreferenceCategory reports itself as disabled, which would make the framework mark the
    // header view (and every view inside it) disabled, so it could never be tapped. The
    // section header is interactive, so report it as enabled. Dependents are unaffected:
    // PreferenceCategory.shouldDisableDependents() consults Preference.isEnabled() directly.
    @Override
    public boolean isEnabled() {
        return true;
    }

    private String stateKey() {
        String key = getKey();
        if (key != null) {
            return key;
        }
        CharSequence title = getTitle();
        return title != null ? title.toString() : "vl_settings_section";
    }

    public boolean isExpanded() {
        Boolean expanded = EXPANDED_STATE.get(stateKey());
        return expanded != null ? expanded : defaultExpanded;
    }

    public void setExpanded(boolean expanded) {
        EXPANDED_STATE.put(stateKey(), expanded);
        applyChildVisibility();
        notifyChanged();
    }

    /**
     * Pushes the current expansion state onto the children. Call this once after the
     * preference tree has been built (and after any conditional removals) so the list is
     * rendered with the right rows from the start.
     */
    public void applyChildVisibility() {
        boolean expanded = isExpanded();
        for (int i = 0; i < getPreferenceCount(); i++) {
            Preference child = getPreference(i);
            if (expanded) {
                Boolean saved = savedChildVisibility.remove(child);
                child.setVisible(saved == null || saved);
            } else {
                if (!savedChildVisibility.containsKey(child)) {
                    savedChildVisibility.put(child, child.isVisible());
                }
                child.setVisible(false);
            }
        }
    }

    private boolean hasVisibleChildren() {
        for (int i = 0; i < getPreferenceCount(); i++) {
            if (getPreference(i).isVisible()) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void onBindViewHolder(@NonNull PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);

        boolean expanded = isExpanded();
        View itemView = holder.itemView;

        // A closed section (or one whose rows were all removed for this device) is a card on
        // its own; an open one is the top of the card its rows continue.
        itemView.setBackgroundResource(expanded && hasVisibleChildren()
                ? R.drawable.vl_settings_bg_card_top
                : R.drawable.vl_settings_bg_card_full);

        View chevron = holder.findViewById(R.id.vl_settings_section_chevron);
        if (chevron instanceof ImageView) {
            chevron.setRotation(expanded ? 90f : 0f);
        }

        setEnabledRecursively(itemView, true);
        itemView.setClickable(true);
        itemView.setFocusable(true);
        itemView.setOnClickListener(v -> setExpanded(!isExpanded()));

        holder.setDividerAllowedAbove(false);
        holder.setDividerAllowedBelow(false);
    }

    private static void setEnabledRecursively(View view, boolean enabled) {
        view.setEnabled(enabled);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                setEnabledRecursively(group.getChildAt(i), enabled);
            }
        }
    }
}
