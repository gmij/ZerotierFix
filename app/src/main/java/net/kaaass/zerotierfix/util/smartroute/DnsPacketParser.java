package net.kaaass.zerotierfix.util.smartroute;

import net.kaaass.zerotierfix.util.LogUtil;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
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

    /**
     * 尝试从 IPv4 UDP 数据包中解析 DNS 响应
     *
     * @param ipPacket 完整的 IPv4 数据包字节数组
     * @return 解析出的 DNS A/AAAA 记录列表，解析失败则返回空列表
     */
    public static List<DnsRecord> parseFromIpPacket(byte[] ipPacket) {
        List<DnsRecord> records = new ArrayList<>();
        try {
            if (ipPacket.length < 28) return records; // IPv4 header(20) + UDP header(8)
            // 检查是否是 UDP 协议 (protocol = 17)
            if ((ipPacket[9] & 0xFF) != 17) return records;
            // IPv4 头部长度
            int ipHeaderLen = (ipPacket[0] & 0x0F) * 4;
            if (ipPacket.length < ipHeaderLen + 8) return records;
            // UDP 源端口（应该是 53）
            int srcPort = ((ipPacket[ipHeaderLen] & 0xFF) << 8) | (ipPacket[ipHeaderLen + 1] & 0xFF);
            if (srcPort != 53) return records;
            // UDP 载荷
            int udpPayloadOffset = ipHeaderLen + 8;
            int udpPayloadLen = ipPacket.length - udpPayloadOffset;
            if (udpPayloadLen < 12) return records;
            byte[] dns = new byte[udpPayloadLen];
            System.arraycopy(ipPacket, udpPayloadOffset, dns, 0, udpPayloadLen);
            return parseDnsResponse(dns);
        } catch (Exception e) {
            LogUtil.d(TAG, "Failed to parse DNS packet: " + e.getMessage());
            return records;
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
