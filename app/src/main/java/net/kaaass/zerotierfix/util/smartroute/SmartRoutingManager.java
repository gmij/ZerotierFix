package net.kaaass.zerotierfix.util.smartroute;

import android.content.Context;
import android.content.SharedPreferences;

import net.kaaass.zerotierfix.util.Constants;
import net.kaaass.zerotierfix.util.LogUtil;

import org.apache.commons.io.FileUtils;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * 智能路由管理器
 * <p>
 * 负责：
 * 1. 在后台下载并缓存数据库文件（chnroutes.txt / gfwlist.txt）
 * 2. 提供 CHINA_DIRECT/COMBINED 所需的中国 IP 与非中国 IP CIDR 数据
 * 3. 提供 GFW_LIST/COMBINED 所需的 GFW 域名集合 + DNS 嗅探 IP 映射
 *
 * <b>数据文件默认下载来源：</b>
 * <ul>
 *   <li>chnroutes.txt – China IP CIDR 列表（jsDelivr CDN 镜像，每行一个 CIDR）</li>
 *   <li>gfwlist.txt   – GFWList ABP 格式（base64 编码）</li>
 * </ul>
 */
public class SmartRoutingManager {

    // ────────────────────────── 路由模式常量 ──────────────────────────

    /** 不启用智能路由 */
    public static final int MODE_OFF = 0;

    /**
     * 国内直连模式：通过系统 VPN 路由表尽量让中国 IP 走物理网络、非中国 IP 走 ZeroTier。
     * 受 Android VPN 路由数量限制，具体路由策略由服务层选择，TUN 内不再丢弃业务包。
     */
    public static final int MODE_CHINA_DIRECT = 1;

    /**
     * GFW 列表模式：基于 DNS 嗅探发现 GFW 域名解析出的 IP，并作为显式路由添加到 VPN 路由表。
     * 该模式受 DNS 解析时序与缓存影响，属于增强分流，不在 TUN 内通过丢包实现直连。
     */
    public static final int MODE_GFW_LIST = 2;

    /**
     * 组合模式：同时使用 GFW 域名列表（DNS 嗅探）和 chnroutes 中国 IP 列表做增强分流。
     * 域名/IP 映射受 DNS 时序影响；已经进入 TUN 的包会继续转发到 ZT，避免黑洞。
     */
    public static final int MODE_COMBINED = 3;

    // ────────────────────────── 下载地址 ──────────────────────────

    // misakaio/chnroutes2 每小时从真实 BGP 路由表生成，数据比 17mon（季度更新）更新，
    // 备用源使用 jsDelivr CDN 镜像。
    private static final String CHNROUTES_URL =
            "https://raw.githubusercontent.com/misakaio/chnroutes2/master/chnroutes.txt";
    private static final String CHNROUTES_URL_FALLBACK =
            "https://cdn.jsdelivr.net/gh/misakaio/chnroutes2@master/chnroutes.txt";

    private static final String GFWLIST_URL =
            "https://raw.githubusercontent.com/gfwlist/gfwlist/master/gfwlist.txt";
    private static final String GFWLIST_URL_FALLBACK =
            "https://cdn.jsdelivr.net/gh/gfwlist/gfwlist@master/gfwlist.txt";

    private static final int DOWNLOAD_CONN_TIMEOUT = 10_000;
    private static final int DOWNLOAD_READ_TIMEOUT = 30_000;

    private static final String TAG = "SmartRoutingManager";

    // ────────────────────────── 单例 ──────────────────────────

    private static volatile SmartRoutingManager instance;

    public static SmartRoutingManager getInstance(Context context) {
        if (instance == null) {
            synchronized (SmartRoutingManager.class) {
                if (instance == null) {
                    instance = new SmartRoutingManager(context.getApplicationContext());
                }
            }
        }
        return instance;
    }

    // ────────────────────────── 内部状态 ──────────────────────────

    private final Context context;
    private final Executor executor = Executors.newSingleThreadExecutor();

    /**
     * VPN 路由超级聚合目标上限。
     * 将中国 IP CIDR 列表从标准聚合后的约 7 000 条压缩到此上限以内，
     * 使 VPN {@code Builder.establish()} 的 Binder 序列化体积约 164 KB（约 82 字节/条），
     * 远低于任何 OEM ROM 的事务上限（最低约 600 KB）。
     *
     * <p>历史：此值曾为 500（≈41 KB）。500 过于激进——超级聚合会将相隔较远的中国 IP 区间强行
     * 合并，产生 172.0.0.0/8 等巨型块，将 YouTube（172.217.x.x）、Google（142.250.x.x）等
     * 非中国 IP 纳入"排除"列表，导致这些流量绕过 VPN 走物理网络，最终被 GFW 屏蔽。
     * 当前默认采用 3000 条；若某些 ROM 仍建立失败，会自动降档到更保守预算。
     */
    private static final int SUPER_AGGREGATE_DEFAULT_MAX_ENTRIES = 3000;
    private static final int[] SUPER_AGGREGATE_BUDGET_TIERS = {
            3000, 2600, 2200, 1800, 1400
    };
    private static final String SMART_ROUTING_PREFS = "smart_routing_prefs";
    private static final String PREF_KEY_SUPER_AGGREGATE_BUDGET = "super_aggregate_budget";
    static {
        validateBudgetTiersDescending();
    }

    /**
     * 受保护的非中国 CIDR 列表：即使在超级聚合过程中，这些 IP 所在的间隙也不会被填充。
     *
     * <p>主要保护对象为间隙极小（/24 等）但关键的非中国 IP，例如：
     * <ul>
     *   <li>1.1.1.0/24 – Cloudflare DNS 主（1.1.1.1），夹在 1.1.0.0/24 和 1.1.2.0/23 两个中国段之间，
     *       若被合并则 Cloudflare DNS 经物理网络发出、受 GFW 污染，导致全局代理模式下 DNS 解析失败。</li>
     *   <li>1.0.0.0/24 – Cloudflare DNS 备（1.0.0.1），类似情形。</li>
     *   <li>172.217.0.0/16、142.250.0.0/15 及更多 Google 出口段 –
     *       防止 YouTube/Google 高频 IP 在过度聚合时被纳入中国段。</li>
     * </ul>
     *
     * <p>保护为尽力而为（best-effort）：若无法在不填充受保护间隙的前提下达到 maxEntries，
     * 算法将回退到普通最小间隙合并，确保最终收敛。
     */
    private static final String[] PROTECTED_NON_CHINA_CIDRS = {
            "1.1.1.0/24",   // Cloudflare DNS 主（1.1.1.1）
            "1.0.0.0/24",   // Cloudflare DNS 备（1.0.0.1）
            "8.8.8.0/24",   // Google DNS 主（8.8.8.8）
            "8.8.4.0/24",   // Google DNS 备（8.8.4.4）
            "66.102.0.0/20", // Google
            "108.177.0.0/17", // Google
            "172.217.0.0/16", // YouTube/Google
            "142.250.0.0/15", // YouTube/Google
            "142.252.0.0/15", // YouTube/Google
            "172.253.0.0/16", // YouTube/Google
            "173.194.0.0/16", // YouTube/Google
            "74.125.0.0/16",  // YouTube/Google
            "64.233.160.0/19", // YouTube/Google
            "209.85.128.0/17", // YouTube/Google
            "216.239.32.0/19", // YouTube/Google
            "199.36.154.0/23", // Google 前端 VIP（Google APIs / Accounts 常见段）
    };

    /**
     * Google/YouTube/Google Play 相关域名后缀。
     * 命中后优先学习为 VIA_ZT，避免在 CHINA_DIRECT 中被误判/污染解析导致直连失败。
     */
    private static final String[] GOOGLE_GLOBAL_SERVICE_DOMAIN_SUFFIXES = {
            ".google.com",           // Google 主域
            ".googleapis.com",       // Google APIs（含 Play 服务 API）
            ".gstatic.com",          // Play 服务/Google 静态资源
            ".googleusercontent.com",// Google 内容分发
            ".ggpht.com",            // Google 图片/CDN
            ".youtube.com",          // YouTube 主域
            ".googlevideo.com",      // YouTube 视频流 CDN
            ".ytimg.com",            // YouTube 静态资源
            ".youtu.be",             // YouTube 短链接/分享域名
            ".youtube-nocookie.com", // YouTube 嵌入域名
            ".gvt2.com",             // Google 传输/视频 CDN
            ".gvt3.com",             // Google 传输/视频 CDN
            ".gvt1.net",             // Google 视频/更新分发
            ".g.co",                 // Google 短域名（账号/服务跳转）
            ".android.com",          // Android/Play 生态域
    };

    /** China CIDR 列表（CHINA_DIRECT 使用；完整精度，用于 isChineseIp() 查询） */
    private volatile List<CidrBlock> chinaCidrs = Collections.emptyList();

    /** 非 China CIDR 列表（CHINA_DIRECT 路由排除的补集） */
    private volatile List<CidrBlock> nonChinaCidrs = Collections.emptyList();

    /**
     * VPN-safe 中国 IP 超级聚合列表（按当前自适应预算档位）。
     * 由 parseChnroutes() 在后台线程预计算，用于 VPN Builder.establish() 的 excludeRoute/addRoute，
     * 避免大量 CIDR 导致 Binder 序列化超限。精度略低于 chinaCidrs，但对网络连通性无实质影响。
     */
    private static final class VpnSafeRouteSet {
        final List<CidrBlock> china;
        final List<CidrBlock> nonChina;

        VpnSafeRouteSet(List<CidrBlock> china, List<CidrBlock> nonChina) {
            this.china = china;
            this.nonChina = nonChina;
        }
    }
    private volatile VpnSafeRouteSet currentVpnSafeRoutes =
            new VpnSafeRouteSet(Collections.emptyList(), Collections.emptyList());
    /** 标记 currentVpnSafeRoutes 是否已按当前预算完成超级聚合。false 表示需要重新计算。 */
    private volatile boolean vpnSafeComputed = false;
    // 保留字段声明，downgradeSuperAggregateBudget 仍可通过它快速切换
    private volatile Map<Integer, List<CidrBlock>> chinaCidrsVpnSafeByBudget = Collections.emptyMap();
    private volatile Map<Integer, List<CidrBlock>> nonChinaCidrsVpnSafeByBudget = Collections.emptyMap();
    private volatile int currentSuperAggregateBudget = SUPER_AGGREGATE_DEFAULT_MAX_ENTRIES;

    /**
     * Fake-IP 模式标志：设为 true 时跳过超级聚合，直接使用完整精度 chinaCidrs。
     * Fake-IP 模式下 VPN 路由表仅含 0.0.0.0/0，超级聚合结果永不被 VPN Builder 使用。
     */
    private volatile boolean skipSuperAggregate = false;

    /** GFW 域名集合（GFW_LIST 使用） */
    private volatile Set<String> gfwDomains = Collections.emptySet();

    /**
     * DNS 嗅探：IP 字符串 → 域名（用于 GFW 域名检测）
     */
    private final ConcurrentHashMap<String, String> ipToDomain = new ConcurrentHashMap<>();

    /**
     * Fake-IP 模式缓存：域名 → 是否是中国域名。
     * 由 DNS 嗅探与直连路径的真实 IP 学习逻辑在获得真实 IP 后填充；
     * true 表示中国 IP（优先直连），false 表示非中国 IP（优先经 ZT）。
     */
    private final ConcurrentHashMap<String, Boolean> domainChineseness = new ConcurrentHashMap<>();
    /**
     * Fake-IP 模式域名级学习偏好。
     * 仅记录已经验证过的 DIRECT / VIA_ZT 域名决策，用于优化后续同域名首包路径。
     */
    private final ConcurrentHashMap<String, LearnedRoutePolicyStore.Preference> learnedDomainPreferences =
            new ConcurrentHashMap<>();

    /**
     * 已知的 GFW 封锁 IP 集合（GFW_LIST 使用，用于 VPN 路由添加）
     */
    private final Set<InetAddress> gfwIpSet =
            Collections.newSetFromMap(new ConcurrentHashMap<>());

    /** 新增 GFW IP 监听器（用于触发 VPN 路由更新） */
    public interface OnNewGfwIpListener {
        void onNewGfwIp(InetAddress ip);
    }
    private volatile OnNewGfwIpListener onNewGfwIpListener;

    /**
     * chnroutes 数据加载完成监听器（用于在 CHINA_DIRECT 路由数据就绪后重建 VPN 路由）
     */
    public interface OnChnroutesReadyListener {
        void onChnroutesReady();
    }
    private final java.util.concurrent.atomic.AtomicReference<OnChnroutesReadyListener> onChnroutesReadyListenerRef =
            new java.util.concurrent.atomic.AtomicReference<>();

    /**
     * 自学习直连 IP 监听器：当 DNS 嗅探发现新的直播 CDN IP 走 ZT（非中国IP）时触发，
     * 用于通知 ZeroTierOneService 做防抖 VPN 重建，让新 IP 立即走直连。
     */
    public interface OnRoutePolicyChangedListener {
        void onRoutePolicyChanged(String summary);
    }
    private volatile OnRoutePolicyChangedListener onRoutePolicyChangedListener;

    /**
     * 分层式动态路由策略表：
     * DIRECT = 补充进中国直连骨架；VIA_ZT = 从中国直连骨架中挖掉的热点例外。
     *
     * <p>DIRECT 允许更多热点，所以容量略大；VIA_ZT 每个 /32 都会拆分已有 excludeRoute，
     * 为避免 Binder 路由表急剧膨胀，容量保持更小。</p>
     */
    private static final long LEARNED_POLICY_TTL_MS = 7L * 24 * 60 * 60 * 1000;
    private static final int DIRECT_ACTIVATION_HITS = 2;
    private static final int VIA_ZT_ACTIVATION_HITS = 1;
    private static final int PREFIX_PROMOTION_HITS = 3;
    private static final int MAX_ACTIVE_DIRECT_POLICIES = 160;
    private static final int MAX_ACTIVE_VIA_ZT_POLICIES = 24;
    private final LearnedRoutePolicyStore learnedRoutePolicies = new LearnedRoutePolicyStore(
            LEARNED_POLICY_TTL_MS,
            DIRECT_ACTIVATION_HITS,
            VIA_ZT_ACTIVATION_HITS,
            PREFIX_PROMOTION_HITS,
            MAX_ACTIVE_DIRECT_POLICIES,
            MAX_ACTIVE_VIA_ZT_POLICIES);

    private SmartRoutingManager(Context context) {
        this.context = context;
        this.currentSuperAggregateBudget = normalizeBudget(loadPersistedBudget());
    }

    // ────────────────────────── 公开 API ──────────────────────────

    /**
     * 在后台触发数据库文件下载/加载（应在服务启动时调用）
     */
    public void ensureDataReady() {
        executor.execute(this::loadOrDownloadAll);
    }

    /**
     * 判断某 IP 是否为中国 IP（先查自学习集合，再二分查找 chnroutes 列表）
     */
    public boolean isChineseIp(InetAddress address) {
        if (address == null) return false;
        long now = System.currentTimeMillis();
        if (learnedRoutePolicies.matchesActivePolicy(address, LearnedRoutePolicyStore.Preference.VIA_ZT, now)) {
            return false;
        }
        if (learnedRoutePolicies.matchesActivePolicy(address, LearnedRoutePolicyStore.Preference.DIRECT, now)) {
            return true;
        }
        return binaryContains(chinaCidrs, address);
    }

    /**
     * 判断某域名是否被 GFW 封锁
     */
    public boolean isGfwDomain(String domain) {
        return GFWListParser.isGfwBlocked(domain, gfwDomains);
    }

    // ─────────────── Fake-IP 模式专用 API ───────────────────────────

    /**
     * Fake-IP 模式下判断一个域名的流量是否应走 ZeroTier 隧道（VIA_ZT）。
     *
     * <p>决策顺序：
     * <ol>
     *   <li>GFWList / Google 全球服务命中 → VIA_ZT（true，最高优先级）</li>
     *   <li>域名级 learned VIA_ZT 命中 → VIA_ZT（true）</li>
     *   <li>域名级 learned DIRECT 命中 → DIRECT（false）</li>
     *   <li>GeoIP 缓存命中：中国 → DIRECT；非中国 → VIA_ZT</li>
     *   <li>默认 → VIA_ZT（首次访问未知域名时继续走 ZT，等待 DNS/直连结果补充 GeoIP 缓存）</li>
     * </ol>
     *
     * <p>注意：此方法仅根据域名做决策，无需解析真实 IP，因此可在 DNS 拦截路径的热路径上同步调用。
     *
     * @param domain 查询域名（已小写）
     * @return true = 走 ZeroTier 隧道；false = 直连（分配 fake IP）
     */
    public boolean shouldRouteViaTunnel(String domain) {
        if (domain == null) return true; // 安全默认：走 ZT
        String d = domain.toLowerCase();
        // ① GFWList
        if (isGfwDomain(d)) return true;
        // ② Google/YouTube 等全球服务
        if (isGoogleGlobalServiceDomain(d)) return true;
        // ③ learned 偏好：仅在非 GFW 域名上生效 —— 避免历史缓存覆盖明确的封锁域策略。
        LearnedRoutePolicyStore.Preference learnedPreference = learnedDomainPreferences.get(d);
        if (learnedPreference == LearnedRoutePolicyStore.Preference.VIA_ZT) return true;
        if (learnedPreference == LearnedRoutePolicyStore.Preference.DIRECT) return false;
        // ④ GeoIP 缓存：国内直连、国外走 ZT。
        Boolean chinese = domainChineseness.get(d);
        if (Boolean.TRUE.equals(chinese)) return false;
        if (Boolean.FALSE.equals(chinese)) return true;
        // ⑤ 默认 → VIA_ZT（安全默认：首访未知域名继续走 ZeroTier，等待后续 GeoIP 学习结果）
        return true;
    }

    /**
     * Fake-IP 模式下的 DNS 记录学习：在代理层成功解析出真实 IP 后调用，
     * 用于更新 learned VIA_ZT 策略（若该 IP 不是中国 IP），以便后续同域名直接复用策略。
     *
     * @param domain  域名（小写）
     * @param realIp  代理层解析到的真实 IP
     */
    public void learnFromDirectConnection(String domain, InetAddress realIp) {
        if (domain == null || realIp == null) return;
        if (!(realIp instanceof java.net.Inet4Address)) return;
        String domainLower = domain.toLowerCase();
        // 无论是否中国 IP，先更新 IP→域名 映射
        ipToDomain.put(realIp.getHostAddress(), domainLower);
        if (isGfwDomain(domainLower) || isGoogleGlobalServiceDomain(domainLower)) {
            return;
        }
        if (isChineseIp(realIp)) {
            // 真实 IP 是中国 IP → 缓存为中国域名，加速后续 shouldRouteViaTunnel() 步骤③判决
            domainChineseness.put(domainLower, Boolean.TRUE);
            learnedDomainPreferences.put(domainLower, LearnedRoutePolicyStore.Preference.DIRECT);
            LogUtil.d(TAG, "fake-IP 学习 DIRECT(CN): " + domain + " → " + realIp.getHostAddress());
        } else {
            domainChineseness.put(domainLower, Boolean.FALSE);
            learnedDomainPreferences.put(domainLower, LearnedRoutePolicyStore.Preference.VIA_ZT);
            // 非中国 IP → 记录为已验证应走 ZT 的外网目标，供后续域名/IP 判决复用。
            LearnedRoutePolicyStore.ChangeSummary cs = learnedRoutePolicies.observe(
                    realIp, LearnedRoutePolicyStore.Preference.VIA_ZT,
                    domainLower, "geoip-non-cn", System.currentTimeMillis(), false);
            if (cs.routingChanged) {
                LogUtil.d(TAG, "fake-IP 学习 VIA_ZT(non-CN): " + domain + " → " + realIp.getHostAddress());
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────


    public Set<InetAddress> getGfwIpSet() {
        return Collections.unmodifiableSet(gfwIpSet);
    }

    /**
     * 返回非中国 CIDR 列表（CHINA_DIRECT 模式路由排除的补集）。
     * 若有 learned 例外，则重新计算补集以确保它们即时生效。
     */
    public List<CidrBlock> getNonChinaCidrs() {
        if (!hasLearnedRouteOverrides()) return nonChinaCidrs;
        return Collections.unmodifiableList(CidrBlock.computeComplement(getChinaCidrs()));
    }

    /**
     * 返回中国 CIDR 列表（基础 chnroutes + DIRECT learned - VIA_ZT learned）。
     */
    public List<CidrBlock> getChinaCidrs() {
        if (!hasLearnedRouteOverrides()) return chinaCidrs;
        return Collections.unmodifiableList(buildEffectiveChinaCidrs(chinaCidrs));
    }

    /**
     * 返回 VPN-safe 中国 IP 超级聚合列表（按当前自适应预算档位），
     * 包含 learned DIRECT / VIA_ZT 例外。
     *
     * <p>用于 {@code VpnService.Builder.excludeRoute()} / {@code addRoute()}，
     * 避免全量 CIDR 序列化超出 Binder 事务大小限制。
     * 精度判断（{@link #isChineseIp}）仍使用完整的 chinaCidrs。
     */
    public List<CidrBlock> getChinaCidrsVpnSafe() {
        List<CidrBlock> base = getChinaCidrsVpnSafeBase();
        if (!hasLearnedRouteOverrides()) return base;
        return Collections.unmodifiableList(buildEffectiveChinaCidrs(base));
    }

    /**
     * 返回 VPN-safe 非中国 IP 补集（chinaCidrsVpnSafe 的补集）。
     * 用于 Android 12- 的 {@code addRoute}；若有 learned 例外则重新计算补集。
     */
    public List<CidrBlock> getNonChinaCidrsVpnSafe() {
        List<CidrBlock> base = getNonChinaCidrsVpnSafeBase();
        if (!hasLearnedRouteOverrides()) return base;
        return Collections.unmodifiableList(CidrBlock.computeComplement(getChinaCidrsVpnSafe()));
    }

    public int getCurrentSuperAggregateBudget() {
        return currentSuperAggregateBudget;
    }

    public synchronized int downgradeSuperAggregateBudget() {
        int idx = indexOfBudget(currentSuperAggregateBudget);
        if (idx < 0) idx = 0;
        if (idx >= SUPER_AGGREGATE_BUDGET_TIERS.length - 1) {
            return currentSuperAggregateBudget;
        }
        int next = SUPER_AGGREGATE_BUDGET_TIERS[idx + 1];
        if (next != currentSuperAggregateBudget) {
            currentSuperAggregateBudget = next;
            persistBudget(next);
            // 令懒计算缓存失效，下次 getChinaCidrsVpnSafe() 调用时用新预算重新超级聚合
            this.vpnSafeComputed = false;
        }
        return currentSuperAggregateBudget;
    }

    /**
     * 设置 Fake-IP 超级聚合跳过标志。
     *
     * <p>Fake-IP 模式下 VPN 路由表仅含 0.0.0.0/0，超级聚合结果永不被 VPN Builder 消费。
     * 改变标志会令懒计算缓存失效，下次调用 getChinaCidrsVpnSafe() 时重新按新模式计算。
     *
     * @param skip true 表示跳过超级聚合（Fake-IP 模式），false 恢复正常（CHINA_DIRECT 模式）
     */
    public synchronized void setSkipSuperAggregate(boolean skip) {
        if (this.skipSuperAggregate == skip) return; // 无变化，避免无效缓存失效
        this.skipSuperAggregate = skip;
        this.vpnSafeComputed = false; // 令懒计算缓存失效
    }

    /**
     * 是否已加载 chnroutes 数据（CHINA_DIRECT 是否可用）
     */
    public boolean isChnroutesReady() {
        return !chinaCidrs.isEmpty();
    }

    /**
     * 是否已加载 GFWList 数据
     */
    public boolean isGfwListReady() {
        return !gfwDomains.isEmpty();
    }

    /**
     * 注册新 GFW IP 发现时的回调（用于触发 VPN 路由重建）
     */
    public void setOnNewGfwIpListener(OnNewGfwIpListener listener) {
        this.onNewGfwIpListener = listener;
    }

    /**
     * 注册 chnroutes 数据加载完成回调（用于在 CHINA_DIRECT 路由数据就绪后重建 VPN 路由）
     */
    public void setOnChnroutesReadyListener(OnChnroutesReadyListener listener) {
        setOnChnroutesReadyListener(listener, true);
    }

    /**
     * 注册 chnroutes 数据加载完成回调。
     *
     * @param notifyImmediatelyIfReady 当数据当前已就绪时是否立即触发一次回调。
     *                                 传 false 可用于“只监听下一次刷新完成”场景。
     */
    public void setOnChnroutesReadyListener(OnChnroutesReadyListener listener,
                                            boolean notifyImmediatelyIfReady) {
        if (listener == null) {
            this.onChnroutesReadyListenerRef.set(null);
            return;
        }
        this.onChnroutesReadyListenerRef.set(listener);
        // 覆盖“先就绪后注册/注册后立刻就绪”窗口；CAS 防止与加载线程重复触发。
        if (notifyImmediatelyIfReady
                && isChnroutesReady()
                && this.onChnroutesReadyListenerRef.compareAndSet(listener, null)) {
            listener.onChnroutesReady();
        }
    }

    /**
     * 注册自学习直连 IP 发现回调（用于触发防抖 VPN 重建）
     */
    public void setOnRoutePolicyChangedListener(OnRoutePolicyChangedListener listener) {
        this.onRoutePolicyChangedListener = listener;
    }


    /**
     * 记录一次 learned DIRECT / VIA_ZT 观察。
     */
    public void observeRoutePolicy(InetAddress ip,
                                   LearnedRoutePolicyStore.Preference preference,
                                   String domain,
                                   String reason,
                                   boolean promotePrefix24) {
        LearnedRoutePolicyStore.ChangeSummary change = learnedRoutePolicies.observe(
                ip, preference, domain, reason, System.currentTimeMillis(), promotePrefix24);
        if (!change.routingChanged) return;
        LogUtil.i(LogUtil.DNS_TAG, "学习路由策略更新: " + change.message);
        executor.execute(this::persistLearnedIps);
        OnRoutePolicyChangedListener listener = onRoutePolicyChangedListener;
        if (listener != null) listener.onRoutePolicyChanged(change.message);
    }

    /**
     * 从 {@code learned_direct_ips.txt} 加载之前持久化的 learned 路由策略。
     * 在 {@link #loadOrDownloadAll()} 中调用，VPN 启动即可使用上次积累的学习结果。
     */
    private void loadLearnedIps() {
        File file = new File(context.getFilesDir(), Constants.FILE_LEARNED_DIRECT_IPS);
        if (!file.exists()) return;
        int loaded = 0;
        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = br.readLine()) != null) {
                learnedRoutePolicies.restore(line);
                loaded++;
            }
        } catch (IOException e) {
            LogUtil.w(TAG, "加载 learned 路由策略失败: " + e.getMessage());
        }
        if (loaded > 0) {
            LogUtil.i(TAG, "已加载 " + loaded + " 条 learned 路由策略");
        }
    }

    /**
     * 将当前 learned 路由策略持久化到文件。
     * 在后台线程执行，不阻塞调用方。
     */
    private void persistLearnedIps() {
        File file = new File(context.getFilesDir(), Constants.FILE_LEARNED_DIRECT_IPS);
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("# learned route policies - generated by SmartRoutingManager\n");
            sb.append("# format: PREFERENCE|CIDR|hits|lastSeenAt|domain|reason\n");
            sb.append("# example: DIRECT|43.128.12.0/24|3|1715300000000|cdn.example|live-domain-non-cn /24 热点提升\n");
            for (String line : learnedRoutePolicies.serializeLines(System.currentTimeMillis())) {
                sb.append(line).append('\n');
            }
            org.apache.commons.io.FileUtils.writeStringToFile(
                    file, sb.toString(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException e) {
            LogUtil.w(TAG, "持久化 learned 路由策略失败: " + e.getMessage());
        }
    }

    /**
     * 处理一条 DNS 嗅探记录（由 TunTapAdapter 调用）
     *
     * @param record DNS A/AAAA 记录
     */
    public void onDnsRecord(DnsPacketParser.DnsRecord record) {
        if (record == null || record.ip == null || record.domain == null) return;
        // 记录 IP → 域名 映射（用于后续 GFW 检测）
        String domainLower = record.domain.toLowerCase();
        ipToDomain.put(record.ip.getHostAddress(), domainLower);
        boolean isGoogleService = isGoogleGlobalServiceDomain(record.domain);
        if (isGfwDomain(record.domain) || isGoogleService) {
            learnedDomainPreferences.put(domainLower, LearnedRoutePolicyStore.Preference.VIA_ZT);
            boolean isNew = gfwIpSet.add(record.ip);
            if (isNew) {
                LogUtil.d(LogUtil.DNS_TAG, record.domain + " -> " + record.ip.getHostAddress()
                        + (isGoogleService ? " -> ZT (GoogleService)" : " -> ZT (GFW)"));
                OnNewGfwIpListener l = onNewGfwIpListener;
                if (l != null) l.onNewGfwIp(record.ip);
            }
            observeRoutePolicy(record.ip, LearnedRoutePolicyStore.Preference.VIA_ZT,
                    record.domain, isGoogleService ? "google-service" : "gfw-domain", false);
        } else if (isChineseIp(record.ip)) {
            // Fake-IP 模式：缓存中国域名，加速后续 shouldRouteViaTunnel() 的 GeoIP 判决
            domainChineseness.put(domainLower, Boolean.TRUE);
            learnedDomainPreferences.put(domainLower, LearnedRoutePolicyStore.Preference.DIRECT);
            if (isLiveStreamingDomain(record.domain)) {
                LogUtil.d(LogUtil.DNS_TAG, record.domain + " -> " + record.ip.getHostAddress()
                        + " -> direct (CN live)");
            }
        } else {
            domainChineseness.put(domainLower, Boolean.FALSE);
            learnedDomainPreferences.put(domainLower, LearnedRoutePolicyStore.Preference.VIA_ZT);
            observeRoutePolicy(record.ip, LearnedRoutePolicyStore.Preference.VIA_ZT,
                    record.domain, "geoip-non-cn", false);
        }
    }

    /** 把 IPv4 InetAddress 转换为 uint32 long（用于 learnedDirectIpSet 查找） */
    private static long toUint32(InetAddress ip) {
        byte[] b = ip.getAddress();
        if (b.length != 4) return -1;
        return ((b[0] & 0xFFL) << 24) | ((b[1] & 0xFFL) << 16)
                | ((b[2] & 0xFFL) << 8) | (b[3] & 0xFFL);
    }

    /**
     * 查找某 IP 地址对应的域名（由 DNS 嗅探记录，可能为 null）
     */
    public String getDomainForIp(java.net.InetAddress address) {
        if (address == null) return null;
        return ipToDomain.get(address.getHostAddress());
    }

    /**
     * 返回 learned 策略对该 IP 的诊断描述（若无命中则返回 null）。
     */
    public String getLearnedPolicyDescription(InetAddress address) {
        return learnedRoutePolicies.describeActivePolicy(address, System.currentTimeMillis());
    }

    // ────────────────────────── 下载 / 加载逻辑 ──────────────────────────

    private void loadOrDownloadAll() {
        loadLearnedIps();       // 先加载上次积累的 learned 路由策略，确保 VPN 启动即生效
        loadOrDownloadChnroutes();
        loadOrDownloadGfwList();
    }

    private void loadOrDownloadChnroutes() {
        File file = new File(context.getFilesDir(), Constants.FILE_CHNROUTES);
        // 文件不存在、太小，或超过 7 天未更新时触发下载；确保腾讯新增 CDN 网段能被及时获取。
        boolean needsDownload = !file.exists() || file.length() < 100
                || (System.currentTimeMillis() - file.lastModified() > 7L * 24 * 60 * 60 * 1000);
        if (needsDownload && (!file.exists() || file.length() < 100)) {
            // 先从 APK 内置 assets 复制种子文件（无需网络，立即可用）
            copyFromAssets(Constants.FILE_CHNROUTES, file);
        }
        // 立即解析当前文件（assets 种子或之前缓存的版本），确保路由表尽快就绪，
        // 不阻塞于后续的网络下载，消除 VPN 建立前 chnroutes 未就绪的竞态窗口。
        if (file.exists() && file.length() >= 100) {
            parseChnroutes(file);
        }
        if (needsDownload) {
            // 尝试从网络下载最新版本；仅在实际获取到新内容时重新解析
            long sizeBefore = file.exists() ? file.length() : 0;
            downloadFile(CHNROUTES_URL, CHNROUTES_URL_FALLBACK, file, "chnroutes");
            if (file.exists() && file.length() != sizeBefore && file.length() >= 100) {
                parseChnroutes(file);
            }
        }
    }

    private void loadOrDownloadGfwList() {
        File file = new File(context.getFilesDir(), Constants.FILE_GFWLIST);
        boolean needsDownload = !file.exists() || file.length() < 100;
        if (needsDownload) {
            // 先从 APK 内置 assets 复制种子文件（无需网络，立即可用）
            copyFromAssets(Constants.FILE_GFWLIST, file);
        }
        // 立即解析当前文件
        if (file.exists() && file.length() >= 100) {
            parseGfwList(file);
        }
        if (needsDownload) {
            // 尝试从网络下载最新版本；仅在实际获取到新内容时重新解析
            long sizeBefore = file.exists() ? file.length() : 0;
            downloadFile(GFWLIST_URL, GFWLIST_URL_FALLBACK, file, "gfwlist");
            if (file.exists() && file.length() != sizeBefore && file.length() >= 100) {
                parseGfwList(file);
            }
        }
    }

    /**
     * 将 APK assets 目录中的内置数据文件复制到 filesDir（仅在文件不存在时调用）。
     *
     * @return 复制成功返回 true，assets 中不存在或复制失败返回 false
     */
    private boolean copyFromAssets(String fileName, File dest) {
        try (InputStream in = context.getAssets().open(fileName)) {
            FileUtils.copyInputStreamToFile(in, dest);
            LogUtil.i(TAG, "从内置 assets 复制 " + fileName + "（" + dest.length() + " bytes）");
            return true;
        } catch (IOException e) {
            // assets 中不存在该文件属于正常情况（本地开发环境）
            LogUtil.d(TAG, "assets 中无 " + fileName + "，将通过网络下载");
            return false;
        }
    }

    private void downloadFile(String primaryUrl, String fallbackUrl, File dest, String tag) {
        LogUtil.i(TAG, "下载 " + tag + " 数据...");
        try {
            FileUtils.copyURLToFile(new URL(primaryUrl), dest,
                    DOWNLOAD_CONN_TIMEOUT, DOWNLOAD_READ_TIMEOUT);
            LogUtil.i(TAG, "成功下载 " + tag);
        } catch (IOException e1) {
            LogUtil.w(TAG, "主源下载 " + tag + " 失败: " + e1.getMessage() + "，尝试备用源");
            try {
                FileUtils.copyURLToFile(new URL(fallbackUrl), dest,
                        DOWNLOAD_CONN_TIMEOUT, DOWNLOAD_READ_TIMEOUT);
                LogUtil.i(TAG, "从备用源成功下载 " + tag);
            } catch (IOException e2) {
                LogUtil.e(TAG, "备用源下载 " + tag + " 也失败: " + e2.getMessage());
            }
        }
    }

    private void parseChnroutes(File file) {
        List<CidrBlock> blocks = new ArrayList<>(8000);
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                CidrBlock block = CidrBlock.parse(line);
                if (block != null) blocks.add(block);
            }
        } catch (IOException e) {
            LogUtil.e(TAG, "读取 chnroutes 文件失败: " + e.getMessage());
            return;
        }
        // 追加 assets/chnroutes_supplement.txt 中的补充 IP 段：
        // 所有公开数据源均缺少这些段，但微信视频号直播 CDN 依赖它们。
        // 该文件随 APK 打包发布，无需网络更新，确保直播流量直连而非经 ZT 境外节点中转。
        int supplementalAdded = 0;
        try (InputStream supplementIn = context.getAssets().open(Constants.FILE_CHNROUTES_SUPPLEMENT);
             BufferedReader supplementReader = new BufferedReader(new java.io.InputStreamReader(supplementIn))) {
            String line;
            while ((line = supplementReader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                CidrBlock block = CidrBlock.parse(line);
                if (block != null) {
                    blocks.add(block);
                    supplementalAdded++;
                }
            }
        } catch (IOException e) {
            LogUtil.w(TAG, "读取 chnroutes_supplement.txt 失败: " + e.getMessage());
        }
        Collections.sort(blocks);
        // CIDR 聚合：将相邻/重叠的中国 CIDR 合并为最小等价集合。
        // 对于 chnroutes2 BGP 数据，聚合通常可减少 20-40% 的条目数（将相邻 /24 合并为 /23、/22 等），
        // 显著降低 VPN 路由表的 Binder parcel 大小，从而避免 establish() 抛出 TransactionTooLargeException。
        int beforeAgg = blocks.size();
        List<CidrBlock> aggregated = CidrBlock.aggregate(blocks);
        this.chinaCidrs = Collections.unmodifiableList(aggregated);
        this.nonChinaCidrs = Collections.unmodifiableList(
                CidrBlock.computeComplement(aggregated));

        // 超级聚合改为懒计算（在首次调用 getChinaCidrsVpnSafe() 时按需计算），避免后台加载线程
        // 与 setSkipSuperAggregate() 调用时序的竞态，也节省启动 CPU（Fake-IP 模式下永不使用）。
        this.chinaCidrsVpnSafeByBudget = Collections.emptyMap();
        this.nonChinaCidrsVpnSafeByBudget = Collections.emptyMap();
        this.currentVpnSafeRoutes = new VpnSafeRouteSet(Collections.emptyList(), Collections.emptyList());
        this.vpnSafeComputed = false; // 通知懒计算路径需要重新生成

        LogUtil.i(TAG, "已加载 " + beforeAgg + " 条中国 IP 路由（含 "
                + supplementalAdded + " 条腾讯云补充段），聚合后 " + aggregated.size()
                + " 条（补集 " + nonChinaCidrs.size() + " 条），超级聚合待按需计算");
        // 通知等待中的 CHINA_DIRECT VPN 路由重建（getAndSet 原子读取并清除，消除竞态）
        OnChnroutesReadyListener l = onChnroutesReadyListenerRef.getAndSet(null);
        if (l != null) {
            l.onChnroutesReady();
        }
    }

    private void parseGfwList(File file) {
        try {
            String content = FileUtils.readFileToString(file,
                    java.nio.charset.StandardCharsets.UTF_8);
            Set<String> domains;
            // gfwlist.txt 是 base64 编码的
            if (!content.contains("||") && !content.contains("[AutoProxy")) {
                domains = GFWListParser.parseBase64(content);
            } else {
                domains = GFWListParser.parseText(content);
            }
            this.gfwDomains = Collections.unmodifiableSet(domains);
            LogUtil.i(TAG, "已加载 " + domains.size() + " 个 GFW 域名");
        } catch (IOException e) {
            LogUtil.e(TAG, "读取 gfwlist 文件失败: " + e.getMessage());
        }
    }

    /**
     * 强制重新下载所有数据文件（忽略缓存）
     */
    public void forceRefresh() {
        executor.execute(() -> {
            deleteFile(Constants.FILE_CHNROUTES);
            deleteFile(Constants.FILE_GFWLIST);
            loadOrDownloadAll();
        });
    }

    private void deleteFile(String name) {
        File f = new File(context.getFilesDir(), name);
        if (f.exists() && !f.delete()) {
            LogUtil.w(TAG, "无法删除文件: " + name);
        }
    }

    // ────────────────────────── 工具方法 ──────────────────────────

    /**
     * 判断域名是否属于直播相关服务（微信视频号、腾讯视频、B 站等），
     * 用于将该类域名的 DNS 解析日志提升为 INFO 级，release 包也可见。
     *
     * <p><b>注意 – DNS 嗅探盲区</b>：此方法仅在 DNS 响应经由 ZeroTier 虚拟网络返回时生效
     * （{@code onVirtualNetworkFrame} 捕获）。在 CHINA_DIRECT 模式下，国内 DNS 服务器
     * （114.114.114.114 等）是中国 IP，已通过 {@code excludeRoute} 排除在 VPN 之外，
     * DNS 请求直接走物理网络，响应<em>不经过</em> ZeroTier，{@code onDnsRecord} 永远不会被调用。
     * 这意味着在 CHINA_DIRECT 模式下此 INFO 日志实际不会出现；
     * 若需判断哪些直播 CDN IP 在走 ZT，请通过 {@code [CONN]} 日志观察进入 TUN 的原始 IP。
     */
    private static boolean isLiveStreamingDomain(String domain) {
        if (domain == null) return false;
        String d = domain.toLowerCase();
        // 使用域名后缀匹配，避免误匹配（如 notweixin.example.com）
        return d.endsWith(".weixin.qq.com") || d.endsWith(".weixin.com")
                || d.endsWith(".video.qq.com") || d.endsWith(".live.qq.com")
                || d.endsWith(".kvideo.qq.com")          // 微信视频号直播 CDN
                || d.endsWith(".qpic.cn") || d.endsWith(".qpic.com")
                || d.endsWith(".myqcloud.com")            // 腾讯云对象存储/CDN（视频号用）
                || d.endsWith(".tencent.com") || d.endsWith(".tencentvideo.com")
                || d.endsWith(".wx.qq.com")               // 微信通用域名
                || d.endsWith(".v.qq.com")                // 腾讯视频
                || d.endsWith(".bilibili.com") || d.endsWith(".bilivideo.com")
                || d.endsWith(".youku.com") || d.endsWith(".iqiyi.com")
                || d.endsWith(".huya.com") || d.endsWith(".huya.cn")  // 虎牙直播
                || d.endsWith(".douyu.com") || d.endsWith(".douyucdn.cn") // 斗鱼直播
                || d.endsWith(".vlive.qq.com") || d.endsWith(".livep.qq.com");
    }

    private static boolean isGoogleGlobalServiceDomain(String domain) {
        if (domain == null) return false;
        String d = domain.toLowerCase();
        for (String suffix : GOOGLE_GLOBAL_SERVICE_DOMAIN_SUFFIXES) {
            if (d.equals(suffix.substring(1)) || d.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 在已排序的 CIDR 列表中二分查找指定 IP 是否命中
     */
    private static boolean binaryContains(List<CidrBlock> sorted, InetAddress address) {
        if (sorted.isEmpty()) return false;
        byte[] bytes = address.getAddress();
        if (bytes.length != 4) return false;
        long ip = ((bytes[0] & 0xFFL) << 24) | ((bytes[1] & 0xFFL) << 16)
                | ((bytes[2] & 0xFFL) << 8) | (bytes[3] & 0xFFL);

        int lo = 0, hi = sorted.size() - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            CidrBlock b = sorted.get(mid);
            long startL = b.startIp & 0xFFFFFFFFL;
            long endL   = b.endIp   & 0xFFFFFFFFL;
            if (ip < startL) {
                hi = mid - 1;
            } else if (ip > endL) {
                lo = mid + 1;
            } else {
                return true;
            }
        }
        return false;
    }

    private boolean hasLearnedRouteOverrides() {
        long now = System.currentTimeMillis();
        return !learnedRoutePolicies.getActiveCidrs(LearnedRoutePolicyStore.Preference.DIRECT, now).isEmpty()
                || !learnedRoutePolicies.getActiveCidrs(LearnedRoutePolicyStore.Preference.VIA_ZT, now).isEmpty();
    }

    private List<CidrBlock> buildEffectiveChinaCidrs(List<CidrBlock> baseChinaCidrs) {
        long now = System.currentTimeMillis();
        List<CidrBlock> learnedDirect = learnedRoutePolicies.getActiveCidrs(
                LearnedRoutePolicyStore.Preference.DIRECT, now);
        List<CidrBlock> learnedViaZt = learnedRoutePolicies.getActiveCidrs(
                LearnedRoutePolicyStore.Preference.VIA_ZT, now);
        List<CidrBlock> merged = new ArrayList<>(baseChinaCidrs.size() + learnedDirect.size());
        merged.addAll(baseChinaCidrs);
        merged.addAll(learnedDirect);
        List<CidrBlock> effective = CidrBlock.aggregate(merged);
        if (!learnedViaZt.isEmpty()) {
            effective = CidrBlock.subtract(effective, learnedViaZt);
        }
        return effective;
    }

    /**
     * 懒计算：若 VPN-safe 路由集合尚未按当前预算计算，立即在调用线程上同步计算。
     *
     * <p>若 skipSuperAggregate=true（Fake-IP 模式），直接使用完整精度的 chinaCidrs，不做超级聚合。
     * 若 chinaCidrs 尚未加载（启动期间），不设置 vpnSafeComputed，等 parseChnroutes 完成后再触发真正计算。
     *
     * <p>此方法自身已持有 synchronized(this) 锁。
     */
    private synchronized void ensureVpnSafeComputed() {
        if (vpnSafeComputed) return;
        List<CidrBlock> base = this.chinaCidrs;
        if (base.isEmpty()) {
            // chnroutes 尚未加载：返回空集合，但不置 vpnSafeComputed=true，
            // 确保 parseChnroutes() 完成后的下次调用能触发真正的计算。
            // CHINA_DIRECT 路径的 waitForChnroutesReady() 可防止此分支被反复命中。
            currentVpnSafeRoutes = new VpnSafeRouteSet(Collections.emptyList(), Collections.emptyList());
            return;
        }
        if (skipSuperAggregate) {
            // Fake-IP 模式：直接使用完整精度列表，跳过耗时超级聚合
            currentVpnSafeRoutes = new VpnSafeRouteSet(base, this.nonChinaCidrs);
            vpnSafeComputed = true;
            LogUtil.d(TAG, "VPN-safe 路由集合已就绪（Fake-IP 模式，跳过超级聚合，"
                    + base.size() + " 条）");
            return;
        }
        // CHINA_DIRECT 模式：在此线程上同步完成超级聚合（VPN rebuild 线程，非主线程，可接受）
        List<CidrBlock> protectedBlocks = new ArrayList<>();
        for (String cidr : PROTECTED_NON_CHINA_CIDRS) {
            CidrBlock b = CidrBlock.parse(cidr);
            if (b != null) protectedBlocks.add(b);
        }
        int budget = normalizeBudget(currentSuperAggregateBudget);
        if (budget != currentSuperAggregateBudget) currentSuperAggregateBudget = budget;

        Map<Integer, List<CidrBlock>> chinaByBudget = new LinkedHashMap<>();
        Map<Integer, List<CidrBlock>> nonChinaByBudget = new LinkedHashMap<>();
        for (int tier : SUPER_AGGREGATE_BUDGET_TIERS) {
            List<CidrBlock> superAgg = CidrBlock.superAggregate(base, tier, protectedBlocks);
            List<CidrBlock> safeChina = Collections.unmodifiableList(CidrBlock.subtract(superAgg, protectedBlocks));
            chinaByBudget.put(tier, safeChina);
            nonChinaByBudget.put(tier, Collections.unmodifiableList(CidrBlock.computeComplement(safeChina)));
        }
        this.chinaCidrsVpnSafeByBudget = Collections.unmodifiableMap(chinaByBudget);
        this.nonChinaCidrsVpnSafeByBudget = Collections.unmodifiableMap(nonChinaByBudget);
        List<CidrBlock> selectedChina = getListForBudget(chinaCidrsVpnSafeByBudget, budget, base);
        List<CidrBlock> selectedNonChina = getListForBudget(nonChinaCidrsVpnSafeByBudget, budget,
                CidrBlock.computeComplement(base));
        this.currentVpnSafeRoutes = new VpnSafeRouteSet(selectedChina, selectedNonChina);
        vpnSafeComputed = true;
        LogUtil.i(TAG, "超级聚合完成：" + selectedChina.size() + " 条（预算 " + budget
                + "，补集 " + selectedNonChina.size() + " 条）");
    }

    private List<CidrBlock> getChinaCidrsVpnSafeBase() {
        if (!vpnSafeComputed) ensureVpnSafeComputed();
        return currentVpnSafeRoutes.china;
    }

    private List<CidrBlock> getNonChinaCidrsVpnSafeBase() {
        if (!vpnSafeComputed) ensureVpnSafeComputed();
        return currentVpnSafeRoutes.nonChina;
    }

    private static List<CidrBlock> getListForBudget(Map<Integer, List<CidrBlock>> byBudget, int budget,
                                                    List<CidrBlock> fallback) {
        List<CidrBlock> list = byBudget.get(budget);
        return list != null ? list : fallback;
    }

    private int loadPersistedBudget() {
        SharedPreferences prefs = context.getSharedPreferences(SMART_ROUTING_PREFS, Context.MODE_PRIVATE);
        return prefs.getInt(PREF_KEY_SUPER_AGGREGATE_BUDGET, SUPER_AGGREGATE_DEFAULT_MAX_ENTRIES);
    }

    private void persistBudget(int budget) {
        SharedPreferences prefs = context.getSharedPreferences(SMART_ROUTING_PREFS, Context.MODE_PRIVATE);
        prefs.edit().putInt(PREF_KEY_SUPER_AGGREGATE_BUDGET, budget).apply();
    }

    private static int normalizeBudget(int budget) {
        // 注意：预算档位数组必须按“降序”排序。
        int first = SUPER_AGGREGATE_BUDGET_TIERS[0];
        int normalized = first;
        for (int tier : SUPER_AGGREGATE_BUDGET_TIERS) {
            if (budget >= tier) {
                normalized = tier;
                break;
            }
        }
        return normalized;
    }

    private static int indexOfBudget(int budget) {
        for (int i = 0; i < SUPER_AGGREGATE_BUDGET_TIERS.length; i++) {
            if (SUPER_AGGREGATE_BUDGET_TIERS[i] == budget) return i;
        }
        return -1;
    }

    private static void validateBudgetTiersDescending() {
        for (int i = 1; i < SUPER_AGGREGATE_BUDGET_TIERS.length; i++) {
            if (SUPER_AGGREGATE_BUDGET_TIERS[i] >= SUPER_AGGREGATE_BUDGET_TIERS[i - 1]) {
                throw new IllegalStateException("SUPER_AGGREGATE_BUDGET_TIERS must be strictly descending");
            }
        }
    }
}
