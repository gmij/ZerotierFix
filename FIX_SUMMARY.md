# Fix Summary: Per-App Routing Network Link Switching Issue

## Problem (问题)
在per-app模式下开启代理时，网络链路切换处理不正确。所有应用都走VPN转发，而不是只有选中的应用走VPN转发，其他应用走原始路由。

Translation: When enabling proxy in per-app mode, network link switching is handled incorrectly. All apps go through VPN forwarding instead of only selected apps going through VPN, with others using original routing.

## Solution (解决方案)
修改 `ZeroTierOneService.java` 的 `updateTunnelConfig()` 方法，在per-app路由模式下不添加全局路由（0.0.0.0/0）。

Translation: Modified the `updateTunnelConfig()` method in `ZeroTierOneService.java` to NOT add global routes (0.0.0.0/0) when in per-app routing mode.

## Changes Made (修改内容)

### Code Changes (代码修改)
File: `app/src/main/java/net/kaaass/zerotierfix/service/ZeroTierOneService.java`

```diff
@@ -827,6 +827,7 @@ public class ZeroTierOneService extends VpnService implements Runnable, EventLis
         var assignedAddresses = virtualNetworkConfig.getAssignedAddresses();
         LogUtil.i(TAG, "address length: " + assignedAddresses.length);
         boolean isRouteViaZeroTier = networkConfig.getRouteViaZeroTier();
+        boolean isPerAppRouting = networkConfig.getPerAppRouting();
 
         // 遍历 ZT 网络中当前设备的 IP 地址，组播配置
         for (var vpnAddress : assignedAddresses) {
@@ -872,7 +873,8 @@ public class ZeroTierOneService extends VpnService implements Runnable, EventLis
         }
 
         // 如果启用了全局路由，添加默认路由(0.0.0.0/0 和 ::/0)
-        if (isRouteViaZeroTier) {
+        // 注意：per-app路由模式下不添加全局路由，只有选中的应用会通过VPN接口的特定路由
+        if (isRouteViaZeroTier && !isPerAppRouting) {
             try {
                 // 使用ZeroTier全局路由模式
                 LogUtil.i(TAG, "使用ZeroTier全局路由模式");
@@ -939,6 +941,9 @@ public class ZeroTierOneService extends VpnService implements Runnable, EventLis
             } catch (Exception e) {
                 LogUtil.e(TAG, "添加默认路由时出错: " + e.getMessage(), e);
             }
+        } else if (isPerAppRouting) {
+            // Per-app路由模式：不添加全局路由，只有选中的应用通过ZeroTier网络的特定路由
+            LogUtil.i(TAG, "使用Per-App路由模式：跳过全局路由，仅选中的应用将通过VPN");
         }
 
         // 遍历网络的路由规则，将网络负责路由的地址路由至 VPN
```

**Summary:**
- Added 1 variable: `isPerAppRouting`
- Modified 1 condition: `if (isRouteViaZeroTier)` → `if (isRouteViaZeroTier && !isPerAppRouting)`
- Added 1 comment (line 876)
- Added 1 else-if block with logging (lines 944-946)
- **Total: 6 lines changed**

### Documentation (文档)
1. **PER_APP_ROUTING_FIX.md** (219 lines)
   - Problem analysis and root cause
   - Solution explanation
   - Testing instructions (4 test cases)
   - Verification methods
   - Technical details

2. **PER_APP_ROUTING_BEHAVIOR.md** (227 lines)
   - Before/after behavior comparison
   - Traffic flow examples
   - Routing table examples
   - Log output examples
   - Testing commands

## Technical Explanation (技术说明)

### Root Cause (根本原因)
Android VPN API has two levels of control:
1. **Application filters**: Which apps can use VPN
2. **Route configuration**: Which IPs go through VPN

The bug: Global routes (0.0.0.0/0) were added even in per-app mode, causing ALL traffic to go through VPN regardless of app filters.

### The Fix (修复方法)
Check BOTH flags before adding global routes:
- `isRouteViaZeroTier`: User wants global routing?
- `isPerAppRouting`: User wants per-app routing?

Only add global routes when: `isRouteViaZeroTier == true` AND `isPerAppRouting == false`

### Result (结果)
- **Global mode**: All apps use VPN (except this app) ✅
- **Per-app mode**: Only selected apps use VPN ✅
- **No conflict**: Routing and app filters work together correctly ✅

## Testing (测试)

### Quick Test (快速测试)
1. Enable per-app routing
2. Select Chrome browser only
3. Open Chrome → should access ZeroTier network ✅
4. Open Firefox → should use direct connection ✅

### Verification (验证)
Check logs for:
```
ZT1_Service: 使用Per-App路由模式：跳过全局路由，仅选中的应用将通过VPN
```

Check routing table:
```bash
adb shell ip route show dev tun0
# Should NOT show 0.0.0.0/0 in per-app mode
```

## Impact (影响)

### Minimal Changes (最小化修改)
- Only 1 file modified
- Only 6 lines of code changed
- No API changes
- No database changes
- No breaking changes

### High Impact (高影响)
- Fixes critical per-app routing bug
- Enables proper network link switching
- Allows granular control over which apps use VPN
- Improves user experience significantly

## Commits (提交记录)

```
c10d105 Add detailed behavior comparison documentation
17d48fb Add comprehensive documentation for per-app routing fix
a347ac9 Fix per-app routing: prevent global routes in per-app mode
```

## Files Modified (修改的文件)

```
 PER_APP_ROUTING_BEHAVIOR.md                                              | 227 +++++
 PER_APP_ROUTING_FIX.md                                                   | 219 +++++
 app/src/main/java/net/kaaass/zerotierfix/service/ZeroTierOneService.java |   7 +-
 3 files changed, 452 insertions(+), 1 deletion(-)
```

## Status (状态)

- ✅ Code changes completed
- ✅ Documentation completed
- ✅ Solution verified (code review)
- ⏳ Waiting for manual testing on device
- ⏳ Waiting for user feedback

## Next Steps (下一步)

1. Build the APK (requires network connection for dependencies)
2. Install on test device
3. Test per-app routing with multiple apps
4. Verify logs show correct mode
5. Confirm non-selected apps use direct connection
6. Merge PR after testing

## References (参考文档)

- Issue: 还是不行，认真检查下，在per-app时，是不是在开启代理时，没有处理好网络链路的切换？
- Branch: `copilot/fix-network-switching-issues`
- Files: 
  - `PER_APP_ROUTING_FIX.md` - Comprehensive guide
  - `PER_APP_ROUTING_BEHAVIOR.md` - Before/after comparison
  - `ZeroTierOneService.java` - Code fix

---

**Date**: 2025-12-26
**Author**: GitHub Copilot Agent
**Status**: ✅ Ready for Testing
