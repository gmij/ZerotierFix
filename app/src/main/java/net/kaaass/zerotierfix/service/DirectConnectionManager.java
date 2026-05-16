package net.kaaass.zerotierfix.service;

import android.net.VpnService;

import net.kaaass.zerotierfix.util.LogUtil;
import net.kaaass.zerotierfix.util.smartroute.FakeIpPool;
import net.kaaass.zerotierfix.util.smartroute.SmartRoutingManager;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.Iterator;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Fake-IP 模式下的直连代理管理器。
 *
 * <p>当流量目标是 Fake-IP 池（198.18.0.0/15）时，不走 ZeroTier 隧道，
 * 而是在此处创建 {@link VpnService#protect()} 过的 socket，直接连接到真实目标，
 * 并在 TUN 侧（{@code out}）和真实网络侧（socket）之间双向 pipe 数据。
 *
 * <h3>TCP 实现</h3>
 * <ul>
 *   <li>每条连接维护一个 {@link TcpSession}，包含简化的 TCP 状态机（SYN→ESTABLISHED→FIN）。</li>
 *   <li>从 TUN 读到的 TCP 包由 {@link TunTapAdapter} 调用 {@link #handleTcpPacket} 传入。</li>
 *   <li>每个 session 启动一个专用 "server→TUN" 读线程，将真实服务器的响应封装为
 *       TCP 数据包写回 TUN（{@code out.write()}）。</li>
 * </ul>
 *
 * <h3>UDP 实现</h3>
 * <ul>
 *   <li>无状态映射：{@code (srcIP, srcPort, dstFakeIP, dstPort)} → protected DatagramSocket。</li>
 *   <li>UDP 回包由后台线程侦听并写回 TUN。</li>
 * </ul>
 */
public class DirectConnectionManager {

    private static final String TAG = "DirectConnMgr";

    // TCP 标志位
    private static final int TCP_FIN = 0x01;
    private static final int TCP_SYN = 0x02;
    private static final int TCP_RST = 0x04;
    private static final int TCP_ACK = 0x10;
    private static final int TCP_PSH = 0x08;

    /** UDP 会话超时：60 秒无活动则回收 */
    private static final long UDP_SESSION_TIMEOUT_MS = 60_000;
    /** 等待 DNS 解析（fake-IP → 真实 IP）的最长时间 */
    private static final long RESOLVE_TIMEOUT_MS = 3_000;
    /** 直连 DNS 服务器（114.114.114.114 和备用 AliDNS） */
    private static final String[] DIRECT_DNS_SERVERS = {"114.114.114.114", "223.5.5.5"};

    private final VpnService vpnService;
    private final FakeIpPool fakeIpPool;
    private final SmartRoutingManager smartRouter;

    /** TCP 会话表：sessionKey → TcpSession */
    private final ConcurrentHashMap<Long, TcpSession> tcpSessions = new ConcurrentHashMap<>();
    /** UDP 会话表：sessionKey → UdpSession */
    private final ConcurrentHashMap<Long, UdpSession> udpSessions = new ConcurrentHashMap<>();

    /** 线程池：每条 TCP 连接一个读线程 + 定期清理 UDP 会话 */
    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "DirectProxy-Worker");
        t.setDaemon(true);
        return t;
    });

    /** 写 TUN 的锁：proxy 线程和 TUN receive 线程都会写 out */
    private final Object tunWriteLock;

    private volatile FileOutputStream tunOut;
    private volatile boolean stopped = false;

    private final Random random = new Random();

    public DirectConnectionManager(VpnService vpnService, FakeIpPool fakeIpPool,
                                   SmartRoutingManager smartRouter, Object tunWriteLock) {
        this.vpnService = vpnService;
        this.fakeIpPool = fakeIpPool;
        this.smartRouter = smartRouter;
        this.tunWriteLock = tunWriteLock;
    }

    public void setTunOut(FileOutputStream out) {
        this.tunOut = out;
    }

    public void stop() {
        stopped = true;
        executor.shutdownNow();
        for (TcpSession s : tcpSessions.values()) s.close();
        tcpSessions.clear();
        for (UdpSession s : udpSessions.values()) s.close();
        udpSessions.clear();
    }

    // ─────────────────────────────── TCP ──────────────────────────────────────

    /**
     * 处理一个发往 Fake-IP 的 TCP 数据包。
     *
     * @param packet 完整 IPv4 数据包
     * @return true 表示已消费（不应再转发到 ZeroTier）；false 表示未处理
     */
    public boolean handleTcpPacket(byte[] packet) {
        if (stopped) return false;
        if (packet.length < 40) return false; // IP(20) + TCP(20)

        int ipHdrLen = (packet[0] & 0x0F) * 4;
        if (packet.length < ipHdrLen + 20) return false;

        // 源/目 IP
        byte[] srcIpBytes = new byte[4];
        byte[] dstIpBytes = new byte[4];
        System.arraycopy(packet, 12, srcIpBytes, 0, 4);
        System.arraycopy(packet, 16, dstIpBytes, 0, 4);
        int dstIpInt = bytesToInt(dstIpBytes);

        if (!FakeIpPool.isFakeIpInt(dstIpInt)) return false;

        int tcpOff = ipHdrLen;
        int srcPort = ((packet[tcpOff] & 0xFF) << 8) | (packet[tcpOff + 1] & 0xFF);
        int dstPort = ((packet[tcpOff + 2] & 0xFF) << 8) | (packet[tcpOff + 3] & 0xFF);
        int seqNum  = (((packet[tcpOff + 4] & 0xFF) << 24) | ((packet[tcpOff + 5] & 0xFF) << 16)
                    |  ((packet[tcpOff + 6] & 0xFF) << 8)  |  (packet[tcpOff + 7] & 0xFF));
        int ackNum  = (((packet[tcpOff + 8] & 0xFF) << 24) | ((packet[tcpOff + 9] & 0xFF) << 16)
                    |  ((packet[tcpOff + 10] & 0xFF) << 8) |  (packet[tcpOff + 11] & 0xFF));
        int dataOff = ((packet[tcpOff + 12] & 0xF0) >> 4) * 4;
        int flags   = packet[tcpOff + 13] & 0xFF;

        long sessionKey = makeKey(srcIpBytes, srcPort, dstIpBytes, dstPort);

        TcpSession session = tcpSessions.get(sessionKey);

        if ((flags & TCP_SYN) != 0 && (flags & TCP_ACK) == 0) {
            // ── 新连接 SYN ──
            if (session != null) {
                // 重复 SYN（可能是重传），重发 SYN-ACK
                session.sendSynAck(seqNum);
                return true;
            }
            // 查找真实目标
            InetAddress fakeIpAddr = FakeIpPool.intToAddr(dstIpInt);
            String domain = fakeIpPool.getDomain(fakeIpAddr);
            if (domain == null) {
                LogUtil.w(TAG, "TCP SYN: 找不到 fake IP " + FakeIpPool.intToString(dstIpInt) + " 对应的域名，丢弃");
                sendRst(srcIpBytes, dstIpBytes, srcPort, dstPort, 0, seqNum + 1);
                return true;
            }
            TcpSession newSession = new TcpSession(sessionKey, srcIpBytes, dstIpBytes,
                    srcPort, dstPort, domain, dstPort, seqNum);
            tcpSessions.put(sessionKey, newSession);
            executor.submit(() -> newSession.connect());
            return true;
        }

        if (session == null) {
            // 未知会话的非 SYN 包 → RST
            sendRst(srcIpBytes, dstIpBytes, srcPort, dstPort, ackNum, seqNum + 1);
            return true;
        }

        if ((flags & TCP_RST) != 0) {
            session.close();
            tcpSessions.remove(sessionKey);
            return true;
        }

        if ((flags & TCP_FIN) != 0) {
            session.handleFin(seqNum);
            return true;
        }

        if ((flags & TCP_ACK) != 0) {
            // 提取 payload
            int payloadStart = tcpOff + dataOff;
            int payloadLen = packet.length - payloadStart;
            if (payloadLen > 0) {
                byte[] payload = new byte[payloadLen];
                System.arraycopy(packet, payloadStart, payload, 0, payloadLen);
                session.handleData(seqNum, payload);
            } else {
                // 纯 ACK（三次握手最后一个 ACK）
                session.handleAck(seqNum, ackNum);
            }
            return true;
        }

        return true; // 消费但忽略其他标志
    }

    // ─────────────────────────────── UDP ──────────────────────────────────────

    /**
     * 处理一个发往 Fake-IP 的 UDP 数据包。
     *
     * @param packet 完整 IPv4 数据包
     * @return true 表示已消费
     */
    public boolean handleUdpPacket(byte[] packet) {
        if (stopped) return false;
        int ipHdrLen = (packet[0] & 0x0F) * 4;
        if (packet.length < ipHdrLen + 8) return false;

        byte[] srcIpBytes = new byte[4];
        byte[] dstIpBytes = new byte[4];
        System.arraycopy(packet, 12, srcIpBytes, 0, 4);
        System.arraycopy(packet, 16, dstIpBytes, 0, 4);
        int dstIpInt = bytesToInt(dstIpBytes);
        if (!FakeIpPool.isFakeIpInt(dstIpInt)) return false;

        int srcPort = ((packet[ipHdrLen] & 0xFF) << 8) | (packet[ipHdrLen + 1] & 0xFF);
        int dstPort = ((packet[ipHdrLen + 2] & 0xFF) << 8) | (packet[ipHdrLen + 3] & 0xFF);
        int udpPayloadLen = packet.length - ipHdrLen - 8;
        if (udpPayloadLen <= 0) return true;
        byte[] payload = new byte[udpPayloadLen];
        System.arraycopy(packet, ipHdrLen + 8, payload, 0, udpPayloadLen);

        long sessionKey = makeKey(srcIpBytes, srcPort, dstIpBytes, dstPort);
        UdpSession session = udpSessions.get(sessionKey);

        if (session == null) {
            InetAddress fakeIpAddr = FakeIpPool.intToAddr(dstIpInt);
            String domain = fakeIpPool.getDomain(fakeIpAddr);
            if (domain == null) {
                LogUtil.w(TAG, "UDP: 找不到 fake IP 对应域名，丢弃");
                return true;
            }
            // 解析真实 IP
            InetAddress realIp = resolveViaDirect(domain);
            if (realIp == null) {
                LogUtil.w(TAG, "UDP: 无法解析 " + domain + "，丢弃");
                return true;
            }
            fakeIpPool.storeRealIp(fakeIpAddr, realIp);
            if (smartRouter != null) smartRouter.learnFromDirectConnection(domain, realIp);

            session = new UdpSession(sessionKey, srcIpBytes, dstIpBytes, srcPort, dstPort, realIp);
            udpSessions.put(sessionKey, session);
            final UdpSession finalSession = session;
            executor.submit(() -> finalSession.startReadLoop());
        }
        session.send(payload);
        purgeStaleUdpSessions();
        return true;
    }

    // ─────────────────────────── TcpSession ───────────────────────────────────

    private class TcpSession {
        final long key;
        final byte[] clientIp;   // 发起方 IP（app side）
        final byte[] fakeIp;     // fake IP（app 连接的目标）
        final int clientPort;
        final int fakePort;      // = 真实目标端口
        final String domain;
        final int realPort;

        // 客户端（app → proxy）序列号跟踪
        volatile int clientIsn;      // 客户端初始序列号
        volatile int clientNextSeq;  // 下次期望收到的客户端序列号（= clientIsn + 1 after SYN）

        // 服务端（proxy → app）序列号
        volatile int serverIsn;
        volatile int serverNextSeq;  // proxy 下次向 app 发送的序列号

        volatile Socket socket;
        volatile boolean established = false;
        volatile boolean closed = false;

        TcpSession(long key, byte[] clientIp, byte[] fakeIp,
                   int clientPort, int fakePort, String domain, int realPort, int clientIsn) {
            this.key       = key;
            this.clientIp  = clientIp;
            this.fakeIp    = fakeIp;
            this.clientPort = clientPort;
            this.fakePort  = fakePort;
            this.domain    = domain;
            this.realPort  = realPort;
            this.clientIsn = clientIsn;
            this.clientNextSeq = clientIsn + 1; // SYN 占一个序号
            this.serverIsn = random.nextInt();
            this.serverNextSeq = serverIsn + 1; // SYN-ACK 后 proxy → app 的第一个 seq
        }

        /** 异步建立到真实服务器的连接 */
        void connect() {
            try {
                // 1. 解析真实 IP
                InetAddress fakeIpAddr;
                try {
                    fakeIpAddr = InetAddress.getByAddress(fakeIp);
                } catch (UnknownHostException e) {
                    close(); return;
                }
                InetAddress realIp = fakeIpPool.getRealIp(fakeIpAddr);
                if (realIp == null) {
                    realIp = resolveViaDirect(domain);
                    if (realIp != null) {
                        fakeIpPool.storeRealIp(fakeIpAddr, realIp);
                        if (smartRouter != null) smartRouter.learnFromDirectConnection(domain, realIp);
                    }
                }
                if (realIp == null) {
                    LogUtil.w(TAG, "TCP: 无法解析 " + domain + "，发送 RST");
                    sendRst(clientIp, fakeIp, clientPort, fakePort, serverIsn, clientNextSeq);
                    cleanup(); return;
                }

                // 2. 创建 protected socket
                Socket s = new Socket();
                if (!vpnService.protect(s)) {
                    LogUtil.w(TAG, "TCP: protect() 失败（" + domain + "），发送 RST");
                    try { s.close(); } catch (IOException ignored) {}
                    sendRst(clientIp, fakeIp, clientPort, fakePort, serverIsn, clientNextSeq);
                    cleanup(); return;
                }
                s.setSoTimeout(0); // 无超时，靠 close() 中断阻塞 read
                s.connect(new InetSocketAddress(realIp, realPort), 10_000);
                this.socket = s;
                this.established = true;

                // 3. 发 SYN-ACK 给 app
                sendSynAck(clientIsn);

                // 4. 启动从 server → TUN 的读线程
                executor.submit(this::readFromServer);

            } catch (IOException e) {
                if (!closed) LogUtil.w(TAG, "TCP connect(" + domain + "): " + e.getMessage());
                sendRst(clientIp, fakeIp, clientPort, fakePort, serverIsn, clientNextSeq);
                cleanup();
            }
        }

        void sendSynAck(int clientSynSeq) {
            byte[] pkt = buildTcpPacket(fakeIp, clientIp, fakePort, clientPort,
                    serverIsn, clientSynSeq + 1, TCP_SYN | TCP_ACK, null);
            writeTun(pkt);
        }

        void handleAck(int seq, int ack) {
            // 握手完成的纯 ACK，不需要额外处理
        }

        void handleData(int seq, byte[] payload) {
            if (!established || closed) return;
            Socket s = socket;
            if (s == null || s.isClosed()) return;
            try {
                OutputStream os = s.getOutputStream();
                os.write(payload);
                os.flush();
                clientNextSeq = seq + payload.length;
                // 发 ACK 给 app
                byte[] ack = buildTcpPacket(fakeIp, clientIp, fakePort, clientPort,
                        serverNextSeq, clientNextSeq, TCP_ACK, null);
                writeTun(ack);
            } catch (IOException e) {
                if (!closed) LogUtil.d(TAG, "TCP write to server: " + e.getMessage());
                close();
            }
        }

        void handleFin(int seq) {
            if (closed) return;
            clientNextSeq = seq + 1;
            // 发 FIN+ACK
            byte[] finAck = buildTcpPacket(fakeIp, clientIp, fakePort, clientPort,
                    serverNextSeq, clientNextSeq, TCP_FIN | TCP_ACK, null);
            writeTun(finAck);
            serverNextSeq++;
            close();
        }

        /** 从真实服务器读数据，封装成 TCP 包写回 TUN */
        void readFromServer() {
            Socket s = socket;
            if (s == null) return;
            try {
                InputStream is = s.getInputStream();
                byte[] buf = new byte[4096];
                int n;
                while (!closed && (n = is.read(buf)) != -1) {
                    byte[] payload = new byte[n];
                    System.arraycopy(buf, 0, payload, 0, n);
                    byte[] pkt = buildTcpPacket(fakeIp, clientIp, fakePort, clientPort,
                            serverNextSeq, clientNextSeq, TCP_PSH | TCP_ACK, payload);
                    writeTun(pkt);
                    serverNextSeq += n;
                }
                if (!closed) {
                    // 服务器关闭连接 → 发 FIN
                    byte[] fin = buildTcpPacket(fakeIp, clientIp, fakePort, clientPort,
                            serverNextSeq, clientNextSeq, TCP_FIN | TCP_ACK, null);
                    writeTun(fin);
                    serverNextSeq++;
                }
            } catch (IOException e) {
                if (!closed) LogUtil.d(TAG, "TCP read from server: " + e.getMessage());
            } finally {
                cleanup();
            }
        }

        void close() {
            closed = true;
            Socket s = socket;
            if (s != null && !s.isClosed()) {
                try { s.close(); } catch (IOException ignored) {}
            }
        }

        private void cleanup() {
            close();
            tcpSessions.remove(key);
        }
    }

    // ─────────────────────────── UdpSession ───────────────────────────────────

    private class UdpSession {
        final long key;
        final byte[] clientIp;
        final byte[] fakeIp;
        final int clientPort;
        final int fakePort;
        final InetAddress realIp;
        volatile DatagramSocket socket;
        volatile long lastActivity = System.currentTimeMillis();
        volatile boolean closed = false;

        UdpSession(long key, byte[] clientIp, byte[] fakeIp,
                   int clientPort, int fakePort, InetAddress realIp) {
            this.key = key;
            this.clientIp = clientIp;
            this.fakeIp = fakeIp;
            this.clientPort = clientPort;
            this.fakePort = fakePort;
            this.realIp = realIp;
        }

        void send(byte[] payload) {
            try {
                if (socket == null || socket.isClosed()) {
                    socket = new DatagramSocket();
                    vpnService.protect(socket);
                }
                DatagramPacket dp = new DatagramPacket(payload, payload.length, realIp, fakePort);
                socket.send(dp);
                lastActivity = System.currentTimeMillis();
            } catch (IOException e) {
                LogUtil.d(TAG, "UDP send: " + e.getMessage());
            }
        }

        void startReadLoop() {
            try {
                if (socket == null || socket.isClosed()) return;
                byte[] buf = new byte[4096];
                DatagramPacket dp = new DatagramPacket(buf, buf.length);
                socket.setSoTimeout(5000);
                while (!closed && !socket.isClosed()) {
                    try {
                        socket.receive(dp);
                        lastActivity = System.currentTimeMillis();
                        int len = dp.getLength();
                        byte[] payload = new byte[len];
                        System.arraycopy(buf, 0, payload, 0, len);
                        writeTun(buildUdpPacket(fakeIp, clientIp, fakePort, clientPort, payload));
                    } catch (java.net.SocketTimeoutException ignored) {
                        if (System.currentTimeMillis() - lastActivity > UDP_SESSION_TIMEOUT_MS) break;
                    }
                }
            } catch (IOException e) {
                if (!closed) LogUtil.d(TAG, "UDP read: " + e.getMessage());
            } finally {
                close();
                udpSessions.remove(key);
            }
        }

        void close() {
            closed = true;
            DatagramSocket s = socket;
            if (s != null && !s.isClosed()) s.close();
        }
    }

    // ─────────────────────────── 工具方法 ─────────────────────────────────────

    /**
     * 通过受保护的 UDP socket 向国内 DNS（114.114.114.114）查询域名 A 记录。
     */
    InetAddress resolveViaDirect(String domain) {
        if (domain == null) return null;
        for (String dnsServer : DIRECT_DNS_SERVERS) {
            try {
                InetAddress result = doUdpDnsQuery(domain, dnsServer, 53, RESOLVE_TIMEOUT_MS);
                if (result != null) return result;
            } catch (Exception e) {
                LogUtil.d(TAG, "直连 DNS 查询 " + domain + "@" + dnsServer + " 失败: " + e.getMessage());
            }
        }
        return null;
    }

    private InetAddress doUdpDnsQuery(String domain, String dnsServer, int dnsPort, long timeoutMs)
            throws IOException {
        DatagramSocket sock = new DatagramSocket();
        try {
            if (!vpnService.protect(sock)) return null;
            sock.setSoTimeout((int) timeoutMs);
            byte[] query = buildSimpleDnsQuery(domain);
            InetAddress serverAddr = InetAddress.getByName(dnsServer);
            sock.send(new DatagramPacket(query, query.length, serverAddr, dnsPort));
            byte[] resp = new byte[512];
            DatagramPacket dp = new DatagramPacket(resp, resp.length);
            sock.receive(dp);
            return parseFirstARecord(resp, dp.getLength());
        } finally {
            sock.close();
        }
    }

    /** 构造一个最小的 DNS A 查询包 */
    private byte[] buildSimpleDnsQuery(String domain) {
        // 计算 QNAME 长度
        String[] labels = domain.split("\\.");
        int qnameLen = 1; // 末尾的 0 字节
        for (String lbl : labels) qnameLen += 1 + lbl.length();
        // DNS header(12) + QNAME + QTYPE(2) + QCLASS(2)
        byte[] pkt = new byte[12 + qnameLen + 4];
        // header
        pkt[0] = 0; pkt[1] = 1;   // transaction ID = 1
        pkt[2] = 1; pkt[3] = 0;   // flags: RD=1
        pkt[4] = 0; pkt[5] = 1;   // QDCOUNT = 1
        int off = 12;
        for (String lbl : labels) {
            pkt[off++] = (byte) lbl.length();
            for (char c : lbl.toCharArray()) pkt[off++] = (byte) c;
        }
        pkt[off++] = 0;    // QNAME terminator
        pkt[off++] = 0; pkt[off++] = 1; // QTYPE = A
        pkt[off++] = 0; pkt[off]   = 1; // QCLASS = IN
        return pkt;
    }

    /** 从 DNS 响应中提取第一条 A 记录 */
    private InetAddress parseFirstARecord(byte[] resp, int len) {
        if (len < 12) return null;
        int anCount = ((resp[6] & 0xFF) << 8) | (resp[7] & 0xFF);
        if (anCount == 0) return null;
        int qdCount = ((resp[4] & 0xFF) << 8) | (resp[5] & 0xFF);
        int off = 12;
        // 跳过问题区域
        for (int i = 0; i < qdCount && off < len; i++) {
            while (off < len) {
                int llen = resp[off] & 0xFF;
                if (llen == 0) { off++; break; }
                if ((llen & 0xC0) == 0xC0) { off += 2; break; }
                off += 1 + llen;
            }
            off += 4; // QTYPE + QCLASS
        }
        // 遍历回答区域
        for (int i = 0; i < anCount && off < len; i++) {
            // 跳过名称
            while (off < len) {
                int llen = resp[off] & 0xFF;
                if (llen == 0) { off++; break; }
                if ((llen & 0xC0) == 0xC0) { off += 2; break; }
                off += 1 + llen;
            }
            if (off + 10 > len) break;
            int type = ((resp[off] & 0xFF) << 8) | (resp[off + 1] & 0xFF);
            int rdlen = ((resp[off + 8] & 0xFF) << 8) | (resp[off + 9] & 0xFF);
            off += 10;
            if (off + rdlen > len) break;
            if (type == 1 && rdlen == 4) {
                byte[] addr = new byte[4];
                System.arraycopy(resp, off, addr, 0, 4);
                try { return InetAddress.getByAddress(addr); } catch (UnknownHostException ignored) {}
            }
            off += rdlen;
        }
        return null;
    }

    private void sendRst(byte[] srcIp, byte[] dstIp, int srcPort, int dstPort, int seq, int ack) {
        byte[] pkt = buildTcpPacket(srcIp, dstIp, srcPort, dstPort, seq, ack, TCP_RST | TCP_ACK, null);
        writeTun(pkt);
    }

    private void writeTun(byte[] pkt) {
        if (pkt == null || stopped) return;
        FileOutputStream out = tunOut;
        if (out == null) return;
        try {
            synchronized (tunWriteLock) {
                out.write(pkt);
            }
        } catch (IOException e) {
            LogUtil.d(TAG, "writeTun: " + e.getMessage());
        }
    }

    private void purgeStaleUdpSessions() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<Long, UdpSession>> it = udpSessions.entrySet().iterator();
        while (it.hasNext()) {
            UdpSession s = it.next().getValue();
            if (now - s.lastActivity > UDP_SESSION_TIMEOUT_MS) {
                s.close();
                it.remove();
            }
        }
    }

    // ─────────────────────────── 数据包构造 ───────────────────────────────────

    /**
     * 构造一个 IPv4/TCP 数据包（20 字节 IP 头 + 20 字节 TCP 头 + payload）。
     *
     * @param srcIp   源 IPv4 地址（4 字节）
     * @param dstIp   目的 IPv4 地址（4 字节）
     * @param srcPort 源端口
     * @param dstPort 目的端口
     * @param seq     TCP 序列号
     * @param ack     TCP 确认号
     * @param flags   TCP 标志（如 {@code TCP_SYN | TCP_ACK}）
     * @param payload TCP payload（null 表示无数据）
     */
    static byte[] buildTcpPacket(byte[] srcIp, byte[] dstIp, int srcPort, int dstPort,
                                  int seq, int ack, int flags, byte[] payload) {
        int payloadLen = (payload == null) ? 0 : payload.length;
        int tcpLen = 20 + payloadLen;
        int totalLen = 20 + tcpLen;
        byte[] pkt = new byte[totalLen];

        // ── IPv4 header ──
        pkt[0] = 0x45;  // version=4, IHL=5
        pkt[1] = 0;
        pkt[2] = (byte)(totalLen >> 8);
        pkt[3] = (byte)(totalLen);
        pkt[4] = 0; pkt[5] = 0;  // identification
        pkt[6] = 0x40; pkt[7] = 0; // DF, no fragment offset
        pkt[8] = 64;    // TTL
        pkt[9] = 6;     // protocol = TCP
        // checksum at [10-11], filled below
        System.arraycopy(srcIp, 0, pkt, 12, 4);
        System.arraycopy(dstIp, 0, pkt, 16, 4);

        // ── TCP header ──
        int t = 20;
        pkt[t]   = (byte)(srcPort >> 8);
        pkt[t+1] = (byte)(srcPort);
        pkt[t+2] = (byte)(dstPort >> 8);
        pkt[t+3] = (byte)(dstPort);
        pkt[t+4] = (byte)(seq >> 24);
        pkt[t+5] = (byte)(seq >> 16);
        pkt[t+6] = (byte)(seq >> 8);
        pkt[t+7] = (byte)(seq);
        pkt[t+8]  = (byte)(ack >> 24);
        pkt[t+9]  = (byte)(ack >> 16);
        pkt[t+10] = (byte)(ack >> 8);
        pkt[t+11] = (byte)(ack);
        pkt[t+12] = 0x50;        // data offset = 5 (20 bytes), reserved = 0
        pkt[t+13] = (byte)(flags);
        pkt[t+14] = (byte)0xFF;  // window size high
        pkt[t+15] = (byte)0xFF;  // window size low = 65535
        // checksum [t+16, t+17] filled below
        pkt[t+18] = 0; pkt[t+19] = 0; // urgent pointer

        if (payloadLen > 0) {
            System.arraycopy(payload, 0, pkt, 40, payloadLen);
        }

        // IP checksum
        int ipCs = oneComplementSum(pkt, 0, 20);
        pkt[10] = (byte)(ipCs >> 8);
        pkt[11] = (byte)(ipCs);

        // TCP pseudo-header checksum: srcIP(4) + dstIP(4) + 0(1) + 6(1) + tcpLen(2) + TCP header + data
        long tcpCs = 0;
        // pseudo-header
        for (int i = 0; i < 4; i++) tcpCs += (srcIp[i] & 0xFF) << (8 * ((i & 1) == 0 ? 1 : 0)); // interleaved
        tcpCs = pseudoAndTcpChecksum(srcIp, dstIp, pkt, 20, tcpLen);
        pkt[t+16] = (byte)(tcpCs >> 8);
        pkt[t+17] = (byte)(tcpCs);

        return pkt;
    }

    /** 构造 IPv4/UDP 数据包 */
    static byte[] buildUdpPacket(byte[] srcIp, byte[] dstIp, int srcPort, int dstPort, byte[] payload) {
        int payloadLen = payload.length;
        int udpLen = 8 + payloadLen;
        int totalLen = 20 + udpLen;
        byte[] pkt = new byte[totalLen];

        pkt[0] = 0x45; pkt[1] = 0;
        pkt[2] = (byte)(totalLen >> 8); pkt[3] = (byte)(totalLen);
        pkt[4] = 0; pkt[5] = 0;
        pkt[6] = 0x40; pkt[7] = 0;
        pkt[8] = 64; pkt[9] = 17; // UDP
        System.arraycopy(srcIp, 0, pkt, 12, 4);
        System.arraycopy(dstIp, 0, pkt, 16, 4);

        pkt[20] = (byte)(srcPort >> 8); pkt[21] = (byte)(srcPort);
        pkt[22] = (byte)(dstPort >> 8); pkt[23] = (byte)(dstPort);
        pkt[24] = (byte)(udpLen >> 8);  pkt[25] = (byte)(udpLen);
        pkt[26] = 0; pkt[27] = 0; // UDP checksum = 0 (optional)
        System.arraycopy(payload, 0, pkt, 28, payloadLen);

        int ipCs = oneComplementSum(pkt, 0, 20);
        pkt[10] = (byte)(ipCs >> 8); pkt[11] = (byte)(ipCs);

        return pkt;
    }

    /** 计算反码和（IP/TCP checksum 通用） */
    private static int oneComplementSum(byte[] buf, int off, int len) {
        long sum = 0;
        int i = off;
        for (; i < off + len - 1; i += 2) {
            sum += ((buf[i] & 0xFF) << 8) | (buf[i + 1] & 0xFF);
        }
        if (i < off + len) sum += (buf[i] & 0xFF) << 8; // 奇数长度填充
        while ((sum >> 16) != 0) sum = (sum & 0xFFFF) + (sum >> 16);
        return (int)(~sum & 0xFFFF);
    }

    /** 计算 TCP/UDP 检验和（含伪头部） */
    private static int pseudoAndTcpChecksum(byte[] srcIp, byte[] dstIp, byte[] pkt, int tcpOff, int tcpLen) {
        long sum = 0;
        // 伪头部
        for (int i = 0; i < 4; i += 2) sum += ((srcIp[i] & 0xFF) << 8) | (srcIp[i + 1] & 0xFF);
        for (int i = 0; i < 4; i += 2) sum += ((dstIp[i] & 0xFF) << 8) | (dstIp[i + 1] & 0xFF);
        sum += 6; // protocol = TCP
        sum += tcpLen;
        // TCP header + data
        for (int i = tcpOff; i < tcpOff + tcpLen - 1; i += 2) {
            sum += ((pkt[i] & 0xFF) << 8) | (pkt[i + 1] & 0xFF);
        }
        if ((tcpLen & 1) != 0) sum += (pkt[tcpOff + tcpLen - 1] & 0xFF) << 8;
        while ((sum >> 16) != 0) sum = (sum & 0xFFFF) + (sum >> 16);
        return (int)(~sum & 0xFFFF);
    }

    private static int bytesToInt(byte[] b) {
        return ((b[0] & 0xFF) << 24) | ((b[1] & 0xFF) << 16)
             | ((b[2] & 0xFF) << 8)  |  (b[3] & 0xFF);
    }

    private static long makeKey(byte[] srcIp, int srcPort, byte[] dstIp, int dstPort) {
        long src = ((long) bytesToInt(srcIp) & 0xFFFFFFFFL);
        long dst = ((long) bytesToInt(dstIp) & 0xFFFFFFFFL);
        return (src ^ (dst << 16)) ^ ((long) srcPort << 48) ^ ((long) dstPort << 32);
    }
}
