package net.kaaass.zerotierfix.util;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;

import net.kaaass.zerotierfix.R;

/**
 * ROM权限引导工具类
 * 用于根据不同的国产ROM显示相应的权限设置指引
 *
 * @author kaaass
 */
public class RomPermissionGuide {
    private static final String TAG = "RomPermissionGuide";

    /**
     * 显示ROM特定的权限设置指引对话框
     * @param context 上下文
     */
    public static void showRomSpecificGuide(Context context) {
        String romName = RomUtils.getRomName();
        int messageResId = getGuideMessageResource(romName);

        new AlertDialog.Builder(context)
            .setTitle(R.string.rom_permission_guide_title)
            .setMessage(messageResId)
            .setPositiveButton(R.string.rom_permission_guide_button, (dialog, which) -> {
                openRomSettings(context);
            })
            .setNegativeButton(R.string.rom_permission_guide_later, null)
            .show();
    }

    /**
     * 显示开机自启动引导对话框
     * @param context 上下文
     */
    public static void showBootStartupGuide(Context context) {
        String romName = RomUtils.getRomName();
        String message = context.getString(R.string.boot_startup_guide_message, romName);

        new AlertDialog.Builder(context)
            .setTitle(R.string.boot_startup_guide_title)
            .setMessage(message)
            .setPositiveButton(R.string.rom_permission_guide_button, (dialog, which) -> {
                openRomSettings(context);
            })
            .setNegativeButton(R.string.rom_permission_guide_later, null)
            .show();
    }

    /**
     * 根据ROM名称获取对应的引导消息资源ID
     * @param romName ROM名称
     * @return 字符串资源ID
     */
    private static int getGuideMessageResource(String romName) {
        switch (romName) {
            case "MIUI":
                return R.string.rom_guide_miui;
            case "EMUI/HarmonyOS":
                return R.string.rom_guide_emui;
            case "ColorOS":
                return R.string.rom_guide_coloros;
            case "OriginOS":
                return R.string.rom_guide_originos;
            case "Flyme":
                return R.string.rom_guide_flyme;
            default:
                return R.string.rom_guide_generic;
        }
    }

    /**
     * 打开ROM特定的权限设置界面
     * @param context 上下文
     */
    private static void openRomSettings(Context context) {
        try {
            Intent intent = getRomSettingsIntent(context);
            if (intent != null) {
                context.startActivity(intent);
                return;
            }
        } catch (Exception e) {
            LogUtil.d(TAG, "Failed to open ROM-specific settings: " + e.getMessage());
        }

        // 如果无法打开ROM特定设置，打开通用应用详情页面
        openApplicationDetailsSettings(context);
    }

    /**
     * 获取ROM特定的设置Intent
     * @param context 上下文
     * @return Intent对象，如果不支持则返回null
     */
    private static Intent getRomSettingsIntent(Context context) {
        if (RomUtils.isMIUI()) {
            // 小米MIUI自启动管理
            Intent intent = new Intent();
            intent.setClassName("com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity");
            return intent;

        } else if (RomUtils.isEMUI()) {
            // 华为EMUI/HarmonyOS应用启动管理
            Intent intent = new Intent();
            intent.setClassName("com.huawei.systemmanager",
                "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity");
            return intent;

        } else if (RomUtils.isColorOS()) {
            // OPPO ColorOS - 打开电池优化设置
            Intent intent = new Intent();
            intent.setAction(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            intent.setData(Uri.parse("package:" + context.getPackageName()));
            return intent;

        } else if (RomUtils.isOriginOS()) {
            // vivo OriginOS - 打开应用详情
            return null; // 使用通用设置

        } else if (RomUtils.isFlyme()) {
            // 魅族Flyme权限管理
            Intent intent = new Intent("com.meizu.safe.security.SHOW_APPSEC");
            intent.putExtra("packageName", context.getPackageName());
            return intent;
        }

        return null;
    }

    /**
     * 打开应用详情设置页面
     * @param context 上下文
     */
    private static void openApplicationDetailsSettings(Context context) {
        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.parse("package:" + context.getPackageName()));
            context.startActivity(intent);
        } catch (Exception e) {
            LogUtil.e(TAG, "Failed to open application details settings", e);
        }
    }

    /**
     * 打开忽略电池优化设置
     * @param context 上下文
     */
    public static void openBatteryOptimizationSettings(Context context) {
        try {
            Intent intent = new Intent();
            intent.setAction(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            intent.setData(Uri.parse("package:" + context.getPackageName()));
            context.startActivity(intent);
        } catch (Exception e) {
            LogUtil.d(TAG, "Failed to open battery optimization settings: " + e.getMessage());
            // 如果失败，打开通用应用详情
            openApplicationDetailsSettings(context);
        }
    }
}
