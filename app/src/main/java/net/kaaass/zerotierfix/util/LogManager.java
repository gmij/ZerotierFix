package net.kaaass.zerotierfix.util;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

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
    private final LinkedList<String> internalLogBuffer = new LinkedList<>(); // 应用内部日志缓冲区
    private final ExecutorService executorService;
    private volatile boolean isShutdown = false;
    private final AtomicBoolean isTaskRunning = new AtomicBoolean(false);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    
    private LogManager() {
        // 私有构造函数
        executorService = Executors.newSingleThreadExecutor();
        // 记录初始化日志
        internalLog("LogManager", "初始化完成", LogLevel.INFO);
    }
    
    public static synchronized LogManager getInstance() {
        if (instance == null) {
            instance = new LogManager();
        }
        return instance;
    }
    
    /**
     * 日志级别枚举
     */
    public enum LogLevel {
        VERBOSE("V"),
        DEBUG("D"),
        INFO("I"),
        WARN("W"),
        ERROR("E");
        
        private final String label;
        
        LogLevel(String label) {
            this.label = label;
        }
        
        public String getLabel() {
            return label;
        }
    }
    
    /**
     * 添加内部日志
     * @param tag 日志标签
     * @param message 日志信息
     * @param level 日志级别
     */
    public void internalLog(String tag, String message, LogLevel level) {
        if (tag == null || message == null || level == null) return;
        
        // 获取当前时间
        String timestamp = new java.text.SimpleDateFormat("MM-dd HH:mm:ss.SSS", 
                java.util.Locale.getDefault()).format(new java.util.Date());
        
        // 获取线程信息
        String threadInfo = Thread.currentThread().getName() + "(" + 
                Thread.currentThread().getId() + ")";
        
        // 格式化日志条目
        String logEntry = timestamp + " " + threadInfo + " " + level.getLabel() + "/" + 
                tag + ": " + message;
        
        // 记录到Android日志系统
        switch (level) {
            case VERBOSE:
                Log.v(tag, message);
                break;
            case DEBUG:
                Log.d(tag, message);
                break;
            case INFO:
                Log.i(tag, message);
                break;
            case WARN:
                Log.w(tag, message);
                break;
            case ERROR:
                Log.e(tag, message);
                break;
        }
        
        // 添加到内部缓冲区
        synchronized (internalLogBuffer) {
            internalLogBuffer.add(logEntry);
            // 限制缓冲区大小
            while (internalLogBuffer.size() > MAX_LOG_LINES) {
                internalLogBuffer.removeFirst();
            }
        }
    }
    
    /**
     * 记录调试日志
     */
    public void debug(String tag, String message) {
        internalLog(tag, message, LogLevel.DEBUG);
    }
    
    /**
     * 记录信息日志
     */
    public void info(String tag, String message) {
        internalLog(tag, message, LogLevel.INFO);
    }
    
    /**
     * 记录警告日志
     */
    public void warn(String tag, String message) {
        internalLog(tag, message, LogLevel.WARN);
    }
    
    /**
     * 记录错误日志
     */
    public void error(String tag, String message) {
        internalLog(tag, message, LogLevel.ERROR);
    }
    
    /**
     * 记录带异常的错误日志
     */
    public void error(String tag, String message, Throwable throwable) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        throwable.printStackTrace(pw);
        internalLog(tag, message + "\n" + sw.toString(), LogLevel.ERROR);
    }
    
    /**
     * 关闭日志管理器，释放资源
     */
    public void shutdown() {
        if (isShutdown) {
            return;
        }
        
        isShutdown = true;
        if (executorService != null && !executorService.isShutdown()) {
            try {
                executorService.shutdown();
                // 等待未完成的任务结束，最多等待2秒
                if (!executorService.awaitTermination(2, TimeUnit.SECONDS)) {
                    executorService.shutdownNow();
                }
            } catch (InterruptedException e) {
                // 重新设置中断状态
                Thread.currentThread().interrupt();
                Log.e(TAG, "关闭执行器时出错", e);
                executorService.shutdownNow();
            }
        }
    }
    
    /**
     * 清空当前日志缓冲区
     */
    public void clearLogs() {
        synchronized (logBuffer) {
            logBuffer.clear();
        }
        
        synchronized (internalLogBuffer) {
            internalLogBuffer.clear();
        }
        
        Process process = null;
        try {
            // 清空系统日志
            process = Runtime.getRuntime().exec(CLEAR_COMMAND);
            boolean completed = process.waitFor(2, TimeUnit.SECONDS); // 最多等待2秒
            
            if (!completed) {
                Log.w(TAG, "清除日志命令执行超时");
                process.destroyForcibly();
            }
        } catch (IOException | InterruptedException e) {
            Log.e(TAG, "清除系统日志失败", e);
            if (e instanceof InterruptedException) {
                // 重新设置中断状态
                Thread.currentThread().interrupt();
            }
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }
    
    /**
     * 异步获取系统日志
     * @param context 应用上下文
     * @param callback 回调函数
     */
    public void getLogsAsync(Context context, LogCallback callback) {
        if (context == null || callback == null) {
            Log.e(TAG, "getLogsAsync调用时上下文或回调为空");
            return;
        }
        
        if (isShutdown || executorService.isShutdown()) {
            Log.w(TAG, "LogManager已关闭，无法处理日志请求");
            safelyCallCallback(callback, "LogManager已关闭，无法获取日志");
            return;
        }
        
        // 如果已经有任务在运行，不重复执行
        if (isTaskRunning.get()) {
            Log.d(TAG, "日志获取任务已在运行，跳过此次请求");
            return;
        }
        
        try {
            final Context appContext = context.getApplicationContext();
            executorService.execute(() -> {
                // 标记任务开始运行
                if (!isTaskRunning.compareAndSet(false, true)) {
                    return;  // 已有其他任务在运行
                }
                
                try {
                    if (!isShutdown) {
                        String logs = getLogs(appContext);
                        safelyCallCallback(callback, logs);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "后台日志处理出错", e);
                    
                    // 获取详细的错误堆栈
                    StringWriter sw = new StringWriter();
                    PrintWriter pw = new PrintWriter(sw);
                    e.printStackTrace(pw);
                    
                    String errorMessage = "获取日志时出错: " + e.getMessage() + "\n" + sw.toString();
                    safelyCallCallback(callback, errorMessage);
                } finally {
                    // 标记任务已完成
                    isTaskRunning.set(false);
                }
            });
        } catch (RejectedExecutionException e) {
            Log.e(TAG, "提交日志任务到执行器失败", e);
            safelyCallCallback(callback, "无法启动日志收集任务: " + e.getMessage());
        } catch (Exception e) {
            Log.e(TAG, "提交日志任务时发生未知错误", e);
            safelyCallCallback(callback, "提交日志任务时发生未知错误: " + e.getMessage());
        }
    }
    
    /**
     * 安全地调用回调函数，确保不会因为回调异常而导致应用崩溃
     */
    private void safelyCallCallback(LogCallback callback, String logs) {
        if (callback == null) return;
        
        // 在主线程中安全地调用回调
        mainHandler.post(() -> {
            try {
                callback.onLogsReady(logs);
            } catch (Exception e) {
                Log.e(TAG, "调用回调函数失败", e);
            }
        });
    }
    
    /**
     * 同步获取系统日志
     * @param context 应用上下文
     * @return 日志内容
     */
    @NonNull
    public String getLogs(Context context) {
        if (context == null) {
            return "错误：上下文为空";
        }
        
        List<String> logLines = new ArrayList<>();
        Process process = null;
        BufferedReader bufferedReader = null;
        
        try {
            process = Runtime.getRuntime().exec(LOG_COMMAND);
            bufferedReader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()));
            
            String line;
            String packageName = context.getPackageName();
            int lineCount = 0;
            while ((line = bufferedReader.readLine()) != null) {
                // 限制处理的行数，防止过大的日志导致OOM
                if (++lineCount > MAX_LOG_LINES * 2) {
                    logLines.add("... 日志太大，已截断 ...");
                    break;
                }
                
                // 只保留与应用相关的日志
                if (line.contains(packageName) || 
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
        } catch (IOException e) {
            Log.e(TAG, "获取日志失败", e);
            return "获取日志时出错: " + e.getMessage();
        } finally {
            // 确保资源被关闭
            if (bufferedReader != null) {
                try {
                    bufferedReader.close();
                } catch (IOException e) {
                    Log.e(TAG, "关闭读取器时出错", e);
                }
            }
            
            if (process != null) {
                try {
                    // 等待进程结束
                    boolean completed = process.waitFor(1, TimeUnit.SECONDS);
                    if (!completed) {
                        // 进程未在1秒内结束，强制终止
                        process.destroyForcibly();
                        Log.w(TAG, "日志命令执行超时，已强制终止");
                    } else {
                        int exitValue = process.exitValue();
                        if (exitValue != 0) {
                            Log.w(TAG, "日志命令以非零状态退出: " + exitValue);
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    Log.e(TAG, "等待进程时被中断", e);
                    process.destroyForcibly();
                } finally {
                    process.destroy();
                }
            }
        }
        
        // 如果没有日志，尝试从缓冲区读取
        if (logLines.isEmpty()) {
            synchronized (logBuffer) {
                logLines.addAll(logBuffer);
            }
        }
        
        // 添加应用内部日志
        synchronized (internalLogBuffer) {
            if (!internalLogBuffer.isEmpty()) {
                logLines.add("=== 应用内部日志 ===");
                logLines.addAll(internalLogBuffer);
                logLines.add("=== 应用内部日志结束 ===");
            }
        }
        
        if (logLines.isEmpty()) {
            return "没有找到日志";
        }
        
        // 限制返回的日志大小，防止OOM
        if (logLines.size() > MAX_LOG_LINES) {
            logLines = logLines.subList(logLines.size() - MAX_LOG_LINES, logLines.size());
            logLines.add(0, "... 日志太大，仅显示最后 " + MAX_LOG_LINES + " 行 ...");
        }
        
        StringBuilder sb = new StringBuilder();
        for (String line : logLines) {
            sb.append(line).append("\n");
        }
        
        return sb.toString();
    }
    
    /**
     * 获取应用信息
     */
    public String getAppInfo(Context context) {
        if (context == null) return "";
        
        try {
            StringBuilder info = new StringBuilder();
            info.append("=== 应用信息 ===\n");
            
            // 应用版本
            String versionName = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0).versionName;
            int versionCode = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0).versionCode;
            info.append("应用版本: ").append(versionName).append(" (").append(versionCode).append(")\n");
            
            // 设备信息
            info.append("设备厂商: ").append(android.os.Build.MANUFACTURER).append("\n");
            info.append("设备型号: ").append(android.os.Build.MODEL).append("\n");
            info.append("Android 版本: ").append(android.os.Build.VERSION.RELEASE)
                    .append(" (API ").append(android.os.Build.VERSION.SDK_INT).append(")\n");
            
            // 系统信息
            info.append("系统架构: ").append(System.getProperty("os.arch")).append("\n");
            
            // 内存信息
            Runtime runtime = Runtime.getRuntime();
            long maxMemory = runtime.maxMemory() / 1024 / 1024;
            long totalMemory = runtime.totalMemory() / 1024 / 1024;
            long freeMemory = runtime.freeMemory() / 1024 / 1024;
            info.append("最大内存: ").append(maxMemory).append("MB\n");
            info.append("已分配内存: ").append(totalMemory).append("MB\n");
            info.append("空闲内存: ").append(freeMemory).append("MB\n");
            
            info.append("=== 应用信息结束 ===\n");
            
            return info.toString();
        } catch (Exception e) {
            Log.e(TAG, "获取应用信息失败", e);
            return "获取应用信息失败: " + e.getMessage();
        }
    }
    
    /**
     * 日志回调接口
     */
    public interface LogCallback {
        void onLogsReady(String logs);
    }
}