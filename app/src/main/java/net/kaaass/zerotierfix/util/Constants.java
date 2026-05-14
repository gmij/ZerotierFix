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
    public static final String PREF_NETWORK_FORWARD_HOTSPOT_TRAFFIC = "network_forward_hotspot_traffic";
    public static final String PREF_NETWORK_SMART_ROUTING_ENABLED = "network_smart_routing_enabled";

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
     * 运行时 learned 路由策略文件（存储在 getFilesDir()）。
     * 新版本使用文本格式持久化 DIRECT / VIA_ZT 两类热点例外，并兼容旧版仅含 DIRECT /32 的内容。
     */
    public static final String FILE_LEARNED_DIRECT_IPS = "learned_direct_ips.txt";
}
