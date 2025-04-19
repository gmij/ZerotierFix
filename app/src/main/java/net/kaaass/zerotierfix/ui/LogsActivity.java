package net.kaaass.zerotierfix.ui;

import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.widget.NestedScrollView;

import net.kaaass.zerotierfix.R;
import net.kaaass.zerotierfix.util.LogManager;

/**
 * 日志活动，用于显示应用日志
 */
public class LogsActivity extends AppCompatActivity {
    private static final String TAG = "LogsActivity";
    
    private TextView mLogsTextView;
    private ProgressBar mProgressBar;
    private NestedScrollView mScrollView;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_logs);
        
        // 设置返回按钮
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(R.string.logs_title);
        }
        
        // 初始化视图
        mLogsTextView = findViewById(R.id.logs_text);
        mProgressBar = findViewById(R.id.logs_progress);
        mScrollView = findViewById(R.id.logs_scroll);
        
        // 加载日志
        loadLogs();
    }
    
    /**
     * 加载日志
     */
    private void loadLogs() {
        showLoading(true);
        
        // 使用LogManager获取日志
        LogManager.getInstance().getLogsAsync(this, logs -> {
            showLoading(false);
            displayLogs(logs);
        });
    }
    
    /**
     * 显示加载状态
     */
    private void showLoading(boolean isLoading) {
        if (isLoading) {
            mProgressBar.setVisibility(View.VISIBLE);
            mLogsTextView.setVisibility(View.GONE);
        } else {
            mProgressBar.setVisibility(View.GONE);
            mLogsTextView.setVisibility(View.VISIBLE);
        }
    }
    
    /**
     * 显示日志内容
     */
    private void displayLogs(String logs) {
        if (logs == null || logs.isEmpty()) {
            mLogsTextView.setText(R.string.no_logs_found);
            return;
        }
        
        mLogsTextView.setText(logs);
        
        // 滚动到底部
        mScrollView.post(() -> mScrollView.fullScroll(View.FOCUS_DOWN));
    }
    
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_logs, menu);
        return true;
    }
    
    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        
        if (id == android.R.id.home) {
            // 处理返回按钮点击
            finish();
            return true;
        } else if (id == R.id.menu_item_refresh) {
            // 刷新日志
            Log.d(TAG, "刷新日志");
            loadLogs();
            return true;
        } else if (id == R.id.menu_item_clear) {
            // 清空日志
            Log.d(TAG, "清空日志");
            LogManager.getInstance().clearLogs();
            Toast.makeText(this, R.string.logs_cleared, Toast.LENGTH_SHORT).show();
            loadLogs();
            return true;
        }
        
        return super.onOptionsItemSelected(item);
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        // 不需要关闭LogManager，因为它是单例的，应该在应用退出时关闭
    }
}