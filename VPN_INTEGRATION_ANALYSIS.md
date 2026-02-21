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
2. **User Education**: Add help screens explaining VPN permission and Always-on VPN setup
3. **Quick Settings Link**: Add button to open Android VPN settings
4. **Status Indicators**: Show Always-on VPN status in app UI

### Final Assessment:

The core VPN functionality **already exists** and is correctly implemented. However, there is **one critical bug** found:

**🐛 BUG**: Boot startup is broken - `StartupReceiver` logs but doesn't actually start the service

The perceived "imperfection" mentioned by the user is likely due to:
- **Boot startup bug** preventing automatic connection after device restart
- User unfamiliarity with Android's VPN permission flow
- Lack of in-app guidance about Always-on VPN setup
- UI/UX not clearly indicating that per-app routing **is** Android's native "smart networking"

**Recommended Action**:
1. **Fix the boot startup bug** (high priority)
2. Focus on user education and UI improvements for the remaining concerns

## Next Steps

1. ✅ **COMPLETED**: Verified `StartupReceiver` implementation - **bug found**
2. Implement boot startup fix
3. Add Always-on VPN helper button and instructions
4. Improve first-run experience with VPN permission explanation
5. Add in-app help documentation
6. Consider adding FAQ section addressing common VPN concerns

---

**Analysis completed on**: 2026-02-21
**ZerotierFix version analyzed**: Based on latest codebase
**Android VPN API references**: Android SDK versions 14-34 (API levels analyzed)
