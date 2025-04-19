package net.kaaass.zerotierfix.util;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.Executors;

/**
 * 日志管理类，用于收集和管理应用日志
 */
public class LogManager {
    private static final String TAG = "LogManager";
    private static final int MAX_LOG_LINES = 10000;
    private static final String LOG_COMMAND = "logcat -d -v threadtime";
    private static final String CLEAR_COMMAND = "logcat -c";
    
    private static LogManager instance;
    private final LinkedList<String> logBuffer = new LinkedList<>();
    
    private LogManager() {
        // 私有构造函数
    }
    
    public static synchronized LogManager getInstance() {
        if (instance == null) {
            instance = new LogManager();
        }
        return instance;
    }
    
    /**
     * 清空当前日志缓冲区
     */
    public void clearLogs() {
        synchronized (logBuffer) {
            logBuffer.clear();
        }
        
        try {
            // 清空系统日志
            Runtime.getRuntime().exec(CLEAR_COMMAND);
        } catch (IOException e) {
            Log.e(TAG, "Failed to clear system logs", e);
        }
    }
    
    /**
     * 异步获取系统日志
     * @param context 应用上下文
     * @param callback 回调函数
     */
    public void getLogsAsync(Context context, LogCallback callback) {
        Executors.newSingleThreadExecutor().execute(() -> {
            String logs = getLogs(context);
            callback.onLogsReady(logs);
        });
    }
    
    /**
     * 同步获取系统日志
     * @param context 应用上下文
     * @return 日志内容
     */
    @NonNull
    public String getLogs(Context context) {
        List<String> logLines = new ArrayList<>();
        
        try {
            Process process = Runtime.getRuntime().exec(LOG_COMMAND);
            BufferedReader bufferedReader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()));
            
            String line;
            while ((line = bufferedReader.readLine()) != null) {
                // 只保留与应用相关的日志
                if (line.contains(context.getPackageName()) || 
                        line.contains("ZeroTier") || 
                        line.contains("zerotier")) {
                    logLines.add(line);
                    
                    // 更新缓冲区
                    synchronized (logBuffer) {
                        logBuffer.add(line);
                        while (logBuffer.size() > MAX_LOG_LINES) {
                            logBuffer.removeFirst();
                        }
                    }
                }
            }
            
            bufferedReader.close();
        } catch (IOException e) {
            Log.e(TAG, "Failed to get logs", e);
            return "Error retrieving logs: " + e.getMessage();
        }
        
        // 如果没有日志，尝试从缓冲区读取
        if (logLines.isEmpty()) {
            synchronized (logBuffer) {
                logLines.addAll(logBuffer);
            }
        }
        
        StringBuilder sb = new StringBuilder();
        for (String line : logLines) {
            sb.append(line).append("\n");
        }
        
        return sb.toString();
    }
    
    /**
     * 日志回调接口
     */
    public interface LogCallback {
        void onLogsReady(String logs);
    }
}