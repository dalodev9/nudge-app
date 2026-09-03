# Phase 03: Medium-Severity Fixes & Play Store Policy Audit

## Overview
This document summarizes the changes implemented in Phase 03 (Medium-Severity Fixes M1–M15) for the Nudge Android application, building upon Phase 01 (Critical) and Phase 02 (High) fixes. It also contains the Architectural Decision Record (ADR) and policy justification documentation for Play Store distribution (M15).

---

## 1. Summary of Implemented Fixes (M1–M15)

### M1: Multi-Key Preference Writes Atomicity
- **Files Modified**: `app/src/main/java/com/nudge/app/data/PreferencesManager.kt`
- **Fix**: Replaced sequential `edit().apply()` calls in `addTrackedApp` and `removeTrackedApp` with atomic `androidx.core.content.edit { ... }` blocks.
- **Verification**: Verified with `PreferencesManagerTest`.

### M2: Stale Alert Cooldown Key Cleanup
- **Files Modified**: `app/src/main/java/com/nudge/app/data/PreferencesManager.kt`
- **Fix**: In `removeTrackedApp`, the package's `alert_cooldown_*` key is removed in the same atomic `edit { }` transaction. Additionally, `setLastAlertTime` cleans up keys when set to 0 or negative.
- **Verification**: Added `removeTrackedApp_cleansUpAlertCooldownKey` test in `PreferencesManagerTest`.

### M3: Timing & Break Enforcement (Snooze vs Take-a-Break, Dismiss Removed)
- **Files Modified**: `app/src/main/java/com/nudge/app/data/SessionTracker.kt`, `app/src/main/java/com/nudge/app/service/ScreenTimeTrackerService.kt`, `OverlayManager.kt`
- **Fix**: Removed manual Dismiss button. Streamlined nudge choices to Take a Break and Snooze:
  - **Snooze**: Suppresses alerts for exactly 5 minutes (`now + SNOOZE_MS`), keeping continuous session duration intact without math degeneration on short limits (≤ 5 min).
  - **Take a Break**: Enforces a 5-minute minimum break (`TAKE_BREAK_MIN_MS`). Reopening the app in < 5 minutes immediately re-triggers the nudge carrying forward previous continuous `minutesUsed`. Reopening after 5+ minutes starts a fresh session.
- **Verification**: Added unit tests in `SessionTrackerTest` covering short-limit snooze, early break re-entry (< 5 min), and normal break re-entry (>= 5 min).

### M4: Dashboard Disabled App Exclusion
- **Files Modified**: `app/src/main/java/com/nudge/app/ui/screens/DashboardViewModel.kt`
- **Fix**: Queries `preferencesManager.getEnabledTrackedApps()` for usage calculation while preserving `allTracked.isNotEmpty()` for configured state.
- **Verification**: Added `refreshData_disabledApp_excludedFromUsageAndTotalMinutes` test in `DashboardViewModelTest`.

### M5: Stable Bar Chart Colors
- **Files Modified**: `app/src/main/java/com/nudge/app/ui/components/UsageBarChart.kt`, `app/src/main/java/com/nudge/app/ui/screens/DashboardScreen.kt`
- **Fix**: Keyed bar colors by package name hash `(packageName.hashCode() and 0x7FFFFFFF) % UsageBarColors.size` instead of list index.

### M6: String Resource Extraction & Dynamic Version Name
- **Files Modified**: `app/src/main/res/values/strings.xml`, `DashboardScreen.kt`, `SettingsScreen.kt`, `PermissionScreen.kt`, `AppPickerBottomSheet.kt`, `CircularProgress.kt`, `UsageCard.kt`, `NotificationHelper.kt`, `OverlayManager.kt`
- **Fix**: Extracted all user-facing strings into `strings.xml` and referenced them via `stringResource()` / `context.getString()`. Replaced hardcoded version string with `stringResource(R.string.version_format, BuildConfig.VERSION_NAME)`.

### M7: Dark Mode Launch Theme
- **Files Modified**: `app/src/main/res/values/themes.xml`, `app/src/main/res/values-night/themes.xml`
- **Fix**: Updated `AppTheme` parent to `android:Theme.Material.Light.NoActionBar` in `values/` and `android:Theme.Material.NoActionBar` in `values-night/` to prevent white flash during dark-mode cold start.

### M8: Explicit `lifecycle-runtime-compose` Dependency
- **Files Modified**: `gradle/libs.versions.toml`, `app/build.gradle.kts`
- **Fix**: Added `androidx-lifecycle-runtime-compose` to version catalog and declared it as direct `implementation` dependency in `app/build.gradle.kts`.

### M9: Guarded Settings-Intent Fallbacks
- **Files Modified**: `app/src/main/java/com/nudge/app/util/PermissionUtils.kt`
- **Fix**: Wrapped primary and fallback `startActivity` calls in `runCatching` + `recoverCatching` + `onFailure` displaying a toast on OEM/ROM variants without settings activities.

### M10: Bar Chart Allocations & Zero-Minute Bars
- **Files Modified**: `app/src/main/java/com/nudge/app/ui/components/UsageBarChart.kt`
- **Fix**: Hoisted `android.graphics.Paint` and `Typeface` allocations outside the `Canvas` draw loop via `remember`. Guarded pill draw so 0-minute items skip drawing a bar fill.

### M11: Race Condition in Concurrent Refreshes
- **Files Modified**: `app/src/main/java/com/nudge/app/ui/screens/DashboardViewModel.kt`
- **Fix**: Managed `refreshJob` cancellation to prevent concurrent coroutines from racing, and removed artificial 500ms delay.

### M12: Safe System Service Casts
- **Files Modified**: `app/src/main/java/com/nudge/app/util/PermissionUtils.kt`, `UsageRepository.kt`, `OverlayManager.kt`
- **Fix**: Replaced unsafe casts with `as? <Service>` and graceful null fallbacks across `UsageStatsManager`, `AppOpsManager`, `WindowManager`, and `PowerManager`.

### M13: Overlay Touch Targets & Accessibility
- **Files Modified**: `app/src/main/java/com/nudge/app/service/OverlayManager.kt`
- **Fix**: Raised button heights and touch targets to 48dp minimum (`minHeight = dp(48f)`), added `contentDescription` on the app icon, and allowed text to scale with device font settings.

### M14: Single Reusable Alert Notification ID
- **Files Modified**: `app/src/main/java/com/nudge/app/service/NotificationHelper.kt`
- **Fix**: Replaced 1,000-slot hash mod notification ID with a single reusable `ALERT_NOTIFICATION_ID = 2001`.

---

## 2. Play Store Permission Audit & Policy Justification (M15 ADR)

### Architectural Decision Record (ADR): Permissions & Background Execution Model

#### Context
The Nudge application is a digital wellbeing tool that helps users reduce excess screen time on specific target apps by providing real-time tracking, foreground notifications, and gentle floating reminder overlays.

Google Play policies impose strict declaration requirements for high-privilege permissions:
1. `PACKAGE_USAGE_STATS` (Special app access)
2. `SYSTEM_ALERT_WINDOW` (Display over other apps)
3. `FOREGROUND_SERVICE_SPECIAL_USE` (Android 14+ FGS type)
4. `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (Battery optimization exemption)

#### Evaluation & Declarations

| Permission / Capability | Purpose in Nudge | Play Store Policy Compliance & Justification |
|---|---|---|
| `android.permission.PACKAGE_USAGE_STATS` | Core functionality: reads foreground app transitions and daily screen time metrics locally via `UsageStatsManager` / `UsageEvents`. | **Justification**: Digital Wellbeing core feature. No personal usage data is transmitted or collected off-device; all calculations occur locally. Handled as a hard permission gate with user consent in onboarding. |
| `android.permission.SYSTEM_ALERT_WINDOW` | Optional reminder mechanism: displays a floating card over monitored apps when time limits are reached. | **Justification**: Prominently disclosed in-app with an explicit opt-in toggle in Settings and onboarding. Fallback to high-priority notification is automatically provided if not granted. |
| `android.permission.FOREGROUND_SERVICE_SPECIAL_USE` | Ongoing background monitoring: allows `ScreenTimeTrackerService` to poll foreground state at 1-second intervals while tracking is active. | **Justification**: In Android 14+ (`targetSdkVersion 35`), screen-time monitoring during user sessions requires `specialUse` because standard FGS types (media, location, camera, data-sync) do not match periodic foreground app polling. Manifest provides `<property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE" android:value="Digital wellbeing screen time monitoring and real-time usage alert dispatching" />`. |
| `android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Background reliability: prevents aggressive OEM Doze / battery savers from killing the monitoring loop. | **Policy Note**: Play Store policy limits direct intent prompts for battery optimization exemption. In Nudge, this is purely optional and presented only inside Settings behind a clear user action ("Background Reliability -> Unrestrict"), guarded against crashes. |
| **Exact Alarms (`SCHEDULE_EXACT_ALARM` / `USE_EXACT_ALARM`)** | **Dropped entirely**. | **Decision**: Exact alarm permissions were avoided in favor of `START_STICKY` foreground service execution and `BootReceiver` restart on boot. This eliminates exact alarm policy declaration overhead and potential rejections. |

#### Privacy Statement
- **Zero Network Transmission**: Nudge does not declare `android.permission.INTERNET`. No analytics, tracking, telemetry, or user usage stats ever leave the device.
- **User Control**: Tracking can be fully paused or configured per-app at any time from the app's Settings screen.
