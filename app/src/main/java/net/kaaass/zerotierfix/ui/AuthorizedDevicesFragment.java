package net.kaaass.zerotierfix.ui;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.android.material.snackbar.BaseTransientBottomBar;
import com.google.android.material.snackbar.Snackbar;

import net.kaaass.zerotierfix.R;
import net.kaaass.zerotierfix.ZerotierFixApplication;
import net.kaaass.zerotierfix.events.AuthorizedDevicesReplyEvent;
import net.kaaass.zerotierfix.events.AuthorizedDevicesRequestEvent;
import net.kaaass.zerotierfix.events.NetworkListReplyEvent;
import net.kaaass.zerotierfix.events.NetworkListRequestEvent;
import net.kaaass.zerotierfix.model.AuthorizedDevice;
import net.kaaass.zerotierfix.model.DaoSession;
import net.kaaass.zerotierfix.model.Network;
import net.kaaass.zerotierfix.model.NetworkDao;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.util.ArrayList;
import java.util.List;

import lombok.ToString;

/**
 * 已授权设备信息展示 fragment
 */
public class AuthorizedDevicesFragment extends Fragment {

    public static final String TAG = "AuthorizedDevicesFragment";

    private final List<AuthorizedDevice> deviceList = new ArrayList<>();
    private final List<Network> networkList = new ArrayList<>();

    private final EventBus eventBus;

    private RecyclerViewAdapter recyclerViewAdapter = null;

    private RecyclerView recyclerView = null;

    private SwipeRefreshLayout swipeRefreshLayout = null;

    private View emptyView = null;
    private TextView currentNetworkText = null;
    private Network currentNetwork = null;

    final private RecyclerView.AdapterDataObserver checkIfEmptyObserver = new RecyclerView.AdapterDataObserver() {
        @Override
        public void onChanged() {
            checkIfEmpty();
        }

        @Override
        public void onItemRangeInserted(int positionStart, int itemCount) {
            checkIfEmpty();
        }

        @Override
        public void onItemRangeRemoved(int positionStart, int itemCount) {
            checkIfEmpty();
        }

        /**
         * 检查列表是否为空
         */
        void checkIfEmpty() {
            if (emptyView != null && recyclerViewAdapter != null) {
                final boolean emptyViewVisible = recyclerViewAdapter.getItemCount() == 0;
                emptyView.setVisibility(emptyViewVisible ? View.VISIBLE : View.GONE);
                recyclerView.setVisibility(emptyViewVisible ? View.GONE : View.VISIBLE);
            }
        }
    };

    public AuthorizedDevicesFragment() {
        this.eventBus = EventBus.getDefault();
        this.eventBus.register(this);
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_authorized_devices, container, false);

        // 空列表提示
        this.emptyView = view.findViewById(R.id.no_data);

        // 当前网络
        this.currentNetworkText = view.findViewById(R.id.current_network);

        // 设置适配器
        this.recyclerView = view.findViewById(R.id.list_device);
        Context context = this.recyclerView.getContext();
        this.recyclerView.setLayoutManager(new LinearLayoutManager(context));
        this.recyclerViewAdapter = new RecyclerViewAdapter(this.deviceList);
        this.recyclerViewAdapter.registerAdapterDataObserver(checkIfEmptyObserver);
        this.recyclerView.setAdapter(this.recyclerViewAdapter);

        // 设置下拉刷新
        this.swipeRefreshLayout = view.findViewById(R.id.swipe_refresh_device);
        this.swipeRefreshLayout.setOnRefreshListener(this::onRefresh);

        // 请求网络列表
        this.eventBus.post(new NetworkListRequestEvent());

        return view;
    }

    /**
     * 收到网络列表
     */
    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onNetworkListReplyEvent(NetworkListReplyEvent event) {
        // 获取网络列表
        List<Network> networks = getNetworkList();
        if (networks != null && !networks.isEmpty()) {
            this.networkList.clear();
            this.networkList.addAll(networks);
            
            // 查找活跃网络
            for (Network network : networks) {
                if (network.getLastActivated()) {
                    this.currentNetwork = network;
                    updateNetworkDisplay();
                    // 请求该网络的已授权设备
                    requestAuthorizedDevices(network);
                    break;
                }
            }
        }
    }
    
    /**
     * 获取网络列表
     */
    private List<Network> getNetworkList() {
        try {
            DaoSession daoSession = ((ZerotierFixApplication) requireActivity().getApplication()).getDaoSession();
            daoSession.clear();
            return daoSession.getNetworkDao().queryBuilder().orderAsc(NetworkDao.Properties.NetworkId).build().forCurrentThread().list();
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private void updateNetworkDisplay() {
        if (this.currentNetwork != null && this.currentNetworkText != null) {
            String networkName = this.currentNetwork.getNetworkName();
            if (networkName == null || networkName.isEmpty()) {
                networkName = this.currentNetwork.getNetworkIdStr();
            }
            this.currentNetworkText.setText(String.format(getString(R.string.current_network), networkName));
        }
    }

    private void requestAuthorizedDevices(Network network) {
        this.eventBus.post(new AuthorizedDevicesRequestEvent(network.getNetworkId()));
    }

    /**
     * 刷新列表
     */
    public void onRefresh() {
        if (this.currentNetwork != null) {
            requestAuthorizedDevices(this.currentNetwork);
        } else {
            this.eventBus.post(new NetworkListRequestEvent());
        }
        // 超时自动重置刷新状态
        new Thread(() -> {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException ignored) {
            }
            Activity activity = getActivity();
            if (activity != null) {
                activity.runOnUiThread(() -> this.swipeRefreshLayout.setRefreshing(false));
            }
        }).start();
    }

    /**
     * 收到已授权设备信息
     */
    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onAuthorizedDevicesReplyEvent(AuthorizedDevicesReplyEvent event) {
        if (!event.isSuccess()) {
            Snackbar.make(requireView(), event.getErrorMessage(), BaseTransientBottomBar.LENGTH_LONG).show();
            this.swipeRefreshLayout.setRefreshing(false);
            return;
        }
        
        List<AuthorizedDevice> devices = event.getDevices();
        if (devices == null) {
            Snackbar.make(requireView(), R.string.fail_retrieve_device_list, BaseTransientBottomBar.LENGTH_LONG).show();
            this.swipeRefreshLayout.setRefreshing(false);
            return;
        }
        
        // 更新数据列表
        this.deviceList.clear();
        this.deviceList.addAll(devices);
        this.recyclerViewAdapter.notifyDataSetChanged();
        this.swipeRefreshLayout.setRefreshing(false);
    }

    /**
     * 已授权设备列表适配器
     */
    public class RecyclerViewAdapter extends RecyclerView.Adapter<RecyclerViewAdapter.ViewHolder> {

        private final List<AuthorizedDevice> mValues;

        public RecyclerViewAdapter(List<AuthorizedDevice> items) {
            mValues = items;
        }

        @NonNull
        @Override
        public RecyclerViewAdapter.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.list_item_device, parent, false);
            return new RecyclerViewAdapter.ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(final RecyclerViewAdapter.ViewHolder holder, int position) {
            AuthorizedDevice device = mValues.get(position);
            holder.mItem = device;
            
            // 设置节点ID
            holder.mNodeId.setText(device.getNodeId());
            
            // 设置设备名称
            String deviceName = device.getDeviceName();
            if (deviceName != null && !deviceName.isEmpty()) {
                holder.mDeviceName.setText(deviceName);
                holder.mDeviceName.setVisibility(View.VISIBLE);
            } else {
                holder.mDeviceName.setVisibility(View.GONE);
            }
            
            // 设置设备状态
            if (device.isOnline()) {
                holder.mStatus.setText(R.string.device_online);
                holder.mStatus.setTextColor(getResources().getColor(R.color.colorSuccess));
            } else {
                holder.mStatus.setText(R.string.device_offline);
                holder.mStatus.setTextColor(getResources().getColor(R.color.colorError));
            }
            
            // 设置IP地址
            StringBuilder ipText = new StringBuilder();
            List<String> ipAddresses = device.getIpAddresses();
            for (int i = 0; i < ipAddresses.size(); i++) {
                ipText.append(ipAddresses.get(i));
                if (i < ipAddresses.size() - 1) {
                    ipText.append(", ");
                }
            }
            holder.mIpAddress.setText(ipText.toString());
        }

        @Override
        public int getItemCount() {
            return mValues.size();
        }

        @ToString
        public class ViewHolder extends RecyclerView.ViewHolder {
            public final View mView;
            public final TextView mNodeId;
            public final TextView mDeviceName;
            public final TextView mStatus;
            public final TextView mIpAddress;
            public AuthorizedDevice mItem;

            public ViewHolder(View view) {
                super(view);
                mView = view;
                mNodeId = view.findViewById(R.id.list_device_node_id);
                mDeviceName = view.findViewById(R.id.list_device_name);
                mStatus = view.findViewById(R.id.list_device_status);
                mIpAddress = view.findViewById(R.id.list_device_ip);
            }
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        this.eventBus.unregister(this);
    }
}
