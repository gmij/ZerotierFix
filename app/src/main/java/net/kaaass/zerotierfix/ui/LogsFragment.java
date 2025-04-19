package net.kaaass.zerotierfix.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
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

import com.google.android.material.bottomappbar.BottomAppBar;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

import net.kaaass.zerotierfix.R;
import net.kaaass.zerotierfix.util.LogManager;

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

    private LogManager logManager;
    private String currentLogs = "";

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
        logManager = LogManager.getInstance();
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_logs, container, false);

        // 初始化视图
        logsContentView = view.findViewById(R.id.logs_content);
        emptyView = view.findViewById(R.id.logs_empty);
        scrollView = view.findViewById(R.id.logs_scroll_view);
        bottomAppBar = view.findViewById(R.id.bottom_app_bar);
        fabShare = view.findViewById(R.id.fab_share);

        // 设置底部工具栏菜单
        bottomAppBar.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id == R.id.menu_copy_logs) {
                copyLogsToClipboard();
                return true;
            } else if (id == R.id.menu_clear_logs) {
                clearLogs();
                return true;
            }
            return false;
        });

        // 设置分享按钮
        fabShare.setOnClickListener(v -> shareLogs());

        // 创建菜单
        MenuInflater inflater1 = getActivity().getMenuInflater();
        Menu menu = bottomAppBar.getMenu();
        inflater1.inflate(R.menu.menu_logs, menu);

        // 加载日志
        loadLogs();

        return view;
    }

    @Override
    public void onResume() {
        super.onResume();
        // 每次回到页面刷新日志
        loadLogs();
    }

    /**
     * 加载日志
     */
    private void loadLogs() {
        if (getContext() == null) return;

        logManager.getLogsAsync(requireContext(), logs -> {
            if (getActivity() == null) return;

            getActivity().runOnUiThread(() -> {
                currentLogs = logs;
                updateUI(logs);
            });
        });
    }

    /**
     * 更新UI显示
     */
    private void updateUI(String logs) {
        if (logs == null || logs.isEmpty()) {
            emptyView.setVisibility(View.VISIBLE);
            logsContentView.setVisibility(View.GONE);
        } else {
            emptyView.setVisibility(View.GONE);
            logsContentView.setVisibility(View.VISIBLE);
            logsContentView.setText(logs);

            // 滚动到底部
            scrollView.post(() -> scrollView.fullScroll(View.FOCUS_DOWN));
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

        ClipboardManager clipboard = (ClipboardManager) requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = ClipData.newPlainText("Zerotier Fix Logs", currentLogs);
        clipboard.setPrimaryClip(clip);
        Toast.makeText(getContext(), R.string.logs_copied, Toast.LENGTH_SHORT).show();
    }

    /**
     * 清空日志
     */
    private void clearLogs() {
        logManager.clearLogs();
        currentLogs = "";
        updateUI("");
        Toast.makeText(getContext(), R.string.logs_cleared, Toast.LENGTH_SHORT).show();
    }

    /**
     * 分享日志
     */
    private void shareLogs() {
        if (currentLogs == null || currentLogs.isEmpty()) {
            Toast.makeText(getContext(), R.string.logs_empty, Toast.LENGTH_SHORT).show();
            return;
        }

        Intent shareIntent = new Intent(Intent.ACTION_SEND);
        shareIntent.setType("text/plain");
        shareIntent.putExtra(Intent.EXTRA_SUBJECT, "Zerotier Fix Logs");
        shareIntent.putExtra(Intent.EXTRA_TEXT, currentLogs);
        startActivity(Intent.createChooser(shareIntent, getString(R.string.logs_share)));
    }
}