package net.kaaass.zerotierfix.ui;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

/**
 * 网络列表 fragment 的容器 activity
 */
public class NetworkListActivity extends SingleFragmentActivity {
    private static final int REQUEST_POST_NOTIFICATIONS = 1001;

    @Override
    public void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        requestNotificationPermissionIfNeeded();
    }

    @Override
    public Fragment createFragment() {
        return new NetworkListFragment();
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return;
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED) {
            return;
        }
        ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.POST_NOTIFICATIONS},
                REQUEST_POST_NOTIFICATIONS);
    }
}
