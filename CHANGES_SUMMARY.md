# Per-App VPN Routing Bug Fixes and UI Improvements

## Summary of Changes

This update addresses critical bugs in the per-app VPN routing feature and implements requested UI improvements.

## 1. Bug Fix #2: Per-App VPN Not Working When Global Routing Disabled (Latest Fix)

### Problem
The per-app VPN routing feature was not working correctly because the VPN configuration was being applied incorrectly when both routing modes were disabled.

### Root Cause
The `configureAllowedDisallowedApps()` method in `ZeroTierOneService.java` was only checking the `isPerAppRouting` flag but ignoring the `isRouteViaZeroTier` parameter. This meant:
- When `isPerAppRouting == false`, the code ALWAYS called `addDisallowedApplication()` for global routing
- This happened even when `isRouteViaZeroTier == false`, causing incorrect VPN behavior

### Solution
Added a conditional check for `isRouteViaZeroTier` before applying global routing configuration. Now the method properly handles three scenarios:
1. **Global routing mode** (`isRouteViaZeroTier == true` AND `isPerAppRouting == false`): Use `addDisallowedApplication()` to exclude only this app
2. **Per-app routing mode** (`isPerAppRouting == true`): Use only `addAllowedApplication()` for selected apps
3. **No routing** (both false): No app routing configuration applied

### Changes Made
- `ZeroTierOneService.java` lines 1121-1136:
  - Added `if (isRouteViaZeroTier)` check before calling `addDisallowedApplication()`
  - Added logging for when neither routing mode is enabled
  - Ensures proper VPN API usage in all scenarios

## 2. Bug Fix #1: Per-App Selected Apps Now Route Through VPN

### Problem
Apps selected in per-app routing mode were not actually going through the VPN connection due to incorrect Android VPN API usage.

### Root Cause
The code was mixing `addAllowedApplication()` and `addDisallowedApplication()` calls, which is not supported by Android's VPN API:
- `addAllowedApplication()` creates a whitelist (ONLY listed apps go through VPN)
- `addDisallowedApplication()` creates a blacklist (ALL apps go through VPN EXCEPT listed ones)
- These two modes are mutually exclusive and cannot be combined

The previous implementation called `addDisallowedApplication()` first (to exclude the app itself), which put the VPN in blacklist mode. Then it called `addAllowedApplication()` for selected apps, but this conflicted with the blacklist mode.

### Solution
- **Global routing mode**: Use `addDisallowedApplication()` to exclude only the app itself (all other apps go through VPN)
- **Per-app routing mode**: Use only `addAllowedApplication()` for selected apps (whitelist mode), and skip adding the app itself entirely

### Changes Made
- `ZeroTierOneService.java`:
  - Moved `addDisallowedApplication()` call inside the global routing mode block
  - Added check to skip the app itself when adding allowed applications in per-app mode
  - This ensures proper separation between whitelist and blacklist modes

## 3. UI Improvement: Selected Apps Prioritized in List

### Feature
Apps that are selected to route via VPN now appear at the top of the app list, making it easier to see which apps are configured.

### Implementation
- Added sorting logic in `AppRoutingFragment.updateAppList()`:
  1. First sort by selection status (selected apps first)
  2. Then sort alphabetically by app name within each group

This provides a better user experience by:
- Making it immediately visible which apps are using VPN
- Reducing scrolling to find selected apps
- Maintaining alphabetical order for easy navigation

## 4. UI Improvement: Per-App Settings Integrated into Network Detail

### Previous Behavior
- Routing mode toggle was in a separate "App Routing" screen
- Users had to navigate through menu → App Routing to switch modes
- The toggle and app selection were on the same screen

### New Behavior
- Per-app routing checkbox is now directly in the Network Detail screen
- Located right below the "Route Via ZeroTier" (global routing) checkbox
- Global routing and per-app routing are mutually exclusive:
  - Enabling one automatically disables the other
  - Prevents configuration conflicts
- "Configure Apps" button appears when per-app mode is enabled
  - Clicking it opens the app selection screen
  - App selection screen now focuses solely on selecting apps

### Benefits
- Cleaner UI architecture with better separation of concerns:
  - Network settings screen: Choose routing mode
  - App selection screen: Select which apps (only relevant in per-app mode)
- More intuitive user flow
- Settings are more discoverable (no need to find menu item)
- Clear visual indication that the two routing modes are mutually exclusive

### Changes Made
1. **NetworkDetailFragment** (`fragment_network_detail.xml`):
   - Added "Per-App Routing" checkbox
   - Added "Configure Apps" button (visible only in per-app mode)
   - Implemented mutual exclusion logic between the two routing modes

2. **NetworkDetailFragment.java**:
   - Added UI controls for per-app routing
   - Implemented checkbox change listeners with mutual exclusion
   - Added button click handler to open app selection screen
   - Connected to ViewModel for persistence

3. **NetworkDetailModel.java**:
   - Added `doUpdatePerAppRouting()` method
   - Triggers network configuration change event for VPN service

4. **AppRoutingFragment** (`fragment_app_routing.xml` and `.java`):
   - Removed routing mode toggle switch
   - Simplified to focus only on app selection
   - Description text now explains per-app routing mode

5. **Strings.xml**:
   - Added `network_per_app_routing` string
   - Added `configure_apps` string
   - Added `empty_network_name` string

## Testing Recommendations

1. **Bug Fix Verification**:
   - Enable per-app routing mode
   - Select one or more apps
   - Verify those apps can access resources through ZeroTier
   - Verify unselected apps use direct connection (not through ZeroTier)

2. **UI Flow Testing**:
   - Open network detail screen
   - Verify per-app routing checkbox is present
   - Enable per-app routing → verify global routing is disabled
   - Enable global routing → verify per-app routing is disabled
   - Enable per-app mode → verify "Configure Apps" button appears
   - Click "Configure Apps" → verify app selection screen opens
   - Select apps → verify they appear at the top of the list
   - Verify settings persist after app restart

3. **Edge Cases**:
   - Test switching between modes multiple times
   - Test with system apps enabled/disabled
   - Test with apps that get uninstalled after selection
   - Test VPN reconnection after changing modes

## Code Quality

All changes follow existing code patterns:
- Consistent with Android VPN API best practices
- Maintains existing database structure
- Uses established event bus patterns
- Follows project's code style
- No breaking changes to existing functionality

## Migration Notes

No database migration required - this update uses existing fields:
- `NetworkConfig.perAppRouting` (already exists)
- `AppRouting` table (already exists)

Users' existing settings will be preserved.
