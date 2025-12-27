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

/**
 * 应用路由设置界面
 */
public class AppRoutingFragment extends Fragment {
    private static final String TAG = "AppRoutingFragment";
    public static final String NETWORK_ID_MESSAGE = "network_id";

    private long networkId;
    private LinearLayout appListContainer;
    private CheckBox showSystemAppsCheckbox;
    private TextView selectedAppsCount;
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
        appListContainer = view.findViewById(R.id.app_list_container);
        showSystemAppsCheckbox = view.findViewById(R.id.show_system_apps_checkbox);
        selectedAppsCount = view.findViewById(R.id.selected_apps_count);
        appsRecyclerView = view.findViewById(R.id.apps_recycler_view);

        // 设置RecyclerView
        appsRecyclerView.setLayoutManager(new LinearLayoutManager(getContext()));
        appsRecyclerView.setNestedScrollingEnabled(false);
        adapter = new AppRoutingAdapter(this::onAppRoutingChanged);
        appsRecyclerView.setAdapter(adapter);

        // 设置显示系统应用切换监听器
        showSystemAppsCheckbox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            showSystemApps = isChecked;
            updateAppList();
        });

        // 直接加载应用列表（因为只有在per-app模式下才能访问此界面）
        loadApps();

        return view;
    }

    /**
     * 加载应用列表
     */
    private void loadApps() {
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

        // 排序：选中的应用优先显示在前面
        filteredApps.sort((app1, app2) -> {
            // 先按是否选中排序（选中的在前）
            if (app1.isRouteViaVpn() != app2.isRouteViaVpn()) {
                return app1.isRouteViaVpn() ? -1 : 1;
            }
            // 再按应用名称排序（处理null情况）
            String name1 = app1.getAppName() != null ? app1.getAppName() : "";
            String name2 = app2.getAppName() != null ? app2.getAppName() : "";
            return name1.compareToIgnoreCase(name2);
        });

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
