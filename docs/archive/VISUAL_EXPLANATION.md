================================================================================
Per-App VPN Routing Fix - Visual Explanation
================================================================================

PROBLEM: All apps were using VPN in per-app mode
CAUSE: Global routes (0.0.0.0/0) were added in per-app mode
FIX: Skip global routes when in per-app mode

================================================================================
BEFORE FIX (BROKEN)
================================================================================

User Configuration:
┌─────────────────────────────────────┐
│ Per-App Routing: ✅ ENABLED         │
│ Selected Apps:                      │
│  ✅ Chrome                          │
│  ✅ WeChat                          │
│  ❌ Firefox (not selected)          │
└─────────────────────────────────────┘

VPN Configuration Applied:
┌─────────────────────────────────────┐
│ Application Filters:                │
│  ✅ Chrome   (addAllowedApp)       │
│  ✅ WeChat   (addAllowedApp)       │
│                                     │
│ Route Configuration:                │
│  ❌ 0.0.0.0/0 → VPN (WRONG!)       │  ← This is the bug!
│  ✅ 10.147.20.0/24 → VPN           │
└─────────────────────────────────────┘

Traffic Flow:
┌─────────────┐      ┌──────────────┐      ┌─────────┐
│   Chrome    │─────▶│  0.0.0.0/0   │─────▶│   VPN   │  ✅ OK
│  (allowed)  │      │ matches all  │      └─────────┘
└─────────────┘      └──────────────┘

┌─────────────┐      ┌──────────────┐      ┌─────────┐
│   Firefox   │─────▶│  0.0.0.0/0   │─────▶│   VPN   │  ❌ WRONG!
│(not allowed)│      │ matches all  │      │ REJECTED│
└─────────────┘      └──────────────┘      └─────────┘
                                                │
                                                ▼
                                        ❌ Connection fails

Result: Firefox cannot connect because global route forces it to VPN,
        but VPN rejects it (not in allowed list)

================================================================================
AFTER FIX (WORKING)
================================================================================

User Configuration:
┌─────────────────────────────────────┐
│ Per-App Routing: ✅ ENABLED         │
│ Selected Apps:                      │
│  ✅ Chrome                          │
│  ✅ WeChat                          │
│  ❌ Firefox (not selected)          │
└─────────────────────────────────────┘

VPN Configuration Applied:
┌─────────────────────────────────────┐
│ Application Filters:                │
│  ✅ Chrome   (addAllowedApp)       │
│  ✅ WeChat   (addAllowedApp)       │
│                                     │
│ Route Configuration:                │
│  ✅ NO 0.0.0.0/0 route (FIXED!)    │  ← Fixed!
│  ✅ 10.147.20.0/24 → VPN           │
└─────────────────────────────────────┘

Traffic Flow:

Chrome (allowed app):
┌─────────────┐      ┌──────────────┐      ┌─────────┐
│   Chrome    │─────▶│ Destination  │      │         │
│  (allowed)  │      │    8.8.8.8   │      │         │
└─────────────┘      └──────────────┘      │         │
                            │               │         │
                            ▼               │         │
                     Check routes:          │   VPN   │
                     - 10.147.20.0/24? NO   │Interface│
                     - Default? Use VPN     │         │
                            │               │         │
                            └──────────────▶│         │  ✅ OK
                                            └─────────┘

Firefox (not allowed app):
┌─────────────┐      ┌──────────────┐      ┌─────────┐
│   Firefox   │─────▶│ Destination  │      │ Direct  │
│(not allowed)│      │    8.8.8.8   │      │Internet │
└─────────────┘      └──────────────┘      │Connection│
                            │               └─────────┘
                            ▼                    ▲
                     Check routes:               │
                     - Not in VPN allowed list   │
                     - Use original routing──────┘  ✅ OK

Result: Chrome uses VPN, Firefox uses direct connection!

================================================================================
CODE CHANGE
================================================================================

Before:
    if (isRouteViaZeroTier) {
        addRoute("0.0.0.0", 0);  // Global route
    }

After:
    if (isRouteViaZeroTier && !isPerAppRouting) {
        addRoute("0.0.0.0", 0);  // Global route
    }

Effect:
- Global mode: Adds 0.0.0.0/0 ✅
- Per-app mode: Skips 0.0.0.0/0 ✅

================================================================================
ROUTING TABLE COMPARISON
================================================================================

Global Mode:
    0.0.0.0/0 dev tun0              ← All traffic to VPN
    10.147.20.0/24 dev tun0
    
Per-App Mode (Before Fix):
    0.0.0.0/0 dev tun0              ← ❌ Should NOT be here!
    10.147.20.0/24 dev tun0
    
Per-App Mode (After Fix):
    10.147.20.0/24 dev tun0         ← ✅ Only specific routes
    (No 0.0.0.0/0)                  ← ✅ Correct!

================================================================================
SUMMARY
================================================================================

The Fix:
    Add one condition: && !isPerAppRouting
    
The Result:
    ✅ Selected apps → VPN
    ✅ Non-selected apps → Direct connection
    ✅ Per-app routing works correctly!

================================================================================
