package net.kaaass.zerotierfix.util;

import android.util.Log;

import net.kaaass.zerotierfix.BuildConfig;

/**
 * 日志工具类，为应用提供统一的日志记录接口
 * 同时记录到系统日志和应用内部日志
 */
public class LogUtil {
    private static final LogManager logManager = LogManager.getInstance();

    /** Tag used for [CONN] business log entries (connection forwarded to ZeroTier) */
    public static final String CONN_TAG = "CONN";
    /**
     * Tag used for [DNS ] business log entries (DNS resolution snoop result).
     * The trailing underscore pads the tag to 4 chars so that [DNS ] aligns with [CONN] in logs.
     */
    public static final String DNS_TAG = "DNS_";
    /**
     * Tag used for VPN route configuration log entries (CHINA_DIRECT CIDR verification,
     * route count summaries, etc.).
     */
    public static final String ROUTE_TAG = "ROUTE";
    
    /**
     * 记录调试级别日志
     *
     * <p>在 release 包中仅写入 Android logcat（Log.d），跳过 logManager 内部缓冲区。
     * DEBUG 条目在业务日志 UI 中会被 formatBusinessEntry() 过滤掉，存入缓冲区毫无意义，
     * 且每次调用都会触发 new SimpleDateFormat / new Date 分配，产生不必要的 GC 压力。
     */
    public static void d(String tag, String message) {
        Log.d(tag, message);
        if (BuildConfig.DEBUG) {
            logManager.debug(tag, message);
        }
    }
    
    /**
     * 记录信息级别日志
     */
    public static void i(String tag, String message) {
        Log.i(tag, message);
        logManager.info(tag, message);
    }
    
    /**
     * 记录警告级别日志
     */
    public static void w(String tag, String message) {
        Log.w(tag, message);
        logManager.warn(tag, message);
    }
    
    /**
     * 记录错误级别日志
     */
    public static void e(String tag, String message) {
        Log.e(tag, message);
        logManager.error(tag, message);
    }
    
    /**
     * 记录带异常的错误级别日志
     */
    public static void e(String tag, String message, Throwable throwable) {
        Log.e(tag, message, throwable);
        logManager.error(tag, message, throwable);
    }
    
    /**
     * 记录网络事件
     */
    public static void logNetworkEvent(String action, String networkId) {
        i("NetworkEvent", action + " 网络 " + networkId);
    }
    
    /**
     * 记录服务状态变化
     */
    public static void logServiceStatus(String status) {
        i("ServiceStatus", status);
    }
    
    /**
     * 记录系统事件
     */
    public static void logSystemEvent(String event) {
        i("System", event);
    }
    
    /**
     * 记录应用配置变更
     */
    public static void logConfig(String name, String value) {
        d("Config", name + " = " + value);
    }
}