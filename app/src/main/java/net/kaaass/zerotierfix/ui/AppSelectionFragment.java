package net.kaaass.zerotierfix.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import net.kaaass.zerotierfix.R;
import net.kaaass.zerotierfix.ZerotierFixApplication;
import net.kaaass.zerotierfix.model.AppInfo;
import net.kaaass.zerotierfix.model.AppRouting;
import net.kaaass.zerotierfix.model.AppRoutingDao;
import net.kaaass.zerotierfix.ui.adapter.AppRoutingAdapter;
import net.kaaass.zerotierfix.util.AppUtils;
import net.kaaass.zerotierfix.util.DatabaseUtils;
import net.kaaass.zerotierfix.util.LogUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * Fragment for selecting apps from full app list
 * Used when user clicks "Add Apps" button
 */
public class AppSelectionFragment extends Fragment {
    private static final String TAG = "AppSelectionFragment";
    public static final String NETWORK_ID_MESSAGE = "network_id";

    private long networkId;
    private CheckBox showSystemAppsCheckbox;
    private TextView selectedAppsCount;
    private RecyclerView appsRecyclerView;
    private AppRoutingAdapter adapter;

    private boolean showSystemApps = false;
    private List<AppInfo> allApps = new ArrayList<>();
    private static List<AppInfo> cachedAllApps = null; // Cache for faster loading

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        if (getArguments() != null) {
            networkId = getArguments().getLong(NETWORK_ID_MESSAGE);
            LogUtil.d(TAG, "Network ID: " + Long.toHexString(networkId));
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, 
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_app_selection, container, false);

        // Initialize views
        showSystemAppsCheckbox = view.findViewById(R.id.show_system_apps_checkbox);
        selectedAppsCount = view.findViewById(R.id.selected_apps_count);
        appsRecyclerView = view.findViewById(R.id.apps_recycler_view);

        // Setup RecyclerView
        appsRecyclerView.setLayoutManager(new LinearLayoutManager(getContext()));
        adapter = new AppRoutingAdapter(this::onAppRoutingChanged);
        appsRecyclerView.setAdapter(adapter);

        // Setup show system apps toggle
        showSystemAppsCheckbox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            showSystemApps = isChecked;
            updateAppList();
        });

        // Load apps list
        loadApps();

        return view;
    }

    /**
     * Load apps list with caching for faster loading
     */
    private void loadApps() {
        Executors.newSingleThreadExecutor().execute(() -> {
            // Use cached list if available, otherwise load from system
            if (cachedAllApps != null) {
                allApps = new ArrayList<>(cachedAllApps);
            } else {
                allApps = AppUtils.getAllInstalledApps(requireContext());
                cachedAllApps = new ArrayList<>(allApps);
            }

            // Load saved routing settings from database
            DatabaseUtils.readLock.lock();
            try {
                var daoSession = ((ZerotierFixApplication) requireActivity().getApplication()).getDaoSession();
                var appRoutingDao = daoSession.getAppRoutingDao();
                var savedRoutings = appRoutingDao.queryBuilder()
                        .where(AppRoutingDao.Properties.NetworkId.eq(networkId))
                        .list();

                // Apply saved settings
                for (AppRouting routing : savedRoutings) {
                    for (AppInfo app : allApps) {
                        if (app.getPackageName().equals(routing.getPackageName())) {
                            app.setRouteViaVpn(routing.getRouteViaVpn());
                            break;
                        }
                    }
                }
            } finally {
                DatabaseUtils.readLock.unlock();
            }

            requireActivity().runOnUiThread(() -> {
                updateAppList();
            });
        });
    }

    /**
     * Update displayed app list based on filters
     */
    private void updateAppList() {
        List<AppInfo> filteredApps = allApps.stream()
                .filter(app -> showSystemApps || !app.isSystemApp())
                .collect(Collectors.toList());

        // Sort by app name
        filteredApps.sort((app1, app2) -> {
            String name1 = app1.getAppName() != null ? app1.getAppName() : "";
            String name2 = app2.getAppName() != null ? app2.getAppName() : "";
            return name1.compareToIgnoreCase(name2);
        });

        adapter.setAppList(filteredApps);
        updateSelectedCount();
    }

    /**
     * Update selected apps count display
     */
    private void updateSelectedCount() {
        long selectedCount = allApps.stream()
                .filter(AppInfo::isRouteViaVpn)
                .count();
        selectedAppsCount.setText(getString(R.string.selected_apps_count, selectedCount));
    }

    /**
     * Handle app routing change
     */
    private void onAppRoutingChanged(AppInfo app, boolean routeViaVpn) {
        // Update count
        updateSelectedCount();
        
        // Save to database in background
        Executors.newSingleThreadExecutor().execute(() -> {
            DatabaseUtils.writeLock.lock();
            try {
                var daoSession = ((ZerotierFixApplication) requireActivity().getApplication()).getDaoSession();
                var appRoutingDao = daoSession.getAppRoutingDao();
                
                if (routeViaVpn) {
                    // Add or update routing
                    var existing = appRoutingDao.queryBuilder()
                            .where(AppRoutingDao.Properties.NetworkId.eq(networkId),
                                   AppRoutingDao.Properties.PackageName.eq(app.getPackageName()))
                            .unique();
                    
                    if (existing == null) {
                        var newRouting = new AppRouting();
                        newRouting.setNetworkId(networkId);
                        newRouting.setPackageName(app.getPackageName());
                        newRouting.setRouteViaVpn(true);
                        appRoutingDao.insert(newRouting);
                    } else {
                        existing.setRouteViaVpn(true);
                        appRoutingDao.update(existing);
                    }
                } else {
                    // Remove routing
                    var existing = appRoutingDao.queryBuilder()
                            .where(AppRoutingDao.Properties.NetworkId.eq(networkId),
                                   AppRoutingDao.Properties.PackageName.eq(app.getPackageName()))
                            .list();
                    
                    if (!existing.isEmpty()) {
                        appRoutingDao.delete(existing.get(0));
                    }
                }
            } finally {
                DatabaseUtils.writeLock.unlock();
            }
            
            LogUtil.d(TAG, "App routing changed: " + app.getPackageName() + " -> " + routeViaVpn);
        });
    }

    /**
     * Clear the cache (call this when apps are installed/uninstalled)
     */
    public static void clearCache() {
        cachedAllApps = null;
    }
}
