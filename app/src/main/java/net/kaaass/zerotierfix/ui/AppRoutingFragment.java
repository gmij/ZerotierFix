package net.kaaass.zerotierfix.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
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
import net.kaaass.zerotierfix.ui.adapter.SelectedAppsAdapter;
import net.kaaass.zerotierfix.util.AppUtils;
import net.kaaass.zerotierfix.util.DatabaseUtils;
import net.kaaass.zerotierfix.util.LogUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;

/**
 * Fragment for showing selected apps with ability to add/remove
 * Used in network detail page for per-app routing configuration
 */
public class AppRoutingFragment extends Fragment {
    private static final String TAG = "AppRoutingFragment";
    public static final String NETWORK_ID_MESSAGE = "network_id";

    private long networkId;
    private TextView selectedAppsCount;
    private TextView emptyStateText;
    private Button addAppsButton;
    private RecyclerView selectedAppsRecyclerView;
    private SelectedAppsAdapter adapter;

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
        selectedAppsCount = view.findViewById(R.id.selected_apps_count);
        emptyStateText = view.findViewById(R.id.empty_state_text);
        addAppsButton = view.findViewById(R.id.add_apps_button);
        selectedAppsRecyclerView = view.findViewById(R.id.selected_apps_recycler_view);

        // 设置RecyclerView
        selectedAppsRecyclerView.setLayoutManager(new LinearLayoutManager(getContext()));
        selectedAppsRecyclerView.setNestedScrollingEnabled(false);
        adapter = new SelectedAppsAdapter(this::onAppRemoved);
        selectedAppsRecyclerView.setAdapter(adapter);

        // 设置添加应用按钮
        addAppsButton.setOnClickListener(v -> {
            Intent intent = new Intent(getActivity(), AppRoutingActivity.class);
            intent.putExtra(NETWORK_ID_MESSAGE, networkId);
            startActivity(intent);
        });

        // 加载选中的应用
        loadSelectedApps();

        return view;
    }

    /**
     * 加载选中的应用列表
     */
    private void loadSelectedApps() {
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
                updateSelectedAppsList();
            });
        });
    }

    /**
     * 更新显示的选中应用列表
     */
    private void updateSelectedAppsList() {
        List<AppInfo> selectedApps = new ArrayList<>();
        
        for (AppInfo app : allApps) {
            if (app.isRouteViaVpn()) {
                selectedApps.add(app);
            }
        }

        // 按应用名称排序
        selectedApps.sort((app1, app2) -> {
            String name1 = app1.getAppName() != null ? app1.getAppName() : "";
            String name2 = app2.getAppName() != null ? app2.getAppName() : "";
            return name1.compareToIgnoreCase(name2);
        });

        adapter.setSelectedApps(selectedApps);
        selectedAppsCount.setText(getString(R.string.selected_apps_count, selectedApps.size()));
        
        // Show/hide empty state
        if (selectedApps.isEmpty()) {
            emptyStateText.setVisibility(View.VISIBLE);
            selectedAppsRecyclerView.setVisibility(View.GONE);
        } else {
            emptyStateText.setVisibility(View.GONE);
            selectedAppsRecyclerView.setVisibility(View.VISIBLE);
        }
    }

    /**
     * 移除应用时的处理
     */
    private void onAppRemoved(AppInfo app) {
        // 更新应用的路由设置
        app.setRouteViaVpn(false);
        
        // 更新显示
        updateSelectedAppsList();
        
        // 保存到数据库
        Executors.newSingleThreadExecutor().execute(() -> {
            DatabaseUtils.writeLock.lock();
            try {
                var daoSession = ((ZerotierFixApplication) requireActivity().getApplication()).getDaoSession();
                var appRoutingDao = daoSession.getAppRoutingDao();
                
                // 删除对应的路由设置
                var existing = appRoutingDao.queryBuilder()
                        .where(AppRoutingDao.Properties.NetworkId.eq(networkId),
                               AppRoutingDao.Properties.PackageName.eq(app.getPackageName()))
                        .list();
                
                if (!existing.isEmpty()) {
                    appRoutingDao.delete(existing.get(0));
                }
            } finally {
                DatabaseUtils.writeLock.unlock();
            }
            
            LogUtil.d(TAG, "Removed app routing: " + app.getPackageName());
        });
    }
    
    @Override
    public void onResume() {
        super.onResume();
        // Reload when returning from AppRoutingActivity
        loadSelectedApps();
    }
}
