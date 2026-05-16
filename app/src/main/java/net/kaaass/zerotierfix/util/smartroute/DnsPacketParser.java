package net.kaaass.zerotierfix.util.smartroute;

import net.kaaass.zerotierfix.util.LogUtil;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 最小化 DNS 响应包解析器
 * 用于从 DNS 响应中提取 域名 → IP 映射（DNS 嗅探）
 */
public class DnsPacketParser {
    private static final String TAG = "DnsPacketParser";

    /**
     * 代表一条解析出的 DNS A/AAAA 记录
     */
    public static class DnsRecord {
        public final String domain;
        public final InetAddress ip;

        public DnsRecord(String domain, InetAddress ip) {
            this.domain = domain;
            this.ip = ip;
        }
    }

    /** 空结果常量，避免在非 DNS 包（如 TCP 视频流）的热路径上重复分配 ArrayList 对象 */
    private static final List<DnsRecord> EMPTY = Collections.emptyList();

    /**
     * 尝试从 IPv4 UDP 数据包中解析 DNS 响应
     *
     * @param ipPacket 完整的 IPv4 数据包字节数组
     * @return 解析出的 DNS A/AAAA 记录列表，解析失败则返回空列表
     */
    public static List<DnsRecord> parseFromIpPacket(byte[] ipPacket) {
        try {
            if (ipPacket.length < 28) return EMPTY; // IPv4 header(20) + UDP header(8)
            // 检查是否是 UDP 协议 (protocol = 17)
            if ((ipPacket[9] & 0xFF) != 17) return EMPTY;
            // IPv4 头部长度
            int ipHeaderLen = (ipPacket[0] & 0x0F) * 4;
            if (ipPacket.length < ipHeaderLen + 8) return EMPTY;
            // UDP 源端口（应该是 53）
            int srcPort = ((ipPacket[ipHeaderLen] & 0xFF) << 8) | (ipPacket[ipHeaderLen + 1] & 0xFF);
            if (srcPort != 53) return EMPTY;
            // UDP 载荷
            int udpPayloadOffset = ipHeaderLen + 8;
            int udpPayloadLen = ipPacket.length - udpPayloadOffset;
            if (udpPayloadLen < 12) return EMPTY;
            byte[] dns = new byte[udpPayloadLen];
            System.arraycopy(ipPacket, udpPayloadOffset, dns, 0, udpPayloadLen);
            return parseDnsResponse(dns);
        } catch (Exception e) {
            LogUtil.d(TAG, "Failed to parse DNS packet: " + e.getMessage());
            return EMPTY;
        }
    }

    /**
     * 解析 DNS 响应包
     */
    private static List<DnsRecord> parseDnsResponse(byte[] dns) {
        List<DnsRecord> records = new ArrayList<>();
        if (dns.length < 12) return records;
        // DNS 头部
        int flags = ((dns[2] & 0xFF) << 8) | (dns[3] & 0xFF);
        // QR 位：1 = 响应
        if ((flags & 0x8000) == 0) return records;
        // RCODE：0 = 无错误
        if ((flags & 0x000F) != 0) return records;

        int qdCount = ((dns[4] & 0xFF) << 8) | (dns[5] & 0xFF);
        int anCount = ((dns[6] & 0xFF) << 8) | (dns[7] & 0xFF);
        if (anCount == 0) return records;

        int offset = 12;
        // 跳过问题区域
        for (int i = 0; i < qdCount && offset < dns.length; i++) {
            int[] result = skipName(dns, offset);
            offset = result[0];
            offset += 4; // QTYPE + QCLASS
        }

        // 解析回答区域
        for (int i = 0; i < anCount && offset < dns.length; i++) {
            // 解析名称（用于日志，但实际我们从问题区域已知查询域名）
            String name = readName(dns, offset);
            int[] skipResult = skipName(dns, offset);
            offset = skipResult[0];
            if (offset + 10 > dns.length) break;

            int type = ((dns[offset] & 0xFF) << 8) | (dns[offset + 1] & 0xFF);
            // int cls = ((dns[offset + 2] & 0xFF) << 8) | (dns[offset + 3] & 0xFF);
            int rdLength = ((dns[offset + 8] & 0xFF) << 8) | (dns[offset + 9] & 0xFF);
            offset += 10;

            if (offset + rdLength > dns.length) break;

            if (type == 1 && rdLength == 4) {
                // A 记录
                byte[] addr = new byte[4];
                System.arraycopy(dns, offset, addr, 0, 4);
                try {
                    InetAddress ip = InetAddress.getByAddress(addr);
                    if (name != null) {
                        records.add(new DnsRecord(name.toLowerCase(), ip));
                    }
                } catch (UnknownHostException ignored) {
                }
            } else if (type == 28 && rdLength == 16) {
                // AAAA 记录
                byte[] addr = new byte[16];
                System.arraycopy(dns, offset, addr, 0, 16);
                try {
                    InetAddress ip = InetAddress.getByAddress(addr);
                    if (name != null) {
                        records.add(new DnsRecord(name.toLowerCase(), ip));
                    }
                } catch (UnknownHostException ignored) {
                }
            }
            offset += rdLength;
        }
        return records;
    }

    // ─────────────────────── DNS 查询拦截（Fake-IP 模式）────────────────────────

    /**
     * 尝试从 IPv4 UDP 数据包中解析出向外发出的 DNS 查询的域名。
     *
     * @param ipPacket 完整 IPv4 数据包（发往 DNS 服务器，dst port == 53）
     * @return 查询的第一个域名（小写）；非 DNS 查询或解析失败则返回 null
     */
    public static String parseQueryDomain(byte[] ipPacket) {
        try {
            if (ipPacket.length < 28) return null;
            if ((ipPacket[9] & 0xFF) != 17) return null;  // UDP only
            int ipHeaderLen = (ipPacket[0] & 0x0F) * 4;
            if (ipPacket.length < ipHeaderLen + 8) return null;
            // 目的端口 == 53
            int dstPort = ((ipPacket[ipHeaderLen + 2] & 0xFF) << 8) | (ipPacket[ipHeaderLen + 3] & 0xFF);
            if (dstPort != 53) return null;
            int udpPayloadOffset = ipHeaderLen + 8;
            int udpPayloadLen = ipPacket.length - udpPayloadOffset;
            if (udpPayloadLen < 12) return null;
            byte[] dns = new byte[udpPayloadLen];
            System.arraycopy(ipPacket, udpPayloadOffset, dns, 0, udpPayloadLen);
            // QR bit == 0 → 这是一个查询
            int flags = ((dns[2] & 0xFF) << 8) | (dns[3] & 0xFF);
            if ((flags & 0x8000) != 0) return null; // 响应包，不处理
            int qdCount = ((dns[4] & 0xFF) << 8) | (dns[5] & 0xFF);
            if (qdCount == 0) return null;
            // 读取问题区域第一个名称
            String name = readName(dns, 12);
            return (name != null) ? name.toLowerCase() : null;
        } catch (Exception e) {
            LogUtil.d(TAG, "parseQueryDomain failed: " + e.getMessage());
            return null;
        }
    }

    /**
     * 将一个 DNS 查询包改写为包含 fake IP A 记录的 DNS 响应包（完整 IPv4/UDP/DNS）。
     *
     * <p>响应复用原查询的 transaction ID，同时将源/目 IP 和端口对调，
     * 写入一条 A 记录（TTL=1s）指向 {@code fakeIp}。
     *
     * @param queryIpPacket 原始 DNS 查询 IPv4 数据包
     * @param fakeIp        要在响应中注入的 fake IP（必须是 IPv4）
     * @return 构造好的 DNS 响应 IPv4 数据包；若输入不合法则返回 null
     */
    public static byte[] buildFakeResponse(byte[] queryIpPacket, java.net.InetAddress fakeIp) {
        try {
            if (queryIpPacket.length < 28) return null;
            if (fakeIp == null) return null;
            byte[] fakeAddr = fakeIp.getAddress();
            if (fakeAddr.length != 4) return null;

            int ipHeaderLen = (queryIpPacket[0] & 0x0F) * 4;
            // 原查询的 src/dst IP（用于对调）
            byte[] origSrcIp = new byte[4];
            byte[] origDstIp = new byte[4];
            System.arraycopy(queryIpPacket, 12, origSrcIp, 0, 4);
            System.arraycopy(queryIpPacket, 16, origDstIp, 0, 4);
            // 原查询的 src/dst port（UDP）
            int origSrcPort = ((queryIpPacket[ipHeaderLen] & 0xFF) << 8) | (queryIpPacket[ipHeaderLen + 1] & 0xFF);
            int origDstPort = ((queryIpPacket[ipHeaderLen + 2] & 0xFF) << 8) | (queryIpPacket[ipHeaderLen + 3] & 0xFF);

            // 原始 DNS 查询载荷
            int udpPayloadOffset = ipHeaderLen + 8;
            int udpPayloadLen = queryIpPacket.length - udpPayloadOffset;
            if (udpPayloadLen < 12) return null;
            byte[] queryDns = new byte[udpPayloadLen];
            System.arraycopy(queryIpPacket, udpPayloadOffset, queryDns, 0, udpPayloadLen);

            // ── 构造 DNS 响应载荷 ──────────────────────────────────────────
            // 响应 = 原问题区域 + 一条 A 记录回答（压缩名称 + type + class + ttl + rdlength + rdata）
            // 压缩指针：0xC0 0x0C（指向 offset 12 = 问题区域名称）
            // A record additional bytes: 2(ptr)+2(type)+2(class)+4(ttl)+2(rdlen)+4(rdata) = 16
            int dnsRespLen = udpPayloadLen + 16;
            byte[] dnsResp = new byte[dnsRespLen];
            System.arraycopy(queryDns, 0, dnsResp, 0, udpPayloadLen);
            // 设置 DNS 响应头标志：QR=1, AA=1, RD=1, RA=1, RCODE=0
            dnsResp[2] = (byte) 0x81;
            dnsResp[3] = (byte) 0x80;
            // ANCOUNT = 1
            dnsResp[6] = 0;
            dnsResp[7] = 1;
            // 回答区域
            int ans = udpPayloadLen;
            dnsResp[ans]   = (byte) 0xC0; // 压缩指针高字节
            dnsResp[ans+1] = 0x0C;        // 指向 offset 12（问题区域名称）
            dnsResp[ans+2] = 0; dnsResp[ans+3] = 1;  // TYPE A
            dnsResp[ans+4] = 0; dnsResp[ans+5] = 1;  // CLASS IN
            dnsResp[ans+6] = 0; dnsResp[ans+7] = 0;  // TTL high
            dnsResp[ans+8] = 0; dnsResp[ans+9] = 1;  // TTL low = 1s
            dnsResp[ans+10] = 0; dnsResp[ans+11] = 4; // RDLENGTH = 4
            System.arraycopy(fakeAddr, 0, dnsResp, ans + 12, 4); // RDATA = fake IP

            // ── 构造 UDP 头部 ──────────────────────────────────────────────
            int udpLen = 8 + dnsRespLen;
            byte[] udp = new byte[udpLen];
            udp[0] = (byte) (origDstPort >> 8); // src = 53
            udp[1] = (byte) (origDstPort);
            udp[2] = (byte) (origSrcPort >> 8); // dst = client port
            udp[3] = (byte) (origSrcPort);
            udp[4] = (byte) (udpLen >> 8);
            udp[5] = (byte) (udpLen);
            udp[6] = 0; udp[7] = 0; // checksum = 0（optional for UDP over IPv4）
            System.arraycopy(dnsResp, 0, udp, 8, dnsRespLen);

            // ── 构造 IPv4 头部 ────────────────────────────────────────────
            int totalLen = 20 + udpLen;
            byte[] pkt = new byte[totalLen];
            pkt[0] = 0x45; // version=4, IHL=5
            pkt[1] = 0;    // DSCP/ECN
            pkt[2] = (byte) (totalLen >> 8);
            pkt[3] = (byte) (totalLen);
            pkt[4] = 0; pkt[5] = 0;   // ID
            pkt[6] = 0x40; pkt[7] = 0; // Don't Fragment
            pkt[8] = 64;  // TTL
            pkt[9] = 17;  // UDP
            // checksum at [10-11] computed below
            System.arraycopy(origDstIp, 0, pkt, 12, 4); // src = original DNS server
            System.arraycopy(origSrcIp, 0, pkt, 16, 4); // dst = original client
            System.arraycopy(udp, 0, pkt, 20, udpLen);
            // IP checksum
            int ipCsum = ipChecksum(pkt, 0, 20);
            pkt[10] = (byte) (ipCsum >> 8);
            pkt[11] = (byte) (ipCsum);
            return pkt;
        } catch (Exception e) {
            LogUtil.d(TAG, "buildFakeResponse failed: " + e.getMessage());
            return null;
        }
    }

    /** 计算 IPv4 头部检验和（不含 checksum 字段本身） */
    static int ipChecksum(byte[] buf, int off, int len) {
        long sum = 0;
        for (int i = off; i < off + len - 1; i += 2) {
            sum += ((buf[i] & 0xFF) << 8) | (buf[i + 1] & 0xFF);
        }
        if ((len & 1) != 0) sum += (buf[off + len - 1] & 0xFF) << 8;
        while ((sum >> 16) != 0) sum = (sum & 0xFFFF) + (sum >> 16);
        return (int)(~sum & 0xFFFF);
    }

    // ─────────────────────────────────────────────────────────────────

    /**
     * 从 DNS 包中读取域名（支持指针压缩）
     */
    private static String readName(byte[] dns, int offset) {
        StringBuilder sb = new StringBuilder();
        int visited = 0;
        while (offset < dns.length) {
            int len = dns[offset] & 0xFF;
            if (len == 0) break;
            if ((len & 0xC0) == 0xC0) {
                // 指针压缩
                if (offset + 1 >= dns.length) break;
                int ptr = ((len & 0x3F) << 8) | (dns[offset + 1] & 0xFF);
                if (visited++ > 10) break; // 防止循环
                offset = ptr;
                continue;
            }
            offset++;
            if (offset + len > dns.length) break;
            if (sb.length() > 0) sb.append('.');
            sb.append(new String(dns, offset, len, java.nio.charset.StandardCharsets.US_ASCII));
            offset += len;
        }
        return sb.length() > 0 ? sb.toString() : null;
    }

    /**
     * 跳过 DNS 域名，返回名称后的下一个偏移量
     */
    private static int[] skipName(byte[] dns, int offset) {
        while (offset < dns.length) {
            int len = dns[offset] & 0xFF;
            if (len == 0) {
                offset++;
                break;
            }
            if ((len & 0xC0) == 0xC0) {
                offset += 2;
                break;
            }
            offset += 1 + len;
        }
        return new int[]{offset};
    }
}
