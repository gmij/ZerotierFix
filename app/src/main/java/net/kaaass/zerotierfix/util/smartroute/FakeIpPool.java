package net.kaaass.zerotierfix.util.smartroute;

import net.kaaass.zerotierfix.util.LogUtil;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fake-IP 地址池，管理 198.18.0.0/15 范围（IANA Benchmarking，不会出现在真实互联网上）。
 *
 * <p>每个域名对应唯一一个 fake IP，使用 LRU 策略淘汰最久未访问的条目。
 * 同时维护 fakeIP → (domain, realIP) 的反向映射，供直连代理层使用。
 *
 * <p>线程安全：domain→fakeIP 的分配通过 {@code lock} 同步；
 * realIP 回填通过 ConcurrentHashMap 实现无锁访问。
 */
public class FakeIpPool {

    private static final String TAG = "FakeIpPool";

    // 198.18.0.0/15：198.18.0.0 – 198.19.255.255（131072 个地址）
    static final int POOL_START = (198 << 24) | (18 << 16);        // 0xC6120000
    static final int POOL_END   = (198 << 24) | (19 << 16) | 0xFFFF; // 0xC613FFFF
    private static final int POOL_MASK_HI = 0xFFFE0000;            // /15

    /** 最多同时缓存的域名→fakeIP 映射数，LRU 淘汰 */
    private static final int MAX_ENTRIES = 10_000;

    // ── 全局单例（VPN 生命周期内复用，避免重建时丢失映射） ───────────────
    private static volatile FakeIpPool INSTANCE;

    public static FakeIpPool sharedInstance() {
        if (INSTANCE == null) {
            synchronized (FakeIpPool.class) {
                if (INSTANCE == null) INSTANCE = new FakeIpPool();
            }
        }
        return INSTANCE;
    }

    // ── domain → fake IP（int）──────────────────────────────────────
    private final LinkedHashMap<String, Integer> domainToFakeIp;

    // ── fakeIP（int）→ domain ───────────────────────────────────────
    private final ConcurrentHashMap<Integer, String> fakeIpToDomain = new ConcurrentHashMap<>();

    // ── fakeIP（int）→ realIP（byte[4]），由代理层回填 ───────────────
    private final ConcurrentHashMap<Integer, byte[]> fakeIpToRealIp = new ConcurrentHashMap<>();

    private int nextAlloc = POOL_START;
    private final Object lock = new Object();

    public FakeIpPool() {
        domainToFakeIp = new LinkedHashMap<String, Integer>(MAX_ENTRIES + 1, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Integer> eldest) {
                if (size() > MAX_ENTRIES) {
                    int evictedIp = eldest.getValue();
                    fakeIpToDomain.remove(evictedIp);
                    fakeIpToRealIp.remove(evictedIp);
                    return true;
                }
                return false;
            }
        };
    }

    // ── 公开 API ─────────────────────────────────────────────────────

    /**
     * 为域名分配（或复用）一个 fake IP。
     *
     * @param domain 查询的域名（不区分大小写）
     * @return 分配的 fake IP 地址；若 domain 为 null 则返回 null
     */
    public InetAddress getOrAllocate(String domain) {
        if (domain == null) return null;
        domain = domain.toLowerCase();
        synchronized (lock) {
            Integer existing = domainToFakeIp.get(domain);
            if (existing != null) {
                return intToAddr(existing);
            }
            int fakeIpInt = allocateNext();
            domainToFakeIp.put(domain, fakeIpInt);
            fakeIpToDomain.put(fakeIpInt, domain);
            LogUtil.d(TAG, "fake IP 分配: " + intToString(fakeIpInt) + " → " + domain);
            return intToAddr(fakeIpInt);
        }
    }

    /**
     * 判断一个原始 int 形式的 IPv4 地址是否属于 fake IP 池。
     * 用于 TUN 热路径，避免 InetAddress 对象分配。
     */
    public static boolean isFakeIpInt(int ipInt) {
        return (ipInt & POOL_MASK_HI) == POOL_START;
    }

    /**
     * 判断一个 InetAddress 是否属于 fake IP 池。
     */
    public boolean isFakeIp(InetAddress address) {
        if (address == null) return false;
        byte[] b = address.getAddress();
        if (b.length != 4) return false;
        return isFakeIpInt(bytesToInt(b));
    }

    /**
     * 获取 fake IP 对应的域名（可能为 null 如果 fake IP 已被 LRU 淘汰）。
     */
    public String getDomain(InetAddress fakeIp) {
        if (fakeIp == null) return null;
        byte[] b = fakeIp.getAddress();
        if (b.length != 4) return null;
        return fakeIpToDomain.get(bytesToInt(b));
    }

    /**
     * 由代理层在成功解析到真实 IP 后回填，以便后续连接可以直接查询。
     */
    public void storeRealIp(InetAddress fakeIp, InetAddress realIp) {
        if (fakeIp == null || realIp == null) return;
        byte[] fb = fakeIp.getAddress();
        byte[] rb = realIp.getAddress();
        if (fb.length != 4 || rb.length != 4) return;
        fakeIpToRealIp.put(bytesToInt(fb), rb.clone());
    }

    /**
     * 获取 fake IP 对应的真实 IP（若代理层尚未回填则返回 null）。
     */
    public InetAddress getRealIp(InetAddress fakeIp) {
        if (fakeIp == null) return null;
        byte[] fb = fakeIp.getAddress();
        if (fb.length != 4) return null;
        byte[] rb = fakeIpToRealIp.get(bytesToInt(fb));
        if (rb == null) return null;
        try {
            return InetAddress.getByAddress(rb);
        } catch (UnknownHostException e) {
            return null;
        }
    }

    // ── 内部工具 ──────────────────────────────────────────────────────

    private int allocateNext() {
        // 简单循环分配；LRU 保证映射条数始终 ≤ MAX_ENTRIES « POOL_SIZE，碰撞极罕见
        int result = nextAlloc;
        nextAlloc++;
        if (nextAlloc > POOL_END) nextAlloc = POOL_START;
        return result;
    }

    public static InetAddress intToAddr(int ip) {
        byte[] b = {(byte)(ip >> 24), (byte)(ip >> 16), (byte)(ip >> 8), (byte)ip};
        try {
            return InetAddress.getByAddress(b);
        } catch (UnknownHostException e) {
            return null;
        }
    }

    private static int bytesToInt(byte[] b) {
        return ((b[0] & 0xFF) << 24) | ((b[1] & 0xFF) << 16)
             | ((b[2] & 0xFF) << 8)  |  (b[3] & 0xFF);
    }

    public static String intToString(int ip) {
        return ((ip >> 24) & 0xFF) + "." + ((ip >> 16) & 0xFF)
             + "." + ((ip >> 8) & 0xFF) + "." + (ip & 0xFF);
    }
}
