package net.kaaass.zerotierfix.util;

import android.os.Build;
import android.text.TextUtils;

import java.lang.reflect.Method;

/**
 * 国产ROM检测工具类
 * 用于识别不同的Android厂商定制系统（MIUI、EMUI、ColorOS等）
 *
 * @author kaaass
 */
public class RomUtils {
    private static final String TAG = "RomUtils";

    /**
     * 检测是否为小米MIUI系统
     */
    public static boolean isMIUI() {
        return !TextUtils.isEmpty(getSystemProperty("ro.miui.ui.version.name"));
    }

    /**
     * 检测是否为华为EMUI/HarmonyOS系统
     */
    public static boolean isEMUI() {
        return !TextUtils.isEmpty(getSystemProperty("ro.build.version.emui")) ||
               Build.MANUFACTURER.equalsIgnoreCase("HUAWEI") ||
               Build.MANUFACTURER.equalsIgnoreCase("HONOR");
    }

    /**
     * 检测是否为OPPO ColorOS系统
     */
    public static boolean isColorOS() {
        return !TextUtils.isEmpty(getSystemProperty("ro.build.version.opporom")) ||
               Build.MANUFACTURER.equalsIgnoreCase("OPPO");
    }

    /**
     * 检测是否为vivo OriginOS系统
     */
    public static boolean isOriginOS() {
        String versionName = getSystemProperty("ro.vivo.os.version");
        return (!TextUtils.isEmpty(versionName) && versionName.contains("OriginOS")) ||
               Build.MANUFACTURER.equalsIgnoreCase("vivo");
    }

    /**
     * 检测是否为魅族Flyme系统
     */
    public static boolean isFlyme() {
        String display = Build.DISPLAY;
        String displayId = getSystemProperty("ro.build.display.id");

        return (display != null && display.toLowerCase().contains("flyme")) ||
               (!TextUtils.isEmpty(displayId) && displayId.toLowerCase().contains("flyme")) ||
               Build.MANUFACTURER.equalsIgnoreCase("Meizu");
    }

    /**
     * 获取ROM名称
     * @return ROM名称（MIUI、EMUI/HarmonyOS、ColorOS、OriginOS、Flyme或Android）
     */
    public static String getRomName() {
        if (isMIUI()) return "MIUI";
        if (isEMUI()) return "EMUI/HarmonyOS";
        if (isColorOS()) return "ColorOS";
        if (isOriginOS()) return "OriginOS";
        if (isFlyme()) return "Flyme";
        return "Android";
    }

    /**
     * 检测是否为国产ROM（需要特殊处理的ROM）
     * @return true表示是国产定制ROM
     */
    public static boolean isChineseRom() {
        return isMIUI() || isEMUI() || isColorOS() || isOriginOS() || isFlyme();
    }

    /**
     * 检测是否需要延迟启动VPN服务
     * MIUI和ColorOS在开机时可能需要延迟启动才能成功
     * @return true表示需要延迟启动
     */
    public static boolean needsDelayedStart() {
        return isMIUI() || isColorOS();
    }

    /**
     * 获取建议的启动延迟时间（毫秒）
     * @return 延迟时间，单位：毫秒
     */
    public static long getStartupDelay() {
        if (needsDelayedStart()) {
            return 3000; // 3秒延迟
        }
        return 0;
    }

    /**
     * 通过反射获取系统属性
     * @param propName 属性名
     * @return 属性值，如果获取失败返回null
     */
    private static String getSystemProperty(String propName) {
        try {
            Class<?> systemProperties = Class.forName("android.os.SystemProperties");
            Method get = systemProperties.getMethod("get", String.class);
            return (String) get.invoke(null, propName);
        } catch (Exception e) {
            LogUtil.d(TAG, "Failed to get system property: " + propName + ", " + e.getMessage());
            return null;
        }
    }
}
