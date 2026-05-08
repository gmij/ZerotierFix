package net.kaaass.zerotierfix.util;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Process;
import android.util.Log;

import net.kaaass.zerotierfix.ui.CrashDialogActivity;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 全局未捕获异常处理器。
 * 捕获崩溃后将堆栈信息保存到 SharedPreferences，然后启动 CrashDialogActivity 显示错误信息。
 */
public class CrashHandler implements Thread.UncaughtExceptionHandler {

    private static final String TAG = "CrashHandler";
    private static final String PREFS_NAME = "crash_handler";
    private static final String KEY_CRASH_LOG = "crash_log";
    private static final String KEY_CRASH_TIME = "crash_time";

    private final Context applicationContext;
    private final Thread.UncaughtExceptionHandler defaultHandler;

    public CrashHandler(Context context) {
        this.applicationContext = context.getApplicationContext();
        this.defaultHandler = Thread.getDefaultUncaughtExceptionHandler();
    }

    /**
     * 注册为全局异常处理器
     */
    public static void install(Context context) {
        Thread.setDefaultUncaughtExceptionHandler(new CrashHandler(context));
    }

    @Override
    public void uncaughtException(Thread thread, Throwable throwable) {
        try {
            // 收集崩溃信息
            String crashLog = buildCrashLog(thread, throwable);
            Log.e(TAG, "应用崩溃:\n" + crashLog);

            // 保存到 SharedPreferences
            SharedPreferences prefs = applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            prefs.edit()
                    .putString(KEY_CRASH_LOG, crashLog)
                    .putLong(KEY_CRASH_TIME, System.currentTimeMillis())
                    .commit();

            // 启动崩溃对话框 Activity
            Intent intent = new Intent(applicationContext, CrashDialogActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            applicationContext.startActivity(intent);
        } catch (Exception e) {
            Log.e(TAG, "崩溃处理器自身出错", e);
        } finally {
            // 终止当前进程
            Process.killProcess(Process.myPid());
        }
    }

    private String buildCrashLog(Thread thread, Throwable throwable) {
        StringBuilder sb = new StringBuilder();

        // 时间
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault());
        sb.append("崩溃时间: ").append(sdf.format(new Date())).append("\n");

        // 设备信息
        sb.append("设备: ").append(Build.MANUFACTURER).append(" ").append(Build.MODEL).append("\n");
        sb.append("Android: ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");

        // 线程信息
        sb.append("线程: ").append(thread.getName()).append(" (id=").append(thread.getId()).append(")\n");
        sb.append("\n");

        // 异常堆栈
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        throwable.printStackTrace(pw);
        sb.append(sw.toString());

        return sb.toString();
    }

    /**
     * 从 SharedPreferences 读取上次崩溃日志
     */
    public static String getLastCrashLog(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getString(KEY_CRASH_LOG, null);
    }

    /**
     * 清除已保存的崩溃日志
     */
    public static void clearCrashLog(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().clear().apply();
    }
}
