package com.limelight.grid;

import android.content.Context;
import android.graphics.BitmapFactory;
import android.view.View;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.RelativeLayout;
import android.widget.TextView;

import com.limelight.AppView;
import com.limelight.LimeLog;
import com.limelight.R;
import com.limelight.grid.assets.CachedAppAssetLoader;
import com.limelight.grid.assets.DiskAssetLoader;
import com.limelight.grid.assets.MemoryAssetLoader;
import com.limelight.grid.assets.NetworkAssetLoader;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.preferences.PreferenceConfiguration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@SuppressWarnings("unchecked")
public class AppGridAdapter extends GenericGridAdapter<AppView.AppObject> {
    private static final int ART_WIDTH_PX = 300;
    private static final int SMALL_WIDTH_DP = 110;
    private static final int LARGE_WIDTH_DP = 170;

    private final ComputerDetails computer;
    private final String uniqueId;
    private final boolean showHiddenApps;

    private CachedAppAssetLoader loader;
    private Set<Integer> hiddenAppIds = new HashSet<>();
    private ArrayList<AppView.AppObject> allApps = new ArrayList<>();

    public AppGridAdapter(Context context, PreferenceConfiguration prefs, ComputerDetails computer, String uniqueId, boolean showHiddenApps) {
        super(context, getLayoutIdForPreferences(prefs));

        this.computer = computer;
        this.uniqueId = uniqueId;
        this.showHiddenApps = showHiddenApps;

        updateLayoutWithPreferences(context, prefs);
    }

    /**
     * Current search text, or empty for no filtering. Lower-cased once here so
     * the per-app comparison in {@link #rebuildItemList} does not re-case it.
     */
    private String searchFilter = "";

    /**
     * Filters the visible list by name.
     *
     * <p>Needed because the grid is the only way to find anything and a synced
     * library is far larger than the handful of apps this screen was built for.
     * Filtering is a substring match on the name, case-insensitive: prefix-only
     * matching would fail on the way people actually recall titles, and anything
     * fuzzier would need a ranking model this does not warrant.
     *
     * <p>Rebuilds from {@code allApps} rather than narrowing {@code itemList},
     * so deleting characters widens the results again instead of only ever
     * shrinking them.
     */
    public void setSearchFilter(String query) {
        String next = query == null ? "" : query.trim().toLowerCase();
        if (next.equals(searchFilter)) {
            return;
        }
        searchFilter = next;
        rebuildItemList();
        notifyDataSetChanged();
    }

    public boolean isSearchActive() {
        return !searchFilter.isEmpty();
    }

    /** Rebuilds the visible list from all apps, applying hidden state and search. */
    private void rebuildItemList() {
        itemList.clear();
        for (AppView.AppObject app : allApps) {
            if (matchesCurrentFilters(app)) {
                itemList.add(app);
            }
        }
        sortList(itemList);
    }

    private boolean matchesCurrentFilters(AppView.AppObject app) {
        if (app.isHidden && !showHiddenApps) {
            return false;
        }
        if (searchFilter.isEmpty()) {
            return true;
        }
        String name = app.app.getAppName();
        return name != null && name.toLowerCase().contains(searchFilter);
    }

    public void updateHiddenApps(Set<Integer> newHiddenAppIds, boolean hideImmediately) {
        this.hiddenAppIds.clear();
        this.hiddenAppIds.addAll(newHiddenAppIds);

        if (hideImmediately) {
            // Reconstruct the itemList with the new hidden app set
            for (AppView.AppObject app : allApps) {
                app.isHidden = hiddenAppIds.contains(app.app.getAppId());
            }
            rebuildItemList();
        }
        else {
            // Just update the isHidden state to show the correct UI indication
            for (AppView.AppObject app : allApps) {
                app.isHidden = hiddenAppIds.contains(app.app.getAppId());
            }
        }

        notifyDataSetChanged();
    }

    private static int getLayoutIdForPreferences(PreferenceConfiguration prefs) {
        if (prefs.smallIconMode) {
            return R.layout.app_grid_item_small;
        }
        else {
            return R.layout.app_grid_item;
        }
    }

    public void updateLayoutWithPreferences(Context context, PreferenceConfiguration prefs) {
        int dpi = context.getResources().getDisplayMetrics().densityDpi;
        int dp;

        if (prefs.smallIconMode) {
            dp = SMALL_WIDTH_DP;
        }
        else {
            dp = LARGE_WIDTH_DP;
        }

        double scalingDivisor = ART_WIDTH_PX / (dp * (dpi / 160.0));
        if (scalingDivisor < 1.0) {
            // We don't want to make them bigger before draw-time
            scalingDivisor = 1.0;
        }
        LimeLog.info("Art scaling divisor: " + scalingDivisor);

        if (loader != null) {
            // Cancel operations on the old loader
            cancelQueuedOperations();
        }

        this.loader = new CachedAppAssetLoader(computer, scalingDivisor,
                new NetworkAssetLoader(context, uniqueId),
                new MemoryAssetLoader(),
                new DiskAssetLoader(context),
                BitmapFactory.decodeResource(context.getResources(), R.drawable.no_app_image));

        // This will trigger the view to reload with the new layout
        setLayoutId(getLayoutIdForPreferences(prefs));
    }

    public void cancelQueuedOperations() {
        loader.cancelForegroundLoads();
        loader.cancelBackgroundLoads();
        loader.freeCacheMemory();
    }

    private static void sortList(List<AppView.AppObject> list) {
        Collections.sort(list, new Comparator<AppView.AppObject>() {
            @Override
            public int compare(AppView.AppObject lhs, AppView.AppObject rhs) {
                int lIndex = lhs.app.getAppIndex();
                int rIndex = rhs.app.getAppIndex();
                if (lIndex == rIndex) {
                    return lhs.app.getAppName().toLowerCase().compareTo(rhs.app.getAppName().toLowerCase());
                } else {
                    return lIndex - rIndex;
                }
            }
        });
    }

    public void addApp(AppView.AppObject app) {
        addAppInternal(app);
        sortList(allApps);
        sortList(itemList);
    }

    /**
     * Adds without sorting. Callers adding more than one app must call
     * {@link #finishBatchAdd()} afterwards.
     *
     * <p>Split out because {@code addApp()} sorted both lists on every single
     * insertion, so building a list of N apps cost N full sorts of a growing
     * list — fine for the six hand-added apps this was written for, and O(N² log
     * N) for a synced library. The fix is the algorithmic shape, not a faster
     * comparator.
     */
    private void addAppInternal(AppView.AppObject app) {
        // Update hidden state
        app.isHidden = hiddenAppIds.contains(app.app.getAppId());

        // Always add the app to the all apps list
        allApps.add(app);

        // Add the app to the adapter data if it's not hidden
        if (matchesCurrentFilters(app)) {
            itemList.add(app);
        }

        // Box art is deliberately NOT prefetched here.
        //
        // This used to queue a cache load per app as it was added, into an
        // executor with a bounded queue and DiscardOldestPolicy. Past the queue
        // bound the OLDEST requests were silently discarded — which is to say
        // the apps at the top of the list, the first tiles the user actually
        // sees, were the ones guaranteed to lose their prefetch. Silent, and
        // biased toward exactly the wrong end.
        //
        // Loading is now driven entirely by what the grid binds, through
        // populateView() -> populateImageView(). That is scale-independent by
        // construction: the work is proportional to what is on screen rather
        // than to library size, so there is no bound to exceed and no threshold
        // to guess at.
    }

    /** Adds a batch of apps, sorting once at the end rather than per insertion. */
    public void addApps(java.util.Collection<AppView.AppObject> apps) {
        for (AppView.AppObject app : apps) {
            addAppInternal(app);
        }
        finishBatchAdd();
    }

    public void finishBatchAdd() {
        sortList(allApps);
        sortList(itemList);
    }

    /** Total apps known, ignoring hidden state and search. */
    public int getTotalAppCount() {
        return allApps.size();
    }

    public void removeApp(AppView.AppObject app) {
        itemList.remove(app);
        allApps.remove(app);
    }

    @Override
    public void clear() {
        super.clear();
        allApps.clear();
    }

    @Override
    public void populateView(View parentView, ImageView imgView, RelativeLayout gridMask, ProgressBar prgView, TextView txtView, ImageView overlayView, AppView.AppObject obj) {
        // Let the cached asset loader handle it
        loader.populateImageView(obj.app, imgView, txtView);

        if (obj.isRunning) {
            // Show the play button overlay
            overlayView.setImageResource(R.drawable.ic_play);
            overlayView.setVisibility(View.VISIBLE);
            gridMask.setBackgroundColor(0x66000000);
        }
        else {
            overlayView.setVisibility(View.GONE);
            gridMask.setBackgroundColor(0x00000000);
        }

        if (obj.isHidden) {
            parentView.setAlpha(0.40f);
        }
        else {
            parentView.setAlpha(1.0f);
        }
    }
}
