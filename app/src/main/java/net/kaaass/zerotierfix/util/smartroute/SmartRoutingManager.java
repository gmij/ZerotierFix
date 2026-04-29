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
 * 2. 提供 CHINA_DIRECT 模式：返回中国 IP 的 CIDR 列表，用于路由排除
 * 3. 提供 GFW_LIST 模式：维护 GFW 域名集合 + DNS 嗅探 IP 映射
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
     * 国内直连模式：中国 IP 走物理网络，境外 IP 走 ZeroTier
     * 需配合 routeViaZeroTier=true 使用
     */
    public static final int MODE_CHINA_DIRECT = 1;

    /**
     * GFW 列表模式：GFW 封锁的域名走 ZeroTier，其余直连
     * 需配合 routeViaZeroTier=false 使用；
     * 已知 GFW IP 将作为显式路由添加到 VPN 路由表
     */
    public static final int MODE_GFW_LIST = 2;

    /**
     * 组合模式：GFW 封锁的域名走 ZeroTier，中国 IP 强制直连，其余直连
     * 需配合 routeViaZeroTier=false 使用；
     * 同时使用 GFW 域名列表（DNS 嗅探）和 chnroutes 中国 IP 列表。
     * 优先级：中国 IP 直连 &gt; GFW 域名走 ZT &gt; 其余直连
     */
    public static final int MODE_COMBINED = 3;

    // ────────────────────────── 下载地址 ──────────────────────────

    private static final String CHNROUTES_URL =
            "https://raw.githubusercontent.com/17mon/china_ip_list/master/china_ip_list.txt";
    private static final String CHNROUTES_URL_FALLBACK =
            "https://cdn.jsdelivr.net/gh/17mon/china_ip_list@master/china_ip_list.txt";

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
     * 判断某 IP 是否为中国 IP（使用已加载的 chnroutes 列表）
     */
    public boolean isChineseIp(InetAddress address) {
        if (address == null) return false;
        List<CidrBlock> list = chinaCidrs;
        // 二分查找：chinaCidrs 已按 startIp 排序
        return binaryContains(list, address);
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
     * 返回非中国 CIDR 列表（CHINA_DIRECT 模式路由排除的补集）
     */
    public List<CidrBlock> getNonChinaCidrs() {
        return nonChinaCidrs;
    }

    /**
     * 返回中国 CIDR 列表
     */
    public List<CidrBlock> getChinaCidrs() {
        return chinaCidrs;
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
            LogUtil.d(LogUtil.DNS_TAG, record.domain + " -> " + record.ip.getHostAddress()
                    + " -> direct (CN)");
        } else {
            LogUtil.d(LogUtil.DNS_TAG, record.domain + " -> " + record.ip.getHostAddress()
                    + " -> ZT");
        }
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
        loadOrDownloadChnroutes();
        loadOrDownloadGfwList();
    }

    private void loadOrDownloadChnroutes() {
        File file = new File(context.getFilesDir(), Constants.FILE_CHNROUTES);
        if (!file.exists() || file.length() < 100) {
            // 先尝试从 APK 内置 assets 复制（无需网络，立即可用）
            copyFromAssets(Constants.FILE_CHNROUTES, file);
            // 再尝试从网络下载最新版本（可能覆盖内置版本）
            downloadFile(CHNROUTES_URL, CHNROUTES_URL_FALLBACK, file, "chnroutes");
        }
        if (file.exists()) {
            parseChnroutes(file);
        }
    }

    private void loadOrDownloadGfwList() {
        File file = new File(context.getFilesDir(), Constants.FILE_GFWLIST);
        if (!file.exists() || file.length() < 100) {
            // 先尝试从 APK 内置 assets 复制（无需网络，立即可用）
            copyFromAssets(Constants.FILE_GFWLIST, file);
            // 再尝试从网络下载最新版本（可能覆盖内置版本）
            downloadFile(GFWLIST_URL, GFWLIST_URL_FALLBACK, file, "gfwlist");
        }
        if (file.exists()) {
            parseGfwList(file);
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
        Collections.sort(blocks);
        this.chinaCidrs = Collections.unmodifiableList(blocks);
        this.nonChinaCidrs = Collections.unmodifiableList(
                CidrBlock.computeComplement(blocks));
        LogUtil.i(TAG, "已加载 " + blocks.size() + " 条中国 IP 路由，补集 "
                + nonChinaCidrs.size() + " 条");
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
