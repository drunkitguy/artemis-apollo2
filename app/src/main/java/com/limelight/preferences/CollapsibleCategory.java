package com.limelight.preferences;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.TypedArray;
import android.util.AttributeSet;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceGroup;
import androidx.preference.PreferenceViewHolder;

import com.limelight.R;

/**
 * A settings section that collapses, carries an icon, and shows a live value in
 * its header.
 *
 * <p>The problem this solves is navigational, not decorative: the settings
 * screen is 134 preferences in one flat scroll, so finding anything means
 * reading past everything. Collapsed sections turn that into a list of thirteen
 * headings.
 *
 * <p>The live value is what makes collapsing safe rather than merely tidy. A
 * collapsed section that shows "Video &mdash; 1080p 60 &middot; 20 Mbps" answers the
 * question most people opened settings to ask, so they never expand it. A
 * collapsed section that shows nothing just hides things.
 *
 * <h3>What this deliberately does not do</h3>
 * It does not touch any child preference's key, default or persistence. It is a
 * {@link PreferenceCategory}, so it is purely a container; expanding and
 * collapsing sets child visibility and nothing else. The expanded/collapsed
 * state is stored under its own private key namespace
 * ({@value #EXPANDED_STATE_PREFIX}) so it cannot collide with a real setting.
 *
 * <p>Thread ownership: everything here runs on the main thread, as all
 * {@code Preference} binding does. No shared state.
 */
public class CollapsibleCategory extends PreferenceCategory {

    /**
     * Namespace for the remembered expand state. Deliberately verbose so it can
     * never collide with a real preference key, and so a stray entry in the
     * shared preferences file is obviously ours.
     */
    private static final String EXPANDED_STATE_PREFIX = "ui_section_expanded_";

    private static final String CHEVRON_EXPANDED = "▼";
    private static final String CHEVRON_COLLAPSED = "▶";

    private int iconResId;
    private boolean expanded;
    private boolean expandedLoaded;

    /**
     * Live text shown on the header. Null hides the field. Owned by whoever
     * calls {@link #setLiveValue}, which is the settings fragment on the main
     * thread.
     */
    private CharSequence liveValue;

    public CollapsibleCategory(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context, attrs);
    }

    public CollapsibleCategory(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context, attrs);
    }

    private void init(Context context, AttributeSet attrs) {
        setLayoutResource(R.layout.pref_collapsible_category);

        if (attrs != null) {
            TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.CollapsibleCategory);
            try {
                iconResId = a.getResourceId(R.styleable.CollapsibleCategory_sectionIcon, 0);
                // Sections start collapsed by default. A screen that opens fully
                // expanded is the screen we are replacing.
                expanded = a.getBoolean(R.styleable.CollapsibleCategory_startExpanded, false);
            }
            finally {
                a.recycle();
            }
        }
    }

    /** Sets the live summary text on the header. Pass null to hide it. */
    public void setLiveValue(CharSequence value) {
        this.liveValue = value;
        notifyChanged();
    }

    private String expandedStateKey() {
        // getKey() is required on every section for this reason; fall back to the
        // title so a section without a key still gets stable-ish behaviour rather
        // than sharing one global state with every other section.
        String key = getKey();
        if (key == null || key.isEmpty()) {
            key = String.valueOf(getTitle());
        }
        return EXPANDED_STATE_PREFIX + key;
    }

    private void loadExpandedStateIfNeeded() {
        if (expandedLoaded) {
            return;
        }
        expandedLoaded = true;

        SharedPreferences prefs = getSharedPreferences();
        if (prefs != null) {
            expanded = prefs.getBoolean(expandedStateKey(), expanded);
        }
    }

    private void saveExpandedState() {
        SharedPreferences prefs = getSharedPreferences();
        if (prefs != null) {
            prefs.edit().putBoolean(expandedStateKey(), expanded).apply();
        }
    }

    /**
     * Applies the current expanded state to the children.
     *
     * <p>Uses {@code setVisible} rather than removing and re-adding preferences.
     * Removing them would detach them from the preference manager, which is how
     * you accidentally stop a setting persisting; visibility leaves every child
     * fully live and merely unrendered.
     */
    private void applyExpandedState() {
        for (int i = 0; i < getPreferenceCount(); i++) {
            setSubtreeVisible(getPreference(i), expanded);
        }
    }

    private void setSubtreeVisible(Preference preference, boolean visible) {
        preference.setVisible(visible);
        // A nested group's own children inherit visibility from the group in the
        // adapter, so there is nothing further to walk for correctness. Recursing
        // anyway would fight any child that manages its own visibility.
    }

    @Override
    public void onAttached() {
        super.onAttached();
        loadExpandedStateIfNeeded();
        applyExpandedState();
    }

    @Override
    public void onBindViewHolder(PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        loadExpandedStateIfNeeded();

        ImageView icon = (ImageView) holder.findViewById(R.id.section_icon);
        if (icon != null) {
            if (iconResId != 0) {
                icon.setImageResource(iconResId);
                icon.setVisibility(View.VISIBLE);
            }
            else {
                icon.setVisibility(View.GONE);
            }
        }

        TextView title = (TextView) holder.findViewById(R.id.section_title);
        if (title != null) {
            title.setText(getTitle());
        }

        TextView value = (TextView) holder.findViewById(R.id.section_value);
        if (value != null) {
            if (liveValue != null && liveValue.length() > 0) {
                value.setText(liveValue);
                value.setVisibility(View.VISIBLE);
            }
            else {
                value.setVisibility(View.GONE);
            }
        }

        TextView chevron = (TextView) holder.findViewById(R.id.section_chevron);
        if (chevron != null) {
            chevron.setText(expanded ? CHEVRON_EXPANDED : CHEVRON_COLLAPSED);
        }

        holder.itemView.setClickable(true);
        holder.itemView.setOnClickListener(v -> toggle());

        // A PreferenceCategory is not selectable by default, which would swallow
        // the click before it reached the listener above.
        holder.itemView.setFocusable(true);
    }

    private void toggle() {
        expanded = !expanded;
        saveExpandedState();
        applyExpandedState();
        notifyChanged();
    }

    /** Expands or collapses without a user tap, e.g. from a search result. */
    public void setExpanded(boolean expand) {
        loadExpandedStateIfNeeded();
        if (expanded == expand) {
            return;
        }
        expanded = expand;
        saveExpandedState();
        applyExpandedState();
        notifyChanged();
    }

    public boolean isExpanded() {
        loadExpandedStateIfNeeded();
        return expanded;
    }

    /** Convenience for callers holding a {@link PreferenceGroup} reference. */
    public static void setLiveValueIfPresent(PreferenceGroup group, CharSequence value) {
        if (group instanceof CollapsibleCategory) {
            ((CollapsibleCategory) group).setLiveValue(value);
        }
    }
}
