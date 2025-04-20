package net.kaaass.zerotierfix.util;

import android.util.Log;

/**
 * 日志工具类，为应用提供统一的日志记录接口
 * 同时记录到系统日志和应用内部日志
 */
public class LogUtil {
    private static final LogManager logManager = LogManager.getInstance();
    
    /**
     * 记录调试级别日志
     */
    public static void d(String tag, String message) {
        Log.d(tag, message);
        logManager.debug(tag, message);
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