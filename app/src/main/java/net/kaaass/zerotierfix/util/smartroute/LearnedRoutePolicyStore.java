package net.kaaass.zerotierfix.util.smartroute;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * 有界的动态路由策略表。
 *
 * <p>职责：
 * <ul>
 *   <li>维护 DIRECT / VIA_ZT 两类学习到的例外策略；</li>
 *   <li>通过命中阈值、TTL、LRU 控制策略规模；</li>
 *   <li>对高频热点自动提升为 /24，减少永远堆积 /32；</li>
 *   <li>提供可持久化的纯文本格式，便于跨 session 复用。</li>
 * </ul>
 */
public class LearnedRoutePolicyStore {

    public enum Preference {
        DIRECT,
        VIA_ZT
    }

    public static final class ChangeSummary {
        public final boolean routingChanged;
        public final String message;

        private ChangeSummary(boolean routingChanged, String message) {
            this.routingChanged = routingChanged;
            this.message = message;
        }

        public static ChangeSummary none() {
            return new ChangeSummary(false, null);
        }

        public static ChangeSummary changed(String message) {
            return new ChangeSummary(true, message);
        }
    }

    private static final class Entry {
        final Preference preference;
        final long network;
        final int prefixLen;
        int hits;
        long lastSeenAt;
        boolean active;
        String lastDomain;
        String lastReason;

        Entry(Preference preference, long network, int prefixLen) {
            this.preference = preference;
            this.network = network;
            this.prefixLen = prefixLen;
        }

        String toCidrString() {
            return ipv4ToString(network) + "/" + prefixLen;
        }
    }

    private final Map<Long, Entry> directEntries = new HashMap<>();
    private final Map<Long, Entry> viaZtEntries = new HashMap<>();
    private final long ttlMs;
    private final int directActivationHits;
    private final int viaZtActivationHits;
    private final int prefixPromotionHits;
    private final int maxActiveDirectEntries;
    private final int maxActiveViaZtEntries;

    public LearnedRoutePolicyStore(long ttlMs,
                                   int directActivationHits,
                                   int viaZtActivationHits,
                                   int prefixPromotionHits,
                                   int maxActiveDirectEntries,
                                   int maxActiveViaZtEntries) {
        this.ttlMs = ttlMs;
        this.directActivationHits = Math.max(1, directActivationHits);
        this.viaZtActivationHits = Math.max(1, viaZtActivationHits);
        this.prefixPromotionHits = Math.max(1, prefixPromotionHits);
        this.maxActiveDirectEntries = Math.max(1, maxActiveDirectEntries);
        this.maxActiveViaZtEntries = Math.max(1, maxActiveViaZtEntries);
    }

    public synchronized ChangeSummary observe(InetAddress ip,
                                              Preference preference,
                                              String domain,
                                              String reason,
                                              long nowMs,
                                              boolean promotePrefix24) {
        long ipLong = toUint32(ip);
        if (ipLong == -1) return ChangeSummary.none();

        StringBuilder changeLog = new StringBuilder();
        boolean changed = pruneExpiredLocked(nowMs, changeLog);
        changed |= observeSingleLocked(ipLong, 32, preference, domain, reason, nowMs, changeLog);
        if (promotePrefix24) {
            long prefix24 = ipLong & 0xFFFFFF00L;
            changed |= observeSingleLocked(prefix24, 24, preference, domain,
                    reason + " /24热点提升", nowMs, changeLog);
        }
        changed |= evictIfNeededLocked(preference, changeLog);
        if (!changed) return ChangeSummary.none();
        return ChangeSummary.changed(changeLog.toString().trim());
    }

    public synchronized void restore(String line) {
        if (line == null) return;
        line = line.trim();
        if (line.isEmpty() || line.startsWith("#")) return;

        try {
            if (!line.contains("|")) {
                // 兼容旧格式：单独的 x.x.x.x/32 行一律视为 DIRECT 且已激活。
                CidrBlock cidr = CidrBlock.parse(line);
                if (cidr == null) return;
                restoreEntryLocked(Preference.DIRECT, cidr.startIp & 0xFFFFFFFFL,
                        cidr.prefixLen, directActivationHits, System.currentTimeMillis(),
                        "legacy", "legacy-direct");
                return;
            }

            String[] parts = line.split("\\|", 6);
            if (parts.length < 4) return;
            Preference preference = Preference.valueOf(parts[0]);
            CidrBlock cidr = CidrBlock.parse(parts[1]);
            if (cidr == null) return;
            int hits = Integer.parseInt(parts[2]);
            long lastSeen = Long.parseLong(parts[3]);
            String domain = parts.length >= 5 ? parts[4] : "restored";
            String reason = parts.length >= 6 ? parts[5] : "restored";
            restoreEntryLocked(preference, cidr.startIp & 0xFFFFFFFFL, cidr.prefixLen,
                    hits, lastSeen, domain, reason);
        } catch (Exception ignored) {
        }
    }

    public synchronized List<String> serializeLines(long nowMs) {
        pruneExpiredLocked(nowMs, null);
        List<Entry> entries = new ArrayList<>();
        for (Entry entry : directEntries.values()) {
            if (entry.active) entries.add(entry);
        }
        for (Entry entry : viaZtEntries.values()) {
            if (entry.active) entries.add(entry);
        }
        entries.sort(Comparator.comparing((Entry e) -> e.preference.name())
                .thenComparingLong(e -> e.network)
                .thenComparingInt(e -> e.prefixLen));

        List<String> lines = new ArrayList<>(entries.size());
        for (Entry entry : entries) {
            lines.add(entry.preference.name() + "|" + entry.toCidrString() + "|"
                    + entry.hits + "|" + entry.lastSeenAt + "|"
                    + sanitize(entry.lastDomain) + "|" + sanitize(entry.lastReason));
        }
        return lines;
    }

    public synchronized List<CidrBlock> getActiveCidrs(Preference preference, long nowMs) {
        pruneExpiredLocked(nowMs, null);
        List<CidrBlock> result = new ArrayList<>();
        for (Entry entry : entriesFor(preference).values()) {
            if (!entry.active) continue;
            result.add(new CidrBlock((int) entry.network, entry.prefixLen));
        }
        if (result.isEmpty()) return Collections.emptyList();
        return CidrBlock.aggregate(result);
    }

    public synchronized boolean matchesActivePolicy(InetAddress ip, Preference preference, long nowMs) {
        return findBestMatch(ip, preference, nowMs) != null;
    }

    public synchronized String describeActivePolicy(InetAddress ip, long nowMs) {
        Entry via = findBestMatch(ip, Preference.VIA_ZT, nowMs);
        if (via != null) {
            return "via-zt " + via.toCidrString() + " hits=" + via.hits + " reason=" + via.lastReason;
        }
        Entry direct = findBestMatch(ip, Preference.DIRECT, nowMs);
        if (direct != null) {
            return "direct " + direct.toCidrString() + " hits=" + direct.hits + " reason=" + direct.lastReason;
        }
        return null;
    }

    private void restoreEntryLocked(Preference preference, long network, int prefixLen, int hits,
                                    long lastSeenAt, String domain, String reason) {
        Entry entry = entriesFor(preference).computeIfAbsent(key(network, prefixLen),
                ignored -> new Entry(preference, network, prefixLen));
        entry.hits = Math.max(entry.hits, hits);
        entry.lastSeenAt = Math.max(entry.lastSeenAt, lastSeenAt);
        entry.lastDomain = domain;
        entry.lastReason = reason;
        entry.active = entry.hits >= activationHitsFor(preference, prefixLen);
        evictIfNeededLocked(preference, null);
    }

    private boolean observeSingleLocked(long network, int prefixLen, Preference preference,
                                        String domain, String reason, long nowMs,
                                        StringBuilder changeLog) {
        Map<Long, Entry> entries = entriesFor(preference);
        Entry entry = entries.computeIfAbsent(key(network, prefixLen),
                ignored -> new Entry(preference, network, prefixLen));
        entry.hits++;
        entry.lastSeenAt = nowMs;
        entry.lastDomain = domain;
        entry.lastReason = reason;
        if (!entry.active && entry.hits >= activationHitsFor(preference, prefixLen)) {
            entry.active = true;
            appendChange(changeLog, "激活 " + preference.name() + " 策略 "
                    + entry.toCidrString() + "（hits=" + entry.hits + ", domain=" + domain
                    + ", reason=" + reason + "）");
            return true;
        }
        return false;
    }

    private boolean evictIfNeededLocked(Preference preference, StringBuilder changeLog) {
        List<Entry> activeEntries = new ArrayList<>();
        for (Entry entry : entriesFor(preference).values()) {
            if (entry.active) activeEntries.add(entry);
        }
        int maxEntries = maxEntriesFor(preference);
        if (activeEntries.size() <= maxEntries) return false;
        activeEntries.sort(Comparator.comparingLong((Entry e) -> e.lastSeenAt)
                .thenComparingInt(e -> e.prefixLen));
        boolean changed = false;
        for (int i = 0; i < activeEntries.size() - maxEntries; i++) {
            Entry evicted = activeEntries.get(i);
            entriesFor(preference).remove(key(evicted.network, evicted.prefixLen));
            appendChange(changeLog, "淘汰 " + preference.name() + " 策略 "
                    + evicted.toCidrString() + "（TTL/LRU 控制上限）");
            changed = true;
        }
        return changed;
    }

    private boolean pruneExpiredLocked(long nowMs, StringBuilder changeLog) {
        boolean changed = false;
        changed |= pruneMapLocked(directEntries, nowMs, changeLog);
        changed |= pruneMapLocked(viaZtEntries, nowMs, changeLog);
        return changed;
    }

    private boolean pruneMapLocked(Map<Long, Entry> entries, long nowMs, StringBuilder changeLog) {
        boolean changed = false;
        Iterator<Entry> iterator = entries.values().iterator();
        while (iterator.hasNext()) {
            Entry entry = iterator.next();
            if (nowMs - entry.lastSeenAt <= ttlMs) continue;
            iterator.remove();
            if (entry.active) {
                appendChange(changeLog, "过期移除 " + entry.preference.name() + " 策略 "
                        + entry.toCidrString());
                changed = true;
            }
        }
        return changed;
    }

    private Entry findBestMatch(InetAddress ip, Preference preference, long nowMs) {
        long ipLong = toUint32(ip);
        if (ipLong == -1) return null;
        pruneExpiredLocked(nowMs, null);
        Entry best = null;
        for (Entry entry : entriesFor(preference).values()) {
            if (!entry.active) continue;
            if (!contains(entry, ipLong)) continue;
            if (best == null || entry.prefixLen > best.prefixLen) {
                best = entry;
            }
        }
        return best;
    }

    private static boolean contains(Entry entry, long ipLong) {
        long mask = entry.prefixLen == 0 ? 0L : ((0xFFFFFFFFL << (32 - entry.prefixLen)) & 0xFFFFFFFFL);
        return (ipLong & mask) == (entry.network & mask);
    }

    private Map<Long, Entry> entriesFor(Preference preference) {
        return preference == Preference.DIRECT ? directEntries : viaZtEntries;
    }

    private int activationHitsFor(Preference preference, int prefixLen) {
        if (prefixLen < 32) return prefixPromotionHits;
        return preference == Preference.DIRECT ? directActivationHits : viaZtActivationHits;
    }

    private int maxEntriesFor(Preference preference) {
        return preference == Preference.DIRECT ? maxActiveDirectEntries : maxActiveViaZtEntries;
    }

    private static long key(long network, int prefixLen) {
        return ((network & 0xFFFFFFFFL) << 6) | (prefixLen & 0x3FL);
    }

    private static long toUint32(InetAddress ip) {
        if (ip == null) return -1;
        byte[] b = ip.getAddress();
        if (b.length != 4) return -1;
        return ((b[0] & 0xFFL) << 24) | ((b[1] & 0xFFL) << 16)
                | ((b[2] & 0xFFL) << 8) | (b[3] & 0xFFL);
    }

    private static String ipv4ToString(long ipLong) {
        return ((ipLong >> 24) & 0xFF) + "." + ((ipLong >> 16) & 0xFF) + "."
                + ((ipLong >> 8) & 0xFF) + "." + (ipLong & 0xFF);
    }

    private static void appendChange(StringBuilder changeLog, String message) {
        if (changeLog == null || message == null || message.isEmpty()) return;
        if (changeLog.length() > 0) changeLog.append("; ");
        changeLog.append(message);
    }

    private static String sanitize(String value) {
        if (value == null || value.isEmpty()) return "-";
        return value.replace('|', '/').replace('\n', ' ').replace('\r', ' ');
    }
}
