package net.kaaass.zerotierfix.util.smartroute;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 表示一个 IPv4 CIDR 地址块
 */
public class CidrBlock implements Comparable<CidrBlock> {
    public final int startIp; // big-endian int
    public final int prefixLen;
    public final int endIp;   // inclusive

    public CidrBlock(int startIp, int prefixLen) {
        this.startIp = startIp;
        this.prefixLen = prefixLen;
        if (prefixLen == 0) {
            // 0.0.0.0/0: covers the entire address space
            // We use the mask approach but avoid the Java shift-by-32 wrap-around:
            // mask = all zeros when prefix=0 (shift by 32 wraps to 0 in Java)
            // So we set endIp explicitly for this edge case.
            this.endIp = (int) 0xFFFFFFFFL; // = -1 signed, = 4294967295 unsigned
        } else {
            int mask = 0xFFFFFFFF << (32 - prefixLen);
            this.endIp = (startIp & mask) | (~mask);
        }
    }

    /**
     * 从 CIDR 字符串（如 "1.2.3.0/24"）解析
     */
    public static CidrBlock parse(String cidr) {
        cidr = cidr.trim();
        int slash = cidr.indexOf('/');
        if (slash < 0) return null;
        String ipStr = cidr.substring(0, slash);
        int prefix;
        try {
            prefix = Integer.parseInt(cidr.substring(slash + 1));
        } catch (NumberFormatException e) {
            return null;
        }
        int ip = ipToInt(ipStr);
        if (ip == Integer.MIN_VALUE) return null;
        // Align start to prefix boundary
        int mask = prefix == 0 ? 0 : (0xFFFFFFFF << (32 - prefix));
        return new CidrBlock(ip & mask, prefix);
    }

    /**
     * 将 IP 字符串转换为 int
     */
    public static int ipToInt(String ip) {
        String[] parts = ip.split("\\.");
        if (parts.length != 4) return Integer.MIN_VALUE;
        try {
            int result = 0;
            for (String part : parts) {
                result = (result << 8) | (Integer.parseInt(part) & 0xFF);
            }
            return result;
        } catch (NumberFormatException e) {
            return Integer.MIN_VALUE;
        }
    }

    /**
     * 检查 InetAddress 是否在此 CIDR 块内
     */
    public boolean contains(InetAddress address) {
        byte[] bytes = address.getAddress();
        if (bytes.length != 4) return false;
        int ip = ((bytes[0] & 0xFF) << 24) | ((bytes[1] & 0xFF) << 16)
                | ((bytes[2] & 0xFF) << 8) | (bytes[3] & 0xFF);
        // Unsigned comparison using long
        long ipL = ip & 0xFFFFFFFFL;
        long startL = startIp & 0xFFFFFFFFL;
        long endL = endIp & 0xFFFFFFFFL;
        return ipL >= startL && ipL <= endL;
    }

    @Override
    public int compareTo(CidrBlock other) {
        long a = this.startIp & 0xFFFFFFFFL;
        long b = other.startIp & 0xFFFFFFFFL;
        return Long.compare(a, b);
    }

    /**
     * CIDR 聚合：将相邻、重叠或连续的 CIDR 合并为最小等价集合，不产生过度近似。
     *
     * <p>算法：
     * <ol>
     *   <li>将每个 CIDR 转换为 [start, end] 整数区间</li>
     *   <li>按 start 排序后合并重叠/相邻区间</li>
     *   <li>用 {@link #rangeToCidrs} 将每段合并后的区间转换回最优 CIDR 表示</li>
     * </ol>
     *
     * <p>示例：{1.0.0.0/24, 1.0.1.0/24} → 两个区间相邻 → 合并为 1.0.0.0/23（1 条）。
     * 对于中国 chnroutes 数据，典型可减少 30-50% 条目数，显著降低 VPN 路由 parcel 大小。
     *
     * @param cidrs 待聚合的 CIDR 列表（无需预排序，不要求不重叠）
     * @return 等价覆盖的最小 CIDR 集合，按 startIp 排序
     */
    public static List<CidrBlock> aggregate(List<CidrBlock> cidrs) {
        if (cidrs == null || cidrs.isEmpty()) return Collections.emptyList();

        // 1. 转为 [start, end] 区间
        List<long[]> ranges = new ArrayList<>(cidrs.size());
        for (CidrBlock c : cidrs) {
            ranges.add(new long[]{c.startIp & 0xFFFFFFFFL, c.endIp & 0xFFFFFFFFL});
        }

        // 2. 排序
        ranges.sort((a, b) -> Long.compare(a[0], b[0]));

        // 3. 合并相邻/重叠区间（条件：next.start <= cur.end + 1）
        List<long[]> merged = new ArrayList<>();
        long[] cur = new long[]{ranges.get(0)[0], ranges.get(0)[1]};
        for (int i = 1; i < ranges.size(); i++) {
            long[] next = ranges.get(i);
            if (next[0] <= cur[1] + 1) {
                if (next[1] > cur[1]) cur[1] = next[1];
            } else {
                merged.add(cur);
                cur = new long[]{next[0], next[1]};
            }
        }
        merged.add(cur);

        // 4. 将每段区间转回最优 CIDR
        List<CidrBlock> result = new ArrayList<>();
        for (long[] range : merged) {
            result.addAll(rangeToCidrs(range[0], range[1]));
        }
        return result;
    }

    /**
     * 计算补集：所有 IPv4 地址空间 (0.0.0.0/0) 减去给定的 CIDR 列表
     * 返回一组不重叠的 CIDR 块，代表非指定区域的地址空间
     */
    public static List<CidrBlock> computeComplement(List<CidrBlock> blocks) {
        if (blocks == null || blocks.isEmpty()) {
            List<CidrBlock> full = new ArrayList<>();
            full.add(new CidrBlock(0, 0)); // 0.0.0.0/0
            return full;
        }

        // 1. 合并并排序所有 CIDR 块为 [start, end] 范围
        List<long[]> ranges = new ArrayList<>();
        for (CidrBlock b : blocks) {
            ranges.add(new long[]{b.startIp & 0xFFFFFFFFL, b.endIp & 0xFFFFFFFFL});
        }
        Collections.sort(ranges, (a, b) -> Long.compare(a[0], b[0]));

        // 2. 合并重叠/相邻范围
        List<long[]> merged = new ArrayList<>();
        long[] cur = ranges.get(0);
        for (int i = 1; i < ranges.size(); i++) {
            long[] next = ranges.get(i);
            if (next[0] <= cur[1] + 1) {
                cur[1] = Math.max(cur[1], next[1]);
            } else {
                merged.add(cur);
                cur = next;
            }
        }
        merged.add(cur);

        // 3. 计算补集间隙
        List<long[]> gaps = new ArrayList<>();
        long prev = 0;
        for (long[] range : merged) {
            if (range[0] > prev) {
                gaps.add(new long[]{prev, range[0] - 1});
            }
            prev = range[1] + 1;
        }
        if (prev <= 0xFFFFFFFFL) {
            gaps.add(new long[]{prev, 0xFFFFFFFFL});
        }

        // 4. 将每个间隙拆分为 CIDR 块
        List<CidrBlock> result = new ArrayList<>();
        for (long[] gap : gaps) {
            result.addAll(rangeToCidrs(gap[0], gap[1]));
        }
        return result;
    }

    /**
     * 将 [start, end] 范围拆分为最优 CIDR 列表
     */
    private static List<CidrBlock> rangeToCidrs(long start, long end) {
        List<CidrBlock> result = new ArrayList<>();
        while (start <= end) {
            // 找到从 start 开始的最大对齐块
            int maxBits = 32;
            long s = start;
            // 找到 start 末尾连续 0 的数量
            for (int i = 0; i < 32; i++) {
                if ((s & 1) == 0) {
                    maxBits--;
                } else {
                    break;
                }
                s >>= 1;
            }
            // 确保块不超出 end
            int prefix = maxBits;
            while (prefix <= 32) {
                long blockEnd = start + (1L << (32 - prefix)) - 1;
                if (blockEnd <= end) break;
                prefix++;
            }
            if (prefix > 32) prefix = 32;
            result.add(new CidrBlock((int) start, prefix));
            start += (1L << (32 - prefix));
            if (start > 0xFFFFFFFFL) break;
        }
        return result;
    }

    /**
     * 将此 CIDR 块转换为 InetAddress（网络地址部分）
     */
    public InetAddress toInetAddress() {
        try {
            byte[] bytes = new byte[]{
                (byte) (startIp >> 24),
                (byte) (startIp >> 16),
                (byte) (startIp >> 8),
                (byte) startIp
            };
            return InetAddress.getByAddress(bytes);
        } catch (UnknownHostException e) {
            return null;
        }
    }

    @Override
    public String toString() {
        byte[] b = new byte[]{
            (byte) (startIp >> 24),
            (byte) (startIp >> 16),
            (byte) (startIp >> 8),
            (byte) startIp
        };
        return (b[0] & 0xFF) + "." + (b[1] & 0xFF) + "." + (b[2] & 0xFF) + "." + (b[3] & 0xFF) + "/" + prefixLen;
    }
}
