package net.kaaass.zerotierfix.service;

import android.os.ParcelFileDescriptor;
import android.support.v4.media.session.PlaybackStateCompat;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructPollfd;

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
    /** IPv4 多播地址范围 224.0.0.0/4 的高 4 位标识，用于原始 int 快速检测 */
    private static final int IPV4_MULTICAST_HIGH_NIBBLE = 0xE;

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
     * 已记录 [CONN] 日志的连接端点集合，用于每条连接只记录一次日志。
     * 键编码为 long：高 32 位为 IPv4 地址，低 16 位为目标端口，避免在高频数据包路径上分配 String 对象。
     * 网络切换时通过 {@link #clearConnLog()} 清空，重新记录新的连接。
     */
    private final Set<Long> connLoggedSet =
            Collections.newSetFromMap(new ConcurrentHashMap<>());
    private static final int MAX_CONN_LOG_ENTRIES = 5000;

    /**
     * 已发出 CHINA_DIRECT 路由漏洞告警的中国 IP 集合（每个 IP 仅告警一次）。
     * 用 long 编码 IPv4（与 connLoggedSet 格式相同，端口固定为 0 确保无冲突）。
     */
    private final Set<Long> chinaDirectLeakWarned =
            Collections.newSetFromMap(new ConcurrentHashMap<>());
    /** 已输出“热点下游流量进入 TUN”证据日志的源 IP 集合。 */
    private final Set<Long> hotspotTrafficLogged =
            Collections.newSetFromMap(new ConcurrentHashMap<>());
    /** 当前识别到的热点下游客户端子网提示。 */
    private volatile long[][] hotspotClientSubnets = new long[0][];

    /**
     * 缓存的 VirtualNetworkConfig，避免在每个数据包的处理热路径上都进行 synchronized 锁获取。
     * 在 {@link #clearRouteMap()} 时清空，由下一个数据包按需重新填充。
     */
    private volatile VirtualNetworkConfig cachedNetworkConfig;
    /** 缓存的本地 MAC 地址（来自 cachedNetworkConfig） */
    private volatile long cachedLocalMac;
    /** 缓存的本地 IPv4 地址（来自 cachedNetworkConfig 的 assignedAddresses） */
    private volatile InetAddress cachedLocalV4Address;
    /** 缓存的本地 IPv4 CIDR 前缀长度 */
    private volatile int cachedV4Cidr;
    /** 缓存的本地 IPv6 地址（来自 cachedNetworkConfig 的 assignedAddresses） */
    private volatile InetAddress cachedLocalV6Address;
    /** 缓存的本地 IPv6 CIDR 前缀长度 */
    private volatile int cachedV6Cidr;

    /**
     * 目标 MAC 快速路径缓存：rawDestIPv4（int）→ finalDestMac（网关解析后的实际 MAC）。
     *
     * <p>第一个发往某目标 IP 的数据包会经过完整的路由查找、ARP 查找和连接日志流程；
     * 成功发送后将 (rawDestIPv4 → destMac) 存入此 Map。
     * 后续所有发往同一目标 IP 的数据包直接使用缓存的 MAC 调用 processVirtualNetworkFrame，
     * 跳过路由查找、ARP 查找、智能路由诊断和连接日志等所有开销，
     * 消除视频/直播高频数据包路径上的每包 synchronized 锁争用和多次 HashMap 查找。
     *
     * <p>键为原始 IPv4 地址的 int 形式（bytes 16-19 packed），避免 InetAddress 对象分配。
     * 在 {@link #clearRouteMap()} 时随其他缓存一起清除，确保路由变更后重新走完整流程。
     */
    private final ConcurrentHashMap<Integer, Long> destMacFastPath = new ConcurrentHashMap<>();

    public TunTapAdapter(ZeroTierOneService zeroTierOneService, long j) {
        this.ztService = zeroTierOneService;
        this.networkId = j;
    }

    /**
     * 清除已记录的连接日志集合。
     * 在网络切换时调用，使得新网络上的连接能被重新记录。
     */
    public void clearConnLog() {
        connLoggedSet.clear();
        chinaDirectLeakWarned.clear();
        hotspotTrafficLogged.clear();
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

    public void setHotspotSubnetHints(java.util.List<long[]> hotspotSubnets) {
        if (hotspotSubnets == null || hotspotSubnets.isEmpty()) {
            this.hotspotClientSubnets = new long[0][];
            hotspotTrafficLogged.clear();
            return;
        }
        long[][] copy = new long[hotspotSubnets.size()][3];
        for (int i = 0; i < hotspotSubnets.size(); i++) {
            long[] subnet = hotspotSubnets.get(i);
            int prefix = (int) subnet[1];
            long mask = prefix == 0 ? 0L : ((prefix == 32) ? 0xFFFFFFFFL : (~0L << (32 - prefix)) & 0xFFFFFFFFL);
            copy[i][0] = subnet[0];
            copy[i][1] = subnet[1];
            copy[i][2] = mask;
        }
        this.hotspotClientSubnets = copy;
        hotspotTrafficLogged.clear();
    }

    /**
     * 发出 [CONN] 业务日志（每个 destIP:dstPort 仅记录一次）。
     *
     * <p>日志格式：{host/IP}:{port}  [路由原因]  (proto; src)
     * 路由原因：GFW → 走ZT | CN-直连 | ZT（全局/per-app）
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

        // 使用 long 编码（IPv4 地址占 bits 16-47，目标端口占 bits 0-15）作为集合键，
        // 避免在高频数据包路径上分配 String 对象，减少 GC 压力。
        byte[] addrBytes = origDestIP.getAddress();
        long ipLong = ((addrBytes[0] & 0xFFL) << 24) | ((addrBytes[1] & 0xFFL) << 16)
                | ((addrBytes[2] & 0xFFL) << 8) | (addrBytes[3] & 0xFFL);
        long connKey = (ipLong << 16) | (dstPort & 0xFFFFL);
        if (!connLoggedSet.add(connKey)) return; // 已记录过此端点

        String destStr = origDestIP.getHostAddress();
        String hostname = (smartRoutingManager != null)
                ? smartRoutingManager.getDomainForIp(origDestIP)
                : null;
        String displayHost = (hostname != null) ? hostname : destStr;
        String protoLabel = (protocol == 6) ? "TCP" : "UDP";
        String srcStr = (sourceIP != null ? sourceIP.getHostAddress() : "?") + ":" + srcPort;

        // 判断路由原因
        String routeReason = describeRouteReason(origDestIP);

        String msg = displayHost + ":" + dstPort + "  [" + routeReason + "]  (" + protoLabel + "; src=" + srcStr + "; dest=" + destStr + ")";
        LogUtil.i(LogUtil.CONN_TAG, msg);
    }

    private String describeRouteReason(InetAddress origDestIP) {
        if (origDestIP == null) {
            return "ZT";
        }
        if (perAppRoutingActive) {
            return "ZT (per-app)";
        }
        if (smartRoutingManager != null && smartRoutingMode != SmartRoutingManager.MODE_OFF) {
            boolean isGfw = smartRoutingManager.getGfwIpSet().contains(origDestIP);
            boolean isCn = smartRoutingManager.isChineseIp(origDestIP);
            String learnedPolicy = smartRoutingManager.getLearnedPolicyDescription(origDestIP);
            if (learnedPolicy != null && learnedPolicy.startsWith("via-zt ")) {
                return "ZT (" + learnedPolicy + ")";
            } else if (isGfw) {
                return "ZT (GFW)";
            } else if (isCn) {
                return "ZT (CN)";
            } else {
                return "ZT (非CN)";
            }
        }
        return "ZT (全局)";
    }

    private void emitHotspotTrafficEvidenceLog(InetAddress sourceIP, InetAddress destIP) {
        if (!(sourceIP instanceof Inet4Address) || hotspotClientSubnets.length == 0) return;
        byte[] addrBytes = sourceIP.getAddress();
        long ipLong = ((addrBytes[0] & 0xFFL) << 24) | ((addrBytes[1] & 0xFFL) << 16)
                | ((addrBytes[2] & 0xFFL) << 8) | (addrBytes[3] & 0xFFL);
        if (!belongsToAnySubnet(ipLong, hotspotClientSubnets)) return;
        if (!hotspotTrafficLogged.add(ipLong)) return;
        String destLabel = destIP != null ? destIP.getHostAddress() : "?";
        LogUtil.i(LogUtil.CONN_TAG, "热点下游流量进入TUN: src=" + sourceIP.getHostAddress()
                + " -> " + destLabel + " [" + describeRouteReason(destIP)
                + "]，当前按现有 VPN/智能路由链路处理");
    }

    private boolean belongsToAnySubnet(long ip, long[][] subnets) {
        for (long[] subnet : subnets) {
            long mask = subnet[2];
            if ((ip & mask) == subnet[0]) {
                return true;
            }
        }
        return false;
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
        // 路由表重建意味着网络配置可能已变更，清除缓存以强制下一个数据包重新加载。
        this.cachedNetworkConfig = null;
        this.cachedLocalV4Address = null;
        this.cachedLocalV6Address = null;
        this.destMacFastPath.clear();
        this.connLoggedSet.clear();
        this.chinaDirectLeakWarned.clear();
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
                    // 使用 Os.poll() 阻塞等待 TUN fd 可读，避免非阻塞 fd 上的轮询延迟。
                    // 原先依赖 Thread.sleep(10) 的轮询方式会导致 ACK 吞吐量上限约 100/s，
                    // 从而将单连接 TCP 下载速度卡在 ~100KB/s。
                    var pfd = new StructPollfd();
                    pfd.fd = TunTapAdapter.this.vpnSocket.getFileDescriptor();
                    pfd.events = (short) OsConstants.POLLIN;
                    var pollFds = new StructPollfd[]{pfd};
                    while (!isInterrupted()) {
                        try {
                            // 最多阻塞 10s。中断路径通过关闭 fd（EBADF）即刻返回，
                            // 无需短超时来响应 interrupt()，加长超时可减少空闲时的 CPU 唤醒频率。
                            Os.poll(pollFds, 10000);
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
                            }
                        } catch (ErrnoException e) {
                            // fd 已关闭（EBADF）或线程被标记中断，正常退出。
                            if (isInterrupted() || e.errno == OsConstants.EBADF) break;
                            LogUtil.e(TunTapAdapter.TAG, "poll error in TUN Receive: " + e.getMessage(), e);
                        } catch (IOException e) {
                            // TUN fd 已关闭或发生 I/O 错误。
                            // 若线程已被标记中断（通常是 interrupt() 先关闭 fd 再设标志），则正常退出；
                            // 否则记录错误并继续等待下一个数据包。
                            if (isInterrupted()) {
                                break;
                            }
                            LogUtil.e(TunTapAdapter.TAG, "Error in TUN Receive: " + e.getMessage(), e);
                        }
                    }
                } finally {
                    LogUtil.d(TunTapAdapter.TAG, "TUN Receive Thread ended");
                    // 关闭 ARP、NDP 表
                    TunTapAdapter.this.ndpTable.stop();
                    TunTapAdapter.this.ndpTable = null;
                    TunTapAdapter.this.arpTable.stop();
                    TunTapAdapter.this.arpTable = null;
                }
            }
        };
        this.receiveThread.start();
    }

    /**
     * 将 {@link VirtualNetworkConfig} 的地址/MAC 信息填充到各 cached* 字段，
     * 避免在每个数据包的处理热路径上重复遍历 getAssignedAddresses()。
     * 由 handleIPv4Packet 和 handleIPv6Packet 在缓存失效时共同调用。
     */
    private void populateNetworkConfigCache(VirtualNetworkConfig config) {
        InetAddress v4 = null;
        InetAddress v6 = null;
        int v4Cidr = 0, v6Cidr = 0;
        for (InetSocketAddress addr : config.getAssignedAddresses()) {
            if (addr.getAddress() instanceof Inet4Address && v4 == null) {
                v4 = addr.getAddress();
                v4Cidr = addr.getPort();
            } else if (addr.getAddress() instanceof Inet6Address && v6 == null) {
                v6 = addr.getAddress();
                v6Cidr = addr.getPort();
            }
        }
        this.cachedLocalMac = config.getMac();
        this.cachedLocalV4Address = v4;
        this.cachedV4Cidr = v4Cidr;
        this.cachedLocalV6Address = v6;
        this.cachedV6Cidr = v6Cidr;
        this.cachedNetworkConfig = config;
    }

    private void handleIPv4Packet(byte[] packetData) {
        // ── 超快路径：无 InetAddress 分配 ──
        // 直接从原始字节提取目的 IPv4（偏移 16–19），避免在每个数据包上都分配
        // InetAddress / byte[] 对象，彻底消除视频/直播高频数据包路径上的 GC 压力。
        // 多播地址 224.0.0.0/4 → 高 4 位 == IPV4_MULTICAST_HIGH_NIBBLE，跳过直接路径。
        // 同时对两个 volatile 字段做本地快照，避免检查与使用之间的 TOCTOU 竞态。
        int rawDestIP = IPPacketUtils.getDestIPv4AsInt(packetData);
        VirtualNetworkConfig configSnap = this.cachedNetworkConfig;
        long localMacSnap = this.cachedLocalMac;
        if (rawDestIP != 0 && (rawDestIP >>> 28) != IPV4_MULTICAST_HIGH_NIBBLE && configSnap != null) {
            Long fastMac = destMacFastPath.get(rawDestIP);
            if (fastMac != null) {
                long[] fastDeadline = new long[1];
                var fastResult = this.node.processVirtualNetworkFrame(
                        System.currentTimeMillis(), this.networkId,
                        localMacSnap, fastMac, IPV4_PACKET, 0, packetData, fastDeadline);
                if (fastResult == ResultCode.RESULT_OK) {
                    this.ztService.setNextBackgroundTaskDeadline(fastDeadline[0]);
                    return;
                }
                // processVirtualNetworkFrame 失败（ZT 节点未就绪等），移除缓存后走完整流程重试
                destMacFastPath.remove(rawDestIP);
            }
        }

        // ── 慢路径（首包或缓存失效） ──
        boolean isMulticast;
        var destIP = IPPacketUtils.getDestIP(packetData);
        var sourceIP = IPPacketUtils.getSourceIP(packetData);

        // 优先使用缓存的 VirtualNetworkConfig，避免在每个数据包上都进行 synchronized 锁获取。
        // 缓存在 clearRouteMap() 时失效，确保网络重新配置后能取到最新配置。
        var virtualNetworkConfig = this.cachedNetworkConfig;
        if (virtualNetworkConfig == null) {
            virtualNetworkConfig = this.ztService.getVirtualNetworkConfig(this.networkId);
            if (virtualNetworkConfig != null) {
                populateNetworkConfigCache(virtualNetworkConfig);
            }
        }

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

        DebugLog.d(TAG, "处理IPv4数据包: 源IP=" + sourceIP + ", 目的IP=" + destIP + ", 数据包大小=" + packetData.length);

        // 代理功能已移除

        // ── 智能路由诊断（仅调试模式，首包生效） ──
        // 分流应由 Android VPN 路由表决定。包已经进入 TUN 后不能通过丢弃实现"直连"，
        // 否则会造成应用侧黑洞；这里只记录异常命中，仍继续转发到 ZT。
        if (!perAppRoutingActive && !isIPv4Multicast(destIP) && smartRoutingManager != null) {

            // ── GFW 列表模式 ──
            if (smartRoutingMode == SmartRoutingManager.MODE_GFW_LIST) {
                if (DebugLog.isDebug()) {
                    Set<InetAddress> gfwIps = smartRoutingManager.getGfwIpSet();
                    if (!gfwIps.isEmpty() && !gfwIps.contains(destIP)) {
                        DebugLog.d(TAG, "智能路由(GFW): 目的IP=" + destIP
                                + " 不在GFW列表但已进入TUN，继续转发以避免黑洞");
                    }
                }
            }

            // ── 组合模式 ──
            if (smartRoutingMode == SmartRoutingManager.MODE_COMBINED) {
                if (DebugLog.isDebug()) {
                    if (smartRoutingManager.isChineseIp(destIP)) {
                        Set<InetAddress> gfwIps = smartRoutingManager.getGfwIpSet();
                        boolean isGfwIp = gfwIps.contains(destIP);
                        DebugLog.d(TAG, "智能路由(组合): 目的IP=" + destIP
                                + (isGfwIp ? " 是GFW中国CDN IP，走ZT转发"
                                : " 是非GFW中国IP但已进入TUN，继续转发以避免黑洞"));
                    }
                }
            }

            // ── 国内直连模式：中国 IP 进入 TUN 说明 OS 路由排除未生效，记录一次性告警 ──
            if (smartRoutingMode == SmartRoutingManager.MODE_CHINA_DIRECT
                    && smartRoutingManager.isChineseIp(destIP)) {
                byte[] addrBytes = destIP.getAddress();
                if (addrBytes != null && addrBytes.length == 4) {
                    long ipLong = ((addrBytes[0] & 0xFFL) << 24) | ((addrBytes[1] & 0xFFL) << 16)
                            | ((addrBytes[2] & 0xFFL) << 8) | (addrBytes[3] & 0xFFL);
                    if (chinaDirectLeakWarned.add(ipLong)) {
                        LogUtil.w(LogUtil.CONN_TAG, "⚠ 国内IP " + destIP.getHostAddress()
                                + " 进入TUN（应直连但OS路由未排除），走ZT转发，可能导致直播卡顿"
                                + "；请检查 chnroutes 数据是否已加载及 VPN 路由是否正确配置");
                    }
                }
            }
        }

        // ── [CONN] 业务日志：非多播 unicast 包通过路由过滤，即将转发至 ZT ──
        if (!isIPv4Multicast(destIP)) {
            emitHotspotTrafficEvidenceLog(sourceIP, destIP);
            emitConnLog(packetData, destIP, sourceIP);
        }

        if (isIPv4Multicast(destIP)) {
            var result = this.node.multicastSubscribe(this.networkId, multicastAddressToMAC(destIP));
            if (result != ResultCode.RESULT_OK) {
                LogUtil.e(TAG, "Error when calling multicastSubscribe: " + result);
            }
            isMulticast = true;
            DebugLog.d(TAG, "IPv4多播数据包: 目的IP=" + destIP);
        } else {
            isMulticast = false;
        }
        var route = routeForDestination(destIP);
        // 修复：VirtualNetworkRoute没有getGateway方法，但Route类有
        InetAddress gateway = null;
        if (route != null) {
            gateway = route.getGateway();
        }

        // 路由决策
        DebugLog.d(TAG, "路由决策: 目的IP=" + destIP + ", 选择路由=" + (route != null ? route.toString() : "无") 
              + ", 网关=" + (gateway != null ? gateway.toString() : "无"));

        // 使用缓存的本地 v4 地址和 CIDR，避免每个包都遍历 getAssignedAddresses()
        InetAddress localV4Address = cachedLocalV4Address;
        int cidr = cachedV4Cidr;

        var destRoute = InetAddressUtils.addressToRouteNo0Route(destIP, cidr);
        var sourceRoute = InetAddressUtils.addressToRouteNo0Route(sourceIP, cidr);
        if (gateway != null && !Objects.equals(destRoute, sourceRoute)) {
            DebugLog.d(TAG, "使用网关: 原始目的IP=" + destIP + " 修改为网关IP=" + gateway);
            destIP = gateway;
        }
        if (localV4Address == null) {
            LogUtil.e(TAG, "Couldn't determine local address");
            return;
        }

        DebugLog.d(TAG, "本地IPv4地址: " + localV4Address + "/" + cidr);

        long localMac = cachedLocalMac;
        long[] nextDeadline = new long[1];
        // 单次 ARP 查找：getMacForAddress 在未命中时返回 -1；多播包直接进入分支（不依赖 ARP 表）。
        // 此处消除了原来 hasMacForAddress + getMacForAddress 的双重 HashMap 查找。
        ARPTable arp = this.arpTable;
        if (arp == null) {
            return; // TUN 正在重建中，arpTable 已被 teardown
        }
        long destMac = arp.getMacForAddress(destIP);
        if (isMulticast || destMac != -1L) {
            // 已确定目标 MAC，直接发送

            DebugLog.d(TAG, "发送IPv4数据包: 本地MAC=" + StringUtils.macAddressToString(localMac) + 
                  ", 目标MAC=" + StringUtils.macAddressToString(destMac) + 
                  ", 目的IP=" + destIP);
                  
            var result = this.node.processVirtualNetworkFrame(System.currentTimeMillis(), this.networkId, localMac, destMac, IPV4_PACKET, 0, packetData, nextDeadline);
            if (result != ResultCode.RESULT_OK) {
                LogUtil.e(TAG, "Error calling processVirtualNetworkFrame: " + result.toString());
                return;
            }
            DebugLog.d(TAG, "数据包已发送至ZeroTier: 目的IP=" + destIP);
            this.ztService.setNextBackgroundTaskDeadline(nextDeadline[0]);

            // ── 填充快速路径缓存 ──
            // 首包成功发送后，将 rawDestIP → finalDestMac 存入缓存，
            // 后续包直接走快速路径，跳过路由查找、ARP 查找和连接日志。
            if (!isMulticast) {
                destMacFastPath.put(rawDestIP, destMac);
            }
        } else {
            // 目标 MAC 未知，进行 ARP 查询
            DebugLog.d(TAG, "Unknown dest MAC address.  Need to look it up. " + destIP);
            destMac = InetAddressUtils.BROADCAST_MAC_ADDRESS;
            packetData = arp.getRequestPacket(localMac, localV4Address, destIP);
            var result = this.node.processVirtualNetworkFrame(System.currentTimeMillis(), this.networkId, localMac, destMac, ARP_PACKET, 0, packetData, nextDeadline);
            if (result != ResultCode.RESULT_OK) {
                LogUtil.e(TAG, "Error sending ARP packet: " + result.toString());
                return;
            }
            DebugLog.d(TAG, "ARP Request Sent!");
            this.ztService.setNextBackgroundTaskDeadline(nextDeadline[0]);
        }
    }

    private void handleIPv6Packet(byte[] packetData) {
        var destIP = IPPacketUtils.getDestIP(packetData);
        var sourceIP = IPPacketUtils.getSourceIP(packetData);

        // 使用缓存的 VirtualNetworkConfig，避免重复加锁
        var virtualNetworkConfig = this.cachedNetworkConfig;
        if (virtualNetworkConfig == null) {
            virtualNetworkConfig = this.ztService.getVirtualNetworkConfig(this.networkId);
            if (virtualNetworkConfig != null) {
                populateNetworkConfigCache(virtualNetworkConfig);
            }
        }

        DebugLog.d(TAG, "处理IPv6数据包: 源IP=" + sourceIP + ", 目的IP=" + destIP + ", 数据包大小=" + packetData.length);

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
            DebugLog.d(TAG, "IPv6多播数据包: 目的IP=" + destIP);
        }
        var route = routeForDestination(destIP);
        var gateway = route != null ? route.getGateway() : null;

        // IPv6路由决策
        DebugLog.d(TAG, "IPv6路由决策: 目的IP=" + destIP + ", 选择路由=" + (route != null ? route.toString() : "无")
                + ", 网关=" + (gateway != null ? gateway.toString() : "无"));

        // 使用缓存的本地 v6 地址和 CIDR，避免每个包都遍历 getAssignedAddresses()
        InetAddress localV6Address = cachedLocalV6Address;
        int cidr = cachedV6Cidr;

        var destRoute = InetAddressUtils.addressToRouteNo0Route(destIP, cidr);
        var sourceRoute = InetAddressUtils.addressToRouteNo0Route(sourceIP, cidr);
        if (gateway != null && !Objects.equals(destRoute, sourceRoute)) {
            DebugLog.d(TAG, "使用IPv6网关: 原始目的IP=" + destIP + " 修改为网关IP=" + gateway);
            destIP = gateway;
        }
        if (localV6Address == null) {
            LogUtil.e(TAG, "Couldn't determine local address");
            return;
        }

        DebugLog.d(TAG, "本地IPv6地址: " + localV6Address + "/" + cidr);

        long localMac = cachedLocalMac;
        long[] nextDeadline = new long[1];

        // 确定目标 MAC 地址
        long destMac;
        boolean sendNSPacket = false;
        if (this.isNeighborSolicitation(packetData)) {
            // 收到本地 NS 报文，根据 NDP 表记录确定是否广播查询
            if (this.ndpTable.hasMacForAddress(destIP)) {
                destMac = this.ndpTable.getMacForAddress(destIP);
                DebugLog.d(TAG, "NS包: 目的IP=" + destIP + "的MAC已知=" + StringUtils.macAddressToString(destMac));
            } else {
                destMac = InetAddressUtils.ipv6ToMulticastAddress(destIP);
                DebugLog.d(TAG, "NS包: 目的IP=" + destIP + "的MAC未知, 使用多播地址=" + StringUtils.macAddressToString(destMac));
            }
        } else if (this.isIPv6Multicast(destIP)) {
            // 多播报文
            destMac = multicastAddressToMAC(destIP);
            DebugLog.d(TAG, "IPv6多播: 目的IP=" + destIP + ", 多播MAC=" + StringUtils.macAddressToString(destMac));
        } else if (this.isNeighborAdvertisement(packetData)) {
            // 收到本地 NA 报文
            if (this.ndpTable.hasMacForAddress(destIP)) {
                destMac = this.ndpTable.getMacForAddress(destIP);
                DebugLog.d(TAG, "NA包: 目的IP=" + destIP + "的MAC已知=" + StringUtils.macAddressToString(destMac));
            } else {
                // 目标 MAC 未知，不发送数据包
                destMac = 0L;
                DebugLog.d(TAG, "NA包: 目的IP=" + destIP + "的MAC未知, 不发送数据包");
            }
            sendNSPacket = true;
        } else {
            // 收到普通数据包，根据 NDP 表记录确定是否发送 NS 请求
            if (this.ndpTable.hasMacForAddress(destIP)) {
                // 目标地址 MAC 已知
                destMac = this.ndpTable.getMacForAddress(destIP);
                DebugLog.d(TAG, "普通IPv6包: 目的IP=" + destIP + "的MAC已知=" + StringUtils.macAddressToString(destMac));
            } else {
                destMac = 0L;
                sendNSPacket = true;
                DebugLog.d(TAG, "普通IPv6包: 目的IP=" + destIP + "的MAC未知, 将发送NS请求");
            }
        }
        // 发送数据包
        if (destMac != 0L) {
            var result = this.node.processVirtualNetworkFrame(System.currentTimeMillis(), this.networkId, localMac, destMac, IPV6_PACKET, 0, packetData, nextDeadline);
            if (result != ResultCode.RESULT_OK) {
                LogUtil.e(TAG, "Error calling processVirtualNetworkFrame: " + result.toString());
            } else {
                DebugLog.d(TAG, "IPv6数据包已发送至ZeroTier: 本地MAC=" + StringUtils.macAddressToString(localMac) +
                        ", 目标MAC=" + StringUtils.macAddressToString(destMac));
                this.ztService.setNextBackgroundTaskDeadline(nextDeadline[0]);
            }
        }
        // 发送 NS 请求
        if (sendNSPacket) {
            if (destMac == 0L) {
                destMac = InetAddressUtils.ipv6ToMulticastAddress(destIP);
                DebugLog.d(TAG, "NS请求使用多播地址: " + StringUtils.macAddressToString(destMac));
            }
            DebugLog.d(TAG, "发送邻居请求(NS): 源IP=" + sourceIP + ", 目的IP=" + destIP);
            packetData = this.ndpTable.getNeighborSolicitationPacket(sourceIP, destIP, localMac);
            var result = this.node.processVirtualNetworkFrame(System.currentTimeMillis(), this.networkId, localMac, destMac, IPV6_PACKET, 0, packetData, nextDeadline);
            if (result != ResultCode.RESULT_OK) {
                LogUtil.e(TAG, "发送NS包失败: " + result.toString());
            } else {
                DebugLog.d(TAG, "NS请求已发送至ZeroTier");
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

        DebugLog.d(TAG, "收到虚拟网络帧: " +
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
            DebugLog.d(TAG, "收到ARP数据包");
            ARPTable arp = this.arpTable;
            if (arp == null) {
                return; // TUN 正在重建中，arpTable 已被 teardown
            }
            var arpReply = arp.processARPPacket(frameData);
            if (arpReply != null && arpReply.getDestMac() != 0 && arpReply.getDestAddress() != null) {
                // 优先使用缓存的本地地址和 MAC，避免在每次 ARP 应答时调用
                // this.node.networkConfig() JNI，防止在直播等高频场景下的延迟抖动。
                // 首次启动缓存未就绪时，回退到 JNI 获取配置（极少发生）。
                InetAddress localV4Address = this.cachedLocalV4Address;
                long localMac = this.cachedLocalMac;
                if (localV4Address == null || localMac == 0) {
                    var networkConfig = this.node.networkConfig(networkId);
                    if (networkConfig != null) {
                        localMac = networkConfig.getMac();
                        for (var address : networkConfig.getAssignedAddresses()) {
                            if (address.getAddress() instanceof Inet4Address) {
                                localV4Address = address.getAddress();
                                break;
                            }
                        }
                    }
                }
                if (localV4Address != null && localMac != 0) {
                    var nextDeadline = new long[1];
                    var packetData = arp.getReplyPacket(localMac,
                            localV4Address, arpReply.getDestMac(), arpReply.getDestAddress());
                    DebugLog.d(TAG, "发送ARP应答: 本地地址=" + localV4Address +
                            ", 目标地址=" + arpReply.getDestAddress() +
                            ", 目标MAC=" + StringUtils.macAddressToString(arpReply.getDestMac()));
                    var result = this.node
                            .processVirtualNetworkFrame(System.currentTimeMillis(), networkId,
                                    localMac, srcMac, ARP_PACKET, 0,
                                    packetData, nextDeadline);
                    if (result != ResultCode.RESULT_OK) {
                        LogUtil.e(TAG, "发送ARP应答失败: " + result.toString());
                        return;
                    }
                    DebugLog.d(TAG, "ARP应答已发送!");
                    this.ztService.setNextBackgroundTaskDeadline(nextDeadline[0]);
                }
            }
        } else if (etherType == IPV4_PACKET) {
            // 收到 IPv4 包。根据需要发送至 TUN
            try {
                var sourceIP = IPPacketUtils.getSourceIP(frameData);
                var destIP = IPPacketUtils.getDestIP(frameData);
                DebugLog.d(TAG, "收到IPv4数据包: 源IP=" + sourceIP +
                        ", 目标IP=" + destIP +
                        ", 大小=" + frameData.length + "字节");

                if (sourceIP != null) {
                    if (isIPv4Multicast(sourceIP)) {
                        var result = this.node.multicastSubscribe(this.networkId, multicastAddressToMAC(sourceIP));
                        if (result != ResultCode.RESULT_OK) {
                            LogUtil.e(TAG, "多播订阅错误: " + result);
                        }
                    } else {
                        ARPTable arpRef = this.arpTable;
                        if (arpRef != null) {
                            arpRef.setAddress(sourceIP, srcMac);
                        }
                        DebugLog.d(TAG, "更新ARP表: IP=" + sourceIP + ", MAC=" + StringUtils.macAddressToString(srcMac));
                    }
                }

                // DNS 嗅探：解析 ZT 返回的 DNS 响应，填充 IP→域名 映射和 GFW IP 集合。
                // 即使在全局路由模式（smartRoutingMode=OFF）下也需要解析，
                // 以便 [CONN] 日志能显示域名而非纯 IP。
                //
                // ⚠ 盲区警告 – CHINA_DIRECT 模式：国内 DNS（114DNS / AliDNS）是中国 IP，
                // 已通过 excludeRoute 排除在 VPN 之外，DNS 查询走物理网络直达 DNS 服务器，
                // 响应【不经过】ZeroTier，onVirtualNetworkFrame 永远看不到这些 DNS 包。
                // 因此 CHINA_DIRECT 模式下 ipToDomain 映射始终为空，[CONN] 日志只有裸 IP。
                // 如需排查直播 CDN 路由问题（哪些 IP 走了 ZT），请直接查看 [CONN] 日志中的 IP 列表，
                // 对比 chnroutes 中的中国 IP 段，识别未被排除的 CDN IP 并更新 chnroutes_supplement.txt。
                if (smartRoutingManager != null) {
                    java.util.List<DnsPacketParser.DnsRecord> records =
                            DnsPacketParser.parseFromIpPacket(frameData);
                    for (DnsPacketParser.DnsRecord record : records) {
                        smartRoutingManager.onDnsRecord(record);
                    }
                }

                this.out.write(frameData);
                DebugLog.d(TAG, "IPv4数据包已写入本地TUN: 大小=" + frameData.length);
            } catch (Exception e) {
                LogUtil.e(TAG, "向VPN套接字写入数据失败: " + e.getMessage(), e);
            }
        } else if (etherType == IPV6_PACKET) {
            // 收到 IPv6 包。根据需要发送至 TUN，并更新 NDP 表
            try {
                var sourceIP = IPPacketUtils.getSourceIP(frameData);
                var destIP = IPPacketUtils.getDestIP(frameData);
                DebugLog.d(TAG, "收到IPv6数据包: 源IP=" + sourceIP +
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
                        DebugLog.d(TAG, "更新NDP表: IP=" + sourceIP + ", MAC=" + StringUtils.macAddressToString(srcMac));
                    }
                }
                this.out.write(frameData);
                DebugLog.d(TAG, "IPv6数据包已写入本地TUN: 大小=" + frameData.length);
            } catch (Exception e) {
                LogUtil.e(TAG, "向VPN套接字写入数据失败: " + e.getMessage(), e);
            }
        } else if (frameData.length >= 14) {
            DebugLog.d(TAG, "收到未知类型数据包: 0x" + String.format("%02X%02X", frameData[12], frameData[13]));
        } else {
            DebugLog.d(TAG, "收到未知数据包. 包长度: " + frameData.length);
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
