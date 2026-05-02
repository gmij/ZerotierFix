package net.kaaass.zerotierfix.service;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.IpPrefix;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.VpnService;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.preference.PreferenceManager;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.zerotier.sdk.Event;
import com.zerotier.sdk.EventListener;
import com.zerotier.sdk.Node;
import com.zerotier.sdk.Peer;
import com.zerotier.sdk.PeerPhysicalPath;
import com.zerotier.sdk.ResultCode;
import com.zerotier.sdk.VirtualNetworkConfig;
import com.zerotier.sdk.VirtualNetworkConfigListener;
import com.zerotier.sdk.VirtualNetworkConfigOperation;
import com.zerotier.sdk.VirtualNetworkStatus;

import net.kaaass.zerotierfix.R;
import net.kaaass.zerotierfix.ZerotierFixApplication;
import net.kaaass.zerotierfix.events.AfterJoinNetworkEvent;
import net.kaaass.zerotierfix.events.ErrorEvent;
import net.kaaass.zerotierfix.events.IsServiceRunningReplyEvent;
import net.kaaass.zerotierfix.events.IsServiceRunningRequestEvent;
import net.kaaass.zerotierfix.events.ManualDisconnectEvent;
import net.kaaass.zerotierfix.events.NetworkConfigChangedByUserEvent;
import net.kaaass.zerotierfix.events.NetworkListReplyEvent;
import net.kaaass.zerotierfix.events.NetworkListRequestEvent;
import net.kaaass.zerotierfix.events.NetworkReconfigureEvent;
import net.kaaass.zerotierfix.events.NodeDestroyedEvent;
import net.kaaass.zerotierfix.events.NodeIDEvent;
import net.kaaass.zerotierfix.events.NodeStatusEvent;
import net.kaaass.zerotierfix.events.NodeStatusRequestEvent;
import net.kaaass.zerotierfix.events.OrbitMoonEvent;
import net.kaaass.zerotierfix.events.PeerInfoReplyEvent;
import net.kaaass.zerotierfix.events.PeerInfoRequestEvent;
import net.kaaass.zerotierfix.events.StopEvent;
import net.kaaass.zerotierfix.events.VPNErrorEvent;
import net.kaaass.zerotierfix.events.VirtualNetworkConfigChangedEvent;
import net.kaaass.zerotierfix.events.VirtualNetworkConfigReplyEvent;
import net.kaaass.zerotierfix.events.VirtualNetworkConfigRequestEvent;
import net.kaaass.zerotierfix.model.AppNode;
import net.kaaass.zerotierfix.model.AppRoutingDao;
import net.kaaass.zerotierfix.model.AssignedAddress;
import net.kaaass.zerotierfix.model.MoonOrbit;
import net.kaaass.zerotierfix.model.Network;
import net.kaaass.zerotierfix.model.NetworkConfig;
import net.kaaass.zerotierfix.model.NetworkDao;
import net.kaaass.zerotierfix.model.type.DNSMode;
import net.kaaass.zerotierfix.ui.NetworkListActivity;
import net.kaaass.zerotierfix.util.Constants;
import net.kaaass.zerotierfix.util.DatabaseUtils;
import net.kaaass.zerotierfix.util.InetAddressUtils;
import net.kaaass.zerotierfix.util.LogUtil;
import net.kaaass.zerotierfix.util.NetworkInfoUtils;
// import net.kaaass.zerotierfix.util.ProxyManager; // 代理功能已移除
import net.kaaass.zerotierfix.util.StringUtils;
import net.kaaass.zerotierfix.util.smartroute.CidrBlock;
import net.kaaass.zerotierfix.util.smartroute.SmartRoutingManager;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantReadWriteLock;

// TODO: clear up
public class ZeroTierOneService extends VpnService implements Runnable, EventListener, VirtualNetworkConfigListener {
    public static final int MSG_JOIN_NETWORK = 1;
    public static final int MSG_LEAVE_NETWORK = 2;
    public static final String ZT1_NETWORK_ID = "com.zerotier.one.network_id";
    public static final String ZT1_USE_DEFAULT_ROUTE = "com.zerotier.one.use_default_route";
    private static final String[] DISALLOWED_APPS = {"com.android.vending"};
    private static final String TAG = "ZT1_Service";
    private static final int ZT_NOTIFICATION_TAG = 5919812;
    /** 移动数据接口名称前缀——这些是"上行"互联网提供者，不应排除在 VPN 路由之外。 */
    private static final String[] MOBILE_DATA_IFACE_PREFIXES = {
            "rmnet", "v4-rmnet",   // Qualcomm (rmnet_data0 等) 及其 CLAT/464XLAT 接口
            "ccmni",               // MediaTek
            "wwan",                // 通用 WWAN
            "seth",                // Samsung LTE (seth_lte0)
            "r_rmnet"              // Qualcomm IPA 辅助接口
    };
    /** 链路本地地址网络前缀 169.254.0.0，uint32。 */
    private static final long LINK_LOCAL_PREFIX = 0xA9FE0000L; // 169.254.0.0
    /** 链路本地地址子网掩码 /16，uint32。 */
    private static final long LINK_LOCAL_MASK   = 0xFFFF0000L; // /16
    /** CGN（运营商级 NAT）网段 100.64.0.0，uint32。 */
    private static final long CGN_PREFIX = 0x64400000L; // 100.64.0.0
    /** CGN 子网掩码 /10，uint32。 */
    private static final long CGN_MASK   = 0xFFC00000L; // /10
    /**
     * 国内直连模式专用 DNS 服务器：使用国内权威 DNS，使微信视频号等内容解析到国内 CDN 节点。
     * 114DNS 和阿里 DNS 均为中国 IP，在 CHINA_DIRECT 模式下会被排除在 VPN 路由之外，
     * DNS 查询直接经物理网络发出，返回国内 CDN 地址，避免经过境外 ZeroTier 节点绕路。
     * 仅在 per-app 路由模式下使用；全局代理模式下改用 INTERNATIONAL_DNS_SERVERS。
     */
    private static final String[] DOMESTIC_DNS_SERVERS = {
            "114.114.114.114",  // 114DNS 主
            "114.114.115.115",  // 114DNS 备
            "223.5.5.5",        // AliDNS 主
            "223.6.6.6",        // AliDNS 备
    };
    /**
     * 全局代理模式专用 DNS 服务器：使用国际 DNS，经 VPN（ZeroTier）发出，绕过 GFW 的 DNS 污染。
     * 这些 IP 均为非中国 IP，在 CHINA_DIRECT 路由下会进入 VPN 隧道，不受 GFW DNS 污染影响，
     * 因此可以正确解析 google.com 等被封锁域名，避免证书错误。
     */
    private static final String[] INTERNATIONAL_DNS_SERVERS = {
            "8.8.8.8",    // Google DNS 主
            "8.8.4.4",    // Google DNS 备
            "1.1.1.1",    // Cloudflare DNS 主
            "1.0.0.1",    // Cloudflare DNS 备
    };
    /**
     * Android 13+ excludeRoute 最大条数。
     * Binder 单次事务上限（1 MB）在部分 OEM ROM 中更低（如 600 KB）；
     * 中国 IP CIDR 约 7 400+ 条，全部序列化约 620 KB，可能触发 TransactionTooLargeException。
     * 限制为 4 000 条（排除覆盖最广的短前缀 CIDR），序列化约 336 KB，安全余量充足。
     */
    private static final int MAX_EXCLUDE_ROUTES_ANDROID13 = 4000;
    private final IBinder mBinder = new ZeroTierBinder();
    private final DataStore dataStore = new DataStore(this);
    private final EventBus eventBus = EventBus.getDefault();
    private final Map<Long, VirtualNetworkConfig> virtualNetworkConfigMap = new HashMap();
    FileInputStream in;
    FileOutputStream out;
    DatagramSocket svrSocket;
    ParcelFileDescriptor vpnSocket;
    private int bindCount = 0;
    private boolean disableIPv6 = false;
    private int mStartID = -1;
    private long networkId = 0;
    private long nextBackgroundTaskDeadline = 0;
    private Node node;
    private NotificationManager notificationManager;
    private TunTapAdapter tunTapAdapter;
    private UdpCom udpCom;
    private Thread udpThread;
    /** 网络变化回调，用于在 WiFi/4G 切换时重新配置 VPN 路由 */
    private ConnectivityManager.NetworkCallback networkCallback;
    /** 用于异步 debounce 网络变化事件的后台线程和 Handler */
    private HandlerThread networkChangeThread;
    private Handler networkChangeHandler;
    /** debounce 延迟后执行 VPN 重建的 Runnable */
    private final Runnable networkChangeRunnable = this::doNetworkChangedUpdate;
    /** 防止网络变化事件过于频繁触发重配的最小间隔（毫秒） */
    private static final long NETWORK_CHANGE_DEBOUNCE_MS = 3000;
    /**
     * 自学习直连 IP 触发 VPN 重建的 Runnable。
     * 与 networkChangeRunnable 独立，避免互相取消。
     */
    private final Runnable learnedIpRebuildRunnable = () -> {
        LogUtil.i(TAG, "自学习直连 IP 触发 VPN 路由重建（" + LEARNED_IP_REBUILD_DEBOUNCE_MS / 1000
                + "s 防抖到期），重新配置 excludeRoute 使新发现的直播 CDN IP 走物理直连");
        doNetworkChangedUpdate();
    };
    /** 自学习 IP 触发重建的防抖延迟（10 秒，确保批量 IP 一次重建） */
    private static final long LEARNED_IP_REBUILD_DEBOUNCE_MS = 10_000;
    /**
     * 上次触发重建时的链路地址快照。
     * 仅当链路地址发生变化（如 IP 切换）时才重配 VPN；
     * DNS 更新、MTU 变化等不改变地址的事件将被忽略，避免启动时的误报。
     */
    private Set<LinkAddress> lastLinkAddresses = null;
    /**
     * 防止 updateTunnelConfig 并发执行。
     * 若有另一次 VPN 配置正在进行（例如来自 onNetworkReconfigure 和 networkChangeHandler 同时触发），
     * 后到的调用将在当前配置完成后延迟重建，避免两次并发建立均失败（TransactionTooLargeException 双重触发）。
     */
    private final java.util.concurrent.atomic.AtomicBoolean isConfiguringVpn =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    /**
     * 腾讯云 CIDR 验证日志一次性标志。
     * configureChinaDirectRouting 可能在 VPN 重建时多次调用；该标志确保 11 条验证日志只打印一次，
     * 避免每次重建都重复输出相同信息。chnroutes 刷新后重置，重新执行一次验证。
     */
    private volatile boolean tencentCidrsVerified = false;
    private Thread v4MulticastScanner = new Thread() {
        /* class com.zerotier.one.service.ZeroTierOneService.AnonymousClass1 */
        List<String> subscriptions = new ArrayList<>();

        @Override
        public void run() {
            LogUtil.d(ZeroTierOneService.TAG, "IPv4 Multicast Scanner Thread Started.");
            while (!isInterrupted()) {
                try {
                    List<String> groups = NetworkInfoUtils.listMulticastGroupOnInterface("tun0", false);

                    ArrayList<String> arrayList2 = new ArrayList<>(this.subscriptions);
                    ArrayList<String> arrayList3 = new ArrayList<>(groups);
                    arrayList3.removeAll(arrayList2);
                    for (String str : arrayList3) {
                        try {
                            byte[] hexStringToByteArray = StringUtils.hexStringToBytes(str);
                            for (int i = 0; i < hexStringToByteArray.length / 2; i++) {
                                byte b = hexStringToByteArray[i];
                                hexStringToByteArray[i] = hexStringToByteArray[(hexStringToByteArray.length - i) - 1];
                                hexStringToByteArray[(hexStringToByteArray.length - i) - 1] = b;
                            }
                            ResultCode multicastSubscribe = ZeroTierOneService.this.node.multicastSubscribe(ZeroTierOneService.this.networkId, TunTapAdapter.multicastAddressToMAC(InetAddress.getByAddress(hexStringToByteArray)));
                            if (multicastSubscribe != ResultCode.RESULT_OK) {
                                LogUtil.e(ZeroTierOneService.TAG, "Error when calling multicastSubscribe: " + multicastSubscribe);
                            }
                        } catch (Exception e) {
                            LogUtil.e(ZeroTierOneService.TAG, e.toString(), e);
                        }
                    }
                    arrayList2.removeAll(new ArrayList<>(groups));
                    for (String str2 : arrayList2) {
                        try {
                            byte[] hexStringToByteArray2 = StringUtils.hexStringToBytes(str2);
                            for (int i2 = 0; i2 < hexStringToByteArray2.length / 2; i2++) {
                                byte b2 = hexStringToByteArray2[i2];
                                hexStringToByteArray2[i2] = hexStringToByteArray2[(hexStringToByteArray2.length - i2) - 1];
                                hexStringToByteArray2[(hexStringToByteArray2.length - i2) - 1] = b2;
                            }
                            ResultCode multicastUnsubscribe = ZeroTierOneService.this.node.multicastUnsubscribe(ZeroTierOneService.this.networkId, TunTapAdapter.multicastAddressToMAC(InetAddress.getByAddress(hexStringToByteArray2)));
                            if (multicastUnsubscribe != ResultCode.RESULT_OK) {
                                LogUtil.e(ZeroTierOneService.TAG, "Error when calling multicastUnsubscribe: " + multicastUnsubscribe);
                            }
                        } catch (Exception e) {
                            LogUtil.e(ZeroTierOneService.TAG, e.toString(), e);
                        }
                    }
                    this.subscriptions = groups;
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    LogUtil.e(ZeroTierOneService.TAG, "V4 Multicast Scanner Thread Interrupted", e);
                    break;
                }
            }
            LogUtil.d(ZeroTierOneService.TAG, "IPv4 Multicast Scanner Thread Ended.");
        }
    };
    private Thread v6MulticastScanner = new Thread() {
        /* class com.zerotier.one.service.ZeroTierOneService.AnonymousClass2 */
        List<String> subscriptions = new ArrayList<>();

        @Override
        public void run() {
            LogUtil.d(ZeroTierOneService.TAG, "IPv6 Multicast Scanner Thread Started.");
            while (!isInterrupted()) {
                try {
                    List<String> groups = NetworkInfoUtils.listMulticastGroupOnInterface("tun0", true);

                    ArrayList<String> arrayList2 = new ArrayList<>(this.subscriptions);
                    ArrayList<String> arrayList3 = new ArrayList<>(groups);
                    arrayList3.removeAll(arrayList2);
                    for (String str : arrayList3) {
                        try {
                            ResultCode multicastSubscribe = ZeroTierOneService.this.node.multicastSubscribe(ZeroTierOneService.this.networkId, TunTapAdapter.multicastAddressToMAC(InetAddress.getByAddress(StringUtils.hexStringToBytes(str))));
                            if (multicastSubscribe != ResultCode.RESULT_OK) {
                                LogUtil.e(ZeroTierOneService.TAG, "Error when calling multicastSubscribe: " + multicastSubscribe);
                            }
                        } catch (Exception e) {
                            LogUtil.e(ZeroTierOneService.TAG, e.toString(), e);
                        }
                    }
                    arrayList2.removeAll(new ArrayList<>(groups));
                    for (String str2 : arrayList2) {
                        try {
                            ResultCode multicastUnsubscribe = ZeroTierOneService.this.node.multicastUnsubscribe(ZeroTierOneService.this.networkId, TunTapAdapter.multicastAddressToMAC(InetAddress.getByAddress(StringUtils.hexStringToBytes(str2))));
                            if (multicastUnsubscribe != ResultCode.RESULT_OK) {
                                LogUtil.e(ZeroTierOneService.TAG, "Error when calling multicastUnsubscribe: " + multicastUnsubscribe);
                            }
                        } catch (Exception e) {
                            LogUtil.e(ZeroTierOneService.TAG, e.toString(), e);
                        }
                    }
                    this.subscriptions = groups;
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    LogUtil.e(ZeroTierOneService.TAG, "V6 Multicast Scanner Thread Interrupted", e);
                    break;
                }
            }
            LogUtil.d(ZeroTierOneService.TAG, "IPv6 Multicast Scanner Thread Ended.");
        }
    };
    private Thread vpnThread;

    public VirtualNetworkConfig getVirtualNetworkConfig(long j) {
        VirtualNetworkConfig virtualNetworkConfig;
        synchronized (this.virtualNetworkConfigMap) {
            virtualNetworkConfig = this.virtualNetworkConfigMap.get(Long.valueOf(j));
        }
        return virtualNetworkConfig;
    }

    public VirtualNetworkConfig setVirtualNetworkConfig(long j, VirtualNetworkConfig virtualNetworkConfig) {
        VirtualNetworkConfig put;
        synchronized (this.virtualNetworkConfigMap) {
            put = this.virtualNetworkConfigMap.put(Long.valueOf(j), virtualNetworkConfig);
        }
        return put;
    }

    public VirtualNetworkConfig clearVirtualNetworkConfig(long j) {
        VirtualNetworkConfig remove;
        synchronized (this.virtualNetworkConfigMap) {
            remove = this.virtualNetworkConfigMap.remove(Long.valueOf(j));
        }
        return remove;
    }

    private void logBindCount() {
        LogUtil.i(TAG, "Bind Count: " + this.bindCount);
    }

    public IBinder onBind(Intent intent) {
        LogUtil.d(TAG, "Bound by: " + getPackageManager().getNameForUid(Binder.getCallingUid()));
        this.bindCount++;
        logBindCount();
        return this.mBinder;
    }

    public boolean onUnbind(Intent intent) {
        LogUtil.d(TAG, "Unbound by: " + getPackageManager().getNameForUid(Binder.getCallingUid()));
        this.bindCount--;
        logBindCount();
        return false;
    }

    /* access modifiers changed from: protected */
    protected void setNextBackgroundTaskDeadline(long j) {
        synchronized (this) {
            this.nextBackgroundTaskDeadline = j;
        }
    }

    /**
     * 启动 ZT 服务，连接至给定网络或最近连接的网络
     */
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        long networkId;
        LogUtil.d(TAG, "onStartCommand");
        if (startId == 3) {
            LogUtil.i(TAG, "Authorizing VPN");
            return START_NOT_STICKY;
        } else if (intent == null) {
            LogUtil.e(TAG, "NULL intent.  Cannot start");
            return START_NOT_STICKY;
        }
        this.mStartID = startId;

        // 注册事件总线监听器
        if (!this.eventBus.isRegistered(this)) {
            this.eventBus.register(this);
        }

        // 确定待启动的网络 ID
        if (intent.hasExtra(ZT1_NETWORK_ID)) {
            // Intent 中指定了目标网络，直接使用此 ID
            networkId = intent.getLongExtra(ZT1_NETWORK_ID, 0);
        } else {
            // 默认启用最近一次启动的网络
            DatabaseUtils.readLock.lock();
            try {
                var daoSession = ((ZerotierFixApplication) getApplication()).getDaoSession();
                daoSession.clear();
                var lastActivatedNetworks = daoSession.getNetworkDao().queryBuilder()
                        .where(NetworkDao.Properties.LastActivated.eq(true))
                        .list();
                if (lastActivatedNetworks == null || lastActivatedNetworks.isEmpty()) {
                    LogUtil.e(TAG, "Couldn't find last activated connection");
                    return START_NOT_STICKY;
                } else if (lastActivatedNetworks.size() > 1) {
                    LogUtil.e(TAG, "Multiple networks marked as last connected: " + lastActivatedNetworks.size());
                    for (Network network : lastActivatedNetworks) {
                        LogUtil.e(TAG, "ID: " + Long.toHexString(network.getNetworkId()));
                    }
                    throw new IllegalStateException("Database is inconsistent");
                } else {
                    networkId = lastActivatedNetworks.get(0).getNetworkId();
                    LogUtil.i(TAG, "Got Always On request for ZeroTier");
                }
            } finally {
                DatabaseUtils.readLock.unlock();
            }
        }
        if (networkId == 0) {
            LogUtil.e(TAG, "Network ID not provided to service");
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        this.networkId = networkId;

        // 触发智能路由数据文件下载（后台进行，不阻塞启动）
        SmartRoutingManager.getInstance(this).ensureDataReady();

        // 检查当前的网络环境
        var preferences = PreferenceManager.getDefaultSharedPreferences(this);
        boolean useCellularData = preferences.getBoolean(Constants.PREF_NETWORK_USE_CELLULAR_DATA, false);
        this.disableIPv6 = preferences.getBoolean(Constants.PREF_NETWORK_DISABLE_IPV6, false);
        var currentNetworkInfo = NetworkInfoUtils.getNetworkInfoCurrentConnection(this);

        if (currentNetworkInfo == NetworkInfoUtils.CurrentConnection.CONNECTION_NONE) {
            // 未连接网络
            Toast.makeText(this, R.string.toast_no_network, Toast.LENGTH_SHORT).show();
            stopSelf(this.mStartID);
            return START_NOT_STICKY;
        } else if (currentNetworkInfo == NetworkInfoUtils.CurrentConnection.CONNECTION_MOBILE &&
                !useCellularData) {
            // 使用移动网络，但未在设置中允许移动网络访问
            Toast.makeText(this, R.string.toast_mobile_data, Toast.LENGTH_LONG).show();
            stopSelf(this.mStartID);
            return START_NOT_STICKY;
        }

        // 启动 ZT 服务
        synchronized (this) {
            try {
                // 创建本地 ZT 服务 Socket，监听本地端口
                if (this.svrSocket == null) {
                    this.svrSocket = new DatagramSocket(null);
                    this.svrSocket.setReuseAddress(true);
                    this.svrSocket.setSoTimeout(1000);
                    this.svrSocket.bind(new InetSocketAddress(9994));
                }
                if (!protect(this.svrSocket)) {
                    LogUtil.e(TAG, "Error protecting UDP socket from feedback loop.");
                }

                // 创建本地节点
                if (this.node == null) {
                    this.udpCom = new UdpCom(this, this.svrSocket);
                    this.tunTapAdapter = new TunTapAdapter(this, networkId);

                    // 创建节点对象并初始化
                    var dataStore = this.dataStore;
                    this.node = new Node(System.currentTimeMillis());
                    var result = this.node.init(dataStore, dataStore, this.udpCom, this, this.tunTapAdapter, this, null);

                    if (result == ResultCode.RESULT_OK) {
                        LogUtil.d(TAG, "ZeroTierOne Node Initialized");
                    } else {
                        LogUtil.e(TAG, "Error starting ZT1 Node: " + result);
                        return START_NOT_STICKY;
                    }
                    this.onNodeStatusRequest(null);

                    // 持久化当前节点信息
                    long address = this.node.address();
                    DatabaseUtils.writeLock.lock();
                    try {
                        var appNodeDao = ((ZerotierFixApplication) getApplication())
                                .getDaoSession().getAppNodeDao();
                        var nodesList = appNodeDao.queryBuilder().build()
                                .forCurrentThread().list();
                        if (nodesList.isEmpty()) {
                            var appNode = new AppNode();
                            appNode.setNodeId(address);
                            appNode.setNodeIdStr(String.format("%10x", address));
                            appNodeDao.insert(appNode);
                        } else {
                            var appNode = nodesList.get(0);
                            appNode.setNodeId(address);
                            appNode.setNodeIdStr(String.format("%10x", address));
                            appNodeDao.save(appNode);
                        }
                    } finally {
                        DatabaseUtils.writeLock.unlock();
                    }

                    this.eventBus.post(new NodeIDEvent(address));
                    this.udpCom.setNode(this.node);
                    this.tunTapAdapter.setNode(this.node);

                    // 启动 UDP 消息处理线程
                    var thread = new Thread(this.udpCom, "UDP Communication Thread");
                    this.udpThread = thread;
                    thread.start();
                }

                // 创建并启动 VPN 服务线程
                if (this.vpnThread == null) {
                    var thread = new Thread(this, "ZeroTier Service Thread");
                    this.vpnThread = thread;
                    thread.start();
                }

                // 启动 UDP 消息处理线程
                if (!this.udpThread.isAlive()) {
                    this.udpThread.start();
                }
            } catch (Exception e) {
                LogUtil.e(TAG, e.toString(), e);
                return START_NOT_STICKY;
            }
        }
        joinNetwork(networkId);
        return START_STICKY;
    }

    public void stopZeroTier() {
        // 取消网络变化回调
        unregisterNetworkChangeCallback();

        if (this.svrSocket != null) {
            this.svrSocket.close();
            this.svrSocket = null;
        }
        if (this.udpThread != null && this.udpThread.isAlive()) {
            this.udpThread.interrupt();
            try {
                this.udpThread.join();
            } catch (InterruptedException ignored) {
            }
            this.udpThread = null;
        }
        if (this.tunTapAdapter != null && this.tunTapAdapter.isRunning()) {
            this.tunTapAdapter.interrupt();
            try {
                this.tunTapAdapter.join();
            } catch (InterruptedException ignored) {
            }
            this.tunTapAdapter = null;
        }
        if (this.vpnThread != null && this.vpnThread.isAlive()) {
            this.vpnThread.interrupt();
            try {
                this.vpnThread.join();
            } catch (InterruptedException ignored) {
            }
            this.vpnThread = null;
        }
        if (this.v4MulticastScanner != null) {
            this.v4MulticastScanner.interrupt();
            try {
                this.v4MulticastScanner.join();
            } catch (InterruptedException ignored) {
            }
            this.v4MulticastScanner = null;
        }
        if (this.v6MulticastScanner != null) {
            this.v6MulticastScanner.interrupt();
            try {
                this.v6MulticastScanner.join();
            } catch (InterruptedException ignored) {
            }
            this.v6MulticastScanner = null;
        }
        if (this.vpnSocket != null) {
            try {
                this.vpnSocket.close();
            } catch (Exception e) {
                LogUtil.e(TAG, "Error closing VPN socket: " + e, e);
            }
            this.vpnSocket = null;
        }
        if (this.node != null) {
            this.eventBus.post(new NodeDestroyedEvent());
            this.node.close();
            this.node = null;
        }
        if (this.eventBus.isRegistered(this)) {
            this.eventBus.unregister(this);
        }
        if (this.notificationManager != null) {
            this.notificationManager.cancel(ZT_NOTIFICATION_TAG);
        }
        if (!stopSelfResult(this.mStartID)) {
            LogUtil.e(TAG, "stopSelfResult() failed!");
        }
    }

    public void onDestroy() {
        try {
            stopZeroTier();
            if (this.vpnSocket != null) {
                try {
                    this.vpnSocket.close();
                } catch (Exception e) {
                    LogUtil.e(TAG, "Error closing VPN socket: " + e, e);
                }
                this.vpnSocket = null;
            }
            stopSelf(this.mStartID);
            if (this.eventBus.isRegistered(this)) {
                this.eventBus.unregister(this);
            }
        } catch (Exception e) {
            LogUtil.e(TAG, e.toString(), e);
        } finally {
            super.onDestroy();
        }
    }

    public void onRevoke() {
        stopZeroTier();
        if (this.vpnSocket != null) {
            try {
                this.vpnSocket.close();
            } catch (Exception e) {
                LogUtil.e(TAG, "Error closing VPN socket: " + e, e);
            }
            this.vpnSocket = null;
        }
        stopSelf(this.mStartID);
        if (this.eventBus.isRegistered(this)) {
            this.eventBus.unregister(this);
        }
        super.onRevoke();
    }

    public void run() {
        LogUtil.d(TAG, "ZeroTierOne Service Started");
        LogUtil.d(TAG, "This Node Address: " + com.zerotier.sdk.util.StringUtils.addressToString(this.node.address()));
        while (!Thread.interrupted()) {
            try {
                long currentTime = System.currentTimeMillis();
                long taskDeadline;
                synchronized (this) {
                    taskDeadline = this.nextBackgroundTaskDeadline;
                }
                long sleepMs;
                if (Long.compare(taskDeadline, currentTime) <= 0) {
                    // 后台任务截止时间已到，执行后台任务
                    long[] newDeadline = {0};
                    var taskResult = this.node.processBackgroundTasks(currentTime, newDeadline);
                    synchronized (this) {
                        this.nextBackgroundTaskDeadline = newDeadline[0];
                    }
                    if (taskResult != ResultCode.RESULT_OK) {
                        LogUtil.e(TAG, "Error on processBackgroundTasks: " + taskResult.toString());
                        shutdown();
                    }
                    // 按 ZeroTier 指定的下一个截止时间睡眠。
                    // 此处重新取时刻以计入 processBackgroundTasks 本身的耗时；
                    // 若 sleepMs 为负（任务执行期间已超过截止时间），下方 if (sleepMs > 0) 会跳过睡眠。
                    sleepMs = newDeadline[0] - System.currentTimeMillis();
                } else {
                    sleepMs = taskDeadline - currentTime;
                }
                // 限制最长睡眠时间，避免在更新截止时间时无法及时响应
                if (sleepMs > 5000) sleepMs = 5000;
                if (sleepMs > 0) {
                    Thread.sleep(sleepMs);
                }
            } catch (InterruptedException ignored) {
                break;
            } catch (Exception e) {
                LogUtil.e(TAG, e.toString(), e);
            }
        }
        LogUtil.d(TAG, "ZeroTierOne Service Ended");
    }

    @Subscribe(threadMode = ThreadMode.POSTING)
    public void onStopEvent(StopEvent stopEvent) {
        stopZeroTier();
    }

    @Subscribe(threadMode = ThreadMode.POSTING)
    public void onManualDisconnect(ManualDisconnectEvent manualDisconnectEvent) {
        stopZeroTier();
    }

    @Subscribe(threadMode = ThreadMode.POSTING)
    public void onIsServiceRunningRequest(IsServiceRunningRequestEvent event) {
        this.eventBus.post(new IsServiceRunningReplyEvent(true));
    }

    /**
     * 加入 ZT 网络
     */
    public void joinNetwork(long networkId) {
        if (this.node == null) {
            LogUtil.e(TAG, "Can't join network if ZeroTier isn't running");
            return;
        }
        // 连接到新网络
        var result = this.node.join(networkId);
        if (result != ResultCode.RESULT_OK) {
            this.eventBus.post(new ErrorEvent(result));
            return;
        }
        // 连接后事件
        this.eventBus.post(new AfterJoinNetworkEvent());
    }

    /**
     * 离开 ZT 网络
     */
    public void leaveNetwork(long networkId) {
        if (this.node == null) {
            LogUtil.e(TAG, "Can't leave network if ZeroTier isn't running");
            return;
        }
        var result = this.node.leave(networkId);
        if (result != ResultCode.RESULT_OK) {
            this.eventBus.post(new ErrorEvent(result));
            return;
        }
        var networkConfigs = this.node.networkConfigs();
        if (networkConfigs != null && networkConfigs.length != 0) {
            return;
        }
        stopZeroTier();
        if (this.vpnSocket != null) {
            try {
                this.vpnSocket.close();
            } catch (Exception e) {
                LogUtil.e(TAG, "Error closing VPN socket", e);
            }
            this.vpnSocket = null;
        }
        stopSelf(this.mStartID);
    }

    @Subscribe(threadMode = ThreadMode.BACKGROUND)
    public void onNetworkListRequest(NetworkListRequestEvent requestNetworkListEvent) {
        VirtualNetworkConfig[] networks;
        Node node2 = this.node;
        if (node2 != null && (networks = node2.networkConfigs()) != null && networks.length > 0) {
            this.eventBus.post(new NetworkListReplyEvent(networks));
        }
    }

    /**
     * 请求节点状态事件回调
     *
     * @param event 事件
     */
    @Subscribe(threadMode = ThreadMode.BACKGROUND)
    public void onNodeStatusRequest(NodeStatusRequestEvent event) {
        // 返回节点状态
        if (this.node != null) {
            this.eventBus.post(new NodeStatusEvent(this.node.status(), this.node.getVersion()));
        }
    }

    /**
     * 请求 Peer 信息事件回调
     */
    @Subscribe(threadMode = ThreadMode.BACKGROUND)
    public void onRequestPeerInfo(PeerInfoRequestEvent event) {
        if (this.node == null) {
            this.eventBus.post(new PeerInfoReplyEvent(null));
            return;
        }
        this.eventBus.post(new PeerInfoReplyEvent(this.node.peers()));
    }

    /**
     * 请求网络配置事件回调
     */
    @Subscribe(threadMode = ThreadMode.BACKGROUND)
    public void onVirtualNetworkConfigRequest(VirtualNetworkConfigRequestEvent event) {
        if (this.node == null) {
            this.eventBus.post(new VirtualNetworkConfigReplyEvent(null));
            return;
        }
        var config = this.node.networkConfig(event.getNetworkId());
        this.eventBus.post(new VirtualNetworkConfigReplyEvent(config));
    }

    @Subscribe(threadMode = ThreadMode.ASYNC)
    public void onNetworkReconfigure(NetworkReconfigureEvent event) {
        boolean isChanged = event.isChanged();
        var network = event.getNetwork();
        var networkConfig = event.getVirtualNetworkConfig();
        boolean configUpdated = isChanged && updateTunnelConfig(network);
        boolean networkIsOk = networkConfig.getStatus() == VirtualNetworkStatus.NETWORK_STATUS_OK;

        if (configUpdated || !networkIsOk) {
            this.eventBus.post(new VirtualNetworkConfigChangedEvent(networkConfig));
        }
    }

    @Subscribe(threadMode = ThreadMode.ASYNC)
    public void onNetworkConfigChangedByUser(NetworkConfigChangedByUserEvent event) {
        Network network = event.getNetwork();
        if (network.getNetworkId() != this.networkId) {
            return;
        }
        updateTunnelConfig(network);
    }

    /**
     * Zerotier 事件回调
     *
     * @param event {@link Event} enum
     */
    @Override
    public void onEvent(Event event) {
        LogUtil.d(TAG, "Event: " + event.toString());
        // 更新节点状态
        if (this.node.isInited()) {
            this.eventBus.post(new NodeStatusEvent(this.node.status(), this.node.getVersion()));
        }
    }

    @Override // com.zerotier.sdk.EventListener
    public void onTrace(String str) {
        LogUtil.d(TAG, "Trace: " + str);
    }

    /**
     * 当 ZT 网络配置发生更新
     */
    @Override
    public int onNetworkConfigurationUpdated(long networkId, VirtualNetworkConfigOperation op, VirtualNetworkConfig config) {
        LogUtil.d(TAG, "Virtual Network Config Operation: " + op);
        DatabaseUtils.writeLock.lock();
        try {
            // 查找网络 ID 对应的配置
            var networkDao = ((ZerotierFixApplication) getApplication())
                    .getDaoSession()
                    .getNetworkDao();
            var matchedNetwork = networkDao.queryBuilder()
                    .where(NetworkDao.Properties.NetworkId.eq(networkId))
                    .list();
            if (matchedNetwork.size() != 1) {
                throw new IllegalStateException("Database is inconsistent");
            }
            var network = matchedNetwork.get(0);
            // 根据当前网络状态确定更改配置的行为
            switch (op) {
                case VIRTUAL_NETWORK_CONFIG_OPERATION_UP:
                    LogUtil.d(TAG, "Network Type: " + config.getType() + " Network Status: " + config.getStatus() + " Network Name: " + config.getName() + " ");
                    // 将网络配置的更新交给第一次 Update
                    break;
                case VIRTUAL_NETWORK_CONFIG_OPERATION_CONFIG_UPDATE:
                    LogUtil.d(TAG, "Network Config Update!");
                    boolean isChanged = setVirtualNetworkConfigAndUpdateDatabase(network, config);
                    this.eventBus.post(new NetworkReconfigureEvent(isChanged, network, config));
                    break;
                case VIRTUAL_NETWORK_CONFIG_OPERATION_DOWN:
                case VIRTUAL_NETWORK_CONFIG_OPERATION_DESTROY:
                    LogUtil.d(TAG, "Network Down!");
                    clearVirtualNetworkConfig(networkId);
                    break;
            }
            return 0;
        } finally {
            DatabaseUtils.writeLock.unlock();
        }
    }

    private boolean setVirtualNetworkConfigAndUpdateDatabase(Network network, VirtualNetworkConfig virtualNetworkConfig) {
        if ((DatabaseUtils.writeLock instanceof ReentrantReadWriteLock.WriteLock) && !((ReentrantReadWriteLock.WriteLock) DatabaseUtils.writeLock).isHeldByCurrentThread()) {
            throw new IllegalStateException("DatabaseUtils.writeLock not held");
        }
        VirtualNetworkConfig virtualNetworkConfig2 = getVirtualNetworkConfig(network.getNetworkId());
        setVirtualNetworkConfig(network.getNetworkId(), virtualNetworkConfig);
        var networkName = virtualNetworkConfig.getName();
        if (networkName != null && !networkName.isEmpty()) {
            network.setNetworkName(networkName);
        }
        network.update();
        return !virtualNetworkConfig.equals(virtualNetworkConfig2);
    }

    protected void shutdown() {
        stopZeroTier();
        if (this.vpnSocket != null) {
            try {
                this.vpnSocket.close();
            } catch (Exception e) {
                LogUtil.e(TAG, "Error closing VPN socket", e);
            }
            this.vpnSocket = null;
        }
        stopSelf(this.mStartID);
    }

    private boolean updateTunnelConfig(Network network) {
        // 防止并发执行：若已有 VPN 配置正在进行，延迟 3s 后由 networkChangeHandler 重试一次。
        if (!isConfiguringVpn.compareAndSet(false, true)) {
            LogUtil.d(TAG, "updateTunnelConfig: 已有配置正在进行，延迟 3s 重试");
            if (networkChangeHandler != null) {
                networkChangeHandler.removeCallbacks(networkChangeRunnable);
                networkChangeHandler.postDelayed(networkChangeRunnable, NETWORK_CHANGE_DEBOUNCE_MS);
            } else {
                LogUtil.w(TAG, "updateTunnelConfig: networkChangeHandler 未就绪，重试请求已丢弃");
            }
            return false;
        }
        try {
            return doUpdateTunnelConfig(network);
        } finally {
            isConfiguringVpn.set(false);
        }
    }

    private boolean doUpdateTunnelConfig(Network network) {
        long networkId = network.getNetworkId();
        var networkConfig = network.getNetworkConfig();
        var virtualNetworkConfig = getVirtualNetworkConfig(networkId);
        if (virtualNetworkConfig == null) {
            return false;
        }

        // 重启 TUN TAP
        if (this.tunTapAdapter.isRunning()) {
            this.tunTapAdapter.interrupt();
            try {
                this.tunTapAdapter.join();
            } catch (InterruptedException ignored) {
            }
        }
        this.tunTapAdapter.clearRouteMap();

        // 重启 VPN Socket
        if (this.in != null) {
            try {
                this.in.close();
            } catch (Exception e) {
                LogUtil.e(TAG, "Error closing VPN input stream: " + e, e);
            }
            this.in = null;
        }
        if (this.out != null) {
            try {
                this.out.close();
            } catch (Exception e) {
                LogUtil.e(TAG, "Error closing VPN output stream: " + e, e);
            }
            this.out = null;
        }
        if (this.vpnSocket != null) {
            try {
                this.vpnSocket.close();
            } catch (Exception e) {
                LogUtil.e(TAG, "Error closing VPN socket: " + e, e);
            }
            this.vpnSocket = null;
        }

        // 配置 VPN
        LogUtil.d(TAG, "Configuring VpnService.Builder");
        var builder = new VpnService.Builder();
        var assignedAddresses = virtualNetworkConfig.getAssignedAddresses();
        boolean isRouteViaZeroTier = networkConfig.getRouteViaZeroTier();
        boolean isPerAppRouting = networkConfig.getPerAppRouting();

        // 遍历 ZT 网络中当前设备的 IP 地址，组播配置
        for (var vpnAddress : assignedAddresses) {
            LogUtil.d(TAG, "Adding VPN Address: " + vpnAddress.getAddress()
                    + " Mac: " + com.zerotier.sdk.util.StringUtils.macAddressToString(virtualNetworkConfig.getMac()));
            byte[] rawAddress = vpnAddress.getAddress().getAddress();

            if (!this.disableIPv6 || !(vpnAddress.getAddress() instanceof Inet6Address)) {
                var address = vpnAddress.getAddress();
                var port = vpnAddress.getPort();
                var route = InetAddressUtils.addressToRoute(address, port);
                if (route == null) {
                    LogUtil.e(TAG, "NULL route calculated!");
                    continue;
                }

                // 计算 VPN 地址相关的组播 MAC 与 ADI
                long multicastGroup;
                long multicastAdi;
                if (rawAddress.length == 4) {
                    // IPv4
                    multicastGroup = InetAddressUtils.BROADCAST_MAC_ADDRESS;
                    multicastAdi = ByteBuffer.wrap(rawAddress).getInt();
                } else {
                    // IPv6
                    multicastGroup = ByteBuffer.wrap(new byte[]{
                                    0, 0, 0x33, 0x33, (byte) 0xFF, rawAddress[13], rawAddress[14], rawAddress[15]})
                            .getLong();
                    multicastAdi = 0;
                }

                // 订阅组播并添加至 TUN TAP 路由
                var result = this.node.multicastSubscribe(networkId, multicastGroup, multicastAdi);
                if (result != ResultCode.RESULT_OK) {
                    LogUtil.e(TAG, "Error joining multicast group");
                } else {
                    LogUtil.d(TAG, "Joined multicast group");
                }
                builder.addAddress(address, port);
                builder.addRoute(route, port);
                this.tunTapAdapter.addRouteAndNetwork(new Route(route, port), networkId);
            }
        }

        // 如果启用了全局路由或per-app路由，添加默认路由(0.0.0.0/0 和 ::/0)
        // Per-app模式需要全局路由，这样选中的应用才能访问互联网
        // 只有选中的应用能使用这些路由（通过addAllowedApplication限制）
        boolean shouldAddGlobalRoutes = isRouteViaZeroTier || isPerAppRouting;
        int smartRoutingMode = networkConfig.getSmartRoutingMode();
        if (shouldAddGlobalRoutes) {
            try {
                // 新版本始终启用国内直连分流，无需依赖 DB 中的 smartRoutingMode 值：
                //   • 全局路由（isRouteViaZeroTier=true）：中国 IP 排除在 VPN 之外，直接走物理网络
                //   • Per-app 路由（isPerAppRouting=true）：选中应用（如微信）的中国 CDN 流量
                //     同样走物理网络，仅非中国 IP 进入 VPN/ZT，避免直播 CDN 因 ZT 绕路而卡顿
                configureChinaDirectRouting(builder, virtualNetworkConfig, assignedAddresses);
                
                // 大幅增强对本地连接的保护，避免VPN路由循环
                // 1. 保护常用DNS查询连接
                // protectSocketConnection("8.8.8.8", 53);
                // protectSocketConnection("8.8.4.4", 53);
                // protectSocketConnection("114.114.114.114", 53);
                // protectSocketConnection("223.5.5.5", 53);
                // protectSocketConnection("1.1.1.1", 53);
                // protectSocketConnection("119.29.29.29", 53);
                
                // // 2. 保护关键Google服务
                // protectSocketConnection("googleapis.com", 443);
                // protectSocketConnection("google.com", 443);
                
                // // 3. 保护局域网连接 - 更完整的方式
                // String[] commonPrivateNetworks = {
                //     "10.0.0.0", "172.16.0.0", "192.168.0.0", "127.0.0.0" 
                // };
                // for (String privateNet : commonPrivateNetworks) {
                //     protectSocketConnection(privateNet, 0);
                //     LogUtil.i(TAG, "保护私有网络: " + privateNet);
                // }
                
                try {
                    NetworkInterface[] networkInterfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
                            .toArray(new NetworkInterface[0]);
                    for (NetworkInterface networkInterface : networkInterfaces) {
                        if (networkInterface.isUp() && !networkInterface.isLoopback() && 
                                !networkInterface.getName().equals("tun0") && 
                                !networkInterface.getName().startsWith("zt")) {
                            for (InetAddress address : Collections.list(networkInterface.getInetAddresses())) {
                                if (address instanceof Inet4Address) {
                                    // 保护到局域网的路由
                                    // String ip = address.getHostAddress();
                                    // if (ip != null && ip.contains(".")) {
                                    //     String subnet = ip.substring(0, ip.lastIndexOf(".")) + ".0";
                                    //     protectSocketConnection(subnet, 0);
                                    //     LogUtil.i(TAG, "保护局域网连接: " + subnet);
                                        
                                    //     // // 保护整个C类网络
                                    //     // if (ip.indexOf(".") > 0) {
                                    //     //     String classC = ip.substring(0, ip.indexOf(".")) + ".0.0.0";
                                    //     //     protectSocketConnection(classC, 0);
                                    //     //     LogUtil.i(TAG, "保护C类网络: " + classC);
                                    //     // }
                                    // }
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    LogUtil.e(TAG, "保护局域网连接时出错: " + e.getMessage());
                }
                
                // 新版本始终以 CHINA_DIRECT 运行，跳过 IPv6 全局路由（::/0 通过 ZT）。
                // 中国 IPv6 地址（腾讯 CDN、CERNET）直接走物理网络，无需经境外 ZT 节点转发。
                
            } catch (Exception e) {
                LogUtil.e(TAG, "添加默认路由时出错: " + e.getMessage(), e);
            }
        }

        // 遍历网络的路由规则，将网络负责路由的地址路由至 VPN
        try {
            var v4Loopback = InetAddress.getByName("0.0.0.0");
            var v6Loopback = InetAddress.getByName("::");
            if (virtualNetworkConfig.getRoutes().length > 0) {
                for (var routeConfig : virtualNetworkConfig.getRoutes()) {
                    var target = routeConfig.getTarget();
                    var via = routeConfig.getVia();
                    var targetAddress = target.getAddress();
                    var targetPort = target.getPort();
                    var viaAddress = InetAddressUtils.addressToRoute(targetAddress, targetPort);

                    boolean isIPv6Route = (targetAddress instanceof Inet6Address) || (viaAddress instanceof Inet6Address);
                    boolean isDisabledV6Route = this.disableIPv6 && isIPv6Route;
                    
                    // 修改路由判断逻辑，避免默认路由进VPN导致路由循环
                    boolean isDefaultRoute = viaAddress != null && 
                            (viaAddress.equals(v4Loopback) || viaAddress.equals(v6Loopback));
                    boolean shouldRouteToZerotier = viaAddress != null && (
                            // 全局路由模式下，默认路由不经过VPN
                            (isRouteViaZeroTier && !isDefaultRoute) 
                            // 非全局路由模式下，保持原有逻辑
                            || (!isRouteViaZeroTier && !viaAddress.equals(v4Loopback) && !viaAddress.equals(v6Loopback))
                    );

                    if (!isDisabledV6Route && shouldRouteToZerotier) {
                        builder.addRoute(viaAddress, targetPort);
                        Route route = new Route(viaAddress, targetPort);
                        if (via != null) {
                            route.setGateway(via.getAddress());
                        }
                        this.tunTapAdapter.addRouteAndNetwork(route, networkId);
                    }
                }
            }
            builder.addRoute(InetAddress.getByName("224.0.0.0"), 4);

        } catch (Exception e) {
            this.eventBus.post(new VPNErrorEvent(e.getLocalizedMessage()));
            return false;
        }

        // 配置DNS和MTU
        if (Build.VERSION.SDK_INT >= 29) {
            builder.setMetered(false);
        }
        
        // 增强DNS服务器配置
        addDNSServers(builder, network);
        
        // 配置允许绕过的APP包
        configureAllowedDisallowedApps(builder, isRouteViaZeroTier);

        // 配置 MTU
        int mtu = virtualNetworkConfig.getMtu();
        LogUtil.d(TAG, "MTU from Network Config: " + mtu);
        if (mtu == 0) {
            mtu = 2800;
        }
        LogUtil.d(TAG, "MTU Set: " + mtu);
        builder.setMtu(mtu);

        builder.setSession(Constants.VPN_SESSION_NAME);

        // 建立 VPN 连接
        // establish() 通过 Binder 传输路由表，若路由条数过多可能抛出 TransactionTooLargeException。
        // 捕获该异常并降级为全局路由（0.0.0.0/0），确保 VPN 至少能正常启动。
        try {
            this.vpnSocket = builder.establish();
        } catch (RuntimeException e) {
            LogUtil.e(TAG, "establish() 失败（可能路由条数过多）：" + e.getMessage() + "，降级为全局路由重试", e);
            // 降级：重建 builder，仅使用 0.0.0.0/0 全局路由
            var fallbackBuilder = new VpnService.Builder();
            for (var vpnAddress : assignedAddresses) {
                if (!this.disableIPv6 || !(vpnAddress.getAddress() instanceof Inet6Address)) {
                    var address = vpnAddress.getAddress();
                    var port = vpnAddress.getPort();
                    fallbackBuilder.addAddress(address, port);
                }
            }
            try {
                List<long[]> localSubnets = detectLocalSubnetsToExclude();
                addGlobalRoutesToBuilder(fallbackBuilder, localSubnets);
            } catch (Exception fallbackEx) {
                LogUtil.e(TAG, "降级全局路由配置失败: " + fallbackEx.getMessage(), fallbackEx);
                try {
                    fallbackBuilder.addRoute(InetAddress.getByName("0.0.0.0"), 0);
                } catch (Exception ignored) { }
            }
            addDNSServers(fallbackBuilder, network);
            configureAllowedDisallowedApps(fallbackBuilder, isRouteViaZeroTier);
            fallbackBuilder.setMtu(mtu);
            fallbackBuilder.setSession(Constants.VPN_SESSION_NAME);
            this.vpnSocket = fallbackBuilder.establish();
        }
        if (this.vpnSocket == null) {
            this.eventBus.post(new VPNErrorEvent(getString(R.string.toast_vpn_application_not_prepared)));
            return false;
        }
        this.in = new FileInputStream(this.vpnSocket.getFileDescriptor());
        this.out = new FileOutputStream(this.vpnSocket.getFileDescriptor());
        this.tunTapAdapter.setVpnSocket(this.vpnSocket);
        this.tunTapAdapter.setFileStreams(this.in, this.out);

        // 配置 TunTapAdapter 路由上下文（用于 CONN 日志和诊断）。
        // 新版本始终以 CHINA_DIRECT 运行，无需依赖 DB 中的 smartRoutingMode 值。
        // Per-app 模式：perAppRoutingActive=true，仅选定应用进入 TUN。
        int effectiveSmartRoutingMode = shouldAddGlobalRoutes
                ? SmartRoutingManager.MODE_CHINA_DIRECT
                : smartRoutingMode;
        SmartRoutingManager smartRouter = SmartRoutingManager.getInstance(this);
        this.tunTapAdapter.setSmartRouting(smartRouter, effectiveSmartRoutingMode, isPerAppRouting);

        // 注册自学习直连 IP 回调：DNS 嗅探发现直播 CDN 走 ZT 时，
        // 用 10 秒防抖重建 VPN，让新 IP 立即走物理直连，实现"越用越好用"。
        smartRouter.setOnNewLearnedIpListener(ip -> {
            if (networkChangeHandler != null) {
                networkChangeHandler.removeCallbacks(learnedIpRebuildRunnable);
                networkChangeHandler.postDelayed(learnedIpRebuildRunnable,
                        LEARNED_IP_REBUILD_DEBOUNCE_MS);
            }
        });

        this.tunTapAdapter.startThreads();

        // 状态栏提示
        if (this.notificationManager == null) {
            this.notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        }
        if (Build.VERSION.SDK_INT >= 26) {
            String channelName = getString(R.string.channel_name);
            String description = getString(R.string.channel_description);
            var channel = new NotificationChannel(
                    Constants.CHANNEL_ID, channelName, NotificationManager.IMPORTANCE_HIGH);
            channel.setDescription(description);
            this.notificationManager.createNotificationChannel(channel);
        }
        int pendingIntentFlag = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 31) {
            pendingIntentFlag |= PendingIntent.FLAG_IMMUTABLE;
        }
        var pendingIntent =
                PendingIntent.getActivity(this, 0,
                        new Intent(this, NetworkListActivity.class)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP
                                        | Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        , pendingIntentFlag);
        var notification = new NotificationCompat.Builder(this, Constants.CHANNEL_ID)
                .setPriority(1)
                .setOngoing(true)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(getString(R.string.notification_title_connected))
                .setContentText(getString(R.string.notification_text_connected, network.getNetworkIdStr()))
                .setColor(ContextCompat.getColor(getApplicationContext(), R.color.zerotier_orange))
                .setContentIntent(pendingIntent).build();
        this.notificationManager.notify(ZT_NOTIFICATION_TAG, notification);
        LogUtil.i(TAG, "ZeroTier One Connected");

        // 注册网络变化回调：当手机在 WiFi/4G/5G 之间切换时重新配置 VPN 路由
        registerNetworkChangeCallback();

        // 旧版本 Android 多播处理
        if (Build.VERSION.SDK_INT < 29) {
            if (this.v4MulticastScanner != null && !this.v4MulticastScanner.isAlive()) {
                this.v4MulticastScanner.start();
            }
            if (!this.disableIPv6 && this.v6MulticastScanner != null && !this.v6MulticastScanner.isAlive()) {
                this.v6MulticastScanner.start();
            }
        }
        return true;
    }
    
    /**
     * 保护套接字连接，避免VPN路由循环
     */
    private void protectSocketConnection(String host, int port) {
        // try {
        //     DatagramSocket socket = new DatagramSocket();
        //     socket.connect(InetAddress.getByName(host), port);
        //     boolean success = protect(socket);
        //     LogUtil.i(TAG, "保护连接到 " + host + ":" + port + (success ? " 成功" : " 失败"));
        //     socket.close();
            
        //     // 同时尝试保护TCP连接
        //     Socket tcpSocket = new Socket();
        //     tcpSocket.connect(new InetSocketAddress(host, port == 0 ? 80 : port), 500);
        //     success = protect(tcpSocket);
        //     LogUtil.i(TAG, "保护TCP连接到 " + host + ":" + (port == 0 ? 80 : port) + (success ? " 成功" : " 失败"));
        //     tcpSocket.close();
        // } catch (Exception e) {
        //     // 忽略连接错误，不是所有地址都能连接成功
        //     LogUtil.d(TAG, "保护连接尝试: " + host + ":" + port + " - " + e.getMessage());
        // }
    }

    /**
     * 注册网络变化回调。
     * 当手机在 WiFi、4G/5G、蓝牙热点等网络之间切换时，本地子网会发生变化，
     * 需要重新计算 VPN 路由排除列表以确保本地子网不被意外路由至 TUN。
     */
    private void registerNetworkChangeCallback() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            // API 24 以下不支持 registerDefaultNetworkCallback
            return;
        }
        // 启动后台 HandlerThread，用于异步执行 VPN 重建，避免阻塞系统回调线程
        if (networkChangeThread == null) {
            networkChangeThread = new HandlerThread("ZT-NetworkChange");
            networkChangeThread.start();
            networkChangeHandler = new Handler(networkChangeThread.getLooper());
        }
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return;
            unregisterNetworkChangeCallback(); // 避免重复注册
            this.networkCallback = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(android.net.Network network) {
                    LogUtil.i(TAG, "网络变化: 新网络可用 (" + network + ")");
                    // 新网络出现时重置地址快照，确保后续 onLinkPropertiesChanged 能正常比较
                    lastLinkAddresses = null;
                    onNetworkChanged();
                }

                @Override
                public void onLost(android.net.Network network) {
                    LogUtil.i(TAG, "网络变化: 网络丢失 (" + network + ")");
                    lastLinkAddresses = null;
                    onNetworkChanged();
                }

                @Override
                public void onLinkPropertiesChanged(android.net.Network network,
                                                    LinkProperties linkProperties) {
                    // 只有链路地址（IP 地址/前缀）发生变化时才重配 VPN。
                    // DNS 更新、MTU 变化、DHCP 续租等不改变地址的事件将被忽略，
                    // 避免 VPN 启动时因 Android 系统多次下发配置而产生误报日志和无效重建。
                    Set<LinkAddress> newAddresses = new HashSet<>(linkProperties.getLinkAddresses());
                    if (newAddresses.equals(lastLinkAddresses)) {
                        LogUtil.d(TAG, "网络变化: 链路属性变化（地址未变，跳过重配）(" + network + ")");
                        return;
                    }
                    lastLinkAddresses = newAddresses;
                    LogUtil.i(TAG, "网络变化: 链路地址变化 (" + network + ")");
                    onNetworkChanged();
                }
            };
            cm.registerDefaultNetworkCallback(this.networkCallback);
            LogUtil.d(TAG, "已注册网络变化回调");
        } catch (Exception e) {
            LogUtil.w(TAG, "注册网络变化回调失败: " + e.getMessage());
        }
    }

    /**
     * 取消网络变化回调
     */
    private void unregisterNetworkChangeCallback() {
        if (this.networkCallback == null) return;
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                cm.unregisterNetworkCallback(this.networkCallback);
            }
        } catch (Exception e) {
            LogUtil.d(TAG, "取消网络回调时出错: " + e.getMessage());
        }
        this.networkCallback = null;
        this.lastLinkAddresses = null;
        // 停止 HandlerThread（取消所有待执行的重建任务）
        if (networkChangeHandler != null) {
            networkChangeHandler.removeCallbacks(networkChangeRunnable);
            networkChangeHandler.removeCallbacks(learnedIpRebuildRunnable);
            networkChangeHandler = null;
        }
        if (networkChangeThread != null) {
            networkChangeThread.quit();
            networkChangeThread = null;
        }
    }

    /**
     * 网络环境发生变化时，将重建任务 debounce 提交到后台 HandlerThread。
     * 连续多次触发时，旧的 pending 任务会被取消，只执行最后一次。
     * 系统回调线程立即返回，不会被 VPN 重建阻塞。
     */
    private void onNetworkChanged() {
        if (networkChangeHandler == null) return;
        networkChangeHandler.removeCallbacks(networkChangeRunnable);
        networkChangeHandler.postDelayed(networkChangeRunnable, NETWORK_CHANGE_DEBOUNCE_MS);
    }

    /**
     * 实际执行 VPN 路由重建的逻辑，由 {@link #networkChangeRunnable} 在后台线程调用。
     */
    private void doNetworkChangedUpdate() {
        // 清除连接日志缓存，以便网络切换后重新记录
        if (this.tunTapAdapter != null) {
            this.tunTapAdapter.clearConnLog();
        }

        // 触发 VPN 隧道重建
        if (this.networkId != 0 && this.node != null) {
            LogUtil.i(TAG, "网络环境变化，将重新配置 VPN 路由");
            DatabaseUtils.readLock.lock();
            try {
                var daoSession = ((ZerotierFixApplication) getApplication()).getDaoSession();
                var networks = daoSession.getNetworkDao().queryBuilder()
                        .where(NetworkDao.Properties.NetworkId.eq(this.networkId))
                        .list();
                if (networks.size() == 1) {
                    Network network = networks.get(0);
                    updateTunnelConfig(network);
                }
            } catch (Exception e) {
                LogUtil.e(TAG, "网络变化后重配 VPN 失败: " + e.getMessage(), e);
            } finally {
                DatabaseUtils.readLock.unlock();
            }
        }
    }
    
    /**
     * 配置允许/不允许的应用
     */
    private void configureAllowedDisallowedApps(VpnService.Builder builder, boolean isRouteViaZeroTier) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            // Android 5.0 以下版本不支持per-app VPN
            return;
        }

        // 获取网络配置
        DatabaseUtils.readLock.lock();
        Network network = null;
        try {
            var daoSession = ((ZerotierFixApplication) getApplication()).getDaoSession();
            var networkDao = daoSession.getNetworkDao();
            var networks = networkDao.queryBuilder()
                    .where(NetworkDao.Properties.NetworkId.eq(this.networkId))
                    .list();
            if (networks.size() == 1) {
                network = networks.get(0);
            }
        } finally {
            DatabaseUtils.readLock.unlock();
        }

        if (network == null) {
            LogUtil.e(TAG, "无法获取网络配置，跳过per-app路由设置");
            return;
        }

        NetworkConfig networkConfig = network.getNetworkConfig();
        boolean isPerAppRouting = networkConfig.getPerAppRouting();

        if (!isPerAppRouting) {
            // 只有在启用全局路由时才配置
            if (isRouteViaZeroTier) {
                // 全局路由模式，所有应用都通过VPN（除了本应用）
                // 排除本应用自身，避免VPN循环
                try {
                    builder.addDisallowedApplication(getPackageName());
                    LogUtil.d(TAG, "排除应用: " + getPackageName() + " (本应用)");
                } catch (Exception e) {
                    LogUtil.e(TAG, "无法排除应用 " + getPackageName(), e);
                }
            } else {
                LogUtil.d(TAG, "未启用全局路由或per-app路由");
            }
            return;
        }

        // Per-app路由模式（正向模式：仅选中的应用走VPN，其他应用走原始路由）
        LogUtil.d(TAG, "使用per-app路由模式（正向模式）");

        // 从数据库获取应用路由设置
        DatabaseUtils.readLock.lock();
        Set<String> allowedPackages = new HashSet<>();
        try {
            var daoSession = ((ZerotierFixApplication) getApplication()).getDaoSession();
            var appRoutingDao = daoSession.getAppRoutingDao();
            var appRoutings = appRoutingDao.queryBuilder()
                    .where(AppRoutingDao.Properties.NetworkId.eq(this.networkId))
                    .list();

            // 收集所有应该走VPN的应用（routeViaVpn=true）
            for (var routing : appRoutings) {
                if (routing.getRouteViaVpn()) {
                    allowedPackages.add(routing.getPackageName());
                    LogUtil.d(TAG, "选中应用（将走VPN）: " + routing.getPackageName());
                }
            }
        } finally {
            DatabaseUtils.readLock.unlock();
        }

        // 使用 addAllowedApplication 为选中的应用配置白名单模式
        // 注意：不要添加本应用自身，让本应用走原始路由避免VPN循环
        int allowedCount = 0;
        for (String packageName : allowedPackages) {
            // 跳过本应用自身
            if (packageName.equals(getPackageName())) {
                LogUtil.d(TAG, "跳过本应用: " + getPackageName() + " (本应用不应使用VPN)");
                continue;
            }
            
            try {
                builder.addAllowedApplication(packageName);
                allowedCount++;
                LogUtil.d(TAG, "允许应用走VPN: " + packageName);
            } catch (Exception e) {
                LogUtil.e(TAG, "无法添加允许应用 " + packageName + ": " + e.getMessage(), e);
            }
        }

        LogUtil.i(TAG, "VPN已就绪: " + allowedCount + " 个应用通过Per-app路由走VPN");
    }

    private void addDNSServers(VpnService.Builder builder, Network network) {
        var networkConfig = network.getNetworkConfig();
        var virtualNetworkConfig = getVirtualNetworkConfig(network.getNetworkId());
        var dnsMode = DNSMode.fromInt(networkConfig.getDnsMode());
        boolean isRouteViaZeroTier = networkConfig.getRouteViaZeroTier();
        boolean isPerAppRouting = networkConfig.getPerAppRouting();
        // 全局代理模式（isRouteViaZeroTier=true）：中国 IP 排除在 VPN 外，DNS 服务器本身却是中国 IP，
        // 国内 DNS（114/AliDNS）直连时会对 google.com 等境外域名进行 DNS 污染，导致证书错误。
        // 使用国际 DNS（Google/Cloudflare），这些 IP 是非中国 IP，会经 VPN/ZT 发出，绕过 GFW 污染。
        // Per-app 路由模式（isPerAppRouting=true）：选中的应用（如微信）需要国内 CDN 解析到国内节点，
        // 保持使用国内 DNS（在 CHINA_DIRECT 路由下直接走物理网络，不受 GFW 干扰）。
        boolean isGlobalProxy = isRouteViaZeroTier;
        boolean usesDomesticDns = isPerAppRouting && !isRouteViaZeroTier;

        switch (dnsMode) {
            case NETWORK_DNS:
                if (virtualNetworkConfig.getDns() == null) {
                    if (isGlobalProxy) {
                        LogUtil.d(TAG, "全局代理模式：添加国际 DNS 服务器（Google/Cloudflare，经 ZT 发出）");
                        addInternationalDNSServers(builder);
                    } else if (usesDomesticDns) {
                        LogUtil.d(TAG, "Per-app 路由模式：添加国内 DNS 服务器（114DNS / AliDNS）");
                        addDomesticDNSServers(builder);
                    }
                    return;
                }
                builder.addSearchDomain(virtualNetworkConfig.getDns().getDomain());
                for (var inetSocketAddress : virtualNetworkConfig.getDns().getServers()) {
                    InetAddress address = inetSocketAddress.getAddress();
                    if (address instanceof Inet4Address) {
                        builder.addDnsServer(address);
                    } else if ((address instanceof Inet6Address) && !this.disableIPv6) {
                        builder.addDnsServer(address);
                    }
                }
                // 路由激活时额外添加 DNS 备援
                if (isGlobalProxy) {
                    LogUtil.d(TAG, "全局代理模式：添加国际备用 DNS 服务器");
                    addInternationalDNSServers(builder);
                } else if (usesDomesticDns) {
                    LogUtil.d(TAG, "Per-app 路由模式：添加国内备用 DNS 服务器");
                    addDomesticDNSServers(builder);
                }
                break;
                
            case CUSTOM_DNS:
                for (var dnsServer : networkConfig.getDnsServers()) {
                    try {
                        InetAddress byName = InetAddress.getByName(dnsServer.getNameserver());
                        if (byName instanceof Inet4Address) {
                            builder.addDnsServer(byName);
                        } else if ((byName instanceof Inet6Address) && !this.disableIPv6) {
                            builder.addDnsServer(byName);
                        }
                    } catch (Exception e) {
                        LogUtil.e(TAG, "Exception parsing DNS server: " + e, e);
                    }
                }
                if (isGlobalProxy) {
                    LogUtil.d(TAG, "全局代理模式（自定义DNS）：追加国际备用 DNS 服务器");
                    addInternationalDNSServers(builder);
                } else if (usesDomesticDns) {
                    // 若自定义 DNS 是境外服务（如 8.8.8.8），CDN 域名会被解析到境外节点，
                    // 追加国内 DNS 作为备用，确保国内 CDN 走直连。
                    LogUtil.d(TAG, "Per-app 路由模式（自定义DNS）：追加国内备用 DNS 服务器");
                    addDomesticDNSServers(builder);
                }
                break;
                
            default:
                if (isGlobalProxy) {
                    LogUtil.d(TAG, "全局代理模式（默认DNS）：添加国际 DNS 服务器");
                    addInternationalDNSServers(builder);
                } else if (usesDomesticDns) {
                    LogUtil.d(TAG, "Per-app 路由模式（默认DNS）：添加国内 DNS 服务器");
                    addDomesticDNSServers(builder);
                }
                break;
        }
    }
    

    /**
     * 配置使用代理服务器的IPv4路由
     * @param builder VPN构建器
     */
    // 代理功能已移除
    private void configureProxyRouting(VpnService.Builder builder) throws Exception {
    }

    /**
     * 配置使用代理服务器的IPv6路由 - 已移除，保留方法签名避免编译错误
     * @param builder VPN构建器
     */
    private void configureProxyIPv6Routing(VpnService.Builder builder) throws Exception {
    }

    /**
     * 添加国内直连模式专用 DNS 服务器（114DNS / AliDNS）。
     *
     * <p>这些 DNS 服务器本身是中国 IP，在 CHINA_DIRECT 路由下会被排除在 VPN 之外，
     * 因此 DNS 查询经物理网络直接发出，返回国内 CDN 地址（如微信视频号 CDN）。
     * 若改用 Cloudflare/Google DNS，境外 DNS 会将微信视频号等内容解析到境外 CDN 节点，
     * 导致流量绕行境外 ZeroTier 节点，引发明显卡顿。
     */
    private void addDomesticDNSServers(VpnService.Builder builder) {
        for (String dns : DOMESTIC_DNS_SERVERS) {
            try {
                builder.addDnsServer(InetAddress.getByName(dns));
            } catch (Exception e) {
                LogUtil.w(TAG, "添加国内 DNS 服务器失败 " + dns + ": " + e.getMessage());
            }
        }
        LogUtil.i(TAG, "CHINA_DIRECT 模式：已添加国内 DNS 服务器（114DNS / AliDNS）");
    }

    /**
     * 添加全局代理模式专用 DNS 服务器（Google DNS / Cloudflare DNS）。
     *
     * <p>这些服务器是非中国 IP，在 CHINA_DIRECT 路由下会进入 VPN 隧道（ZeroTier），
     * 因此 DNS 查询经 ZeroTier 加密转发，不受 GFW DNS 污染影响，
     * 可正确解析 google.com 等被封锁域名，避免证书错误。
     */
    private void addInternationalDNSServers(VpnService.Builder builder) {
        for (String dns : INTERNATIONAL_DNS_SERVERS) {
            try {
                builder.addDnsServer(InetAddress.getByName(dns));
            } catch (Exception e) {
                LogUtil.w(TAG, "添加国际 DNS 服务器失败 " + dns + ": " + e.getMessage());
            }
        }
        LogUtil.i(TAG, "全局代理模式：已添加国际 DNS 服务器（Google DNS / Cloudflare DNS）");
    }

    /**
     * 配置国内直连模式的 IPv4 VPN 路由：非中国 IP 走 ZeroTier，中国 IP 直接走物理网络。
     *
     * <p>与全局路由的差异：
     * <ul>
     *   <li>Android 13+：添加 0.0.0.0/0 后用 {@link VpnService.Builder#excludeRoute(IpPrefix)}
     *       排除所有中国 IP 段及本地子网</li>
     *   <li>Android 12 及以下：直接添加各条非中国 CIDR（已排除本地活跃子网）</li>
     * </ul>
     *
     * <p>若 chnroutes 数据尚未加载完毕，临时回退到全局路由（排除本地子网），
     * 并注册一次性回调：数据就绪后自动触发 VPN 路由重建。
     */
    private void configureChinaDirectRouting(VpnService.Builder builder,
                                             VirtualNetworkConfig virtualNetworkConfig,
                                             InetSocketAddress[] assignedAddresses) throws Exception {
        SmartRoutingManager router = SmartRoutingManager.getInstance(this);
        List<long[]> localSubnets = detectLocalSubnetsToExclude();

        // 找 ZeroTier 网关（与 configureDirectGlobalRouting 逻辑相同）
        InetAddress zerotierGateway = null;
        if (virtualNetworkConfig.getRoutes().length > 0) {
            for (var routeConfig : virtualNetworkConfig.getRoutes()) {
                var via = routeConfig.getVia();
                if (via != null) {
                    zerotierGateway = via.getAddress();
                    break;
                }
            }
        }
        if (zerotierGateway == null && assignedAddresses.length > 0) {
            for (var addr : assignedAddresses) {
                if (addr.getAddress() instanceof Inet4Address) {
                    byte[] ipBytes = addr.getAddress().getAddress();
                    ipBytes[3] = 1;
                    zerotierGateway = InetAddress.getByAddress(ipBytes);
                    break;
                }
            }
        }

        // TUN 层始终保留 0.0.0.0/0 默认路由，使 TunTapAdapter 能将所有进入 TUN 的包转发到 ZT 网关。
        // （OS 已通过 excludeRoute/非中国路由保证中国 IP 不进入 TUN，此规则仅对非中国 IP 生效）
        Route defaultTunRoute = new Route(InetAddress.getByName("0.0.0.0"), 0);
        if (zerotierGateway != null) defaultTunRoute.setGateway(zerotierGateway);
        this.tunTapAdapter.addRouteAndNetwork(defaultTunRoute, networkId);

        if (!router.isChnroutesReady()) {
            // chnroutes 尚未加载，临时使用全局路由，数据就绪后触发重建
            LogUtil.w(TAG, "CHINA_DIRECT 模式：chnroutes 尚未加载，临时全局路由，就绪后重建");
            router.setOnChnroutesReadyListener(() -> {
                LogUtil.i(TAG, "chnroutes 已就绪，触发 CHINA_DIRECT VPN 路由重建");
                // chnroutes 新鲜加载完毕，重置一次性验证标志，使下次重建重新打印腾讯 CIDR 验证日志
                tencentCidrsVerified = false;
                onNetworkChanged();
            });
            addGlobalRoutesToBuilder(builder, localSubnets);
            return;
        }

        // 验证腾讯云补充 IP 段是否已正确加入中国 IP 列表（这些段是微信视频号直播的 CDN，
        // 若缺失则直播流量会经 ZT 境外节点中转，导致明显卡顿）。
        // 如果下面某段出现 NOT_IN_CHINA，说明 chnroutes_supplement.txt 或当前 chnroutes.txt
        // 缺少该段，需要手动触发强制刷新或更新 chnroutes_supplement.txt。
        // tencentCidrsVerified 标志确保此验证在每次 chnroutes 加载后只打印一次，
        // 避免每次 VPN 重建都重复输出相同的 11 条日志。
        if (!tencentCidrsVerified) {
            tencentCidrsVerified = true;
            String[] tencentCidrChecks = {
                    // ── AS45090 (Tencent Cloud China) ──
                    "43.128.0.0",  // 43.128.0.0/13  腾讯云新节点（视频号直播 CDN 调度）
                    "43.152.0.0",  // 43.152.0.0/13  腾讯云上海节点
                    "162.62.0.0",  // 162.62.0.0/16  腾讯云国内节点
                    "101.33.0.0",  // 101.33.0.0/17  腾讯云广州/北京
                    "119.28.0.0",  // 119.28.0.0/16  旧 QCloud
                    // ── AS132203 (Tencent Cloud International, 用于中国 CDN) ──
                    "129.226.0.0", // 129.226.0.0/16 视频号直播实测命中（szlong.weixin.qq.com）
                    "150.109.0.0", // 150.109.0.0/16 WeChat CDN / apmplus
                    "49.51.0.0",   // 49.51.0.0/16   Tencent Cloud International
                    // ── 其他常见腾讯/视频直播相关 IP（应在 chnroutes.txt BGP 数据中） ──
                    "183.2.0.0",   // 183.2.0.0/16   腾讯 AS17623（微信 CDN 常用）
                    "175.27.0.0",  // 175.27.0.0/16  腾讯云（B 站/视频号 CDN）
                    "58.250.0.0",  // 58.250.0.0/15  腾讯 AS17623
            };
            String[] tencentCidrLabels = {
                    "43.128.0.0/13", "43.152.0.0/13", "162.62.0.0/16",
                    "101.33.0.0/17", "119.28.0.0/16",
                    "129.226.0.0/16", "150.109.0.0/16", "49.51.0.0/16",
                    "183.2.0.0/16", "175.27.0.0/16", "58.250.0.0/15",
            };
            for (int i = 0; i < tencentCidrChecks.length; i++) {
                try {
                    InetAddress sample = InetAddress.getByName(tencentCidrChecks[i]);
                    boolean inChina = router.isChineseIp(sample);
                    if (inChina) {
                        LogUtil.i(LogUtil.ROUTE_TAG, "腾讯云 " + tencentCidrLabels[i]
                                + " → IN_CHINA（已排除出 VPN）✓");
                    } else {
                        LogUtil.w(LogUtil.ROUTE_TAG, "腾讯云 " + tencentCidrLabels[i]
                                + " → NOT_IN_CHINA（未排除出 VPN，直播可能卡顿！）");
                    }
                } catch (Exception e) {
                    LogUtil.w(LogUtil.ROUTE_TAG, "验证腾讯云 CIDR " + tencentCidrLabels[i] + " 失败: " + e.getMessage());
                }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+：0.0.0.0/0 + excludeRoute(中国IP) + excludeRoute(本地子网)
            // 部分 OEM ROM 的 Binder 事务上限低于 AOSP 1 MB（如 600 KB），
            // 全量 7 400+ 条中国 CIDR 序列化约 620 KB 会触发 TransactionTooLargeException。
            // 按前缀长度升序排序（短前缀 = 覆盖更广），取最重要的 MAX_EXCLUDE_ROUTES_ANDROID13 条，
            // 序列化约 336 KB，安全余量充足。
            builder.addRoute(InetAddress.getByName("0.0.0.0"), 0);
            List<CidrBlock> sortedChinaCidrs = new ArrayList<>(router.getChinaCidrs());
            // 按前缀长度升序排序：prefixLen 数值越小表示覆盖范围越大（/8 > /24），
            // 升序排列后 /8 排在 /24 之前，优先 excludeRoute 覆盖最广的 CIDR，
            // 在截断时确保最重要（覆盖最多 IP）的段被排除。
            sortedChinaCidrs.sort(Comparator.comparingInt(c -> c.prefixLen));
            int excluded = 0;
            for (CidrBlock cidr : sortedChinaCidrs) {
                if (excluded >= MAX_EXCLUDE_ROUTES_ANDROID13) break;
                InetAddress addr = cidr.toInetAddress();
                if (addr != null) {
                    builder.excludeRoute(new IpPrefix(addr, cidr.prefixLen));
                    excluded++;
                }
            }
            for (long[] s : localSubnets) {
                builder.excludeRoute(new IpPrefix(longToIpv4Addr(s[0]), (int) s[1]));
            }
            if (excluded < router.getChinaCidrs().size()) {
                LogUtil.w(TAG, "CHINA_DIRECT (Android 13+): 0.0.0.0/0 + 排除 "
                        + excluded + "/" + router.getChinaCidrs().size() + " 条中国 IP（已截断）+ "
                        + localSubnets.size() + " 个本地子网");
            } else {
                LogUtil.d(TAG, "CHINA_DIRECT (Android 13+): 0.0.0.0/0 + 排除 "
                        + excluded + " 条中国 IP + "
                        + localSubnets.size() + " 个本地子网");
            }
        } else {
            // Android 12-：添加非中国 CIDR，每条再剔除本地活跃子网。
            // 非中国 CIDR 补集约 14,000+ 条。所有 Android 版本的 Binder 事务上限均为 1MB；
            // 14,000 条路由序列化后约 1.1MB，会触发 TransactionTooLargeException 导致 establish() 失败。
            // 将总路由条数限制在安全范围内（3000 条 × ~80 字节 ≈ 240KB，远低于 1MB Binder 上限）。
            // 按前缀长度升序排序（前缀越短 = 覆盖越广），优先保留覆盖最大 IP 范围的路由。
            final int MAX_ROUTES_LEGACY_ANDROID = 3000;
            List<CidrBlock> nonChina = new ArrayList<>(router.getNonChinaCidrs());
            nonChina.sort(Comparator.comparingInt(c -> c.prefixLen));
            int added = 0;
            for (CidrBlock cidr : nonChina) {
                if (added >= MAX_ROUTES_LEGACY_ANDROID) break;
                InetAddress addr = cidr.toInetAddress();
                if (addr == null) continue;
                // 将此 CIDR 剔除所有本地子网后的残余部分加入 VPN 路由
                List<long[]> parts = new ArrayList<>();
                parts.add(new long[]{cidr.startIp & 0xFFFFFFFFL, cidr.prefixLen});
                for (long[] local : localSubnets) {
                    List<long[]> next = new ArrayList<>();
                    for (long[] part : parts) next.addAll(routeMinusCidr(part, local));
                    parts = next;
                }
                for (long[] r : parts) {
                    if (added >= MAX_ROUTES_LEGACY_ANDROID) break;
                    builder.addRoute(longToIpv4Addr(r[0]), (int) r[1]);
                    added++;
                }
            }
            if (added >= MAX_ROUTES_LEGACY_ANDROID) {
                LogUtil.w(TAG, "CHINA_DIRECT (Android 12-): 已达路由上限 " + MAX_ROUTES_LEGACY_ANDROID
                        + " 条，共 " + nonChina.size() + " 条非中国路由，"
                        + (nonChina.size() - MAX_ROUTES_LEGACY_ANDROID)
                        + " 条未加入 VPN 路由表");
            }
            LogUtil.i(TAG, "CHINA_DIRECT (Android 12-): 添加 " + added + "/" + nonChina.size() + " 条非中国路由");
        }
    }

    /**
     * 将全局路由（0.0.0.0/0 排除本地子网）添加到 VPN builder，作为 CHINA_DIRECT 数据未就绪时的回退。
     */
    private void addGlobalRoutesToBuilder(VpnService.Builder builder, List<long[]> localSubnets) throws Exception {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            builder.addRoute(InetAddress.getByName("0.0.0.0"), 0);
            for (long[] s : localSubnets) {
                builder.excludeRoute(new IpPrefix(longToIpv4Addr(s[0]), (int) s[1]));
            }
        } else {
            List<long[]> vpnRoutes = localSubnets.isEmpty()
                    ? Collections.singletonList(new long[]{0L, 0L})
                    : computeGlobalRoutesExcluding(localSubnets);
            for (long[] r : vpnRoutes) {
                builder.addRoute(longToIpv4Addr(r[0]), (int) r[1]);
            }
        }
    }

    /**
     * 配置直接通过ZeroTier的IPv4路由（不使用代理）。
     *
     * <p>全局/Per-app 路由都以系统 VPN 路由表为准，避免在 TUN 收包后丢弃业务流量。
     * 本地子网（WiFi/蓝牙 PAN/USB 共享等）需要从 VPN 中排除，使局域网访问仍走真实接口：
     * <ul>
     *   <li>Android 13+：添加 0.0.0.0/0 后使用 {@link VpnService.Builder#excludeRoute(IpPrefix)} 排除本地子网</li>
     *   <li>Android 12 及以下：使用 CIDR 路由分裂生成排除本地子网后的全局路由补集</li>
     * </ul>
     */
    private void configureDirectGlobalRouting(VpnService.Builder builder, VirtualNetworkConfig virtualNetworkConfig,
                                              InetSocketAddress[] assignedAddresses,
                                              boolean isPerAppRouting) throws Exception {
        // 获取ZeroTier网络中的网关
        InetAddress zerotierGateway = null;
        
        if (virtualNetworkConfig.getRoutes().length > 0) {
            for (var routeConfig : virtualNetworkConfig.getRoutes()) {
                var via = routeConfig.getVia();
                if (via != null) {
                    zerotierGateway = via.getAddress();
                    LogUtil.d(TAG, "找到ZeroTier网关: " + zerotierGateway.getHostAddress());
                    break;
                }
            }
        }
        
        if (zerotierGateway == null && assignedAddresses.length > 0) {
            for (var addr : assignedAddresses) {
                if (addr.getAddress() instanceof Inet4Address) {
                    byte[] ipBytes = addr.getAddress().getAddress();
                    ipBytes[3] = 1;
                    zerotierGateway = InetAddress.getByAddress(ipBytes);
                    LogUtil.d(TAG, "推断的网关地址: " + zerotierGateway.getHostAddress());
                    break;
                }
            }
        }

        // 检测本机活跃的本地网络子网（蓝牙PAN bt-pan、WiFi局域网 wlan0、USB共享 usb0 等），
        // 将这些子网排除在 VPN 之外，避免本地连接被意外路由至 TUN。
        List<long[]> localSubnets = detectLocalSubnetsToExclude();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            InetAddress defaultRoute = InetAddress.getByName("0.0.0.0");
            builder.addRoute(defaultRoute, 0);
            Route vpnRoute = new Route(defaultRoute, 0);
            if (zerotierGateway != null) vpnRoute.setGateway(zerotierGateway);
            this.tunTapAdapter.addRouteAndNetwork(vpnRoute, networkId);

            for (long[] subnet : localSubnets) {
                InetAddress addr = longToIpv4Addr(subnet[0]);
                int prefix = (int) subnet[1];
                // excludeRoute is an Android 13+ API; the TIRAMISU guard above is required.
                // Older devices use the CIDR split fallback below to preserve the same behavior.
                builder.excludeRoute(new IpPrefix(addr, prefix));
            }
            if (!localSubnets.isEmpty()) {
                LogUtil.d(TAG, "Android 13+ 路由排除：添加 0.0.0.0/0，并通过 excludeRoute 排除 "
                        + localSubnets.size() + " 个本地子网");
            }
        } else {
            List<long[]> vpnRoutes = localSubnets.isEmpty()
                    ? Collections.singletonList(new long[]{0L, 0L})
                    : computeGlobalRoutesExcluding(localSubnets);
            if (!localSubnets.isEmpty()) {
                LogUtil.d(TAG, "路由分裂：排除 " + localSubnets.size() + " 个本地子网，生成 "
                        + vpnRoutes.size() + " 条VPN路由");
            }
            for (long[] r : vpnRoutes) {
                InetAddress addr = longToIpv4Addr(r[0]);
                int prefix = (int) r[1];
                builder.addRoute(addr, prefix);
                Route vpnRoute = new Route(addr, prefix);
                if (zerotierGateway != null) vpnRoute.setGateway(zerotierGateway);
                this.tunTapAdapter.addRouteAndNetwork(vpnRoute, networkId);
            }
        }
        LogUtil.d(TAG, isPerAppRouting
                ? "Per-app路由模式：添加全局路由（排除本地子网，仅指定应用生效）"
                : "全局路由模式：添加全局路由（排除本地子网），所有非本地流量通过ZeroTier");
    }
    
    /**
     * 配置直接通过ZeroTier的IPv6全局路由(不使用代理)
     */
    private void configureDirectIPv6Routing(VpnService.Builder builder, VirtualNetworkConfig virtualNetworkConfig,
                                           InetSocketAddress[] assignedAddresses) throws Exception {
        InetAddress v6DefaultRoute = InetAddress.getByName("::");
        builder.addRoute(v6DefaultRoute, 0);
        LogUtil.d(TAG, "添加IPv6全局路由 ::/0");
        
        // 创建IPv6路由
        Route ipv6Route = new Route(v6DefaultRoute, 0);
        
        // 尝试找到IPv6网关
        if (assignedAddresses.length > 0) {
            for (var addr : assignedAddresses) {
                if (addr.getAddress() instanceof Inet6Address) {
                    // 推断IPv6网关
                    ipv6Route.setGateway(addr.getAddress());
                    LogUtil.d(TAG, "IPv6推断网关: " + addr.getAddress().getHostAddress());
                    break;
                }
            }
        }
        
        this.tunTapAdapter.addRouteAndNetwork(ipv6Route, networkId);
        
        // 保护IPv6连接
        protectSocketConnection("2001:4860:4860::8888", 53);
        protectSocketConnection("2400:3200::1", 53);
        protectSocketConnection("2606:4700:4700::1111", 53);
    }

    /**
     * 检测本机所有活跃的<em>本地共享</em>网络子网，用于在全局路由模式下从 VPN 路由表中排除这些子网，
     * 避免蓝牙 PAN（bt-pan）、USB 网络共享（usb0/rndis0）、WiFi 局域网等本地连接
     * 被意外路由至 TUN 接口而无法使用。
     * <p>
     * 以下接口类型会被跳过，<em>不会</em>加入排除列表：
     * <ul>
     *   <li>ZeroTier 虚拟接口（zt*）和 TUN 接口（tun*）</li>
     *   <li>移动数据接口：rmnet*、ccmni*、wwan*、seth*、r_rmnet* 以及对应的
     *       CLAT/464XLAT 接口（v4-rmnet*）——这些是"上行"互联网提供者而非本地共享接口，
     *       将其子网排除在 VPN 路由之外会导致 4G/5G 网络无法访问</li>
     *   <li>链路本地地址（169.254.x.x）——这些是未连接接口上的自动分配地址</li>
     *   <li>前缀长度 &lt; 8 的子网——过于宽泛，可能误排除大量公网地址</li>
     * </ul>
     *
     * @return 每个子网表示为 {@code long[]{networkAddressAsUint32, prefixLen}}
     */
    private static List<long[]> detectLocalSubnetsToExclude() {
        List<long[]> subnets = new ArrayList<>();
        Set<String> processedSubnets = new HashSet<>(); // 去重：避免相同子网被多次加入
        try {
            List<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface ni : interfaces) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                String name = ni.getName();
                // 跳过 ZeroTier 虚拟接口（zt*）和 TUN 接口——它们不是本地物理连接
                if (name.startsWith("zt") || name.startsWith("tun")) continue;
                // 跳过移动数据接口：这些是"上行"互联网提供者，排除其子网会导致 4G/5G 断网
                boolean isMobileData = false;
                for (String prefix : MOBILE_DATA_IFACE_PREFIXES) {
                    if (name.startsWith(prefix)) { isMobileData = true; break; }
                }
                if (isMobileData) {
                    LogUtil.d(TAG, "跳过移动数据接口 [" + name + "]，不加入子网排除列表");
                    continue;
                }
                // 跳过 dummy 接口（某些系统上虚拟创建的占位接口）
                if (name.startsWith("dummy")) continue;
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    InetAddress addr = ia.getAddress();
                    if (!(addr instanceof Inet4Address)) continue;
                    // getNetworkPrefixLength() returns short; guard against invalid values
                    int prefix = ia.getNetworkPrefixLength();
                    // 前缀过短（< 8）意味着排除超过 1/256 的地址空间，拒绝处理
                    if (prefix < 8 || prefix > 32) continue;
                    long net = ipv4BytesToLong(addr.getAddress());
                    // 跳过链路本地地址（169.254.x.x），它们是未连接接口上的自动分配地址
                    if ((net & LINK_LOCAL_MASK) == LINK_LOCAL_PREFIX) continue;
                    // 跳过 CGN 地址（100.64.0.0/10），部分运营商使用该段，不应排除
                    if ((net & CGN_MASK) == CGN_PREFIX) {
                        LogUtil.d(TAG, "跳过 CGN 地址 [" + name + "]: " + addr.getHostAddress() + "/" + prefix);
                        continue;
                    }
                    long mask = prefix == 32 ? 0xFFFFFFFFL
                                             : (~0L << (32 - prefix)) & 0xFFFFFFFFL;
                    net &= mask;
                    // 去重
                    String key = net + "/" + prefix;
                    if (!processedSubnets.add(key)) continue;
                    subnets.add(new long[]{net, prefix});
                    LogUtil.d(TAG, "排除本地子网 [" + name + "]: " + addr.getHostAddress() + "/" + prefix);
                }
            }
        } catch (Exception e) {
            LogUtil.w(TAG, "检测本地子网时出错: " + e.getMessage());
        }
        return subnets;
    }

    /**
     * 计算覆盖 0.0.0.0/0 但排除 {@code excludedCidrs} 中所有子网的最小 CIDR 路由集合。
     * <p>采用 CIDR 路由分裂（route splitting）算法：从完整地址空间 0.0.0.0/0 出发，
     * 逐步剔除需要排除的子网，返回剩余路由列表。
     * 每个路由表示为 {@code long[]{networkAddressAsUint32, prefixLen}}。
     */
    static List<long[]> computeGlobalRoutesExcluding(List<long[]> excludedCidrs) {
        List<long[]> remaining = new ArrayList<>();
        remaining.add(new long[]{0L, 0L}); // 0.0.0.0/0
        for (long[] excl : excludedCidrs) {
            List<long[]> next = new ArrayList<>();
            for (long[] r : remaining) {
                next.addAll(routeMinusCidr(r, excl));
            }
            remaining = next;
        }
        return remaining;
    }

    /**
     * 从 {@code route} CIDR 中减去 {@code excl} CIDR，返回不与 excl 重叠的子路由列表。
     * route 和 excl 均为 {@code long[]{networkAddressAsUint32, prefixLen}}。
     */
    private static List<long[]> routeMinusCidr(long[] route, long[] excl) {
        long rNet = route[0]; int rPfx = (int) route[1];
        long eNet = excl[0]; int ePfx = (int) excl[1];
        // excl 与 route 无重叠，route 保持不变
        if (!ipv4CidrContains(rNet, rPfx, eNet)) {
            return Collections.singletonList(route);
        }
        // 完全重叠，移除 route
        if (rNet == eNet && rPfx == ePfx) {
            return Collections.emptyList();
        }
        // excl 在 route 内部：将 route 一分为二，递归处理包含 excl 的那一半
        int newPfx = rPfx + 1;
        long bitPos = 1L << (31 - rPfx);
        long lower = rNet;
        long upper = rNet | bitPos;
        List<long[]> result = new ArrayList<>();
        if (ipv4CidrContains(lower, newPfx, eNet)) {
            // excl 在下半部分
            result.addAll(routeMinusCidr(new long[]{lower, newPfx}, excl));
            result.add(new long[]{upper, newPfx});
        } else {
            // excl 在上半部分
            result.add(new long[]{lower, newPfx});
            result.addAll(routeMinusCidr(new long[]{upper, newPfx}, excl));
        }
        return result;
    }

    /**
     * 判断 IPv4 地址 {@code ipLong}（uint32）是否属于 {@code network/prefix} 所表示的子网。
     */
    private static boolean ipv4CidrContains(long network, int prefix, long ipLong) {
        if (prefix == 0) return true;
        long mask = (~0L << (32 - prefix)) & 0xFFFFFFFFL;
        return (network & mask) == (ipLong & mask);
    }

    /**
     * 将 IPv4 地址的 uint32 表示转换为 {@link InetAddress}。
     */
    private static InetAddress longToIpv4Addr(long ipLong) throws UnknownHostException {
        return InetAddress.getByAddress(new byte[]{
                (byte) ((ipLong >> 24) & 0xFF),
                (byte) ((ipLong >> 16) & 0xFF),
                (byte) ((ipLong >> 8) & 0xFF),
                (byte) (ipLong & 0xFF)
        });
    }

    /**
     * 将 IPv4 地址的 4 字节数组转换为 uint32 的 long 表示。
     */
    static long ipv4BytesToLong(byte[] bytes) {
        return ((bytes[0] & 0xFFL) << 24) | ((bytes[1] & 0xFFL) << 16)
             | ((bytes[2] & 0xFFL) << 8)  |  (bytes[3] & 0xFFL);
    }

    /**
     * 入轨事件
     */
    @Subscribe
    public void onOrbitMoonEvent(OrbitMoonEvent event) {
        if (this.node == null) {
            LogUtil.e(TAG, "Can't orbit network if ZeroTier isn't running");
            return;
        }
        // 入轨
        for (MoonOrbit moonOrbit : event.getMoonOrbits()) {
            LogUtil.i(TAG, "Orbiting moon: " + Long.toHexString(moonOrbit.getMoonWorldId()));
            this.orbitNetwork(moonOrbit.getMoonWorldId(), moonOrbit.getMoonSeed());
        }
    }

    /**
     * 当前网络入轨 Moon
     *
     * @param moonWorldId Moon 节点地址
     * @param moonSeed    Moon 种子节点地址
     */
    public void orbitNetwork(Long moonWorldId, Long moonSeed) {
        if (this.node == null) {
            LogUtil.e(TAG, "Can't orbit network if ZeroTier isn't running");
            return;
        }
        // 入轨
        ResultCode result = this.node.orbit(moonWorldId, moonSeed);
        if (result != ResultCode.RESULT_OK) {
            LogUtil.e(TAG, "Failed to orbit " + Long.toHexString(moonWorldId));
            this.eventBus.post(new ErrorEvent(result));
        }
    }

    /**
     * 检查全局流量 VPN 功能是否正常工作
     */
    public boolean isGlobalTrafficVpnWorking() {
        // 检查 VPN 是否已建立
        if (this.vpnSocket == null) {
            LogUtil.e(TAG, "全局流量VPN未工作: VPN套接字为空");
            return false;
        }

        // 检查 TUN TAP 适配器是否正在运行
        if (this.tunTapAdapter == null || !this.tunTapAdapter.isRunning()) {
            LogUtil.e(TAG, "全局流量VPN未工作: TUN TAP适配器未运行");
            return false;
        }

        // 检查是否有全局路由
        var virtualNetworkConfig = getVirtualNetworkConfig(this.networkId);
        if (virtualNetworkConfig == null) {
            LogUtil.e(TAG, "全局流量VPN未工作: 虚拟网络配置为空");
            return false;
        }
        var routes = virtualNetworkConfig.getRoutes();
        try {
            InetAddress v4Loopback = InetAddress.getByName("0.0.0.0");
            InetAddress v6Loopback = InetAddress.getByName("::");
            
            for (var route : routes) {
                var target = route.getTarget();
                if (target.getAddress().equals(v4Loopback) || 
                    target.getAddress().equals(v6Loopback)) {
                    LogUtil.d(TAG, "全局流量VPN正在工作: 发现全局路由 " + target.getAddress());
                    return true;
                }
            }
        } catch (UnknownHostException e) {
            LogUtil.e(TAG, "解析地址时出错: " + e.getMessage(), e);
            return false;
        }

        LogUtil.e(TAG, "全局流量VPN未工作: 未发现全局路由");
        return false;
    }



    public class ZeroTierBinder extends Binder {
        public ZeroTierBinder() {
        }

        public ZeroTierOneService getService() {
            return ZeroTierOneService.this;
        }
    }
}
