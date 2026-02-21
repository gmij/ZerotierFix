package net.kaaass.zerotierfix.service;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.preference.PreferenceManager;

import net.kaaass.zerotierfix.ZerotierFixApplication;
import net.kaaass.zerotierfix.model.Network;
import net.kaaass.zerotierfix.util.Constants;
import net.kaaass.zerotierfix.util.DatabaseUtils;
import net.kaaass.zerotierfix.util.LogUtil;

/**
 * 开机自启动接收器
 * 在设备启动完成后自动启动ZeroTier VPN服务
 */
public class StartupReceiver extends BroadcastReceiver {
    private static final String TAG = "StartupReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        LogUtil.i(TAG, "Received: " + action);

        // 只处理开机完成广播
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action) &&
            !Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)) {
            return;
        }

        // 检查用户设置是否允许开机自启动
        var pref = PreferenceManager.getDefaultSharedPreferences(context);
        if (!pref.getBoolean(Constants.PREF_GENERAL_START_ZEROTIER_ON_BOOT, true)) {
            LogUtil.i(TAG, "Preferences set to not start ZeroTier on boot");
            return;
        }

        LogUtil.i(TAG, "Starting ZeroTier service on boot");
        startZeroTierService(context);
    }

    /**
     * 启动ZeroTier服务
     * 从数据库中读取已加入的网络并启动服务
     */
    private void startZeroTierService(Context context) {
        try {
            var app = (ZerotierFixApplication) context.getApplicationContext();
            DatabaseUtils.readLock.lock();
            try {
                var networkDao = app.getDaoSession().getNetworkDao();
                var networks = networkDao.loadAll();

                if (networks.isEmpty()) {
                    LogUtil.i(TAG, "No networks to start");
                    return;
                }

                // 启动第一个网络的VPN服务
                // 注：目前只支持启动一个网络，未来可以根据需求扩展为启动所有网络
                for (Network network : networks) {
                    Intent serviceIntent = new Intent(context, ZeroTierOneService.class);
                    serviceIntent.putExtra(ZeroTierOneService.ZT1_NETWORK_ID, network.getNetworkId());

                    // Android 8.0+需要使用startForegroundService
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(serviceIntent);
                    } else {
                        context.startService(serviceIntent);
                    }

                    LogUtil.i(TAG, "Started ZeroTier service for network: " + network.getNetworkIdStr());
                    break; // 只启动第一个网络
                }
            } finally {
                DatabaseUtils.readLock.unlock();
            }
        } catch (Exception e) {
            LogUtil.e(TAG, "Failed to start ZeroTier service", e);
        }
    }
}
