package net.kaaass.zerotierfix.util.smartroute;

import android.content.Context;

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
import java.util.HashSet;
import java.util.List;
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

    /** China CIDR 列表（CHINA_DIRECT 使用） */
    private volatile List<CidrBlock> chinaCidrs = Collections.emptyList();

    /** 非 China CIDR 列表（CHINA_DIRECT 路由排除的补集） */
    private volatile List<CidrBlock> nonChinaCidrs = Collections.emptyList();

    /** GFW 域名集合（GFW_LIST 使用） */
    private volatile Set<String> gfwDomains = Collections.emptySet();

    /**
     * DNS 嗅探：IP 字符串 → 域名（用于 GFW 域名检测）
     */
    private final ConcurrentHashMap<String, String> ipToDomain = new ConcurrentHashMap<>();

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
    public interface OnNewLearnedIpListener {
        void onNewLearnedIp(InetAddress ip);
    }
    private volatile OnNewLearnedIpListener onNewLearnedIpListener;

    /**
     * 自学习直连 IP 集合（uint32 整数形式，用于 isChineseIp O(1) 快速查找）。
     * 在 DNS 嗅探发现直播 CDN IP 走 ZT 时动态增长，跨 session 持久化到文件。
     */
    private final java.util.concurrent.CopyOnWriteArraySet<Long> learnedDirectIpSet =
            new java.util.concurrent.CopyOnWriteArraySet<>();

    /**
     * 自学习直连 IP 的 CIDR 列表（全部为 /32），用于 VPN 路由 excludeRoute 配置。
     * 与 chinaCidrs 一起通过 getChinaCidrs() 对外提供。
     */
    private final java.util.concurrent.CopyOnWriteArrayList<CidrBlock> learnedDirectCidrs =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    /** 自学习 IP 上限，防止无限增长 */
    private static final int MAX_LEARNED_IPS = 500;

    private SmartRoutingManager(Context context) {
        this.context = context;
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
        long ipLong = toUint32(address);
        if (ipLong != -1 && learnedDirectIpSet.contains(ipLong)) return true;
        return binaryContains(chinaCidrs, address);
    }

    /**
     * 判断某域名是否被 GFW 封锁
     */
    public boolean isGfwDomain(String domain) {
        return GFWListParser.isGfwBlocked(domain, gfwDomains);
    }

    /**
     * 返回已知的 GFW 封锁 IP 集合（用于 VPN 路由配置）
     */
    public Set<InetAddress> getGfwIpSet() {
        return Collections.unmodifiableSet(gfwIpSet);
    }

    /**
     * 返回非中国 CIDR 列表（CHINA_DIRECT 模式路由排除的补集）。
     * 若有自学习 IP，重新计算补集以确保它们也被排除。
     */
    public List<CidrBlock> getNonChinaCidrs() {
        if (learnedDirectCidrs.isEmpty()) return nonChinaCidrs;
        return Collections.unmodifiableList(CidrBlock.computeComplement(getChinaCidrs()));
    }

    /**
     * 返回中国 CIDR 列表（包含 chnroutes + supplement + 自学习 IP）。
     */
    public List<CidrBlock> getChinaCidrs() {
        List<CidrBlock> learned = new ArrayList<>(learnedDirectCidrs);
        if (learned.isEmpty()) return chinaCidrs;
        List<CidrBlock> merged = new ArrayList<>(chinaCidrs.size() + learned.size());
        merged.addAll(chinaCidrs);
        merged.addAll(learned);
        Collections.sort(merged);
        return Collections.unmodifiableList(merged);
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
        this.onChnroutesReadyListenerRef.set(listener);
    }

    /**
     * 注册自学习直连 IP 发现回调（用于触发防抖 VPN 重建）
     */
    public void setOnNewLearnedIpListener(OnNewLearnedIpListener listener) {
        this.onNewLearnedIpListener = listener;
    }


    /**
     * 尝试将一个直播 CDN IP 加入自学习直连列表。
     * <p>
     * 条件：IPv4、未超出上限、此前未知。满足条件时：
     * <ol>
     *   <li>立即更新内存中的 {@link #learnedDirectIpSet} 和 {@link #learnedDirectCidrs}，
     *       使 {@link #isChineseIp} 和 {@link #getChinaCidrs} 即刻生效；</li>
     *   <li>通过后台线程将 IP 持久化到 {@code learned_direct_ips.txt}；</li>
     *   <li>触发 {@link OnNewLearnedIpListener}，由 ZeroTierOneService 在 10 s 防抖后
     *       重建 VPN 路由，使新 IP 真正走物理网络直连。</li>
     * </ol>
     *
     * @param ip     要学习的 IP（仅处理 IPv4 /32）
     * @param domain 触发该学习的域名（仅用于日志）
     */
    public void learnDirectIp(InetAddress ip, String domain) {
        if (ip == null) return;
        long ipLong = toUint32(ip);
        if (ipLong == -1) return; // 仅学习 IPv4
        if (learnedDirectIpSet.contains(ipLong)) {
            LogUtil.d(TAG, "自学习直连 IP 已知: " + ip.getHostAddress() + " (domain=" + domain + ")");
            return;
        }
        if (learnedDirectIpSet.size() >= MAX_LEARNED_IPS) {
            LogUtil.d(TAG, "自学习直连 IP 已达上限 " + MAX_LEARNED_IPS + "，跳过 " + ip.getHostAddress());
            return;
        }
        // 更新内存（线程安全）
        learnedDirectIpSet.add(ipLong);
        // 注意：(int) ipLong 的高位截断是有意为之——CidrBlock 内部以 signed int 存储 IP，
        // 与 parseChnroutes 的处理方式一致（binaryContains 同样使用 signed int 比较）。
        int ipInt = (int) ipLong;
        learnedDirectCidrs.add(new CidrBlock(ipInt, 32));
        LogUtil.i(LogUtil.DNS_TAG, "✅ 自学习直连: " + ip.getHostAddress()
                + " (" + domain + ") → 已加入直连列表，下次 VPN 重建后生效，将持久化到 "
                + Constants.FILE_LEARNED_DIRECT_IPS);
        // 持久化（异步，不阻塞主流程）
        executor.execute(this::persistLearnedIps);
        // 触发 VPN 重建回调
        OnNewLearnedIpListener l = onNewLearnedIpListener;
        if (l != null) l.onNewLearnedIp(ip);
    }

    /**
     * 从 {@code learned_direct_ips.txt} 加载之前持久化的自学习直连 IP。
     * 在 {@link #loadOrDownloadAll()} 中调用，VPN 启动即可使用上次积累的学习结果。
     */
    private void loadLearnedIps() {
        File file = new File(context.getFilesDir(), Constants.FILE_LEARNED_DIRECT_IPS);
        if (!file.exists()) return;
        int loaded = 0;
        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                // 格式：x.x.x.x/32 或 x.x.x.x
                String ipStr = line.contains("/") ? line.substring(0, line.indexOf('/')) : line;
                try {
                    InetAddress ip = InetAddress.getByName(ipStr);
                    long ipLong = toUint32(ip);
                    if (ipLong != -1 && learnedDirectIpSet.add(ipLong)) {
                        learnedDirectCidrs.add(new CidrBlock((int) ipLong, 32));
                        loaded++;
                    }
                } catch (Exception e) {
                    LogUtil.d(TAG, "跳过无效自学习 IP 行: " + line);
                }
                if (learnedDirectIpSet.size() >= MAX_LEARNED_IPS) break;
            }
        } catch (IOException e) {
            LogUtil.w(TAG, "加载自学习直连 IP 失败: " + e.getMessage());
        }
        if (loaded > 0) {
            LogUtil.i(TAG, "已加载 " + loaded + " 个自学习直连 IP（共 "
                    + learnedDirectIpSet.size() + " 个）");
        }
    }

    /**
     * 将当前 {@link #learnedDirectIpSet} 持久化到文件。
     * 在后台线程执行，不阻塞调用方。
     */
    private void persistLearnedIps() {
        File file = new File(context.getFilesDir(), Constants.FILE_LEARNED_DIRECT_IPS);
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("# 自学习直连 IP 列表 - 由 SmartRoutingManager 在 DNS 嗅探时自动生成\n");
            sb.append("# 每行一个 IPv4 /32 CIDR，启动时自动加载，无需手动编辑\n");
            for (Long ipLong : learnedDirectIpSet) {
                long l = ipLong & 0xFFFFFFFFL;
                int a = (int) ((l >> 24) & 0xFF);
                int b = (int) ((l >> 16) & 0xFF);
                int c = (int) ((l >> 8) & 0xFF);
                int d = (int) (l & 0xFF);
                sb.append(a).append('.').append(b).append('.').append(c).append('.').append(d)
                        .append("/32\n");
            }
            org.apache.commons.io.FileUtils.writeStringToFile(
                    file, sb.toString(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException e) {
            LogUtil.w(TAG, "持久化自学习直连 IP 失败: " + e.getMessage());
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
        ipToDomain.put(record.ip.getHostAddress(), record.domain.toLowerCase());
        if (isGfwDomain(record.domain)) {
            boolean isNew = gfwIpSet.add(record.ip);
            if (isNew) {
                LogUtil.i(LogUtil.DNS_TAG, record.domain + " -> " + record.ip.getHostAddress()
                        + " -> ZT (GFW)");
                OnNewGfwIpListener l = onNewGfwIpListener;
                if (l != null) l.onNewGfwIp(record.ip);
            }
        } else if (isChineseIp(record.ip)) {
            // 直播相关域名升级为 INFO 级，release 包也可见，用于确认国内 CDN 是否走直连
            if (isLiveStreamingDomain(record.domain)) {
                LogUtil.i(LogUtil.DNS_TAG, record.domain + " -> " + record.ip.getHostAddress()
                        + " -> direct (CN)");
            } else {
                LogUtil.d(LogUtil.DNS_TAG, record.domain + " -> " + record.ip.getHostAddress()
                        + " -> direct (CN)");
            }
        } else {
            // 非中国 IP：直播相关域名自动触发自学习。
            // learnDirectIp 内部会判断是否新 IP：
            //   - 新 IP → 打印 "✅ 自学习" 日志 + 触发防抖 VPN 重建
            //   - 已知 IP → 静默跳过（已在 learnedDirectIpSet 中）
            if (isLiveStreamingDomain(record.domain)) {
                learnDirectIp(record.ip, record.domain);
            } else {
                LogUtil.d(LogUtil.DNS_TAG, record.domain + " -> " + record.ip.getHostAddress()
                        + " -> ZT");
            }
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

    // ────────────────────────── 下载 / 加载逻辑 ──────────────────────────

    private void loadOrDownloadAll() {
        loadLearnedIps();       // 先加载上次积累的自学习直连 IP，确保 VPN 启动即生效
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
        LogUtil.i(TAG, "已加载 " + beforeAgg + " 条中国 IP 路由（含 "
                + supplementalAdded + " 条腾讯云补充段），聚合后 " + aggregated.size()
                + " 条，补集 " + nonChinaCidrs.size() + " 条");
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
}
