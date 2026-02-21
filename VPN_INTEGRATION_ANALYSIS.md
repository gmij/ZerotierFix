# Android VPN Integration Analysis for ZerotierFix

## Issue Summary

**Original Request (Translated from Chinese):**
> The current version is functioning normally in all aspects, but there's one imperfection. The built-in Android VPN cannot be started directly; it needs to be opened separately, and Android's own smart networking cannot be reused (meaning specifying which apps use the VPN). Analyze whether this can be resolved.

## Current Implementation Status

### What ZerotierFix Currently Does

The application already implements **both** requested features:

1. **Automatic VPN Start**: The VPN service (`ZeroTierOneService`) is automatically started when a network is joined
   - Location: `/app/src/main/java/net/kaaass/zerotierfix/service/ZeroTierOneService.java:434`
   - Method: `onStartCommand()` automatically calls `joinNetwork(networkId)`
   - The VPN connection is established without requiring manual activation

2. **Per-App VPN Routing** (Android's "Smart Networking"): Fully implemented and functional
   - Location: `/app/src/main/java/net/kaaass/zerotierfix/service/ZeroTierOneService.java:1103-1195`
   - Method: `configureAllowedDisallowedApps()`
   - Uses Android's `VpnService.Builder.addAllowedApplication()` API
   - UI for app selection: `NetworkDetailFragment`, `AppRoutingFragment`, `AppSelectionFragment`

### How the VPN Service Works

#### Automatic Startup Flow:
```
User joins ZeroTier network
    ↓
NetworkDetailActivity/UI calls service
    ↓
ZeroTierOneService.onStartCommand() receives Intent
    ↓
Service automatically calls joinNetwork(networkId)
    ↓
VPN tunnel is established via updateTunnelConfig()
    ↓
VpnService.Builder.establish() creates VPN connection
    ↓
Android system shows VPN icon in status bar
```

#### Per-App VPN Configuration:
```
User unchecks "Route All Traffic" in NetworkDetailFragment
    ↓
Per-app routing mode enabled (perAppRouting = true)
    ↓
AppRoutingFragment shows list of selected apps
    ↓
User clicks "Add Apps" → AppSelectionFragment
    ↓
Selected apps stored in AppRouting database table
    ↓
configureAllowedDisallowedApps() reads database
    ↓
builder.addAllowedApplication(packageName) for each selected app
    ↓
Only selected apps use VPN; others use direct connection
```

## 国产安卓系统特殊考虑 (Chinese Android OEM Considerations)

### 主流厂商VPN处理差异

中国主流Android厂商（小米MIUI、华为EMUI/HarmonyOS、OPPO ColorOS、vivo OriginOS、魅族Flyme等）对VPN功能有特殊的处理和限制：

#### 1. 小米 MIUI

**已知问题：**
- **自启动限制**：MIUI默认禁止应用自启动，包括BOOT_COMPLETED广播
  - 用户必须在"设置 → 应用设置 → 授权管理 → 自启动管理"中手动允许
  - StartupReceiver即使实现正确也可能不工作
- **后台运行限制**：MIUI积极清理后台应用
  - VPN服务可能被"神隐模式"或"省电优化"杀掉
  - 需要在"省电与电池 → 应用智能省电模式 → 无限制"设置
- **VPN权限二次确认**：某些MIUI版本在VPN连接时会弹出额外的安全提示

**解决方案：**
```java
// 检测MIUI并引导用户设置
private boolean isMIUI() {
    return !TextUtils.isEmpty(getSystemProperty("ro.miui.ui.version.name"));
}

// 打开MIUI自启动管理
Intent intent = new Intent();
intent.setClassName("com.miui.securitycenter",
    "com.miui.permcenter.autostart.AutoStartManagementActivity");
startActivity(intent);
```

#### 2. 华为 EMUI/HarmonyOS

**已知问题：**
- **受保护应用列表**：应用不在"受保护应用"列表中会被清理
- **启动管理严格**：类似MIUI，需要手动允许自启动
- **VPN应用白名单**：部分华为设备对VPN应用有特殊审核
- **省电模式影响**：华为的省电策略可能强制关闭VPN连接

**解决方案：**
```java
// 检测华为系统
private boolean isHuawei() {
    return Build.MANUFACTURER.equalsIgnoreCase("HUAWEI") ||
           Build.MANUFACTURER.equalsIgnoreCase("HONOR");
}

// 引导用户设置受保护应用
Intent intent = new Intent();
intent.setClassName("com.huawei.systemmanager",
    "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity");
startActivity(intent);
```

#### 3. OPPO ColorOS

**已知问题：**
- **自启动白名单**：需要在"设置 → 应用管理 → 自启动"中添加
- **后台冻结**：ColorOS会冻结后台应用，VPN可能断开
- **电池优化严格**：需要关闭"电池优化"

**解决方案：**
```java
// 检测OPPO
private boolean isOPPO() {
    return Build.MANUFACTURER.equalsIgnoreCase("OPPO");
}

// 引导用户关闭电池优化
Intent intent = new Intent();
intent.setAction(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
intent.setData(Uri.parse("package:" + getPackageName()));
startActivity(intent);
```

#### 4. vivo OriginOS

**已知问题：**
- **后台高耗电控制**：VPN服务可能被识别为高耗电应用
- **自启动需申请**：需要在"i管家 → 应用管理 → 自启动"中设置
- **Pure模式限制**：Pure模式可能阻止VPN连接

**解决方案：**
- 提示用户将应用加入"后台高耗电"白名单
- 引导关闭Pure模式或将应用加入信任列表

#### 5. 魅族 Flyme

**已知问题：**
- **待机耗电管理**：Flyme的待机耗电管理可能关闭VPN
- **自启动管理**：需要在"手机管家 → 权限管理 → 自启动管理"设置
- **网络监控**：Flyme对VPN流量有特殊监控

**解决方案：**
```java
// 检测魅族
private boolean isMeizu() {
    return Build.MANUFACTURER.equalsIgnoreCase("Meizu");
}

// 引导用户设置
Intent intent = new Intent("com.meizu.safe.security.SHOW_APPSEC");
intent.putExtra("packageName", getPackageName());
startActivity(intent);
```

### 通用解决方案建议

#### 1. 添加厂商检测工具类

**新建文件**: `/app/src/main/java/net/kaaass/zerotierfix/util/RomUtils.java`

```java
public class RomUtils {
    private static final String TAG = "RomUtils";

    public static boolean isMIUI() {
        return !TextUtils.isEmpty(getSystemProperty("ro.miui.ui.version.name"));
    }

    public static boolean isEMUI() {
        return !TextUtils.isEmpty(getSystemProperty("ro.build.version.emui"));
    }

    public static boolean isColorOS() {
        return !TextUtils.isEmpty(getSystemProperty("ro.build.version.opporom"));
    }

    public static boolean isOriginOS() {
        String versionName = getSystemProperty("ro.vivo.os.version");
        return !TextUtils.isEmpty(versionName) && versionName.contains("OriginOS");
    }

    public static boolean isFlyme() {
        return Build.DISPLAY.toLowerCase().contains("flyme") ||
               !TextUtils.isEmpty(getSystemProperty("ro.build.display.id"))
                   && getSystemProperty("ro.build.display.id").toLowerCase().contains("flyme");
    }

    public static String getRomName() {
        if (isMIUI()) return "MIUI";
        if (isEMUI()) return "EMUI/HarmonyOS";
        if (isColorOS()) return "ColorOS";
        if (isOriginOS()) return "OriginOS";
        if (isFlyme()) return "Flyme";
        return "Android";
    }

    private static String getSystemProperty(String propName) {
        try {
            Class<?> systemProperties = Class.forName("android.os.SystemProperties");
            Method get = systemProperties.getMethod("get", String.class);
            return (String) get.invoke(null, propName);
        } catch (Exception e) {
            return null;
        }
    }
}
```

#### 2. 添加权限引导帮助界面

在应用首次启动或VPN无法连接时，根据检测到的ROM显示相应的设置引导：

```java
public class PermissionGuideActivity extends AppCompatActivity {

    private void showRomSpecificGuide() {
        String romName = RomUtils.getRomName();
        String guideMessage = "";

        switch (romName) {
            case "MIUI":
                guideMessage = "MIUI用户需要：\n" +
                    "1. 允许自启动：设置 → 应用设置 → 授权管理 → 自启动管理\n" +
                    "2. 关闭省电限制：省电与电池 → 应用智能省电 → 无限制\n" +
                    "3. 锁定后台：最近任务中长按应用图标 → 锁定";
                break;
            case "EMUI/HarmonyOS":
                guideMessage = "华为/荣耀用户需要：\n" +
                    "1. 加入受保护应用：设置 → 应用 → 应用启动管理\n" +
                    "2. 忽略电池优化：设置 → 电池 → 应用启动管理\n" +
                    "3. 允许后台活动";
                break;
            case "ColorOS":
                guideMessage = "OPPO用户需要：\n" +
                    "1. 允许自启动：设置 → 应用管理 → 自启动\n" +
                    "2. 关闭电池优化：设置 → 电池 → 应用耗电管理";
                break;
            case "OriginOS":
                guideMessage = "vivo用户需要：\n" +
                    "1. 允许自启动：i管家 → 应用管理 → 自启动\n" +
                    "2. 加入后台高耗电白名单";
                break;
            case "Flyme":
                guideMessage = "魅族用户需要：\n" +
                    "1. 允许自启动：手机管家 → 权限管理 → 自启动管理\n" +
                    "2. 待机耗电管理中允许后台运行";
                break;
            default:
                guideMessage = "为确保VPN正常工作，请：\n" +
                    "1. 关闭电池优化\n" +
                    "2. 允许后台运行\n" +
                    "3. 允许自启动";
        }

        new AlertDialog.Builder(this)
            .setTitle("系统权限设置指引")
            .setMessage(guideMessage)
            .setPositiveButton("去设置", (dialog, which) -> openRomSettings())
            .setNegativeButton("稍后", null)
            .show();
    }

    private void openRomSettings() {
        try {
            Intent intent = getRomSettingsIntent();
            if (intent != null) {
                startActivity(intent);
            }
        } catch (Exception e) {
            // 打开通用设置
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        }
    }

    private Intent getRomSettingsIntent() {
        if (RomUtils.isMIUI()) {
            Intent intent = new Intent();
            intent.setClassName("com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity");
            return intent;
        } else if (RomUtils.isEMUI()) {
            Intent intent = new Intent();
            intent.setClassName("com.huawei.systemmanager",
                "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity");
            return intent;
        }
        // 其他厂商返回null，使用通用设置
        return null;
    }
}
```

#### 3. 增强StartupReceiver的兼容性

除了修复启动服务的bug，还需要考虑厂商限制：

```java
public class StartupReceiver extends BroadcastReceiver {
    private static final String TAG = "StartupReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        Log.i(TAG, "Received: " + action + " on " + RomUtils.getRomName());

        if (!Intent.ACTION_BOOT_COMPLETED.equals(action) &&
            !Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)) {
            return;
        }

        var pref = PreferenceManager.getDefaultSharedPreferences(context);
        if (!pref.getBoolean(Constants.PREF_GENERAL_START_ZEROTIER_ON_BOOT, true)) {
            Log.i(TAG, "Preferences set to not start ZeroTier on boot");
            return;
        }

        Log.i(TAG, "Starting ZeroTier service on boot (ROM: " + RomUtils.getRomName() + ")");

        // 对于某些ROM，延迟启动可能更可靠
        if (RomUtils.isMIUI() || RomUtils.isColorOS()) {
            // MIUI和ColorOS可能需要延迟
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                startZeroTierService(context);
            }, 3000); // 延迟3秒
        } else {
            startZeroTierService(context);
        }
    }

    private void startZeroTierService(Context context) {
        try {
            var app = (ZerotierFixApplication) context.getApplicationContext();
            DatabaseUtils.readLock.lock();
            try {
                var networkDao = app.getDaoSession().getNetworkDao();
                var networks = networkDao.loadAll();

                if (networks.isEmpty()) {
                    Log.i(TAG, "No networks to start");
                    return;
                }

                for (Network network : networks) {
                    Intent serviceIntent = new Intent(context, ZeroTierOneService.class);
                    serviceIntent.putExtra(ZeroTierOneService.ZT1_NETWORK_ID, network.getNetworkId());

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(serviceIntent);
                    } else {
                        context.startService(serviceIntent);
                    }

                    Log.i(TAG, "Started ZeroTier service for network: " + network.getNetworkIdStr());
                    break; // 只启动第一个网络
                }
            } finally {
                DatabaseUtils.readLock.unlock();
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to start ZeroTier service", e);
        }
    }
}
```

#### 4. 添加VPN连接状态监控

对于国产ROM，VPN可能被意外杀掉，需要监控并自动重连：

```java
public class VpnConnectionMonitor extends BroadcastReceiver {
    private static final String TAG = "VpnConnectionMonitor";

    @Override
    public void onReceive(Context context, Intent intent) {
        // 监听网络变化，检查VPN是否断开
        ConnectivityManager cm = (ConnectivityManager)
            context.getSystemService(Context.CONNECTIVITY_SERVICE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Network network = cm.getActiveNetwork();
            NetworkCapabilities capabilities = cm.getNetworkCapabilities(network);

            if (capabilities != null) {
                boolean isVpn = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN);

                if (!isVpn && shouldBeConnected(context)) {
                    Log.w(TAG, "VPN disconnected unexpectedly, attempting to reconnect");
                    // 尝试重新连接
                    restartVpnService(context);
                }
            }
        }
    }

    private boolean shouldBeConnected(Context context) {
        // 检查是否应该连接（根据应用状态和用户设置）
        return true; // 简化实现
    }

    private void restartVpnService(Context context) {
        // 重启VPN服务
    }
}
```

### 测试建议

在以下设备/系统上进行完整测试：

1. **小米**：MIUI 12/13/14 (基于Android 11/12/13)
2. **华为**：EMUI 11/12, HarmonyOS 2/3/4
3. **OPPO**：ColorOS 11/12/13
4. **vivo**：OriginOS 2/3
5. **魅族**：Flyme 9/10
6. **原生Android**：作为基准对比

**重点测试场景：**
- 设备重启后VPN自动连接
- 应用被系统清理后能否恢复
- 省电模式/超级省电模式下的行为
- 长时间后台运行的稳定性
- 多个VPN应用共存的兼容性

## Android VPN API Analysis

### What Android VPN API Provides

Android's `VpnService` API (available since Android 4.0 / API 14) provides the following capabilities:

1. **User Consent Model**:
   - VPN connections **require explicit user approval** on first use
   - `VpnService.prepare()` must be called to request permission
   - System shows a dialog: "App wants to set up a VPN connection that allows it to monitor network traffic"
   - This is a **security feature** that cannot be bypassed

2. **Per-App VPN (Android 5.0+ / API 21)**:
   - `Builder.addAllowedApplication(String packageName)`: Whitelist mode (only these apps use VPN)
   - `Builder.addDisallowedApplication(String packageName)`: Blacklist mode (all except these use VPN)
   - Cannot mix both modes in a single VPN connection
   - **Already implemented** in ZerotierFix

3. **Always-On VPN (Android 7.0+ / API 24)**:
   - System setting: Settings → Network & Internet → VPN → Gear icon → Always-on VPN toggle
   - User can enable "Always-on VPN" + "Block connections without VPN"
   - This is a **user-configured** setting, not app-initiated
   - App can detect if it's set as always-on via `VpnService.isAlwaysOn()` (API 29+)

4. **VPN Service Lifecycle**:
   - Service must extend `VpnService` (✅ already done)
   - Must declare `android.permission.BIND_VPN_SERVICE` (✅ already in manifest)
   - Service is started via `startService()` or `bindService()` (✅ already implemented)
   - VPN connection is active as long as the FileDescriptor from `establish()` is open (✅ already managed)

### What Cannot Be Automated

The following are **Android platform limitations** that cannot be programmatically bypassed:

1. **Initial VPN Permission Dialog**:
   - **Cannot be automated** - requires user tap on system dialog
   - This is by design for security/privacy protection
   - Once granted, permission persists until app is uninstalled
   - Location: User must tap "OK" on first VPN connection attempt

2. **Always-On VPN Setting**:
   - **Cannot be enabled programmatically** by the app
   - User must manually navigate to: Settings → VPN → ZerotierFix → Enable "Always-on VPN"
   - This is a deliberate Android security restriction (prevents malicious VPN apps from forcing themselves on)

3. **VPN Connection Notification**:
   - Android **requires** a visible notification when VPN is active
   - This is mandatory and cannot be hidden
   - Location: `/app/src/main/java/net/kaaass/zerotierfix/service/ZeroTierOneService.java:1053-1061`

## Analysis: Is the User's Concern Valid?

### Possibility 1: User Confusion About VPN Permission Dialog

**Symptom**: On first launch, user sees Android's VPN permission dialog and interprets this as "needing to open VPN separately"

**Reality**: This is the standard Android VPN permission flow. After granting permission once:
- VPN starts automatically when ZeroTier network is joined
- No manual VPN activation required on subsequent uses
- Permission persists across app restarts

**Solution**: This is working as designed. No code changes needed. Consider adding in-app explanation:
```
"On first use, Android will ask for VPN permission.
After granting permission, the VPN will start automatically."
```

### Possibility 2: User Wants "Always-On VPN" Auto-Configuration

**Symptom**: User wants the app to automatically enable Android's "Always-on VPN" system setting

**Reality**: This is **not possible** due to Android security restrictions. The user must:
1. Go to Settings → Network & Internet → VPN
2. Tap the gear icon next to ZerotierFix
3. Toggle "Always-on VPN"
4. Optionally enable "Block connections without VPN"

**Solution**: Cannot be automated. Best approach:
- Add a help screen with instructions on enabling Always-on VPN
- Add a button that opens Android VPN settings using `ACTION_VPN_SETTINGS` intent
- Detect if always-on is enabled and show status in UI

### Possibility 3: User Wants VPN to Start at Device Boot

**Symptom**: User wants ZeroTier VPN to automatically connect when Android device boots

**Current State**:
- ✅ `StartupReceiver` already exists (`AndroidManifest.xml:62-68`)
- ✅ Listens for `BOOT_COMPLETED` broadcast
- ❌ **ISSUE FOUND**: `StartupReceiver` only logs but **does NOT start the service**
  - File: `/app/src/main/java/net/kaaass/zerotierfix/service/StartupReceiver.java:15-23`
  - Current behavior: Checks preference, logs message, but doesn't call `startService()`
  - Result: VPN does NOT automatically start on boot despite preference setting

**Solution**: Implement actual service startup in `StartupReceiver`

### Possibility 4: User Confused About Per-App vs Global Routing

**Symptom**: User thinks per-app routing is not working or is separate from Android's system VPN

**Reality**: The app correctly uses Android's native per-app VPN API (`addAllowedApplication`). This **is** Android's official "smart networking" mechanism.

**Solution**: UI/UX improvement - clarify that:
- When "Route All Traffic" is unchecked, the app list **is** Android's per-app VPN
- The selected apps will show ZerotierFix as their active VPN in Android system settings
- Other apps bypass the VPN and use direct connection

## Recommendations

### 1. Fix Boot Startup (HIGH PRIORITY - Bug Fix)

**Issue**: `StartupReceiver` currently does NOT start the VPN service on boot.

**File**: `/app/src/main/java/net/kaaass/zerotierfix/service/StartupReceiver.java`

**Current Code** (lines 15-23):
```java
public void onReceive(Context context, Intent intent) {
    Log.i(TAG, "Received: " + intent.getAction() + ". Starting ZeroTier One service.");
    var pref = PreferenceManager.getDefaultSharedPreferences(context);
    if (pref.getBoolean(Constants.PREF_GENERAL_START_ZEROTIER_ON_BOOT, true)) {
        Log.i(TAG, "Preferences set to start ZeroTier on boot");
    } else {
        Log.i(TAG, "Preferences set to not start ZeroTier on boot");
    }
    // ❌ NO SERVICE STARTUP CODE - Only logs!
}
```

**Required Fix**:
```java
public void onReceive(Context context, Intent intent) {
    Log.i(TAG, "Received: " + intent.getAction() + ". Starting ZeroTier One service.");
    var pref = PreferenceManager.getDefaultSharedPreferences(context);
    if (!pref.getBoolean(Constants.PREF_GENERAL_START_ZEROTIER_ON_BOOT, true)) {
        Log.i(TAG, "Preferences set to not start ZeroTier on boot");
        return;
    }

    Log.i(TAG, "Starting ZeroTier service on boot");

    // Get the last connected network from database
    var app = (ZerotierFixApplication) context.getApplicationContext();
    DatabaseUtils.readLock.lock();
    try {
        var networkDao = app.getDaoSession().getNetworkDao();
        var networks = networkDao.loadAll();

        // Find networks that should auto-connect (e.g., last used or marked for startup)
        for (Network network : networks) {
            // Start service for each network (or just the first one)
            Intent serviceIntent = new Intent(context, ZeroTierOneService.class);
            serviceIntent.putExtra(ZeroTierOneService.ZT1_NETWORK_ID, network.getNetworkId());

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent);
            } else {
                context.startService(serviceIntent);
            }

            Log.i(TAG, "Started ZeroTier service for network: " + network.getNetworkIdStr());
            break; // Start only the first/primary network
        }
    } finally {
        DatabaseUtils.readLock.unlock();
    }
}
```

**Impact**: This fix will enable automatic VPN connection on device boot, addressing one of the user's main concerns.

### 2. Add Always-On VPN Helper (High Priority)

**File**: `/app/src/main/java/net/kaaass/zerotierfix/ui/view/NetworkDetailFragment.java`

**Addition**: Add a button/link to help users enable Always-on VPN:

```java
// Add button in layout
Button alwaysOnVpnButton = view.findViewById(R.id.enable_always_on_vpn_button);
alwaysOnVpnButton.setOnClickListener(v -> {
    // Open Android VPN settings
    Intent intent = new Intent(Settings.ACTION_VPN_SETTINGS);
    startActivity(intent);

    // Show helper dialog
    new AlertDialog.Builder(requireContext())
        .setTitle(R.string.always_on_vpn_help_title)
        .setMessage(R.string.always_on_vpn_help_message)
        .setPositiveButton(android.R.string.ok, null)
        .show();
});
```

**String Resources** (`strings.xml`):
```xml
<string name="always_on_vpn_help_title">Enable Always-On VPN</string>
<string name="always_on_vpn_help_message">
    To ensure ZeroTier is always connected:\n
    1. Tap the gear icon next to ZerotierFix\n
    2. Enable "Always-on VPN"\n
    3. (Optional) Enable "Block connections without VPN" for maximum security
</string>
```

### 3. Add Always-On VPN Status Indicator (Medium Priority)

**File**: `/app/src/main/java/net/kaaass/zerotierfix/service/ZeroTierOneService.java`

**Addition**: Detect and display always-on VPN status:

```java
// In updateTunnelConfig() or similar method
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
    boolean isAlwaysOn = isAlwaysOn();
    LogUtil.i(TAG, "Always-on VPN status: " + isAlwaysOn);
    // Post event to update UI
    eventBus.post(new AlwaysOnVpnStatusEvent(isAlwaysOn));
}
```

### 4. Improve First-Run Experience (Medium Priority)

**Add**: Tutorial/Welcome screen on first launch explaining:
1. VPN permission dialog is normal and required by Android
2. How to enable Always-on VPN for automatic connection
3. How per-app routing works and its benefits
4. Boot startup behavior

### 5. Add In-App Documentation (Low Priority)

**File**: New markdown file `/app/src/main/assets/vpn_help.md`

**Content**: Comprehensive guide covering:
- VPN permission explanation
- Always-on VPN setup
- Per-app routing configuration
- Boot startup behavior
- Troubleshooting common issues

**Display**: Add "VPN Help" menu item in `NetworkDetailActivity` or `PrefsActivity`

## Technical Implementation Details

### Current VPN Service Architecture (Summary)

```
ZeroTierOneService extends VpnService
├── onStartCommand() - Service entry point
│   ├── Initialize ZeroTier Node (JNI)
│   └── joinNetwork(networkId)
│
├── updateTunnelConfig() - Configure VPN tunnel (line 720-1075)
│   ├── VpnService.Builder configuration
│   ├── addAddress() - Add ZeroTier IP addresses
│   ├── addRoute() - Add network routes
│   ├── addDnsServer() - Configure DNS
│   ├── configureAllowedDisallowedApps() - Per-app routing
│   └── builder.establish() - Create VPN interface
│
├── configureAllowedDisallowedApps() - Per-app VPN (line 1103-1195)
│   ├── Query AppRouting database for selected apps
│   ├── Global mode: addDisallowedApplication(own package)
│   └── Per-app mode: addAllowedApplication(selected packages)
│
└── run() - Main service thread
    └── TunTapAdapter.run() - Packet processing loop
```

### Per-App Routing Database Schema

```sql
-- AppRouting table (GreenDAO entity)
CREATE TABLE APP_ROUTING (
    _id INTEGER PRIMARY KEY,
    NETWORK_ID INTEGER NOT NULL,
    PACKAGE_NAME TEXT NOT NULL,
    ROUTE_VIA_VPN INTEGER NOT NULL  -- Boolean: 1=use VPN, 0=bypass VPN
);
```

### UI Flow for Per-App Configuration

```
NetworkDetailFragment
├── Checkbox: "Route All Traffic"
│   ├── Checked → Global routing (all apps via VPN)
│   │             perAppRouting=false, routeViaZeroTier=true
│   │             AppRoutingFragment hidden
│   │
│   └── Unchecked → Per-app routing (select apps)
│                   perAppRouting=true, routeViaZeroTier=false
│                   AppRoutingFragment shown
│
└── AppRoutingFragment (shown in per-app mode)
    ├── Displays selected apps (from AppRouting table)
    ├── Shows count: "X apps selected"
    └── "Add Apps" button → AppRoutingActivity
        └── AppSelectionFragment
            ├── Lists all installed apps
            ├── Checkbox per app (routeViaVpn)
            ├── Filter: Show/hide system apps
            └── Save → Insert/Update AppRouting records
```

## Conclusion

**Answer to the Question: "Can This Be Resolved?"**

### ✅ Already Resolved (No Changes Needed):

1. **Automatic VPN Start**: Already implemented. VPN starts automatically when joining a ZeroTier network.

2. **Per-App VPN ("Smart Networking")**: Already fully implemented using Android's native `addAllowedApplication()` API.

### ⚠️ Cannot Be Fully Automated (Android Limitations):

1. **VPN Permission Dialog**: Android security requirement. Must be accepted once by user. **No workaround possible.**

2. **Always-On VPN Setting**: Must be enabled manually by user in Android Settings. **Cannot be automated by app.**

### ✅ Can Be Improved (Recommendations):

1. **Boot Startup Bug Fix** (HIGH PRIORITY): Fix `StartupReceiver` to actually start the service - currently it only logs but doesn't start VPN
2. **Chinese ROM Compatibility** (HIGH PRIORITY): Add ROM detection and user guidance for MIUI, EMUI, ColorOS, OriginOS, Flyme
3. **User Education**: Add help screens explaining VPN permission and Always-on VPN setup
4. **Quick Settings Link**: Add button to open Android VPN settings and ROM-specific permission settings
5. **Status Indicators**: Show Always-on VPN status in app UI

### Final Assessment:

The core VPN functionality **already exists** and is correctly implemented. However, there are **critical issues** for Chinese market:

**🐛 BUG**: Boot startup is broken - `StartupReceiver` logs but doesn't actually start the service

**⚠️ CHINESE ROM ISSUES**: Major domestic Android manufacturers (Xiaomi MIUI, Huawei EMUI/HarmonyOS, OPPO ColorOS, vivo OriginOS, Meizu Flyme) have aggressive background app management that will kill VPN connections and prevent auto-start even with proper implementation.

The perceived "imperfection" mentioned by the user is likely due to:
- **Boot startup bug** preventing automatic connection after device restart
- **Chinese ROM restrictions** preventing auto-start and background execution
- User unfamiliarity with Android's VPN permission flow
- Lack of ROM-specific guidance for permission settings
- UI/UX not clearly indicating that per-app routing **is** Android's native "smart networking"

**Recommended Action**:
1. **Fix the boot startup bug** (high priority)
2. **Add ROM detection and user guidance** (high priority for Chinese market)
3. Focus on user education with ROM-specific setup instructions

## Next Steps

1. ✅ **COMPLETED**: Verified `StartupReceiver` implementation - **bug found**
2. ✅ **COMPLETED**: Analyzed Chinese ROM VPN handling issues
3. Implement boot startup fix with ROM compatibility
4. Add RomUtils class for manufacturer detection
5. Add ROM-specific permission guide UI
6. Add Always-on VPN helper button and instructions
7. Improve first-run experience with VPN permission explanation
8. Add in-app help documentation with ROM-specific guides
9. Consider adding FAQ section addressing common VPN concerns

---

**Analysis completed on**: 2026-02-21
**ZerotierFix version analyzed**: Based on latest codebase
**Android VPN API references**: Android SDK versions 14-34 (API levels analyzed)
**Chinese ROM analysis**: MIUI, EMUI/HarmonyOS, ColorOS, OriginOS, Flyme
