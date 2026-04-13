# Per-App Routing Network Link Switching Fix

## Problem Statement (问题描述)

还是不行，认真检查下，在per-app时，是不是在开启代理时，没有处理好网络链路的切换？只有选中的app才走vpn转发，其他的走原始路由。

Translation: Still not working, carefully check if during per-app mode, when enabling the proxy, the network link switching is not handled properly? Only selected apps should go through VPN forwarding, others should go through original routing.

## Root Cause Analysis (根本原因分析)

### The Issue
When per-app routing mode was enabled, **ALL traffic was still going through the VPN** instead of only the selected apps. This happened because:

1. The VPN configuration was adding global routes (0.0.0.0/0 and ::/0) even in per-app mode
2. Global routes override the per-app application filters
3. Result: All traffic was forced through VPN regardless of app selection

### Technical Explanation

Android VPN API works at two levels:

1. **Application Filter Level** (应用过滤层)
   - `addAllowedApplication(packageName)` - Whitelist mode: ONLY these apps use VPN
   - `addDisallowedApplication(packageName)` - Blacklist mode: ALL apps use VPN EXCEPT these
   - These are mutually exclusive

2. **Route Configuration Level** (路由配置层)
   - `addRoute(address, prefixLength)` - Which destination IPs go through VPN
   - `0.0.0.0/0` means ALL IPv4 traffic
   - `::/0` means ALL IPv6 traffic

### The Problem

In `ZeroTierOneService.java`, line 875:
```java
if (isRouteViaZeroTier) {
    configureDirectGlobalRouting(builder, ...); // Adds 0.0.0.0/0 route
}
```

This code checked ONLY the `isRouteViaZeroTier` flag, which indicates whether to use global routing. However, it did NOT check the `isPerAppRouting` flag.

**Result:**
- Even in per-app mode, if user had previously enabled "Route Via ZeroTier", the global routes were added
- The global routes (0.0.0.0/0) meant ALL traffic goes to VPN
- This overrode the `addAllowedApplication()` whitelist
- All apps used VPN, not just the selected ones

## Solution (解决方案)

### Code Changes

Modified `ZeroTierOneService.java` in the `updateTunnelConfig()` method:

**Line 830:** Added per-app routing flag
```java
boolean isRouteViaZeroTier = networkConfig.getRouteViaZeroTier();
boolean isPerAppRouting = networkConfig.getPerAppRouting();  // NEW
```

**Line 877:** Updated condition to check both flags
```java
// Before:
if (isRouteViaZeroTier) {

// After:
if (isRouteViaZeroTier && !isPerAppRouting) {
```

**Lines 944-946:** Added logging for per-app mode
```java
} else if (isPerAppRouting) {
    LogUtil.i(TAG, "使用Per-App路由模式：跳过全局路由，仅选中的应用将通过VPN");
}
```

### How It Works Now

#### Global Routing Mode (全局路由模式)
When `isRouteViaZeroTier = true` AND `isPerAppRouting = false`:
1. ✅ Adds global routes (0.0.0.0/0 and ::/0)
2. ✅ Uses `addDisallowedApplication()` to exclude only this app
3. ✅ Result: All apps go through VPN except this app

#### Per-App Routing Mode (Per-App路由模式)
When `isPerAppRouting = true`:
1. ✅ Does NOT add global routes
2. ✅ Only adds specific ZeroTier network routes
3. ✅ Uses `addAllowedApplication()` for selected apps only
4. ✅ Result: 
   - Selected apps → through VPN interface using specific routes
   - Non-selected apps → through original routing (direct connection)

#### No Routing Mode (无路由模式)
When both flags are `false`:
1. ✅ Does NOT add global routes
2. ✅ No app filtering applied
3. ✅ Result: VPN interface exists but no traffic routed through it

## Testing Instructions (测试说明)

### Test Case 1: Per-App Routing with Selected Apps
1. Open network detail screen
2. Enable "Per-App Routing" checkbox
3. Click "Configure Apps" button
4. Select 2-3 apps (e.g., browser, messaging app)
5. Connect to ZeroTier network
6. **Expected Result:**
   - Selected apps can access ZeroTier network resources
   - Selected apps' traffic goes through VPN
   - Non-selected apps use direct internet connection
   - Non-selected apps cannot access ZeroTier network resources

### Test Case 2: Global Routing Mode
1. Open network detail screen
2. Enable "Route Via ZeroTier" checkbox
3. Ensure "Per-App Routing" is disabled
4. Connect to ZeroTier network
5. **Expected Result:**
   - All apps (except this app) go through VPN
   - All apps can access ZeroTier network resources
   - All internet traffic goes through ZeroTier

### Test Case 3: Switching Between Modes
1. Start with global routing enabled
2. Switch to per-app routing
3. Select some apps
4. Verify only selected apps use VPN
5. Switch back to global routing
6. Verify all apps use VPN again

### Test Case 4: Check Logs
Enable verbose logging and check for these messages:
- Global mode: "使用ZeroTier全局路由模式"
- Per-app mode: "使用Per-App路由模式：跳过全局路由，仅选中的应用将通过VPN"
- Per-app mode: "Per-app路由配置完成: 允许=X 个应用通过VPN"

## Verification Methods (验证方法)

### Method 1: Check Network Interface
```bash
adb shell ip route
```
Look for routes to VPN interface. In per-app mode, should NOT see `0.0.0.0/0` route.

### Method 2: Test Connectivity
1. Select Chrome browser only in per-app mode
2. Open Chrome → should access ZeroTier network
3. Open another browser (not selected) → should use direct connection
4. Try accessing a ZeroTier-only resource in both browsers

### Method 3: Check Logs
```bash
adb logcat -s ZT1_Service:D
```
Look for routing configuration messages

## Technical Details (技术细节)

### Route Priority
In Android VPN:
1. Most specific routes take precedence
2. But 0.0.0.0/0 is catch-all, overrides app filters
3. Per-app mode relies on NO global routes being present

### Why This Fix Works
- Removes the conflict between route config and app filters
- Per-app mode now relies purely on:
  - App whitelist (`addAllowedApplication()`)
  - Specific network routes (ZeroTier network ranges only)
  - Android automatically routes non-whitelisted apps through original interface

### Android VPN Routing Logic
```
For each packet:
1. Check if app is allowed/disallowed for VPN
   - If disallowed → use original routing
   - If not in whitelist (when whitelist exists) → use original routing
2. Check routing table for destination IP
   - If matches VPN route → send to VPN interface
   - Otherwise → use original routing
```

## Known Limitations (已知限制)

1. **Android Version**: Per-app VPN requires Android 5.0+ (API 21+)
2. **System Apps**: Some system apps may not be filtered correctly
3. **Split Tunneling**: Cannot have both global and per-app routing simultaneously
4. **DNS**: DNS queries from non-selected apps may still leak to VPN in some configurations

## Related Code (相关代码)

### Key Files Modified
- `app/src/main/java/net/kaaass/zerotierfix/service/ZeroTierOneService.java`

### Key Methods
- `updateTunnelConfig()` - Lines 792-1063
- `configureAllowedDisallowedApps()` - Lines 1091-1187
- `configureDirectGlobalRouting()` - Lines 1289-1333

## Future Enhancements (未来改进)

1. Add UI indicator showing current routing mode
2. Show real-time list of apps using VPN
3. Add per-app traffic statistics
4. Implement smart routing rules (e.g., route certain domains only)
5. Add import/export of per-app configurations

## Conclusion (结论)

This fix ensures that per-app routing mode works correctly by preventing global routes from being added when in per-app mode. The change is minimal (6 lines) but critical for proper network link switching. Only selected apps will now use VPN forwarding, while others use original routing as intended.

---

**Date**: 2025-12-26
**Issue**: Network link switching not handled properly in per-app mode
**Fix**: Prevent global routes (0.0.0.0/0) from being added in per-app routing mode
**Lines Changed**: 6 lines in ZeroTierOneService.java
**Status**: ✅ Fixed, awaiting testing
