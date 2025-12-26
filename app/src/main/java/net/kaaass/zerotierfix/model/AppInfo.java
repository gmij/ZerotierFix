package net.kaaass.zerotierfix.model;

import android.graphics.drawable.Drawable;

/**
 * 应用信息模型，用于在UI中显示
 */
public class AppInfo {
    private String packageName;
    private String appName;
    private Drawable appIcon;
    private boolean isSystemApp;
    private boolean routeViaVpn;

    public AppInfo(String packageName, String appName, Drawable appIcon, boolean isSystemApp) {
        this.packageName = packageName;
        this.appName = appName;
        this.appIcon = appIcon;
        this.isSystemApp = isSystemApp;
        this.routeViaVpn = false; // 默认不通过VPN
    }

    public String getPackageName() {
        return packageName;
    }

    public void setPackageName(String packageName) {
        this.packageName = packageName;
    }

    public String getAppName() {
        return appName;
    }

    public void setAppName(String appName) {
        this.appName = appName;
    }

    public Drawable getAppIcon() {
        return appIcon;
    }

    public void setAppIcon(Drawable appIcon) {
        this.appIcon = appIcon;
    }

    public boolean isSystemApp() {
        return isSystemApp;
    }

    public void setSystemApp(boolean systemApp) {
        isSystemApp = systemApp;
    }

    public boolean isRouteViaVpn() {
        return routeViaVpn;
    }

    public void setRouteViaVpn(boolean routeViaVpn) {
        this.routeViaVpn = routeViaVpn;
    }
}
