package net.kaaass.zerotierfix.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.SwitchCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import net.kaaass.zerotierfix.R;
import net.kaaass.zerotierfix.ZerotierFixApplication;
import net.kaaass.zerotierfix.model.AppInfo;
import net.kaaass.zerotierfix.model.AppRouting;
import net.kaaass.zerotierfix.model.AppRoutingDao;
import net.kaaass.zerotierfix.model.Network;
import net.kaaass.zerotierfix.model.NetworkConfig;
import net.kaaass.zerotierfix.model.NetworkDao;
import net.kaaass.zerotierfix.ui.adapter.AppRoutingAdapter;
import net.kaaass.zerotierfix.util.AppUtils;
import net.kaaass.zerotierfix.util.DatabaseUtils;
import net.kaaass.zerotierfix.util.LogUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;

/**
 * 应用路由设置界面
 */
public class AppRoutingFragment extends Fragment {
    private static final String TAG = "AppRoutingFragment";
    public static final String NETWORK_ID_MESSAGE = "network_id";

    private long networkId;
    private SwitchCompat routingModeSwitch;
    private TextView routingModeDescription;
    private LinearLayout appListContainer;
    private CheckBox showSystemAppsCheckbox;
    private TextView selectedAppsCount;
    private SwipeRefreshLayout swipeRefreshLayout;
    private RecyclerView appsRecyclerView;
    private AppRoutingAdapter adapter;

    private boolean showSystemApps = false;
    private List<AppInfo> allApps = new ArrayList<>();

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
        View view = inflater.inflate(R.layout.fragment_app_routing, container, false);

        // 初始化视图
        routingModeSwitch = view.findViewById(R.id.routing_mode_switch);
        routingModeDescription = view.findViewById(R.id.routing_mode_description);
        appListContainer = view.findViewById(R.id.app_list_container);
        showSystemAppsCheckbox = view.findViewById(R.id.show_system_apps_checkbox);
        selectedAppsCount = view.findViewById(R.id.selected_apps_count);
        swipeRefreshLayout = view.findViewById(R.id.swipe_refresh_layout);
        appsRecyclerView = view.findViewById(R.id.apps_recycler_view);

        // 设置RecyclerView
        appsRecyclerView.setLayoutManager(new LinearLayoutManager(getContext()));
        adapter = new AppRoutingAdapter(this::onAppRoutingChanged);
        appsRecyclerView.setAdapter(adapter);

        // 设置刷新监听器
        swipeRefreshLayout.setOnRefreshListener(this::loadApps);

        // 设置路由模式切换监听器
        routingModeSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            onRoutingModeChanged(isChecked);
        });

        // 设置显示系统应用切换监听器
        showSystemAppsCheckbox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            showSystemApps = isChecked;
            updateAppList();
        });

        // 加载当前路由模式
        loadRoutingMode();

        return view;
    }

    /**
     * 加载路由模式设置
     */
    private void loadRoutingMode() {
        Executors.newSingleThreadExecutor().execute(() -> {
            DatabaseUtils.readLock.lock();
            try {
                var daoSession = ((ZerotierFixApplication) requireActivity().getApplication()).getDaoSession();
                var networkDao = daoSession.getNetworkDao();
                var networks = networkDao.queryBuilder()
                        .where(NetworkDao.Properties.NetworkId.eq(networkId))
                        .list();

                if (networks.size() == 1) {
                    Network network = networks.get(0);
                    NetworkConfig networkConfig = network.getNetworkConfig();
                    boolean isPerAppRouting = networkConfig.getPerAppRouting();

                    requireActivity().runOnUiThread(() -> {
                        routingModeSwitch.setChecked(isPerAppRouting);
                        updateUIForRoutingMode(isPerAppRouting);
                    });

                    if (isPerAppRouting) {
                        loadApps();
                    }
                }
            } finally {
                DatabaseUtils.readLock.unlock();
            }
        });
    }

    /**
     * 路由模式改变时的处理
     */
    private void onRoutingModeChanged(boolean isPerAppRouting) {
        updateUIForRoutingMode(isPerAppRouting);
        
        // 保存到数据库
        Executors.newSingleThreadExecutor().execute(() -> {
            DatabaseUtils.writeLock.lock();
            try {
                var daoSession = ((ZerotierFixApplication) requireActivity().getApplication()).getDaoSession();
                var networkDao = daoSession.getNetworkDao();
                var networks = networkDao.queryBuilder()
                        .where(NetworkDao.Properties.NetworkId.eq(networkId))
                        .list();

                if (networks.size() == 1) {
                    Network network = networks.get(0);
                    NetworkConfig networkConfig = network.getNetworkConfig();
                    networkConfig.setPerAppRouting(isPerAppRouting);
                    networkConfig.update();
                    
                    LogUtil.i(TAG, "Routing mode changed to: " + 
                            (isPerAppRouting ? "Per-App" : "Global"));
                }
            } finally {
                DatabaseUtils.writeLock.unlock();
            }
        });

        // 如果切换到per-app模式，加载应用列表
        if (isPerAppRouting) {
            loadApps();
        }
    }

    /**
     * 更新UI以反映路由模式
     */
    private void updateUIForRoutingMode(boolean isPerAppRouting) {
        if (isPerAppRouting) {
            routingModeDescription.setText(R.string.per_app_routing_description);
            appListContainer.setVisibility(View.VISIBLE);
        } else {
            routingModeDescription.setText(R.string.global_routing_description);
            appListContainer.setVisibility(View.GONE);
        }
    }

    /**
     * 加载应用列表
     */
    private void loadApps() {
        swipeRefreshLayout.setRefreshing(true);

        Executors.newSingleThreadExecutor().execute(() -> {
            // 获取所有已安装的应用
            allApps = AppUtils.getAllInstalledApps(requireContext());

            // 从数据库加载已保存的路由设置
            DatabaseUtils.readLock.lock();
            try {
                var daoSession = ((ZerotierFixApplication) requireActivity().getApplication()).getDaoSession();
                var appRoutingDao = daoSession.getAppRoutingDao();
                var savedRoutings = appRoutingDao.queryBuilder()
                        .where(AppRoutingDao.Properties.NetworkId.eq(networkId))
                        .list();

                // 应用已保存的设置
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
                swipeRefreshLayout.setRefreshing(false);
            });
        });
    }

    /**
     * 更新显示的应用列表（根据是否显示系统应用）
     */
    private void updateAppList() {
        List<AppInfo> filteredApps = new ArrayList<>();
        int selectedCount = 0;

        for (AppInfo app : allApps) {
            if (showSystemApps || !app.isSystemApp()) {
                filteredApps.add(app);
            }
            if (app.isRouteViaVpn()) {
                selectedCount++;
            }
        }

        adapter.setAppList(filteredApps);
        selectedAppsCount.setText(getString(R.string.selected_apps_count, selectedCount));
    }

    /**
     * 应用路由设置改变时的处理
     */
    private void onAppRoutingChanged(AppInfo app, boolean routeViaVpn) {
        // 更新计数
        updateAppList();

        // 保存到数据库
        Executors.newSingleThreadExecutor().execute(() -> {
            DatabaseUtils.writeLock.lock();
            try {
                var daoSession = ((ZerotierFixApplication) requireActivity().getApplication()).getDaoSession();
                var appRoutingDao = daoSession.getAppRoutingDao();

                // 查找是否已存在
                var existing = appRoutingDao.queryBuilder()
                        .where(AppRoutingDao.Properties.NetworkId.eq(networkId),
                               AppRoutingDao.Properties.PackageName.eq(app.getPackageName()))
                        .list();

                if (existing.isEmpty()) {
                    // 创建新记录
                    AppRouting routing = new AppRouting();
                    routing.setNetworkId(networkId);
                    routing.setPackageName(app.getPackageName());
                    routing.setRouteViaVpn(routeViaVpn);
                    appRoutingDao.insert(routing);
                } else {
                    // 更新现有记录
                    AppRouting routing = existing.get(0);
                    routing.setRouteViaVpn(routeViaVpn);
                    appRoutingDao.update(routing);
                }

                LogUtil.d(TAG, "App routing updated: " + app.getPackageName() + 
                        " -> " + routeViaVpn);
            } finally {
                DatabaseUtils.writeLock.unlock();
            }
        });
    }
}
