package net.kaaass.zerotierfix.util;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

import net.kaaass.zerotierfix.model.AppInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 应用管理工具类
 * 用于获取系统中已安装的应用列表
 */
public class AppUtils {
    private static final String TAG = "AppUtils";

    /**
     * 获取所有已安装的应用（包括系统应用）
     *
     * @param context Context
     * @return 应用列表
     */
    public static List<AppInfo> getAllInstalledApps(Context context) {
        List<AppInfo> appList = new ArrayList<>();
        PackageManager packageManager = context.getPackageManager();

        List<PackageInfo> packages = packageManager.getInstalledPackages(0);
        for (PackageInfo packageInfo : packages) {
            ApplicationInfo appInfo = packageInfo.applicationInfo;

            // 获取应用名称
            String appName = (String) packageManager.getApplicationLabel(appInfo);

            // 获取应用图标，使用默认图标作为后备
            Drawable appIcon;
            try {
                appIcon = packageManager.getApplicationIcon(appInfo);
            } catch (Exception e) {
                // 如果获取图标失败，使用默认图标
                appIcon = packageManager.getDefaultActivityIcon();
                LogUtil.d(TAG, "Failed to get icon for " + packageInfo.packageName + ": " + e.getMessage());
            }

            // 判断是否为系统应用
            boolean isSystemApp = (appInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;

            AppInfo app = new AppInfo(
                    packageInfo.packageName,
                    appName,
                    appIcon,
                    isSystemApp
            );
            appList.add(app);
        }

        // 按应用名称排序
        Collections.sort(appList, new Comparator<AppInfo>() {
            @Override
            public int compare(AppInfo app1, AppInfo app2) {
                return app1.getAppName().compareToIgnoreCase(app2.getAppName());
            }
        });

        LogUtil.d(TAG, "Found " + appList.size() + " installed apps");
        return appList;
    }

    /**
     * 获取所有用户应用（非系统应用）
     *
     * @param context Context
     * @return 应用列表
     */
    public static List<AppInfo> getUserInstalledApps(Context context) {
        List<AppInfo> allApps = getAllInstalledApps(context);
        List<AppInfo> userApps = new ArrayList<>();

        for (AppInfo app : allApps) {
            if (!app.isSystemApp()) {
                userApps.add(app);
            }
        }

        LogUtil.d(TAG, "Found " + userApps.size() + " user apps");
        return userApps;
    }
}
