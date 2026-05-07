package net.kaaass.zerotierfix.util;

/**
 * 维护程序中公共的常量
 *
 * @author kaaass
 */
public class Constants {

    public static final String PREF_NETWORK_USE_CELLULAR_DATA = "network_use_cellular_data";

    public static final String PREF_PLANET_USE_CUSTOM = "planet_use_custom";

    public static final String PREF_SET_PLANET_FILE = "set_planet_file";

    public static final String PREF_NETWORK_DISABLE_IPV6 = "network_disable_ipv6";
    public static final String PREF_NETWORK_AUTO_REBUILD = "network_auto_rebuild";
    public static final String PREF_NETWORK_SMART_ROUTING_ENABLED = "network_smart_routing_enabled";
    /** 仅用于问题定位：开启后在“智能路由增强=开”时仍强制走普通全局/Per-app 路由（绕过 CHINA_DIRECT）。 */
    public static final String PREF_NETWORK_DIAGNOSE_FORCE_DIRECT_GLOBAL = "network_diagnose_force_direct_global";

    public static final String PREF_GENERAL_START_ZEROTIER_ON_BOOT = "general_start_zerotier_on_boot";

    public static final String PREF_DISABLE_NO_NOTIFICATION_ALERT = "disable_no_notification_alert";

    public static final String FILE_CUSTOM_PLANET = "planet.custom";

    public static final String FILE_TEMP = "temp";

    public static final String FILE_PLANET = "planet";

    // "ZT1_v2": bumped from "ZT1" because the old channel was created with IMPORTANCE_LOW
    // on some devices. Android ignores programmatic importance changes on existing channels,
    // so the only way to restore status-bar icon visibility is to use a new channel ID.
    public static final String CHANNEL_ID = "ZT1_v2";

    public static final String VPN_SESSION_NAME = "ZeroTier One";

    public static final String ZT_EVENT = "ZT_EVENT";

    public static final String APP_VERSION_KEY = "app_version";

    public static final String PRIMARY_DNS = "8.8.8.8";

    public static final String SECONDARY_DNS = "8.8.4.4";

    // 代理设置相关常量 (功能已移除)
    /*
    public static final String PREF_PROXY_ENABLED = "proxy_enabled";
    public static final String PREF_PROXY_TYPE = "proxy_type"; 
    public static final String PREF_PROXY_HOST = "proxy_host";
    public static final String PREF_PROXY_PORT = "proxy_port";
    public static final String PREF_PROXY_USERNAME = "proxy_username";
    public static final String PREF_PROXY_PASSWORD = "proxy_password";
    
    // 代理类型
    public static final int PROXY_TYPE_NONE = 0;
    public static final int PROXY_TYPE_SOCKS5 = 1;
    public static final int PROXY_TYPE_HTTP = 2;
    */

    // 全局流量 VPN 功能相关常量
    public static final String PREF_GLOBAL_TRAFFIC_VPN_ENABLED = "global_traffic_vpn_enabled";
    public static final String PREF_GLOBAL_TRAFFIC_VPN_ROUTE = "global_traffic_vpn_route";
    public static final String PREF_GLOBAL_TRAFFIC_VPN_DNS = "global_traffic_vpn_dns";

    // 检查全局流量 VPN 功能是否正常工作的常量
    public static final String CHECK_GLOBAL_TRAFFIC_VPN_WORKING = "check_global_traffic_vpn_working";

    // 智能路由相关文件名
    public static final String FILE_CHNROUTES = "chnroutes.txt";
    public static final String FILE_GFWLIST   = "gfwlist.txt";
    /** 补充 China IP 段，与主 chnroutes 文件合并使用（不通过网络更新，随 APK 打包发布） */
    public static final String FILE_CHNROUTES_SUPPLEMENT = "chnroutes_supplement.txt";
    /**
     * 运行时自学习的直连 IP 列表（存储在 getFilesDir()，每行一个 /32 CIDR）。
     * 由 SmartRoutingManager 在 DNS 嗅探发现直播 CDN 走 ZT 时自动写入，
     * 下次启动时直接加载，无需重新发现，实现"越用越好用"的自适应直连效果。
     */
    public static final String FILE_LEARNED_DIRECT_IPS = "learned_direct_ips.txt";
}
