package net.kaaass.zerotierfix.ui.view;

import android.os.Bundle;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.TableRow;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.UiThread;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;
import androidx.lifecycle.ViewModelProvider;

import com.zerotier.sdk.VirtualNetworkConfig;
import com.zerotier.sdk.util.StringUtils;

import net.kaaass.zerotierfix.R;
import net.kaaass.zerotierfix.model.Network;
import net.kaaass.zerotierfix.model.NetworkConfig;
import net.kaaass.zerotierfix.model.type.DNSMode;
import net.kaaass.zerotierfix.model.type.NetworkStatus;
import net.kaaass.zerotierfix.model.type.NetworkType;
import net.kaaass.zerotierfix.ui.AppRoutingFragment;
import net.kaaass.zerotierfix.ui.NetworkListFragment;
import net.kaaass.zerotierfix.ui.viewmodel.NetworkDetailModel;
import net.kaaass.zerotierfix.util.Constants;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;

/**
 * 展示网络详细信息的 fragment
 *
 * @author kaaass
 */
public class NetworkDetailFragment extends Fragment {
    private static final String TAG = "NetworkDetailView";

    private NetworkDetailModel viewModel;
    private TextView idView;
    private TextView nameView;
    private TextView typeView;
    private TextView macView;
    private TextView mtuView;
    private TextView broadcastView;
    private TextView bridgingView;
    private TextView dnsModeView;
    private CheckBox routingAllView;
    private FrameLayout appRoutingFragmentContainer;
    private TextView ipAddressesView;
    private TableRow dnsView;
    private TextView dnsServersView;

    private long networkId;
    private AppRoutingFragment appRoutingFragment;


    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 获取 ViewModel
        this.viewModel = (NetworkDetailModel) new ViewModelProvider(this).get(NetworkDetailModel.class);

        // 如果参数中有网络 ID，就加载对应网络的信息
        if (getArguments() != null) {
            networkId = getArguments().getLong(NetworkListFragment.NETWORK_ID_MESSAGE);
            viewModel.doRetrieveDetail(networkId);
        } else {
            Log.e(TAG, "Network ID is not set!");
        }
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_network_detail, container, false);

        // 获取各个控件
        this.idView = view.findViewById(R.id.network_detail_network_id);
        this.nameView = view.findViewById(R.id.network_detail_network_name);
        this.typeView = view.findViewById(R.id.network_type_textview);
        this.macView = view.findViewById(R.id.network_mac_textview);
        this.mtuView = view.findViewById(R.id.network_mtu_textview);
        this.broadcastView = view.findViewById(R.id.network_broadcast_textview);
        this.bridgingView = view.findViewById(R.id.network_bridging_textview);
        this.dnsModeView = view.findViewById(R.id.network_dns_mode_textview);
        this.routingAllView = view.findViewById(R.id.network_routing_all);
        this.appRoutingFragmentContainer = view.findViewById(R.id.app_routing_fragment_container);
        this.ipAddressesView = view.findViewById(R.id.network_ipaddresses_textview);
        this.dnsView = view.findViewById(R.id.custom_dns_row);
        this.dnsServersView = view.findViewById(R.id.network_dns_textview);

        // "Route All Traffic" checkbox listener
        // When checked: global routing (per-app routing disabled, app list hidden)
        // When unchecked: per-app routing (app list shown for selection)
        CheckBox.OnCheckedChangeListener routingAllChangeListener = (buttonView, isChecked) -> {
            if (isChecked) {
                // Global routing mode: all traffic through ZeroTier
                viewModel.doUpdateRouteViaZeroTier(true);
                viewModel.doUpdatePerAppRouting(false);
                hideAppRoutingFragment();
            } else {
                // Per-app routing mode: show app list for selection
                viewModel.doUpdateRouteViaZeroTier(false);
                viewModel.doUpdatePerAppRouting(true);
                showAppRoutingFragment();
            }
        };

        // 设置回调
        this.routingAllView.setOnCheckedChangeListener(routingAllChangeListener);

        // 设置对应的 UI 更新操作
        viewModel.getNetwork().observe(getViewLifecycleOwner(), this::updateNetwork);
        viewModel.getNetworkConfig().observe(getViewLifecycleOwner(), this::updateNetworkConfig);
        viewModel.getVirtualNetworkConfig().observe(getViewLifecycleOwner(), this::updateVirtualNetworkConfig);

        return view;
    }

    /**
     * 显示应用路由选择Fragment
     */
    private void showAppRoutingFragment() {
        if (appRoutingFragment == null) {
            // 创建AppRoutingFragment实例
            appRoutingFragment = new AppRoutingFragment();
            Bundle args = new Bundle();
            args.putLong(AppRoutingFragment.NETWORK_ID_MESSAGE, networkId);
            appRoutingFragment.setArguments(args);
        }

        // 显示Fragment
        appRoutingFragmentContainer.setVisibility(View.VISIBLE);
        FragmentTransaction transaction = getChildFragmentManager().beginTransaction();
        transaction.replace(R.id.app_routing_fragment_container, appRoutingFragment);
        transaction.commit();
    }

    /**
     * 隐藏应用路由选择Fragment
     */
    private void hideAppRoutingFragment() {
        appRoutingFragmentContainer.setVisibility(View.GONE);
        if (appRoutingFragment != null) {
            FragmentTransaction transaction = getChildFragmentManager().beginTransaction();
            transaction.remove(appRoutingFragment);
            transaction.commit();
        }
    }
        viewModel.getVirtualNetworkConfig().observe(getViewLifecycleOwner(), this::updateVirtualNetworkConfig);

        return view;
    }

    /**
     * 更新网络基本信息相关的 UI
     */
    @UiThread
    private void updateNetwork(Network network) {
        if (network == null) {
            return;
        }
        this.idView.setText(network.getNetworkIdStr());
        if (network.getNetworkName() != null && !network.getNetworkName().isEmpty()) {
            this.nameView.setText(network.getNetworkName());
        } else {
            this.nameView.setText(getString(R.string.empty_network_name));
        }
    }

    /**
     * 更新持久化网络配置相关的 UI
     */
    @UiThread
    private void updateNetworkConfig(NetworkConfig networkConfig) {
        if (networkConfig == null) {
            return;
        }

        // DNS 模式
        var dnsMode = DNSMode.fromInt(networkConfig.getDnsMode());
        this.dnsModeView.setText(dnsMode.toStringId());

        // 仅当 DNS 模式为网络时显示服务器地址
        this.dnsView.setVisibility(dnsMode == DNSMode.NETWORK_DNS ? View.VISIBLE : View.INVISIBLE);

        // 路由配置
        boolean routeViaZt = networkConfig.getRouteViaZeroTier();
        boolean perAppRouting = networkConfig.getPerAppRouting();
        
        // Checkbox state logic:
        // - If per-app routing is enabled: unchecked (per-app mode)
        // - If global routing is enabled without per-app: checked (global mode)
        // - If neither is enabled: unchecked (no routing)
        // Note: routeViaZt and perAppRouting should be mutually exclusive, 
        // but we handle the case where both might be set by prioritizing per-app
        boolean isGlobalMode = routeViaZt && !perAppRouting;
        this.routingAllView.setChecked(isGlobalMode);
        
        // Show/hide app routing fragment based on routing mode
        if (perAppRouting) {
            showAppRoutingFragment();
        } else {
            hideAppRoutingFragment();
        }
    }

    /**
     * 更新虚拟网络配置（从 ZT 服务实时获取的网络动态配置）相关的 UI
     */
    @UiThread
    private void updateVirtualNetworkConfig(VirtualNetworkConfig virtualNetworkConfig) {
        if (virtualNetworkConfig == null) {
            return;
        }

        // 网络类型
        var ztType = virtualNetworkConfig.getType();
        var type = NetworkType.fromVirtualNetworkType(ztType);
        this.typeView.setText(type.toStringId());

        // MAC、MTU、广播、桥接
        this.macView.setText(StringUtils.macAddressToString(virtualNetworkConfig.getMac()));
        this.mtuView.setText(String.valueOf(virtualNetworkConfig.getMtu()));
        this.broadcastView.setText(booleanToLocalString(virtualNetworkConfig.isBroadcastEnabled()));
        this.bridgingView.setText(booleanToLocalString(virtualNetworkConfig.isBridge()));

        // 分配的 IP 地址 - 简化显示，逗号分隔
        var addresses = virtualNetworkConfig.getAssignedAddresses();
        var strAssignedAddresses = new StringBuilder();

        for (int i = 0; i < addresses.length; i++) {
            String addr = inetSocketAddressToString(addresses[i]);
            if (addr != null) {
                strAssignedAddresses.append(addr);
                if (i < addresses.length - 1) {
                    strAssignedAddresses.append(", ");
                }
            }
        }
        this.ipAddressesView.setText(strAssignedAddresses.toString());

        // DNS 服务地址
        var dns = virtualNetworkConfig.getDns();
        if (dns != null) {
            var dnsServers = dns.getServers();
            var strDnsServers = new StringBuilder();

            for (int i = 0; i < dnsServers.size(); i++) {
                strDnsServers.append(inetSocketAddressToString(dnsServers.get(i)));
                if (i < dnsServers.size() - 1) {
                    strDnsServers.append('\n');
                }
            }
            this.dnsServersView.setText(strDnsServers.toString());
        } else {
            this.dnsServersView.setText("");
        }
    }

    private String booleanToLocalString(boolean z) {
        return z ? getString(R.string.enabled) : getString(R.string.disabled);
    }

    private String inetSocketAddressToString(InetSocketAddress inetSocketAddress) {
        if (inetSocketAddress == null) {
            return null;
        }

        boolean disableIpv6 = PreferenceManager
                .getDefaultSharedPreferences(getActivity())
                .getBoolean(Constants.PREF_NETWORK_DISABLE_IPV6, false);

        try {
            InetAddress address = inetSocketAddress.getAddress();

            // 如果禁用了 IPv6，那么就不显示 IPv6 地址
            if (address instanceof Inet6Address && disableIpv6) {
                return null;
            }

            // 去除地址前面的斜杠
            var strAddress = address.toString();
            if (strAddress.startsWith("/")) {
                strAddress = strAddress.substring(1);
            }

            // 拼接地址和前缀
            return strAddress + "/" + inetSocketAddress.getPort();
        } catch (Exception ignored) {
        }
        return null;
    }
}
