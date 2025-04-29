package net.kaaass.zerotierfix.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import android.text.TextUtils;

import net.kaaass.zerotierfix.util.LogUtil;

/**
 * 代理管理器，用于处理VPN全局路由时的代理转发
 */
public class ProxyManager {
    private static final String TAG = "ProxyManager";
    
    private static ProxyManager instance;
    private SharedPreferences preferences;
    
    private ProxyManager(Context context) {
        preferences = PreferenceManager.getDefaultSharedPreferences(context);
    }
    
    public static synchronized ProxyManager getInstance(Context context) {
        if (instance == null) {
            instance = new ProxyManager(context.getApplicationContext());
        }
        return instance;
    }
    
    /**
     * 检查代理是否启用
     */
    public boolean isProxyEnabled() {
        return preferences.getBoolean(Constants.PREF_PROXY_ENABLED, false);
    }
    
    /**
     * 获取代理类型
     */
    public int getProxyType() {
        String typeStr = preferences.getString(Constants.PREF_PROXY_TYPE, "0");
        try {
            return Integer.parseInt(typeStr);
        } catch (NumberFormatException e) {
            return Constants.PROXY_TYPE_NONE;
        }
    }
    
    /**
     * 获取代理主机
     */
    public String getProxyHost() {
        return preferences.getString(Constants.PREF_PROXY_HOST, "");
    }
    
    /**
     * 获取代理端口
     */
    public int getProxyPort() {
        String portStr = preferences.getString(Constants.PREF_PROXY_PORT, "0");
        try {
            return Integer.parseInt(portStr);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
    
    /**
     * 获取代理用户名
     */
    public String getProxyUsername() {
        return preferences.getString(Constants.PREF_PROXY_USERNAME, "");
    }
    
    /**
     * 获取代理密码
     */
    public String getProxyPassword() {
        return preferences.getString(Constants.PREF_PROXY_PASSWORD, "");
    }
    
    /**
     * 检查代理配置是否有效
     */
    public boolean isProxyConfigValid() {
        if (!isProxyEnabled()) {
            return false;
        }
        
        // 检查代理类型
        int proxyType = getProxyType();
        if (proxyType == Constants.PROXY_TYPE_NONE) {
            return false;
        }
        
        // 检查主机和端口
        String host = getProxyHost();
        int port = getProxyPort();
        
        if (TextUtils.isEmpty(host) || port <= 0 || port > 65535) {
            LogUtil.w(TAG, "无效的代理配置: " + host + ":" + port);
            return false;
        }
        
        return true;
    }
    
    /**
     * 获取代理类型的描述
     */
    public String getProxyTypeString() {
        switch (getProxyType()) {
            case Constants.PROXY_TYPE_SOCKS5:
                return "SOCKS5";
            case Constants.PROXY_TYPE_HTTP:
                return "HTTP";
            default:
                return "None";
        }
    }
    
    /**
     * 获取代理URL，用于日志显示
     */
    public String getProxyUrl() {
        if (!isProxyEnabled() || getProxyType() == Constants.PROXY_TYPE_NONE) {
            return "直接连接";
        }
        
        StringBuilder sb = new StringBuilder();
        sb.append(getProxyTypeString()).append("://");
        
        String username = getProxyUsername();
        String password = getProxyPassword();
        
        // 如果有用户名密码，则添加认证信息
        if (!TextUtils.isEmpty(username)) {
            sb.append(username);
            if (!TextUtils.isEmpty(password)) {
                sb.append(":").append("****");  // 隐藏密码
            }
            sb.append("@");
        }
        
        sb.append(getProxyHost()).append(":").append(getProxyPort());
        return sb.toString();
    }

    /**
     * 检查全局流量 VPN 功能是否正常工作
     */
    public boolean isGlobalTrafficVpnWorking() {
        // 检查代理配置是否有效
        if (isProxyEnabled() && !isProxyConfigValid()) {
            return false;
        }

        // 检查是否有全局路由
        String globalTrafficVpnRoute = preferences.getString(Constants.PREF_GLOBAL_TRAFFIC_VPN_ROUTE, "");
        if (TextUtils.isEmpty(globalTrafficVpnRoute)) {
            return false;
        }

        // 检查是否有全局 DNS 配置
        String globalTrafficVpnDns = preferences.getString(Constants.PREF_GLOBAL_TRAFFIC_VPN_DNS, "");
        if (TextUtils.isEmpty(globalTrafficVpnDns)) {
            return false;
        }

        return true;
    }
}
