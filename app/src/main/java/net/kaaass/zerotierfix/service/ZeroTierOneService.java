package net.kaaass.zerotierfix.service;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.VpnService;
import android.os.Binder;
import android.os.Build;
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
import net.kaaass.zerotierfix.model.AssignedAddress;
import net.kaaass.zerotierfix.model.MoonOrbit;
import net.kaaass.zerotierfix.model.Network;
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
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
                // 在后台任务截止期前循环进行后台任务
                var taskDeadline = this.nextBackgroundTaskDeadline;
                long currentTime = System.currentTimeMillis();
                int cmp = Long.compare(taskDeadline, currentTime);
                if (cmp <= 0) {
                    long[] newDeadline = {0};
                    var taskResult = this.node.processBackgroundTasks(currentTime, newDeadline);
                    synchronized (this) {
                        this.nextBackgroundTaskDeadline = newDeadline[0];
                    }
                    if (taskResult != ResultCode.RESULT_OK) {
                        LogUtil.e(TAG, "Error on processBackgroundTasks: " + taskResult.toString());
                        shutdown();
                    }
                }
                Thread.sleep(cmp > 0 ? taskDeadline - currentTime : 100);
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
        LogUtil.i(TAG, "Virtual Network Config Operation: " + op);
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
                    LogUtil.i(TAG, "Network Config Update!");
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
        if (this.vpnSocket != null) {
            try {
                this.vpnSocket.close();
                this.in.close();
                this.out.close();
            } catch (Exception e) {
                LogUtil.e(TAG, "Error closing VPN socket: " + e, e);
            }
            this.vpnSocket = null;
            this.in = null;
            this.out = null;
        }

        // 配置 VPN
        LogUtil.i(TAG, "Configuring VpnService.Builder");
        var builder = new VpnService.Builder();
        var assignedAddresses = virtualNetworkConfig.getAssignedAddresses();
        LogUtil.i(TAG, "address length: " + assignedAddresses.length);
        boolean isRouteViaZeroTier = networkConfig.getRouteViaZeroTier();

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

        // 如果启用了全局路由，添加默认路由(0.0.0.0/0 和 ::/0)
        if (isRouteViaZeroTier) {
            try {
                // 使用ZeroTier全局路由模式
                LogUtil.i(TAG, "使用ZeroTier全局路由模式");
                configureDirectGlobalRouting(builder, virtualNetworkConfig, assignedAddresses);
                
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
                
                // 添加IPv6全局路由 (::/0)，如果IPv6未禁用
                if (!this.disableIPv6) {
                    configureDirectIPv6Routing(builder, virtualNetworkConfig, assignedAddresses);
                }
                
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
        LogUtil.i(TAG, "MTU from Network Config: " + mtu);
        if (mtu == 0) {
            mtu = 2800;
        }
        LogUtil.i(TAG, "MTU Set: " + mtu);
        builder.setMtu(mtu);

        builder.setSession(Constants.VPN_SESSION_NAME);

        // 建立 VPN 连接
        this.vpnSocket = builder.establish();
        if (this.vpnSocket == null) {
            this.eventBus.post(new VPNErrorEvent(getString(R.string.toast_vpn_application_not_prepared)));
            return false;
        }
        this.in = new FileInputStream(this.vpnSocket.getFileDescriptor());
        this.out = new FileOutputStream(this.vpnSocket.getFileDescriptor());
        this.tunTapAdapter.setVpnSocket(this.vpnSocket);
        this.tunTapAdapter.setFileStreams(this.in, this.out);
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
     * 配置允许/不允许的应用
     */
    private void configureAllowedDisallowedApps(VpnService.Builder builder, boolean isRouteViaZeroTier) {
        if (!isRouteViaZeroTier && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            // 设置部分 APP 不经过 VPN
            // for (var app : DISALLOWED_APPS) {
            //     try {
            //         builder.addDisallowedApplication(app);
            //         LogUtil.d(TAG, "添加排除应用: " + app);
            //     } catch (Exception e3) {
            //         LogUtil.e(TAG, "无法排除应用 " + app, e3);
            //     }
            // }
            
            // 排除更多常见需要直连的应用
            // String[] commonDisallowedApps = {
            //     "com.android.vending", // Google Play商店
            //     "com.google.android.gms", // Google Play服务
            //     "com.google.android.gsf", // Google服务框架
            //     "com.android.providers.downloads", // 下载管理器
            // };
            
            // for (String app : commonDisallowedApps) {
            //     try {
            //         builder.addDisallowedApplication(app);
            //         LogUtil.d(TAG, "添加排除应用: " + app);
            //     } catch (Exception e) {
            //         // 可能应用不存在，忽略错误
            //     }
            // }
        }
    }

    private void addDNSServers(VpnService.Builder builder, Network network) {
        var networkConfig = network.getNetworkConfig();
        var virtualNetworkConfig = getVirtualNetworkConfig(network.getNetworkId());
        var dnsMode = DNSMode.fromInt(networkConfig.getDnsMode());
        boolean isRouteViaZeroTier = networkConfig.getRouteViaZeroTier();

        switch (dnsMode) {
            case NETWORK_DNS:
                if (virtualNetworkConfig.getDns() == null) {
                    // 若无网络DNS，但在全局路由模式下，添加可信DNS
                    if (isRouteViaZeroTier) {
                        LogUtil.i(TAG, "全局路由模式：添加可信DNS服务器");
                        addTrustedDNSServers(builder);
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
                
                // 在全局路由模式下，额外添加可信DNS作为备用
                if (isRouteViaZeroTier) {
                    LogUtil.i(TAG, "全局路由模式：添加可信DNS服务器");
                    addTrustedDNSServers(builder);
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
                break;
                
            default:
                // 默认情况下，全局路由模式添加可信DNS
                if (isRouteViaZeroTier) {
                    LogUtil.i(TAG, "默认DNS模式：添加可信DNS服务器");
                    addTrustedDNSServers(builder);
                }
                break;
        }
    }
    
    /**
     * 添加可信的DNS服务器，避免DNS污染
     * @param builder VPN构建器
     */
    private void addTrustedDNSServers(VpnService.Builder builder) {
        try {
            // 添加Cloudflare的DNS
            builder.addDnsServer(InetAddress.getByName("1.1.1.1"));
            builder.addDnsServer(InetAddress.getByName("1.0.0.1"));
            
            // 添加Google的DNS
            builder.addDnsServer(InetAddress.getByName("8.8.8.8"));
            builder.addDnsServer(InetAddress.getByName("8.8.4.4"));
            
            LogUtil.i(TAG, "已添加Cloudflare和Google可信DNS服务器");
        } catch (Exception e) {
            LogUtil.e(TAG, "添加可信DNS服务器失败: " + e.getMessage(), e);
        }
    }

    /**
     * 配置使用代理服务器的IPv4路由
     * @param builder VPN构建器
     */
    // 代理功能已移除
    private void configureProxyRouting(VpnService.Builder builder) throws Exception {
        // 此方法内容已移除，保留空方法签名以避免编译错误
        LogUtil.i(TAG, "代理功能已移除");
    }

    /**
     * 配置使用代理服务器的IPv6路由 - 已移除，保留方法签名避免编译错误
     * @param builder VPN构建器
     */
    private void configureProxyIPv6Routing(VpnService.Builder builder) throws Exception {
        // 此方法内容已移除，保留空方法签名以避免编译错误
        LogUtil.i(TAG, "IPv6代理功能已移除");
    }
    
    /**
     * 配置直接通过ZeroTier的IPv4全局路由(不使用代理)
     */
    private void configureDirectGlobalRouting(VpnService.Builder builder, VirtualNetworkConfig virtualNetworkConfig, 
                                             InetSocketAddress[] assignedAddresses) throws Exception {
        // 获取ZeroTier网络中的网关
        InetAddress zerotierGateway = null;
        
        // 1. 尝试从路由配置中找到网关
        if (virtualNetworkConfig.getRoutes().length > 0) {
            for (var routeConfig : virtualNetworkConfig.getRoutes()) {
                var via = routeConfig.getVia();
                if (via != null) {
                    zerotierGateway = via.getAddress();
                    LogUtil.i(TAG, "找到ZeroTier网关: " + zerotierGateway.getHostAddress());
                    break;
                }
            }
        }
        
        // 2. 如果没有明确的网关，尝试使用分配给本设备的第一个IP作为默认网关
        if (zerotierGateway == null && assignedAddresses.length > 0) {
            for (var addr : assignedAddresses) {
                if (addr.getAddress() instanceof Inet4Address) {
                    // 尝试从IPv4地址推断网关 (通常是网络的第一个地址)
                    byte[] ipBytes = addr.getAddress().getAddress();
                    ipBytes[3] = 1; // 将最后一位改为1，通常是网关
                    zerotierGateway = InetAddress.getByAddress(ipBytes);
                    LogUtil.i(TAG, "推断的网关地址: " + zerotierGateway.getHostAddress());
                    break;
                }
            }
        }
        
        // 添加IPv4全局路由 (0.0.0.0/0)
        InetAddress v4DefaultRoute = InetAddress.getByName("0.0.0.0");
        builder.addRoute(v4DefaultRoute, 0);
        LogUtil.i(TAG, "添加IPv4全局路由 0.0.0.0/0" + (zerotierGateway != null ? 
                " 网关: " + zerotierGateway.getHostAddress() : " 无指定网关"));
        
        // 添加路由到TunTap，如果有网关则设置网关
        Route defaultRoute = new Route(v4DefaultRoute, 0);
        if (zerotierGateway != null) {
            defaultRoute.setGateway(zerotierGateway);
        }
        this.tunTapAdapter.addRouteAndNetwork(defaultRoute, networkId);
        LogUtil.i(TAG, "全局路由模式：直接通过ZeroTier网络");
    }
    
    /**
     * 配置直接通过ZeroTier的IPv6全局路由(不使用代理)
     */
    private void configureDirectIPv6Routing(VpnService.Builder builder, VirtualNetworkConfig virtualNetworkConfig,
                                           InetSocketAddress[] assignedAddresses) throws Exception {
        InetAddress v6DefaultRoute = InetAddress.getByName("::");
        builder.addRoute(v6DefaultRoute, 0);
        LogUtil.i(TAG, "添加IPv6全局路由 ::/0");
        
        // 创建IPv6路由
        Route ipv6Route = new Route(v6DefaultRoute, 0);
        
        // 尝试找到IPv6网关
        if (assignedAddresses.length > 0) {
            for (var addr : assignedAddresses) {
                if (addr.getAddress() instanceof Inet6Address) {
                    // 推断IPv6网关
                    ipv6Route.setGateway(addr.getAddress());
                    LogUtil.i(TAG, "IPv6推断网关: " + addr.getAddress().getHostAddress());
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
