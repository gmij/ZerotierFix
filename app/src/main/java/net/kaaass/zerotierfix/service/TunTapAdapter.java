package net.kaaass.zerotierfix.service;

import android.os.ParcelFileDescriptor;
import android.support.v4.media.session.PlaybackStateCompat;

import com.zerotier.sdk.Node;
import com.zerotier.sdk.ResultCode;
import com.zerotier.sdk.VirtualNetworkConfig;
import com.zerotier.sdk.VirtualNetworkFrameListener;
import com.zerotier.sdk.util.StringUtils;

import net.kaaass.zerotierfix.util.DebugLog;
import net.kaaass.zerotierfix.util.IPPacketUtils;
import net.kaaass.zerotierfix.util.InetAddressUtils;
import net.kaaass.zerotierfix.util.LogUtil;
import net.kaaass.zerotierfix.util.smartroute.DnsPacketParser;
import net.kaaass.zerotierfix.util.smartroute.SmartRoutingManager;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Collections;
import java.util.HashMap;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

// TODO: clear up
public class TunTapAdapter implements VirtualNetworkFrameListener {
    public static final String TAG = "TunTapAdapter";
    private static final int ARP_PACKET = 2054;
    private static final int IPV4_PACKET = 2048;
    private static final int IPV6_PACKET = 34525;
    private static final int TCP_PROTOCOL = 6;
    private static final int UDP_PROTOCOL = 17;

    private final HashMap<Route, Long> routeMap = new HashMap<>();
    private final long networkId;
    private final ZeroTierOneService ztService;
    private ARPTable arpTable = new ARPTable();
    private FileInputStream in;
    private NDPTable ndpTable = new NDPTable();
    private Node node;
    private FileOutputStream out;
    private Thread receiveThread;
    private ParcelFileDescriptor vpnSocket;
    /** 智能路由管理器（可为 null，表示功能未启用） */
    private SmartRoutingManager smartRoutingManager;
    /** 当前网络的智能路由模式（0=关闭，1=国内直连，2=GFW列表，3=组合模式） */
    private int smartRoutingMode = SmartRoutingManager.MODE_OFF;
    /**
     * 是否启用了 per-app 路由模式。
     * 为 true 时，TUN 中只有指定应用的流量，无需再做智能路由过滤，
     * 所有进入 TUN 的包都应无条件转发给 ZeroTier。
     */
    private boolean perAppRoutingActive = false;

    /**
     * 已记录 [CONN] 日志的连接端点集合（destIP:port），用于每条连接只记录一次日志。
     * TunTapAdapter 每次 VPN 连接都是新实例，无需在 stop 时清理。
     */
    private final Set<String> connLoggedSet =
            Collections.newSetFromMap(new ConcurrentHashMap<>());
    private static final int MAX_CONN_LOG_ENTRIES = 2000;

    public TunTapAdapter(ZeroTierOneService zeroTierOneService, long j) {
        this.ztService = zeroTierOneService;
        this.networkId = j;
    }

    /**
     * 判断是否为TCP数据包
     */
    private boolean isTcpPacket(byte[] data) {
        if (data.length < 20) { // 至少需要一个IP头部
            return false;
        }

        // 获取IP版本
        int version = (data[0] >> 4) & 0xF;

        if (version == 4) { // IPv4
            // 协议字段在第9字节
            return data[9] == TCP_PROTOCOL;
        } else if (version == 6) { // IPv6
            // IPv6的下一个头部字段在第6字节
            return data[6] == TCP_PROTOCOL;
        }

        return false;
    }

    public static long multicastAddressToMAC(InetAddress inetAddress) {
        if (inetAddress instanceof Inet4Address) {
            byte[] address = inetAddress.getAddress();
            return ByteBuffer.wrap(new byte[]{0, 0, 1, 0, 94, (byte) (address[1] & Byte.MAX_VALUE), address[2], address[3]}).getLong();
        } else if (!(inetAddress instanceof Inet6Address)) {
            return 0;
        } else {
            byte[] address2 = inetAddress.getAddress();
            return ByteBuffer.wrap(new byte[]{0, 0, 51, 51, address2[12], address2[13], address2[14], address2[15]}).getLong();
        }
    }

    private void addMulticastRoutes() {
    }

    public void setNode(Node node) {
        this.node = node;
        try {
            var multicastAddress = InetAddress.getByName("224.224.224.224");
            var result = node
                    .multicastSubscribe(this.networkId, multicastAddressToMAC(multicastAddress));
            if (result != ResultCode.RESULT_OK) {
                LogUtil.e(TAG, "Error when calling multicastSubscribe: " + result);
            }
        } catch (UnknownHostException e) {
            LogUtil.e(TAG, e.toString(), e);
        }
    }

    public void setVpnSocket(ParcelFileDescriptor vpnSocket) {
        this.vpnSocket = vpnSocket;
    }

    public void setFileStreams(FileInputStream fileInputStream, FileOutputStream fileOutputStream) {
        this.in = fileInputStream;
        this.out = fileOutputStream;
    }

    /**
     * 配置智能路由
     *
     * @param manager          智能路由管理器（null 表示禁用）
     * @param mode             路由模式，见 {@link SmartRoutingManager#MODE_OFF} 等
     * @param perAppRouting    是否为 per-app 路由模式（指定应用全部走 ZT，无需包过滤）
     */
    public void setSmartRouting(SmartRoutingManager manager, int mode, boolean perAppRouting) {
        this.smartRoutingManager = manager;
        this.smartRoutingMode = mode;
        this.perAppRoutingActive = perAppRouting;
    }

    /**
     * 发出 [CONN] 业务日志（每个 destIP:dstPort 仅记录一次）。
     *
     * @param packetData 完整 IPv4 数据包字节
     * @param origDestIP 路由替换前的原始目的 IP
     * @param sourceIP   源 IP
     */
    private void emitConnLog(byte[] packetData, InetAddress origDestIP, InetAddress sourceIP) {
        if (connLoggedSet.size() >= MAX_CONN_LOG_ENTRIES) return;

        int protocol = packetData[9] & 0xFF; // TCP=6, UDP=17
        if (protocol != 6 && protocol != 17) return; // 只记录 TCP/UDP

        int ipHdrLen = (packetData[0] & 0x0F) * 4;
        if (packetData.length < ipHdrLen + 4) return;
        int srcPort = ((packetData[ipHdrLen]     & 0xFF) << 8) | (packetData[ipHdrLen + 1] & 0xFF);
        int dstPort = ((packetData[ipHdrLen + 2] & 0xFF) << 8) | (packetData[ipHdrLen + 3] & 0xFF);

        String key = origDestIP.getHostAddress() + ":" + dstPort;
        if (!connLoggedSet.add(key)) return; // 已记录过此端点

        String destStr = origDestIP.getHostAddress();
        String hostname = (smartRoutingManager != null)
                ? smartRoutingManager.getDomainForIp(origDestIP)
                : null;

        String cacheInfo = (hostname != null)
                ? "Cache=hit:" + hostname + "; "
                : "Cache=miss; ";
        String displayHost = (hostname != null) ? hostname : destStr;
        String connStr = (sourceIP != null ? sourceIP.getHostAddress() : "?") + ":" + srcPort
                + "-" + destStr + ":" + dstPort;

        String msg = displayHost + ":" + dstPort + "  ZT  (DNS; " + cacheInfo
                + "DestIP=" + destStr + "; Conn=" + connStr + ")";
        LogUtil.i(LogUtil.CONN_TAG, msg);
    }

    public void addRouteAndNetwork(Route route, long networkId) {
        synchronized (this.routeMap) {
            this.routeMap.put(route, networkId);
        }
    }

    public void clearRouteMap() {
        synchronized (this.routeMap) {
            this.routeMap.clear();
            addMulticastRoutes();
        }
    }

    private boolean isIPv4Multicast(InetAddress inetAddress) {
        return (inetAddress.getAddress()[0] & 0xF0) == 224;
    }

    private boolean isIPv6Multicast(InetAddress inetAddress) {
        return (inetAddress.getAddress()[0] & 0xFF) == 0xFF;
    }

    public void startThreads() {
        this.receiveThread = new Thread("Tunnel Receive Thread") {

            @Override
            public void run() {
                // 创建 ARP、NDP 表
                if (TunTapAdapter.this.ndpTable == null) {
                    TunTapAdapter.this.ndpTable = new NDPTable();
                }
                if (TunTapAdapter.this.arpTable == null) {
                    TunTapAdapter.this.arpTable = new ARPTable();
                }
                try {
                    LogUtil.d(TunTapAdapter.TAG, "TUN Receive Thread Started");
                    var buffer = ByteBuffer.allocate(32767);
                    buffer.order(ByteOrder.LITTLE_ENDIAN);
                    while (!isInterrupted()) {
                        try {
                            boolean noDataBeenRead = true;
                            int readCount = TunTapAdapter.this.in.read(buffer.array());
                            if (readCount > 0) {
                                DebugLog.d(TunTapAdapter.TAG, "Sending packet to ZeroTier. " + readCount + " bytes.");
                                var readData = new byte[readCount];
                                System.arraycopy(buffer.array(), 0, readData, 0, readCount);
                                byte iPVersion = IPPacketUtils.getIPVersion(readData);
                                if (iPVersion == 4) {
                                    TunTapAdapter.this.handleIPv4Packet(readData);
                                } else if (iPVersion == 6) {
                                    TunTapAdapter.this.handleIPv6Packet(readData);
                                } else {
                                    LogUtil.e(TunTapAdapter.TAG, "Unknown IP version");
                                }
                                buffer.clear();
                                noDataBeenRead = false;
                            }
                            if (noDataBeenRead) {
                                Thread.sleep(10);
                            }
                        } catch (IOException e) {
                            LogUtil.e(TunTapAdapter.TAG, "Error in TUN Receive: " + e.getMessage(), e);
                        }
                    }
                } catch (InterruptedException ignored) {
                }
                LogUtil.d(TunTapAdapter.TAG, "TUN Receive Thread ended");
                // 关闭 ARP、NDP 表
                TunTapAdapter.this.ndpTable.stop();
                TunTapAdapter.this.ndpTable = null;
                TunTapAdapter.this.arpTable.stop();
                TunTapAdapter.this.arpTable = null;
            }
        };
        this.receiveThread.start();
    }

    private void handleIPv4Packet(byte[] packetData) {
        boolean isMulticast;
        long destMac;
        var destIP = IPPacketUtils.getDestIP(packetData);
        var sourceIP = IPPacketUtils.getSourceIP(packetData);
        var virtualNetworkConfig = this.ztService.getVirtualNetworkConfig(this.networkId);

        // 添加详细日志：记录数据包源目的地址
        LogUtil.d(TAG, "处理IPv4数据包: 源IP=" + sourceIP + ", 目的IP=" + destIP + ", 数据包大小=" + packetData.length);

        if (virtualNetworkConfig == null) {
            LogUtil.e(TAG, "TunTapAdapter has no network config yet");
            return;
        } else if (destIP == null) {
            LogUtil.e(TAG, "destAddress is null");
            return;
        } else if (sourceIP == null) {
            LogUtil.e(TAG, "sourceAddress is null");
            return;
        }

        // 代理功能已移除

        // ── 智能路由过滤（per-app 模式下跳过：TUN 中只有指定应用的流量，无条件走 ZT）──
        if (!perAppRoutingActive && !isIPv4Multicast(destIP) && smartRoutingManager != null) {

            // ── GFW 列表模式 ──
            // 路由表中没有全局路由，仅有已知 GFW IP /32 显式路由会进入 TUN。
            // 额外检查：防止极少数情况下非 GFW 包误入（集合为空时不过滤，避免初始阶段黑洞）。
            if (smartRoutingMode == SmartRoutingManager.MODE_GFW_LIST) {
                Set<InetAddress> gfwIps = smartRoutingManager.getGfwIpSet();
                if (!gfwIps.isEmpty() && !gfwIps.contains(destIP)) {
                    LogUtil.d(TAG, "智能路由(GFW): 目的IP=" + destIP + " 不在GFW列表，跳过ZT转发");
                    return;
                }
            }

            // ── 组合模式 ──
            // 路由表中包含所有非中国 CIDR，GFW 域名的中国 CDN IP 作为 /32 显式路由。
            // 规则：中国IP 且 不在 GFW 集合 → 丢弃（不应出现在路由表中，但作为安全网）
            //       中国IP 且 在 GFW 集合  → 允许（GFW 域名的中国 CDN，仍需走 ZT）
            //       非中国IP               → 允许（非中国 CIDR 路由正常命中）
            if (smartRoutingMode == SmartRoutingManager.MODE_COMBINED) {
                if (smartRoutingManager.isChineseIp(destIP)) {
                    Set<InetAddress> gfwIps = smartRoutingManager.getGfwIpSet();
                    if (!gfwIps.contains(destIP)) {
                        // 中国IP 且 非GFW域名 → 不应进入 ZT，丢弃（物理网络直连兜底）
                        LogUtil.d(TAG, "智能路由(组合): 目的IP=" + destIP + " 是非GFW中国IP，跳过ZT转发");
                        return;
                    }
                    // 中国IP 但属于 GFW 域名（CDN 情况）→ 继续走 ZT
                    LogUtil.d(TAG, "智能路由(组合): 目的IP=" + destIP + " 是GFW中国CDN IP，走ZT转发");
                }
            }
        }

        // ── [CONN] 业务日志：非多播 unicast 包通过路由过滤，即将转发至 ZT ──
        if (!isIPv4Multicast(destIP)) {
            emitConnLog(packetData, destIP, sourceIP);
        }

        if (isIPv4Multicast(destIP)) {
            var result = this.node.multicastSubscribe(this.networkId, multicastAddressToMAC(destIP));
            if (result != ResultCode.RESULT_OK) {
                LogUtil.e(TAG, "Error when calling multicastSubscribe: " + result);
            }
            isMulticast = true;
            LogUtil.d(TAG, "IPv4多播数据包: 目的IP=" + destIP);
        } else {
            isMulticast = false;
        }
        var route = routeForDestination(destIP);
        // 修复：VirtualNetworkRoute没有getGateway方法，但Route类有
        InetAddress gateway = null;
        if (route != null) {
            gateway = route.getGateway();
        }

        // 添加详细日志：记录路由决策过程
        LogUtil.d(TAG, "路由决策: 目的IP=" + destIP + ", 选择路由=" + (route != null ? route.toString() : "无") 
              + ", 网关=" + (gateway != null ? gateway.toString() : "无"));

        // 查找当前节点的 v4 地址
        InetSocketAddress[] ztAddresses = virtualNetworkConfig.getAssignedAddresses();
        InetAddress localV4Address = null;
        int cidr = 0;

        int addressCount = ztAddresses.length;
        for (int i = 0; i < addressCount; i++) {
            InetSocketAddress address = ztAddresses[i];
            if (address.getAddress() instanceof Inet4Address) {
                localV4Address = address.getAddress();
                cidr = address.getPort();
                break;
            }
        }

        var destRoute = InetAddressUtils.addressToRouteNo0Route(destIP, cidr);
        var sourceRoute = InetAddressUtils.addressToRouteNo0Route(sourceIP, cidr);
        if (gateway != null && !Objects.equals(destRoute, sourceRoute)) {
            LogUtil.d(TAG, "使用网关: 原始目的IP=" + destIP + " 修改为网关IP=" + gateway);
            destIP = gateway;
        }
        if (localV4Address == null) {
            LogUtil.e(TAG, "Couldn't determine local address");
            return;
        }

        // 添加详细日志：记录本地地址信息
        LogUtil.d(TAG, "本地IPv4地址: " + localV4Address + "/" + cidr);

        long localMac = virtualNetworkConfig.getMac();
        long[] nextDeadline = new long[1];
        if (isMulticast || this.arpTable.hasMacForAddress(destIP)) {
            // 已确定目标 MAC，直接发送
            if (isIPv4Multicast(destIP)) {
                destMac = this.arpTable.getMacForAddress(destIP);
            } else {
                destMac = this.arpTable.getMacForAddress(destIP);
            }
            
            // 添加详细日志：记录MAC地址和目的地
            LogUtil.d(TAG, "发送IPv4数据包: 本地MAC=" + StringUtils.macAddressToString(localMac) + 
                  ", 目标MAC=" + StringUtils.macAddressToString(destMac) + 
                  ", 目的IP=" + destIP);
                  
            var result = this.node.processVirtualNetworkFrame(System.currentTimeMillis(), this.networkId, localMac, destMac, IPV4_PACKET, 0, packetData, nextDeadline);
            if (result != ResultCode.RESULT_OK) {
                LogUtil.e(TAG, "Error calling processVirtualNetworkFrame: " + result.toString());
                return;
            }
            LogUtil.d(TAG, "数据包已发送至ZeroTier: 目的IP=" + destIP);
            this.ztService.setNextBackgroundTaskDeadline(nextDeadline[0]);
        } else {
            // 目标 MAC 未知，进行 ARP 查询
            LogUtil.d(TAG, "Unknown dest MAC address.  Need to look it up. " + destIP);
            destMac = InetAddressUtils.BROADCAST_MAC_ADDRESS;
            packetData = this.arpTable.getRequestPacket(localMac, localV4Address, destIP);
            var result = this.node.processVirtualNetworkFrame(System.currentTimeMillis(), this.networkId, localMac, destMac, ARP_PACKET, 0, packetData, nextDeadline);
            if (result != ResultCode.RESULT_OK) {
                LogUtil.e(TAG, "Error sending ARP packet: " + result.toString());
                return;
            }
            LogUtil.d(TAG, "ARP Request Sent!");
            this.ztService.setNextBackgroundTaskDeadline(nextDeadline[0]);
        }
    }

    private void handleIPv6Packet(byte[] packetData) {
        var destIP = IPPacketUtils.getDestIP(packetData);
        var sourceIP = IPPacketUtils.getSourceIP(packetData);
        var virtualNetworkConfig = this.ztService.getVirtualNetworkConfig(this.networkId);

        // 添加详细日志：记录IPv6数据包源目的地址
        LogUtil.d(TAG, "处理IPv6数据包: 源IP=" + sourceIP + ", 目的IP=" + destIP + ", 数据包大小=" + packetData.length);

        if (virtualNetworkConfig == null) {
            LogUtil.e(TAG, "TunTapAdapter has no network config yet");
            return;
        } else if (destIP == null) {
            LogUtil.e(TAG, "destAddress is null");
            return;
        } else if (sourceIP == null) {
            LogUtil.e(TAG, "sourceAddress is null");
            return;
        }

        // 代理功能已移除

        if (this.isIPv6Multicast(destIP)) {
            var result = this.node.multicastSubscribe(this.networkId, multicastAddressToMAC(destIP));
            if (result != ResultCode.RESULT_OK) {
                LogUtil.e(TAG, "Error when calling multicastSubscribe: " + result);
            }
            LogUtil.d(TAG, "IPv6多播数据包: 目的IP=" + destIP);
        }
        var route = routeForDestination(destIP);
        var gateway = route != null ? route.getGateway() : null;

        // 添加详细日志：记录IPv6路由决策过程
        LogUtil.d(TAG, "IPv6路由决策: 目的IP=" + destIP + ", 选择路由=" + (route != null ? route.toString() : "无")
                + ", 网关=" + (gateway != null ? gateway.toString() : "无"));

        // 查找当前节点的 v6 地址
        InetSocketAddress[] ztAddresses = virtualNetworkConfig.getAssignedAddresses();
        InetAddress localV6Address = null;
        int cidr = 0;

        int addressCount = ztAddresses.length;
        for (int i = 0; i < addressCount; i++) {
            InetSocketAddress address = ztAddresses[i];
            if (address.getAddress() instanceof Inet6Address) {
                localV6Address = address.getAddress();
                cidr = address.getPort();
                break;
            }
        }

        var destRoute = InetAddressUtils.addressToRouteNo0Route(destIP, cidr);
        var sourceRoute = InetAddressUtils.addressToRouteNo0Route(sourceIP, cidr);
        if (gateway != null && !Objects.equals(destRoute, sourceRoute)) {
            LogUtil.d(TAG, "使用IPv6网关: 原始目的IP=" + destIP + " 修改为网关IP=" + gateway);
            destIP = gateway;
        }
        if (localV6Address == null) {
            LogUtil.e(TAG, "Couldn't determine local address");
            return;
        }

        // 添加详细日志：记录本地IPv6地址信息
        LogUtil.d(TAG, "本地IPv6地址: " + localV6Address + "/" + cidr);

        long localMac = virtualNetworkConfig.getMac();
        long[] nextDeadline = new long[1];

        // 确定目标 MAC 地址
        long destMac;
        boolean sendNSPacket = false;
        if (this.isNeighborSolicitation(packetData)) {
            // 收到本地 NS 报文，根据 NDP 表记录确定是否广播查询
            if (this.ndpTable.hasMacForAddress(destIP)) {
                destMac = this.ndpTable.getMacForAddress(destIP);
                LogUtil.d(TAG, "NS包: 目的IP=" + destIP + "的MAC已知=" + StringUtils.macAddressToString(destMac));
            } else {
                destMac = InetAddressUtils.ipv6ToMulticastAddress(destIP);
                LogUtil.d(TAG, "NS包: 目的IP=" + destIP + "的MAC未知, 使用多播地址=" + StringUtils.macAddressToString(destMac));
            }
        } else if (this.isIPv6Multicast(destIP)) {
            // 多播报文
            destMac = multicastAddressToMAC(destIP);
            LogUtil.d(TAG, "IPv6多播: 目的IP=" + destIP + ", 多播MAC=" + StringUtils.macAddressToString(destMac));
        } else if (this.isNeighborAdvertisement(packetData)) {
            // 收到本地 NA 报文
            if (this.ndpTable.hasMacForAddress(destIP)) {
                destMac = this.ndpTable.getMacForAddress(destIP);
                LogUtil.d(TAG, "NA包: 目的IP=" + destIP + "的MAC已知=" + StringUtils.macAddressToString(destMac));
            } else {
                // 目标 MAC 未知，不发送数据包
                destMac = 0L;
                LogUtil.d(TAG, "NA包: 目的IP=" + destIP + "的MAC未知, 不发送数据包");
            }
            sendNSPacket = true;
        } else {
            // 收到普通数据包，根据 NDP 表记录确定是否发送 NS 请求
            if (this.ndpTable.hasMacForAddress(destIP)) {
                // 目标地址 MAC 已知
                destMac = this.ndpTable.getMacForAddress(destIP);
                LogUtil.d(TAG, "普通IPv6包: 目的IP=" + destIP + "的MAC已知=" + StringUtils.macAddressToString(destMac));
            } else {
                destMac = 0L;
                sendNSPacket = true;
                LogUtil.d(TAG, "普通IPv6包: 目的IP=" + destIP + "的MAC未知, 将发送NS请求");
            }
        }
        // 发送数据包
        if (destMac != 0L) {
            var result = this.node.processVirtualNetworkFrame(System.currentTimeMillis(), this.networkId, localMac, destMac, IPV6_PACKET, 0, packetData, nextDeadline);
            if (result != ResultCode.RESULT_OK) {
                LogUtil.e(TAG, "Error calling processVirtualNetworkFrame: " + result.toString());
            } else {
                LogUtil.d(TAG, "IPv6数据包已发送至ZeroTier: 本地MAC=" + StringUtils.macAddressToString(localMac) +
                        ", 目标MAC=" + StringUtils.macAddressToString(destMac));
                this.ztService.setNextBackgroundTaskDeadline(nextDeadline[0]);
            }
        }
        // 发送 NS 请求
        if (sendNSPacket) {
            if (destMac == 0L) {
                destMac = InetAddressUtils.ipv6ToMulticastAddress(destIP);
                LogUtil.d(TAG, "NS请求使用多播地址: " + StringUtils.macAddressToString(destMac));
            }
            LogUtil.d(TAG, "发送邻居请求(NS): 源IP=" + sourceIP + ", 目的IP=" + destIP);
            packetData = this.ndpTable.getNeighborSolicitationPacket(sourceIP, destIP, localMac);
            var result = this.node.processVirtualNetworkFrame(System.currentTimeMillis(), this.networkId, localMac, destMac, IPV6_PACKET, 0, packetData, nextDeadline);
            if (result != ResultCode.RESULT_OK) {
                LogUtil.e(TAG, "发送NS包失败: " + result.toString());
            } else {
                LogUtil.d(TAG, "NS请求已发送至ZeroTier");
                this.ztService.setNextBackgroundTaskDeadline(nextDeadline[0]);
            }
        }
    }

    public void interrupt() {
        if (this.receiveThread != null) {
            try {
                this.in.close();
                this.out.close();
            } catch (IOException e) {
                LogUtil.e(TAG, "Error stopping in/out: " + e.getMessage(), e);
            }
            this.receiveThread.interrupt();
            try {
                this.receiveThread.join();
            } catch (InterruptedException ignored) {
            }
        }
    }

    public void join() throws InterruptedException {
        this.receiveThread.join();
    }

    private boolean isNeighborSolicitation(byte[] packetData) {
        return packetData[6] == 58 && packetData[40] == -121;
    }

    private boolean isNeighborAdvertisement(byte[] packetData) {
        return packetData[6] == 58 && packetData[40] == -120;
    }

    public boolean isRunning() {
        var thread = this.receiveThread;
        if (thread == null) {
            return false;
        }
        return thread.isAlive();
    }

    /**
     * 响应并处理 ZT 网络发送至本节点的以太网帧
     */
    @Override
    public void onVirtualNetworkFrame(long networkId, long srcMac, long destMac, long etherType,
                                      long vlanId, byte[] frameData) {

        LogUtil.d(TAG, "收到虚拟网络帧: " +
                "网络ID=" + StringUtils.networkIdToString(networkId) +
                ", 源MAC=" + StringUtils.macAddressToString(srcMac) +
                ", 目标MAC=" + StringUtils.macAddressToString(destMac) +
                ", 以太网类型=" + StringUtils.etherTypeToString(etherType) +
                ", VLAN ID=" + vlanId +
                ", 帧长度=" + frameData.length);

        if (this.vpnSocket == null) {
            LogUtil.e(TAG, "vpnSocket为空，无法处理接收的网络帧!");
            return;
        } else if (this.in == null || this.out == null) {
            LogUtil.e(TAG, "输入/输出流未初始化");
            return;
        }

        if (etherType == ARP_PACKET) {
            // 收到 ARP 包。更新 ARP 表，若需要则进行应答
            LogUtil.d(TAG, "收到ARP数据包");
            var arpReply = this.arpTable.processARPPacket(frameData);
            if (arpReply != null && arpReply.getDestMac() != 0 && arpReply.getDestAddress() != null) {
                // 获取本地 V4 地址
                var networkConfig = this.node.networkConfig(networkId);
                InetAddress localV4Address = null;
                for (var address : networkConfig.getAssignedAddresses()) {
                    if (address.getAddress() instanceof Inet4Address) {
                        localV4Address = address.getAddress();
                        break;
                    }
                }
                // 构造并返回 ARP 应答
                if (localV4Address != null) {
                    var nextDeadline = new long[1];
                    var packetData = this.arpTable.getReplyPacket(networkConfig.getMac(),
                            localV4Address, arpReply.getDestMac(), arpReply.getDestAddress());
                    LogUtil.d(TAG, "发送ARP应答: 本地地址=" + localV4Address +
                            ", 目标地址=" + arpReply.getDestAddress() +
                            ", 目标MAC=" + StringUtils.macAddressToString(arpReply.getDestMac()));
                    var result = this.node
                            .processVirtualNetworkFrame(System.currentTimeMillis(), networkId,
                                    networkConfig.getMac(), srcMac, ARP_PACKET, 0,
                                    packetData, nextDeadline);
                    if (result != ResultCode.RESULT_OK) {
                        LogUtil.e(TAG, "发送ARP应答失败: " + result.toString());
                        return;
                    }
                    LogUtil.d(TAG, "ARP应答已发送!");
                    this.ztService.setNextBackgroundTaskDeadline(nextDeadline[0]);
                }
            }
        } else if (etherType == IPV4_PACKET) {
            // 收到 IPv4 包。根据需要发送至 TUN
            try {
                var sourceIP = IPPacketUtils.getSourceIP(frameData);
                var destIP = IPPacketUtils.getDestIP(frameData);
                LogUtil.d(TAG, "收到IPv4数据包: 源IP=" + sourceIP +
                        ", 目标IP=" + destIP +
                        ", 大小=" + frameData.length + "字节");

                if (sourceIP != null) {
                    if (isIPv4Multicast(sourceIP)) {
                        var result = this.node.multicastSubscribe(this.networkId, multicastAddressToMAC(sourceIP));
                        if (result != ResultCode.RESULT_OK) {
                            LogUtil.e(TAG, "多播订阅错误: " + result);
                        }
                    } else {
                        this.arpTable.setAddress(sourceIP, srcMac);
                        LogUtil.d(TAG, "更新ARP表: IP=" + sourceIP + ", MAC=" + StringUtils.macAddressToString(srcMac));
                    }
                }

                // DNS 嗅探：解析 ZT 返回的 DNS 响应，填充 GFW IP 集合
                if (smartRoutingMode != SmartRoutingManager.MODE_OFF && smartRoutingManager != null) {
                    java.util.List<DnsPacketParser.DnsRecord> records =
                            DnsPacketParser.parseFromIpPacket(frameData);
                    for (DnsPacketParser.DnsRecord record : records) {
                        smartRoutingManager.onDnsRecord(record);
                    }
                }

                this.out.write(frameData);
                LogUtil.d(TAG, "IPv4数据包已写入本地TUN: 大小=" + frameData.length);
            } catch (Exception e) {
                LogUtil.e(TAG, "向VPN套接字写入数据失败: " + e.getMessage(), e);
            }
        } else if (etherType == IPV6_PACKET) {
            // 收到 IPv6 包。根据需要发送至 TUN，并更新 NDP 表
            try {
                var sourceIP = IPPacketUtils.getSourceIP(frameData);
                var destIP = IPPacketUtils.getDestIP(frameData);
                LogUtil.d(TAG, "收到IPv6数据包: 源IP=" + sourceIP +
                        ", 目标IP=" + destIP +
                        ", 大小=" + frameData.length + "字节");

                if (sourceIP != null) {
                    if (isIPv6Multicast(sourceIP)) {
                        var result = this.node.multicastSubscribe(this.networkId, multicastAddressToMAC(sourceIP));
                        if (result != ResultCode.RESULT_OK) {
                            LogUtil.e(TAG, "IPv6多播订阅错误: " + result);
                        }
                    } else {
                        this.ndpTable.setAddress(sourceIP, srcMac);
                        LogUtil.d(TAG, "更新NDP表: IP=" + sourceIP + ", MAC=" + StringUtils.macAddressToString(srcMac));
                    }
                }
                this.out.write(frameData);
                LogUtil.d(TAG, "IPv6数据包已写入本地TUN: 大小=" + frameData.length);
            } catch (Exception e) {
                LogUtil.e(TAG, "向VPN套接字写入数据失败: " + e.getMessage(), e);
            }
        } else if (frameData.length >= 14) {
            LogUtil.d(TAG, "收到未知类型数据包: 0x" + String.format("%02X%02X", frameData[12], frameData[13]));
        } else {
            LogUtil.d(TAG, "收到未知数据包. 包长度: " + frameData.length);
        }
    }

    private Route routeForDestination(InetAddress destAddress) {
        synchronized (this.routeMap) {
            for (var route : this.routeMap.keySet()) {
                if (route.belongsToRoute(destAddress)) {
                    return route;
                }
            }
            return null;
        }
    }

    private long networkIdForDestination(InetAddress destAddress) {
        synchronized (this.routeMap) {
            for (Route route : this.routeMap.keySet()) {
                if (route.belongsToRoute(destAddress)) {
                    return this.routeMap.get(route);
                }
            }
            return 0;
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
        if (this.receiveThread == null || !this.receiveThread.isAlive()) {
            LogUtil.e(TAG, "全局流量VPN未工作: 接收线程未运行");
            return false;
        }

        // 代理功能已移除
        LogUtil.d(TAG, "使用直接转发");

        // 检查是否有全局路由
        var virtualNetworkConfig = this.ztService.getVirtualNetworkConfig(this.networkId);
        if (virtualNetworkConfig == null) {
            LogUtil.e(TAG, "全局流量VPN未工作: 虚拟网络配置为空");
            return false;
        }

        try {
            boolean hasGlobalRoute = false;
            var routes = virtualNetworkConfig.getRoutes();
            LogUtil.d(TAG, "检查全局路由 - 共有路由:" + routes.length + "条");

            for (var route : routes) {
                var target = route.getTarget();
                var via = route.getVia();
                // 正确获取网关信息 - 从InetSocketAddress中提取InetAddress
                InetAddress gateway = via != null ? via.getAddress() : null;
                
                LogUtil.d(TAG, "路由: " + target.getAddress() + "/" + target.getPort() +
                        (via != null ? " via " + via : "") +
                        (gateway != null ? " gateway " + gateway : ""));

                if (target.getAddress().equals(InetAddress.getByName("0.0.0.0")) ||
                        target.getAddress().equals(InetAddress.getByName("::"))) {
                    hasGlobalRoute = true;
                    LogUtil.d(TAG, "发现全局路由: " + target.getAddress() + "/" + target.getPort() +
                            (gateway != null ? " 网关:" + gateway : ""));
                }
            }

            if (hasGlobalRoute) {
                // 检查assigned address
                var addresses = virtualNetworkConfig.getAssignedAddresses();
                LogUtil.d(TAG, "分配的地址数量: " + addresses.length);
                for (var addr : addresses) {
                    LogUtil.d(TAG, "分配的地址: " + addr.getAddress() + "/" + addr.getPort());
                }

                LogUtil.d(TAG, "== 全局流量VPN正在工作 - 路由表和网络配置正常 ==");
                return true;
            } else {
                LogUtil.e(TAG, "全局流量VPN未工作: 未配置全局路由");
                return false;
            }
        } catch (UnknownHostException e) {
            LogUtil.e(TAG, "解析IP地址时出错: " + e.getMessage(), e);
            return false;
        }
    }
}
