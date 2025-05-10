package net.kaaass.zerotierfix.ui;

import android.os.Bundle;
import android.view.MenuItem;

import androidx.appcompat.app.ActionBar;
import androidx.fragment.app.Fragment;

/**
 * 已授权设备列表 fragment 容器 activity
 *
 * @author GitHub Copilot
 */
public class AuthorizedDevicesActivity extends SingleFragmentActivity {
    @Override
    public Fragment createFragment() {
        return new AuthorizedDevicesFragment();
    }

    @Override
    public void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        // 添加返回按钮
        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setHomeButtonEnabled(true);
            actionBar.setDisplayHomeAsUpEnabled(true);
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        super.onOptionsItemSelected(item);
        // 返回上一界面
        if (item.getItemId() == android.R.id.home) {
            this.finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
