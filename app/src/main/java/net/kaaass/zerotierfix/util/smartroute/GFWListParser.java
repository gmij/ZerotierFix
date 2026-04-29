package net.kaaass.zerotierfix.util.smartroute;

import net.kaaass.zerotierfix.util.LogUtil;

import java.util.Base64;
import java.util.HashSet;
import java.util.Set;

/**
 * 解析 GFWList 的 ABP 格式规则，提取被封锁的域名
 */
public class GFWListParser {
    private static final String TAG = "GFWListParser";

    /**
     * 解析 base64 编码的 GFWList 内容，返回被封锁域名集合
     *
     * @param base64Content base64 编码的 GFWList 文本
     * @return 被封锁的域名集合（不含前缀点，全小写）
     */
    public static Set<String> parseBase64(String base64Content) {
        String decoded;
        try {
            byte[] bytes = Base64.getMimeDecoder().decode(base64Content.trim());
            decoded = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            LogUtil.e(TAG, "Failed to base64-decode GFWList: " + e.getMessage());
            return new HashSet<>();
        }
        return parseText(decoded);
    }

    /**
     * 解析已解码的 GFWList 文本内容
     */
    public static Set<String> parseText(String content) {
        Set<String> domains = new HashSet<>();
        for (String line : content.split("\\r?\\n")) {
            String domain = extractDomain(line);
            if (domain != null) {
                domains.add(domain);
            }
        }
        LogUtil.i(TAG, "Parsed " + domains.size() + " GFW domains");
        return domains;
    }

    /**
     * 从一行 ABP 规则中提取域名，不能识别的规则返回 null
     */
    private static String extractDomain(String line) {
        line = line.trim();
        // 跳过注释和空行
        if (line.isEmpty() || line.startsWith("!") || line.startsWith("[")) return null;
        // 白名单（@@）跳过
        if (line.startsWith("@@")) return null;

        String domain = null;

        if (line.startsWith("||")) {
            // ||example.com^ — 精确域名规则
            domain = line.substring(2);
        } else if (line.startsWith("|http://")) {
            domain = line.substring(8);
        } else if (line.startsWith("|https://")) {
            domain = line.substring(9);
        } else if (line.startsWith(".")) {
            // .example.com
            domain = line.substring(1);
        } else if (!line.contains("/") && !line.startsWith("*") && line.contains(".")) {
            // 纯域名行（无 * 和 /）
            domain = line;
        }

        if (domain == null) return null;

        // 去除尾部 ^ 或 / 及路径
        int anchor = domain.indexOf('^');
        if (anchor >= 0) domain = domain.substring(0, anchor);
        int slash = domain.indexOf('/');
        if (slash >= 0) domain = domain.substring(0, slash);

        domain = domain.trim().toLowerCase();

        // 过滤掉含有通配符或不像域名的行
        if (domain.isEmpty() || domain.contains("*") || domain.contains(" ")) return null;
        // 必须含有至少一个点
        if (!domain.contains(".")) return null;

        return domain;
    }

    /**
     * 判断给定的主机名是否匹配 GFW 域名集合（支持子域名匹配）
     *
     * @param hostname  要检查的主机名（小写）
     * @param gfwDomains 已解析的 GFW 域名集合
     * @return true 表示该主机名在 GFW 名单中
     */
    public static boolean isGfwBlocked(String hostname, Set<String> gfwDomains) {
        if (hostname == null || hostname.isEmpty()) return false;
        hostname = hostname.toLowerCase();
        // 精确匹配
        if (gfwDomains.contains(hostname)) return true;
        // 逐级父域名匹配
        int dot = hostname.indexOf('.');
        while (dot >= 0 && dot < hostname.length() - 1) {
            String parent = hostname.substring(dot + 1);
            if (gfwDomains.contains(parent)) return true;
            dot = hostname.indexOf('.', dot + 1);
        }
        return false;
    }
}
