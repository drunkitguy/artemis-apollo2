package com.limelight.grid;

import android.content.Context;
import android.content.res.Resources;
import android.view.View;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.RelativeLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.limelight.PcView;
import com.limelight.R;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.PairingManager;
import com.limelight.preferences.PreferenceConfiguration;

import java.util.Collections;
import java.util.Comparator;

public class PcGridAdapter extends GenericGridAdapter<PcView.ComputerObject> {

    /**
     * Invoked when the footer button of a host card is tapped. The action itself
     * is implemented by PcView so that all of the existing pairing/WOL/app-list
     * logic stays in one place.
     */
    public interface HostActionListener {
        void onHostActionClicked(PcView.ComputerObject computer, View anchorView);
    }

    private HostActionListener hostActionListener;

    public PcGridAdapter(Context context, PreferenceConfiguration prefs) {
        super(context, getLayoutIdForPreferences(prefs));
    }

    public void setHostActionListener(HostActionListener listener) {
        this.hostActionListener = listener;
    }

    private static int getLayoutIdForPreferences(PreferenceConfiguration prefs) {
        return R.layout.pc_grid_item;
    }

    public void updateLayoutWithPreferences(Context context, PreferenceConfiguration prefs) {
        // This will trigger the view to reload with the new layout
        setLayoutId(getLayoutIdForPreferences(prefs));
    }

    public void addComputer(PcView.ComputerObject computer) {
        itemList.add(computer);
        sortList();
    }

    private void sortList() {
        Collections.sort(itemList, new Comparator<PcView.ComputerObject>() {
            @Override
            public int compare(PcView.ComputerObject lhs, PcView.ComputerObject rhs) {
                return lhs.details.name.toLowerCase().compareTo(rhs.details.name.toLowerCase());
            }
        });
    }

    public boolean removeComputer(PcView.ComputerObject computer) {
        return itemList.remove(computer);
    }

    private int color(int colorResId) {
        return ContextCompat.getColor(context, colorResId);
    }

    private void applyActionStyle(View actionView, ImageView actionIcon, TextView actionText,
                                  boolean accented) {
        Resources res = context.getResources();
        int horizontalPadding = res.getDimensionPixelSize(R.dimen.vl_space_l);
        int verticalPadding = res.getDimensionPixelSize(R.dimen.vl_space_m);

        actionView.setBackgroundResource(accented ?
                R.drawable.vl_footer_button_accent : R.drawable.vl_footer_button_neutral);

        // setBackgroundResource() can clobber padding, so re-apply it explicitly
        actionView.setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding);

        int foreground = color(accented ? R.color.vl_accent : R.color.vl_secondary_label);
        actionIcon.setColorFilter(foreground);
        actionText.setTextColor(foreground);
    }

    @Override
    public void populateView(View parentView, ImageView imgView, RelativeLayout gridMask, ProgressBar prgView, TextView txtView, ImageView overlayView, final PcView.ComputerObject obj) {
        final ComputerDetails details = obj.details;
        final boolean online = details.state == ComputerDetails.State.ONLINE;
        final boolean unknown = details.state == ComputerDetails.State.UNKNOWN;
        final boolean paired = details.pairState == PairingManager.PairState.PAIRED;

        View tileView = parentView.findViewById(R.id.grid_image_layout);
        ImageView statusIcon = parentView.findViewById(R.id.vl_status_icon);
        TextView statusText = parentView.findViewById(R.id.vl_status_text);
        View actionView = parentView.findViewById(R.id.vl_card_action);
        ImageView actionIcon = parentView.findViewById(R.id.vl_card_action_icon);
        TextView actionText = parentView.findViewById(R.id.vl_card_action_text);

        // Tile: accent fill when the host is reachable, neutral fill otherwise
        tileView.setBackgroundResource(online ?
                R.drawable.vl_tile_accent : R.drawable.vl_tile_neutral);

        imgView.setImageResource(R.drawable.vl_ic_monitor);
        imgView.setColorFilter(color(online ? R.color.vl_accent : R.color.vl_offline));
        imgView.setVisibility(unknown ? View.INVISIBLE : View.VISIBLE);
        imgView.setAlpha(1.0f);

        // Spinner while we're still determining the state of this host
        prgView.setVisibility(unknown ? View.VISIBLE : View.GONE);

        txtView.setText(details.name);
        txtView.setAlpha(1.0f);

        // Status line
        if (online) {
            statusIcon.setImageResource(R.drawable.vl_ic_wifi);
            statusIcon.setColorFilter(color(R.color.vl_online));
            statusText.setText(R.string.pcview_menu_header_online);
            statusText.setTextColor(color(R.color.vl_online));
        }
        else if (unknown) {
            statusIcon.setImageResource(R.drawable.vl_ic_warning);
            statusIcon.setColorFilter(color(R.color.vl_secondary_label));
            statusText.setText(R.string.pcview_menu_header_unknown);
            statusText.setTextColor(color(R.color.vl_secondary_label));
        }
        else {
            statusIcon.setImageResource(R.drawable.vl_ic_warning);
            statusIcon.setColorFilter(color(R.color.vl_offline));
            statusText.setText(R.string.pcview_menu_header_offline);
            statusText.setTextColor(color(R.color.vl_offline));
        }

        // Padlock badge on the tile. We must check that the status is exactly online
        // and unpaired to avoid showing it while the state is still unknown.
        if (online && !paired) {
            overlayView.setImageResource(R.drawable.vl_ic_lock_closed);
            overlayView.setColorFilter(color(R.color.vl_secondary_label));
            overlayView.setAlpha(1.0f);
            overlayView.setVisibility(View.VISIBLE);
        }
        else {
            overlayView.setVisibility(View.GONE);
        }

        // Footer button
        if (online && paired) {
            actionIcon.setImageResource(R.drawable.vl_ic_connect);
            actionText.setText(R.string.vl_action_connect);
            applyActionStyle(actionView, actionIcon, actionText, true);
        }
        else if (online) {
            actionIcon.setImageResource(R.drawable.vl_ic_lock_open);
            actionText.setText(R.string.vl_action_pair_with_pin);
            applyActionStyle(actionView, actionIcon, actionText, true);
        }
        else if (unknown) {
            actionIcon.setImageResource(R.drawable.vl_ic_power);
            actionText.setText(R.string.vl_action_refreshing);
            applyActionStyle(actionView, actionIcon, actionText, false);
        }
        else {
            actionIcon.setImageResource(R.drawable.vl_ic_power);
            actionText.setText(R.string.vl_action_wake_with_lan);
            applyActionStyle(actionView, actionIcon, actionText, false);
        }

        actionView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (hostActionListener != null) {
                    hostActionListener.onHostActionClicked(obj, v);
                }
            }
        });

        // Long-pressing the footer must still raise the host context menu, just
        // like long-pressing anywhere else on the card does.
        actionView.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                return v.showContextMenu();
            }
        });
    }
}
