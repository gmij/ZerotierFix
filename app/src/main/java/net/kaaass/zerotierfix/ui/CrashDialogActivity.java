package net.kaaass.zerotierfix.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import net.kaaass.zerotierfix.R;
import net.kaaass.zerotierfix.util.CrashHandler;

/**
 * 崩溃信息显示页面。
 * 在应用崩溃后启动，显示崩溃日志，提供复制和重启按钮。
 */
public class CrashDialogActivity extends AppCompatActivity {

    private String crashLog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        crashLog = CrashHandler.getLastCrashLog(this);
        if (crashLog == null) {
            // 没有崩溃日志，直接重启
            restartApp();
            return;
        }

        setContentView(R.layout.activity_crash_dialog);

        TextView tvCrashLog = findViewById(R.id.tv_crash_log);
        Button btnCopy = findViewById(R.id.btn_copy);
        Button btnRestart = findViewById(R.id.btn_restart);

        tvCrashLog.setText(crashLog);

        btnCopy.setOnClickListener(v -> {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null) {
                clipboard.setPrimaryClip(ClipData.newPlainText("crash_log", crashLog));
                Toast.makeText(this, "已复制到剪贴板", Toast.LENGTH_SHORT).show();
            }
        });

        btnRestart.setOnClickListener(v -> {
            CrashHandler.clearCrashLog(this);
            restartApp();
        });
    }

    @Override
    public void onBackPressed() {
        CrashHandler.clearCrashLog(this);
        restartApp();
    }

    private void restartApp() {
        Intent intent = new Intent(this, NetworkListActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }
}
