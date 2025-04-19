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

    public static final String PREF_GENERAL_START_ZEROTIER_ON_BOOT = "general_start_zerotier_on_boot";

    public static final String PREF_DISABLE_NO_NOTIFICATION_ALERT = "disable_no_notification_alert";

    public static final String FILE_CUSTOM_PLANET = "planet.custom";

    public static final String FILE_TEMP = "temp";

    public static final String FILE_PLANET = "planet";

    public static final String CHANNEL_ID = "ZT1";

    public static final String VPN_SESSION_NAME = "ZeroTier One";

    public static final String ZT_EVENT = "ZT_EVENT";

    public static final String APP_VERSION_KEY = "app_version";

    public static final String PRIMARY_DNS = "8.8.8.8";

    public static final String SECONDARY_DNS = "8.8.4.4";

    // 代理设置相关常量
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
}
