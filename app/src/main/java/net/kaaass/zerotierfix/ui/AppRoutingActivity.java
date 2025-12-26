package net.kaaass.zerotierfix.ui;

import android.os.Bundle;

import androidx.fragment.app.Fragment;

/**
 * 应用路由设置Activity
 */
public class AppRoutingActivity extends SingleFragmentActivity {
    @Override
    public Fragment createFragment() {
        AppRoutingFragment fragment = new AppRoutingFragment();
        
        // 传递网络ID
        Bundle args = new Bundle();
        if (getIntent().hasExtra(AppRoutingFragment.NETWORK_ID_MESSAGE)) {
            long networkId = getIntent().getLongExtra(AppRoutingFragment.NETWORK_ID_MESSAGE, 0);
            args.putLong(AppRoutingFragment.NETWORK_ID_MESSAGE, networkId);
        }
        fragment.setArguments(args);
        
        return fragment;
    }
}
