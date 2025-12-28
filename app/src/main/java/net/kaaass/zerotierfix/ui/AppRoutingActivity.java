package net.kaaass.zerotierfix.ui;

import android.os.Bundle;

import androidx.fragment.app.Fragment;

/**
 * 应用路由设置Activity - shows full app list for selection
 */
public class AppRoutingActivity extends SingleFragmentActivity {
    @Override
    public Fragment createFragment() {
        AppSelectionFragment fragment = new AppSelectionFragment();
        
        // 传递网络ID
        Bundle args = new Bundle();
        if (getIntent().hasExtra(AppSelectionFragment.NETWORK_ID_MESSAGE)) {
            long networkId = getIntent().getLongExtra(AppSelectionFragment.NETWORK_ID_MESSAGE, 0);
            args.putLong(AppSelectionFragment.NETWORK_ID_MESSAGE, networkId);
        }
        fragment.setArguments(args);
        
        return fragment;
    }
}
