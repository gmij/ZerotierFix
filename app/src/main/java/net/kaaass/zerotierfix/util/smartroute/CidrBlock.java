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

        // 3. 合并相邻/重叠区间（条件：next.start <= cur.end + 1，注意 0xFFFFFFFF 溢出边界）
        List<long[]> merged = new ArrayList<>();
        long[] cur = new long[]{ranges.get(0)[0], ranges.get(0)[1]};
        for (int i = 1; i < ranges.size(); i++) {
            long[] next = ranges.get(i);
            // cur[1] < 0xFFFFFFFFL 时正常比较；cur[1] == 0xFFFFFFFF 时已覆盖全部地址空间，无需再合并
            boolean adjacent = cur[1] < 0xFFFFFFFFL && next[0] <= cur[1] + 1;
            if (adjacent) {
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
     * 计算差集：{@code minuend - subtrahend}。
     *
     * <p>典型用途：在 CHINA_DIRECT 的粗粒度基础骨架上，减去少量 learned VIA_ZT 例外，
     * 让原本命中中国路由的大热点 IP 重新回到 ZeroTier。</p>
     */
    public static List<CidrBlock> subtract(List<CidrBlock> minuend, List<CidrBlock> subtrahend) {
        if (minuend == null || minuend.isEmpty()) return Collections.emptyList();
        if (subtrahend == null || subtrahend.isEmpty()) return aggregate(minuend);

        List<long[]> baseRanges = toMergedRanges(minuend);
        List<long[]> removedRanges = toMergedRanges(subtrahend);
        List<long[]> keptRanges = new ArrayList<>();
        int removeIdx = 0;

        for (long[] base : baseRanges) {
            long cursor = base[0];
            while (removeIdx < removedRanges.size() && removedRanges.get(removeIdx)[1] < cursor) {
                removeIdx++;
            }

            int probeIdx = removeIdx;
            while (probeIdx < removedRanges.size()) {
                long[] removed = removedRanges.get(probeIdx);
                if (removed[0] > base[1]) break;
                if (removed[0] > cursor) {
                    keptRanges.add(new long[]{cursor, Math.min(base[1], removed[0] - 1)});
                }
                if (removed[1] == 0xFFFFFFFFL) {
                    cursor = 0x1_0000_0000L;
                    break;
                }
                cursor = Math.max(cursor, removed[1] + 1);
                if (cursor > base[1]) break;
                probeIdx++;
            }
            if (cursor <= base[1]) {
                keptRanges.add(new long[]{cursor, base[1]});
            }
        }

        List<CidrBlock> result = new ArrayList<>();
        for (long[] kept : keptRanges) {
            result.addAll(rangeToCidrs(kept[0], kept[1]));
        }
        return result;
    }

    /**
     * 超级聚合（有损聚合）：在标准聚合基础上，通过允许少量误差进一步减少 CIDR 数量。
     *
     * <p>当 CIDR 总数超过 maxEntries 时，优先合并间隙最小的相邻区间对——将两个区间之间最少的
     * 非目标 IP 纳入覆盖范围——直到总数 ≤ maxEntries。
     *
     * <p>典型应用：将中国 IP CIDR 列表从标准聚合后的约 7 000 条压缩至约 2 000 条，
     * 以避免 VPN {@code Builder.establish()} 超出 Binder 事务大小限制（部分 OEM ROM 低至 600 KB）。
     * 代价：少量非中国 IP 会被纳入排除范围，走物理网络直连。使用保护列表（protectedBlocks）
     * 可以防止关键非中国 IP（如 Cloudflare DNS 1.1.1.0/24）被意外纳入排除范围。
     *
     * <p>算法（贪心填隙，带保护）：
     * <ol>
     *   <li>将输入 CIDR 转为有序、不重叠的 [start, end] 区间（同 aggregate 前三步）</li>
     *   <li>预计算每个区间对应的 CIDR 数及总数</li>
     *   <li>每轮优先找出相邻区间间隙最小且不含受保护 IP 的对，合并后更新计数；
     *       若所有候选均含受保护 IP，则退而合并最小间隙的对（保护为尽力而为）；
     *       循环直到总数 ≤ maxEntries</li>
     *   <li>将最终区间列表转回 CIDR</li>
     * </ol>
     *
     * <p>复杂度：O(n²)，其中 n 为初始区间数（通常约 7 000），在后台线程运行约 50–500 ms，
     * 不阻塞主线程。
     *
     * @param cidrs          待聚合的输入 CIDR 列表（如已调用 aggregate() 则效率更高）
     * @param maxEntries     目标最大 CIDR 条目数
     * @param protectedBlocks 受保护的非中国 CIDR 列表（间隙中含这些 IP 的合并将被优先跳过）；
     *                        可为 null 或空列表（退化为无保护的普通超级聚合）
     * @return 不超过 maxEntries 条的 CIDR 列表（按 startIp 排序）；若输入已满足则直接返回副本
     */
    public static List<CidrBlock> superAggregate(List<CidrBlock> cidrs, int maxEntries,
                                                  List<CidrBlock> protectedBlocks) {
        if (cidrs == null || cidrs.isEmpty()) return Collections.emptyList();
        if (cidrs.size() <= maxEntries) return new ArrayList<>(cidrs);

        // Build sorted protected ranges for gap-intersection check
        // Each entry: [start, end] (unsigned long)
        List<long[]> protectedRanges = new ArrayList<>();
        if (protectedBlocks != null && !protectedBlocks.isEmpty()) {
            for (CidrBlock p : protectedBlocks) {
                protectedRanges.add(new long[]{p.startIp & 0xFFFFFFFFL, p.endIp & 0xFFFFFFFFL});
            }
            protectedRanges.sort((a, b) -> Long.compare(a[0], b[0]));
        }

        // Step 1: convert to sorted, non-overlapping intervals
        List<long[]> ranges = new ArrayList<>(cidrs.size());
        for (CidrBlock c : cidrs) {
            ranges.add(new long[]{c.startIp & 0xFFFFFFFFL, c.endIp & 0xFFFFFFFFL});
        }
        ranges.sort((a, b) -> Long.compare(a[0], b[0]));
        List<long[]> merged = new ArrayList<>();
        long[] cur = new long[]{ranges.get(0)[0], ranges.get(0)[1]};
        for (int i = 1; i < ranges.size(); i++) {
            long[] next = ranges.get(i);
            boolean adjacent = cur[1] < 0xFFFFFFFFL && next[0] <= cur[1] + 1;
            if (adjacent) {
                if (next[1] > cur[1]) cur[1] = next[1];
            } else {
                merged.add(cur);
                cur = new long[]{next[0], next[1]};
            }
        }
        merged.add(cur);
        ranges = merged;

        // Step 2: precompute CIDR count per interval and total count
        List<Integer> cidrCounts = new ArrayList<>(ranges.size());
        int totalCidrCount = 0;
        for (long[] r : ranges) {
            int cnt = countCidrsForRange(r[0], r[1]);
            cidrCounts.add(cnt);
            totalCidrCount += cnt;
        }

        // Step 3: greedy – find and merge the pair with the smallest inter-range gap
        //         until totalCidrCount <= maxEntries.
        // After the initial merge step above, all consecutive intervals have gap >= 0
        // (gap = next.start - cur.end - 1 >= 0; negative gaps / overlaps cannot occur
        //  because overlapping/adjacent ranges were already merged in the step above).
        // gap == 0 means exactly-adjacent ranges, which are merged for free (no extra IPs).
        //
        // Protected-gap logic: when protectedRanges is non-empty, a gap is "protected" if it
        // overlaps with any protected range. We prefer merging unprotected gaps first; only if
        // no unprotected candidate exists do we fall back to merging the overall smallest gap
        // (protection is best-effort, not a hard constraint, to ensure convergence).
        while (totalCidrCount > maxEntries && ranges.size() > 1) {
            // Track the smallest non-protected gap (preferred merge candidate).
            int minIdxPreferred = -1;
            long minGapPreferred = Long.MAX_VALUE;
            // Track the smallest overall gap regardless of protection (fallback when all
            // remaining gaps are protected and we have no choice but to fill one of them).
            int minIdxFallback = -1;
            long minGapFallback = Long.MAX_VALUE;

            for (int i = 0; i < ranges.size() - 1; i++) {
                // gap >= 0 always holds here (see comment above); Math.max guards against
                // any unexpected edge cases without changing behavior for valid inputs.
                // gap = number of IPs between the end of range[i] and start of range[i+1].
                long gap = Math.max(0, ranges.get(i + 1)[0] - ranges.get(i)[1] - 1);

                // Update the overall fallback minimum first (covers every gap unconditionally).
                if (gap < minGapFallback) {
                    minGapFallback = gap;
                    minIdxFallback = i;
                }

                // Skip protected gaps for the preferred (non-protected) candidate.
                // The gap occupies [ranges[i].end + 1, ranges[i+1].start - 1].
                if (!protectedRanges.isEmpty()) {
                    long gapStart = ranges.get(i)[1] + 1;
                    long gapEnd   = ranges.get(i + 1)[0] - 1;
                    if (gapStart <= gapEnd
                            && gapOverlapsProtected(gapStart, gapEnd, protectedRanges)) {
                        continue;
                    }
                }

                if (gap < minGapPreferred) {
                    minGapPreferred = gap;
                    minIdxPreferred = i;
                }
            }

            // Use the best non-protected candidate if available; fall back to overall minimum.
            int mergeIdx = (minIdxPreferred >= 0) ? minIdxPreferred : minIdxFallback;

            // Read counts before mutation
            int cnt1 = cidrCounts.get(mergeIdx);
            int cnt2 = cidrCounts.get(mergeIdx + 1);
            // Merge ranges[mergeIdx] and ranges[mergeIdx+1] by extending end
            long newEnd = Math.max(ranges.get(mergeIdx)[1], ranges.get(mergeIdx + 1)[1]);
            ranges.get(mergeIdx)[1] = newEnd;
            ranges.remove(mergeIdx + 1);
            int newCnt = countCidrsForRange(ranges.get(mergeIdx)[0], newEnd);
            totalCidrCount = totalCidrCount - cnt1 - cnt2 + newCnt;
            cidrCounts.set(mergeIdx, newCnt);
            cidrCounts.remove(mergeIdx + 1);
        }

        // Step 4: convert intervals back to CIDRs
        List<CidrBlock> result = new ArrayList<>(totalCidrCount);
        for (long[] r : ranges) {
            result.addAll(rangeToCidrs(r[0], r[1]));
        }
        return result;
    }

    /**
     * 超级聚合（无保护列表简化版）：等同于 {@link #superAggregate(List, int, List)} 传入空保护列表。
     *
     * @param cidrs      待聚合的输入 CIDR 列表
     * @param maxEntries 目标最大 CIDR 条目数
     * @return 不超过 maxEntries 条的 CIDR 列表（按 startIp 排序）
     */
    public static List<CidrBlock> superAggregate(List<CidrBlock> cidrs, int maxEntries) {
        return superAggregate(cidrs, maxEntries, null);
    }

    /**
     * 检查间隙 [gapStart, gapEnd] 是否与任何受保护区间重叠。
     * protectedRanges 必须已按 start 排序。
     */
    private static boolean gapOverlapsProtected(long gapStart, long gapEnd,
                                                   List<long[]> protectedRanges) {
        for (long[] p : protectedRanges) {
            if (p[0] > gapEnd) break; // sorted: no further overlap possible
            if (p[1] >= gapStart) return true;
        }
        return false;
    }

    /**
     * 统计将 [start, end] 区间转换为 CIDR 所需的条目数（不创建对象，仅计数）。
     */
    private static int countCidrsForRange(long start, long end) {
        int count = 0;
        while (start <= end) {
            int maxBits = 32;
            long s = start;
            for (int i = 0; i < 32; i++) {
                if ((s & 1) == 0) maxBits--; else break;
                s >>= 1;
            }
            int prefix = maxBits;
            while (prefix <= 32) {
                long blockEnd = start + (1L << (32 - prefix)) - 1;
                if (blockEnd <= end) break;
                prefix++;
            }
            if (prefix > 32) prefix = 32;
            count++;
            start += (1L << (32 - prefix));
            if (start > 0xFFFFFFFFL) break;
        }
        return count;
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

    private static List<long[]> toMergedRanges(List<CidrBlock> cidrs) {
        List<long[]> ranges = new ArrayList<>(cidrs.size());
        for (CidrBlock cidr : cidrs) {
            ranges.add(new long[]{cidr.startIp & 0xFFFFFFFFL, cidr.endIp & 0xFFFFFFFFL});
        }
        ranges.sort((a, b) -> Long.compare(a[0], b[0]));
        List<long[]> merged = new ArrayList<>();
        long[] cur = new long[]{ranges.get(0)[0], ranges.get(0)[1]};
        for (int i = 1; i < ranges.size(); i++) {
            long[] next = ranges.get(i);
            if (next[0] <= cur[1] + 1) {
                cur[1] = Math.max(cur[1], next[1]);
            } else {
                merged.add(cur);
                cur = new long[]{next[0], next[1]};
            }
        }
        merged.add(cur);
        return merged;
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
