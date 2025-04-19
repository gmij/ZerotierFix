package net.kaaass.zerotierfix.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.widget.NestedScrollView;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.Lifecycle;

import com.google.android.material.bottomappbar.BottomAppBar;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.snackbar.Snackbar;

import net.kaaass.zerotierfix.R;
import net.kaaass.zerotierfix.util.LogManager;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.ref.WeakReference;

/**
 * 日志显示Fragment
 */
public class LogsFragment extends Fragment {

    private static final String TAG = "LogsFragment";

    private TextView logsContentView;
    private TextView emptyView;
    private NestedScrollView scrollView;
    private BottomAppBar bottomAppBar;
    private FloatingActionButton fabShare;
    private View rootView;

    private LogManager logManager;
    private String currentLogs = "";
    private boolean isFragmentActive = false;
    private LogCallback logCallback;
    private Handler mainHandler;
    private Thread.UncaughtExceptionHandler defaultExceptionHandler;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
        logManager = LogManager.getInstance();
        logCallback = new LogCallback(this);
        mainHandler = new Handler(Looper.getMainLooper());
        
        // 设置全局异常处理
        setupExceptionHandler();
    }
    
    /**
     * 设置全局异常处理器，防止应用直接崩溃
     */
    private void setupExceptionHandler() {
        try {
            // 保存默认异常处理器
            defaultExceptionHandler = Thread.getDefaultUncaughtExceptionHandler();
            
            // 设置自定义异常处理器
            Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
                try {
                    // 获取异常详细信息
                    StringWriter sw = new StringWriter();
                    PrintWriter pw = new PrintWriter(sw);
                    throwable.printStackTrace(pw);
                    String stackTrace = sw.toString();
                    
                    // 记录日志
                    Log.e(TAG, "捕获到未处理异常: " + throwable.getMessage(), throwable);
                    
                    // 使用主线程显示错误
                    if (mainHandler != null && rootView != null && isAdded()) {
                        mainHandler.post(() -> {
                            try {
                                // 用Snackbar显示错误提示
                                if (rootView != null && isAdded()) {
                                    Snackbar.make(rootView, "发生错误: " + throwable.getMessage(), 
                                            Snackbar.LENGTH_LONG).show();
                                    
                                    // 将异常详情添加到日志内容中
                                    String errorDetail = "--- 应用发生异常 ---\n" + 
                                            stackTrace + 
                                            "\n--- 异常信息结束 ---\n\n";
                                    currentLogs = errorDetail + currentLogs;
                                    updateUI(currentLogs);
                                }
                            } catch (Exception e) {
                                Log.e(TAG, "显示错误信息时发生异常", e);
                                // 如果显示失败，交给默认处理器
                                if (defaultExceptionHandler != null) {
                                    defaultExceptionHandler.uncaughtException(thread, throwable);
                                }
                            }
                        });
                    } else {
                        // 如果无法在UI上显示，交给默认处理器
                        if (defaultExceptionHandler != null) {
                            defaultExceptionHandler.uncaughtException(thread, throwable);
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "处理异常时出错", e);
                    // 如果异常处理器本身出错，使用默认处理器
                    if (defaultExceptionHandler != null) {
                        defaultExceptionHandler.uncaughtException(thread, throwable);
                    }
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "设置异常处理器失败", e);
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        rootView = inflater.inflate(R.layout.fragment_logs, container, false);

        // 初始化视图
        logsContentView = rootView.findViewById(R.id.logs_content);
        emptyView = rootView.findViewById(R.id.logs_empty);
        scrollView = rootView.findViewById(R.id.logs_scroll_view);
        bottomAppBar = rootView.findViewById(R.id.bottom_app_bar);
        fabShare = rootView.findViewById(R.id.fab_share);

        try {
            // 设置底部工具栏菜单
            bottomAppBar.setOnMenuItemClickListener(item -> {
                try {
                    int id = item.getItemId();
                    if (id == R.id.menu_item_refresh) {
                        copyLogsToClipboard();
                        return true;
                    } else if (id == R.id.menu_item_clear) {
                        clearLogs();
                        return true;
                    }
                } catch (Exception e) {
                    showError("菜单操作失败", e);
                }
                return false;
            });

            // 设置分享按钮
            fabShare.setOnClickListener(v -> {
                try {
                    shareLogs();
                } catch (Exception e) {
                    showError("分享日志失败", e);
                }
            });

            // 创建菜单
            if (getActivity() != null) {
                MenuInflater inflater1 = getActivity().getMenuInflater();
                Menu menu = bottomAppBar.getMenu();
                inflater1.inflate(R.menu.menu_logs, menu);
            }
        } catch (Exception e) {
            showError("初始化界面组件失败", e);
        }

        return rootView;
    }

    @Override
    public void onResume() {
        super.onResume();
        isFragmentActive = true;
        // 每次回到页面刷新日志
        loadLogs();
    }
    
    @Override
    public void onPause() {
        super.onPause();
        isFragmentActive = false;
    }
    
    @Override
    public void onDestroy() {
        // 恢复默认异常处理器
        if (defaultExceptionHandler != null) {
            try {
                Thread.setDefaultUncaughtExceptionHandler(defaultExceptionHandler);
            } catch (Exception e) {
                Log.e(TAG, "恢复默认异常处理器失败", e);
            }
        }
        
        // 清除回调引用，避免内存泄漏
        if (logCallback != null) {
            logCallback.detach();
            logCallback = null;
        }
        
        mainHandler = null;
        super.onDestroy();
    }

    /**
     * 加载日志
     */
    private void loadLogs() {
        if (getContext() == null) return;

        try {
            // 使用静态内部类保持回调，避免内存泄漏
            logManager.getLogsAsync(requireContext(), logCallback);
        } catch (Exception e) {
            showError("加载日志失败", e);
            if (isFragmentActive && isAdded()) {
                updateUI("加载日志时出错: " + e.getMessage());
            }
        }
    }
    
    /**
     * 显示错误信息
     */
    private void showError(String message, Exception e) {
        Log.e(TAG, message, e);
        if (rootView != null && isAdded()) {
            try {
                // 使用Snackbar显示错误
                Snackbar.make(rootView, message + ": " + e.getMessage(), Snackbar.LENGTH_LONG).show();
                
                // 记录错误到日志内容
                StringWriter sw = new StringWriter();
                PrintWriter pw = new PrintWriter(sw);
                e.printStackTrace(pw);
                String errorDetail = "--- 错误信息 ---\n" + 
                        message + ": " + e.getMessage() + "\n" +
                        sw.toString() + 
                        "\n--- 错误信息结束 ---\n\n";
                currentLogs = errorDetail + currentLogs;
                updateUI(currentLogs);
            } catch (Exception ex) {
                Log.e(TAG, "显示错误信息失败", ex);
            }
        }
    }

    /**
     * 更新UI显示
     */
    private void updateUI(String logs) {
        if (!isFragmentActive || !isAdded() || getActivity() == null) return;
        
        try {
            if (logs == null || logs.isEmpty()) {
                emptyView.setVisibility(View.VISIBLE);
                logsContentView.setVisibility(View.GONE);
            } else {
                emptyView.setVisibility(View.GONE);
                logsContentView.setVisibility(View.VISIBLE);
                logsContentView.setText(logs);

                // 滚动到底部
                scrollView.post(() -> {
                    try {
                        if (scrollView != null && isAdded()) {
                            scrollView.fullScroll(View.FOCUS_DOWN);
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "滚动到底部失败", e);
                    }
                });
            }
        } catch (Exception e) {
            Log.e(TAG, "更新UI失败", e);
            try {
                if (rootView != null && isAdded()) {
                    Snackbar.make(rootView, "更新日志显示失败: " + e.getMessage(), Snackbar.LENGTH_SHORT).show();
                }
            } catch (Exception snackbarEx) {
                Log.e(TAG, "显示Snackbar失败", snackbarEx);
            }
        }
    }

    /**
     * 复制日志到剪贴板
     */
    private void copyLogsToClipboard() {
        if (currentLogs == null || currentLogs.isEmpty()) {
            Toast.makeText(getContext(), R.string.logs_empty, Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            ClipboardManager clipboard = (ClipboardManager) requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = ClipData.newPlainText("Zerotier Fix Logs", currentLogs);
            clipboard.setPrimaryClip(clip);
            Toast.makeText(getContext(), R.string.logs_copied, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            showError("复制到剪贴板失败", e);
        }
    }

    /**
     * 清空日志
     */
    private void clearLogs() {
        try {
            logManager.clearLogs();
            currentLogs = "";
            updateUI("");
            Toast.makeText(getContext(), R.string.logs_cleared, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            showError("清除日志失败", e);
        }
    }

    /**
     * 分享日志
     */
    private void shareLogs() {
        if (currentLogs == null || currentLogs.isEmpty()) {
            Toast.makeText(getContext(), R.string.logs_empty, Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            Intent shareIntent = new Intent(Intent.ACTION_SEND);
            shareIntent.setType("text/plain");
            shareIntent.putExtra(Intent.EXTRA_SUBJECT, "Zerotier Fix Logs");
            shareIntent.putExtra(Intent.EXTRA_TEXT, currentLogs);
            startActivity(Intent.createChooser(shareIntent, getString(R.string.logs_share)));
        } catch (Exception e) {
            showError("分享日志失败", e);
        }
    }
    
    /**
     * 使用静态内部类处理日志回调，避免内存泄漏
     */
    private static class LogCallback implements LogManager.LogCallback {
        private WeakReference<LogsFragment> fragmentReference;
        
        public LogCallback(LogsFragment fragment) {
            this.fragmentReference = new WeakReference<>(fragment);
        }
        
        public void detach() {
            fragmentReference.clear();
        }
        
        @Override
        public void onLogsReady(String logs) {
            LogsFragment fragment = fragmentReference.get();
            if (fragment == null || !fragment.isFragmentActive || !fragment.isAdded()) return;
            
            try {
                fragment.getActivity().runOnUiThread(() -> {
                    try {
                        LogsFragment fragmentInstance = fragmentReference.get();
                        if (fragmentInstance != null && fragmentInstance.isAdded() && 
                            fragmentInstance.getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.RESUMED)) {
                            fragmentInstance.currentLogs = logs;
                            fragmentInstance.updateUI(logs);
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "日志UI更新失败", e);
                        LogsFragment fragmentInstance = fragmentReference.get();
                        if (fragmentInstance != null && fragmentInstance.rootView != null && fragmentInstance.isAdded()) {
                            try {
                                Snackbar.make(fragmentInstance.rootView, "更新日志失败: " + e.getMessage(), 
                                        Snackbar.LENGTH_SHORT).show();
                            } catch (Exception snackbarEx) {
                                Log.e(TAG, "显示错误Snackbar失败", snackbarEx);
                            }
                        }
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "发送到主线程失败", e);
            }
        }
    }
}