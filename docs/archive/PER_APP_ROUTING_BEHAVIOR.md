# Per-App Routing Fix - Behavior Comparison

## Before Fix (问题修复前)

### Scenario: Per-App Routing Enabled
**User Configuration:**
- Per-App Routing: ✅ Enabled
- Selected Apps: Chrome, WeChat
- Expected: Only Chrome and WeChat use VPN

**What Actually Happened:**
```
VPN Configuration:
├── Application Filters:
│   ├── addAllowedApplication("com.android.chrome")     ✅ Correct
│   └── addAllowedApplication("com.tencent.mm")          ✅ Correct
│
└── Route Configuration:
    ├── addRoute("0.0.0.0", 0)                           ❌ WRONG! Global route added
    ├── addRoute("::", 0)                                ❌ WRONG! Global route added
    └── addRoute("10.147.20.0", 24)                      ✅ Correct (ZeroTier network)

Result: ALL APPS USE VPN
Reason: Global routes (0.0.0.0/0) override application filters
```

**Traffic Flow Before Fix:**
```
User opens Chrome (selected):
├── Android checks: Is Chrome allowed for VPN? → YES
├── Android checks routing table: Does 8.8.8.8 match any VPN route?
├── Finds: 0.0.0.0/0 matches everything
└── Result: Traffic goes through VPN ✅ Correct

User opens Firefox (NOT selected):
├── Android checks: Is Firefox allowed for VPN? → NO
├── But global route 0.0.0.0/0 forces all traffic to VPN interface
├── VPN interface rejects Firefox packets (not in allowed list)
└── Result: Firefox cannot connect ❌ WRONG!
   (Should use direct connection, but global route prevents it)
```

---

## After Fix (问题修复后)

### Scenario: Per-App Routing Enabled
**User Configuration:**
- Per-App Routing: ✅ Enabled
- Selected Apps: Chrome, WeChat
- Expected: Only Chrome and WeChat use VPN

**What Happens Now:**
```
VPN Configuration:
├── Application Filters:
│   ├── addAllowedApplication("com.android.chrome")     ✅ Correct
│   └── addAllowedApplication("com.tencent.mm")          ✅ Correct
│
└── Route Configuration:
    ├── ❌ NO global routes added (0.0.0.0/0 skipped)   ✅ Correct
    └── addRoute("10.147.20.0", 24)                      ✅ Correct (ZeroTier network only)

Result: ONLY SELECTED APPS USE VPN
Reason: No global routes, only specific ZeroTier network routes
```

**Traffic Flow After Fix:**
```
User opens Chrome (selected):
├── Android checks: Is Chrome allowed for VPN? → YES
├── Android checks routing table for destination IP:
│   ├── If destination is 10.147.20.x → matches VPN route → use VPN
│   └── If destination is 8.8.8.8 → no VPN route match → use original routing
└── Result: 
    ├── ZeroTier network traffic: through VPN ✅
    └── Internet traffic: through VPN (because Chrome is allowed) ✅

User opens Firefox (NOT selected):
├── Android checks: Is Firefox allowed for VPN? → NO
├── Android uses original routing (not VPN interface)
└── Result: 
    ├── ZeroTier network: cannot access (not in VPN) ✅
    └── Internet traffic: direct connection ✅
```

---

## Side-by-Side Comparison

| Aspect | Before Fix | After Fix |
|--------|-----------|-----------|
| **Global Routes Added** | ✅ YES (0.0.0.0/0) | ❌ NO |
| **Specific ZT Routes** | ✅ YES | ✅ YES |
| **Selected Apps** | Use VPN ✅ | Use VPN ✅ |
| **Non-selected Apps** | Use VPN ❌ | Use Direct Connection ✅ |
| **Per-App Works?** | ❌ NO | ✅ YES |

---

## Code Comparison

### Before Fix
```java
// Line 829
boolean isRouteViaZeroTier = networkConfig.getRouteViaZeroTier();

// Line 875
if (isRouteViaZeroTier) {  // ❌ Only checks one flag
    configureDirectGlobalRouting(builder, ...); // Adds 0.0.0.0/0
}
```

### After Fix
```java
// Lines 829-830
boolean isRouteViaZeroTier = networkConfig.getRouteViaZeroTier();
boolean isPerAppRouting = networkConfig.getPerAppRouting();  // NEW

// Line 877
if (isRouteViaZeroTier && !isPerAppRouting) {  // ✅ Checks both flags
    configureDirectGlobalRouting(builder, ...); // Adds 0.0.0.0/0
}
// Lines 944-946
else if (isPerAppRouting) {  // NEW
    LogUtil.i(TAG, "使用Per-App路由模式：跳过全局路由，仅选中的应用将通过VPN");
}
```

---

## Routing Table Examples

### Global Routing Mode
```
ip route show table all

# VPN Interface (tun0)
0.0.0.0/0 dev tun0              # All IPv4 traffic
::/0 dev tun0                   # All IPv6 traffic
10.147.20.0/24 dev tun0         # ZeroTier network
```

### Per-App Routing Mode (Before Fix) - WRONG
```
ip route show table all

# VPN Interface (tun0)
0.0.0.0/0 dev tun0              # ❌ Should NOT be here!
::/0 dev tun0                   # ❌ Should NOT be here!
10.147.20.0/24 dev tun0         # ✅ Correct
```

### Per-App Routing Mode (After Fix) - CORRECT
```
ip route show table all

# VPN Interface (tun0)
10.147.20.0/24 dev tun0         # ✅ Only specific ZT routes
# NO 0.0.0.0/0 route             # ✅ Correct!

# Main routing table still has default route to original interface
0.0.0.0/0 dev wlan0             # ✅ Non-VPN apps use this
```

---

## Log Output Examples

### Before Fix (Per-App Mode)
```
ZT1_Service: Configuring VpnService.Builder
ZT1_Service: address length: 1
ZT1_Service: 使用ZeroTier全局路由模式         ❌ WRONG MESSAGE
ZT1_Service: 添加IPv4全局路由 0.0.0.0/0      ❌ SHOULD NOT HAPPEN
ZT1_Service: Per-app路由配置完成: 允许=2 个应用通过VPN
                                            ⚠️ Conflict: Global routes + App filters
```

### After Fix (Per-App Mode)
```
ZT1_Service: Configuring VpnService.Builder
ZT1_Service: address length: 1
ZT1_Service: 使用Per-App路由模式：跳过全局路由，仅选中的应用将通过VPN  ✅
ZT1_Service: Per-app路由配置完成: 允许=2 个应用通过VPN                ✅
                                            ✅ Correct: No global routes
```

---

## Testing Commands

### Check if global routes are present
```bash
adb shell ip route | grep "0.0.0.0/0 dev tun0"
# Should be empty in per-app mode
```

### Check VPN interface routes
```bash
adb shell ip route show dev tun0
# Should only show ZeroTier network routes, NOT 0.0.0.0/0
```

### Check app-specific routing (requires root)
```bash
adb shell su -c "iptables -t mangle -L OUTPUT -v"
# Shows per-app routing rules
```

### Monitor traffic in real-time
```bash
adb shell tcpdump -i tun0 -n
# Should only show traffic from selected apps
```

---

## Summary

**The Problem:** Global routes were added in per-app mode, causing all apps to use VPN.

**The Fix:** Check `isPerAppRouting` flag and skip global routes when in per-app mode.

**The Result:** Per-app routing now works correctly - only selected apps use VPN, others use direct connection.

**Lines Changed:** Only 6 lines, but critical for correct behavior!
