package net.kaaass.zerotierfix.ui;

import androidx.fragment.app.Fragment;

/**
 * 应用路由设置Activity
 */
public class AppRoutingActivity extends SingleFragmentActivity {
    @Override
    public Fragment createFragment() {
        return new AppRoutingFragment();
    }
}
