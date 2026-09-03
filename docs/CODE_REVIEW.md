# Nudge — Code Review

**Scope:** full `app/` module at commit `3c00f10` (Kotlin 2.0.21, AGP 9.0.1, Compose M3, minSdk 24 / targetSdk 36).
**Reviewed:** 27 source files, manifest, Gradle/version-catalog config, resources.

Findings are grouped by severity. Each entry names the exact location, states the defect, explains why it matters, and gives a concrete fix.

| Severity | Count | Meaning |
|---|---|---|
| 🔴 Critical | 5 | Crashes, data races, or the advertised feature is measurably wrong |
| 🟠 High | 8 | Battery/ANR/memory problems, dead-end UX, no safety net |
| 🟡 Medium | 15 | Correctness edge cases, policy risk, maintainability |
| 🔵 Low | 11 | Hygiene, dead code, naming, tooling |

---

## 🔴 Critical

### C1 — Shared mutable service state is mutated from three threads with no synchronization

**Where:** `app/src/main/java/com/nudge/app/service/ScreenTimeTrackerService.kt:38-41`

```kotlin
private var isScreenOn = true
private var currentActivePackage: String? = null
private var sessionStartTime: Long = 0L
private val alertCooldownMap = mutableMapOf<String, Long>()
```

These four fields are written from **three different threads**:

| Writer | Thread | Lines |
|---|---|---|
| `screenReceiver.onReceive` | main | 66, 71-74 |
| `checkForegroundApp` / `sendAlertIfNeeded` | `Dispatchers.Default` | 154-155, 185-186, 195 |
| Overlay button callbacks (posted via `mainHandler`) | main | 209-210, 215-218 |

`alertCooldownMap` is a plain `LinkedHashMap`. A concurrent `put` from the main thread (`onSnooze`, line 217) while the tracking loop is writing (line 195) can corrupt the internal table — in the best case a lost update, in the worst an infinite loop or `ConcurrentModificationException` inside a foreground service.

The `Long`/`String?` fields have no memory barrier, so the tracking loop on `Dispatchers.Default` can read a stale `isScreenOn` and keep polling after the screen turns off, or a stale `sessionStartTime` and fire an alert immediately after a snooze.

**Why it matters:** the tracking loop is the entire product. A torn read here produces exactly the symptom users report as "it nagged me right after I snoozed" or "it kept draining battery with the screen off", and it is invisible in testing.

**Fix — confine all mutation to one dispatcher.** The cleanest form is a single-threaded confinement context plus atomic containers:

```kotlin
private val trackerDispatcher = Dispatchers.Default.limitedParallelism(1)
private val serviceScope = CoroutineScope(SupervisorJob() + trackerDispatcher)

@Volatile private var isScreenOn = true
private var currentActivePackage: String? = null   // only touched on trackerDispatcher
private var sessionStartTime: Long = 0L            // only touched on trackerDispatcher
private val alertCooldownMap = ConcurrentHashMap<String, Long>()
```

and route every off-dispatcher mutation back through the scope:

```kotlin
onSnooze = {
    serviceScope.launch {
        val limitMs = preferencesManager.sessionTimeLimitMinutes * 60_000L
        sessionStartTime = System.currentTimeMillis() -
            (limitMs - SNOOZE_MS).coerceAtLeast(0L)
        val now = System.currentTimeMillis()
        alertCooldownMap[packageName] = now
        preferencesManager.setLastAlertTime(packageName, now)
    }
}
```

Do the same for `onTakeBreak` and for the `SCREEN_OFF` branch of `screenReceiver`.

---

### C2 — Unhandled exceptions in ViewModel coroutines crash the app and strand the loading spinner

**Where:** `ui/screens/DashboardViewModel.kt:39-71`, `ui/screens/SettingsViewModel.kt:53-64`

```kotlin
fun refreshData(showLoading: Boolean = false, isPullToRefresh: Boolean = false) {
    viewModelScope.launch(Dispatchers.IO) {
        ...
        val appUsages = usageRepository.getTodayUsageForTrackedApps(trackedPackages)   // can throw
        ...
        _uiState.update { it.copy(isLoading = false, isRefreshing = false, ...) }
    }
}
```

There is no `try/catch` and no `CoroutineExceptionHandler`. `queryAndAggregateUsageStats` throws `SecurityException` the moment the user revokes Usage Access from Settings — which is one tap away, and this app actively sends users into that Settings screen. An uncaught exception in `viewModelScope` propagates to the default handler and **terminates the process**. If it were caught by anything upstream, the second failure mode kicks in: `isLoading` / `isRefreshing` are only ever cleared on the success path, so the dashboard hangs on a spinner forever.

`DashboardUiState` also has no error field, so there is no way to render a recoverable state.

**Fix:**

```kotlin
data class DashboardUiState(
    ...
    val errorMessage: String? = null,
    val hasUsagePermission: Boolean = true
)

fun refreshData(showLoading: Boolean = false, isPullToRefresh: Boolean = false) {
    refreshJob?.cancel()                       // also fixes M11
    refreshJob = viewModelScope.launch {
        _uiState.update {
            it.copy(isLoading = showLoading, isRefreshing = isPullToRefresh, errorMessage = null)
        }
        runCatching {
            withContext(Dispatchers.IO) {
                val tracked = preferencesManager.getEnabledTrackedApps()
                usageRepository.getTodayUsageForTrackedApps(tracked) to
                    preferencesManager.sessionTimeLimitMinutes
            }
        }.onSuccess { (usages, limit) ->
            _uiState.update {
                it.copy(
                    isLoading = false, isRefreshing = false,
                    appUsages = usages, totalMinutes = usages.sumOf { u -> u.usageMinutes },
                    limitMinutes = limit, hasConfiguredApps = usages.isNotEmpty()
                )
            }
        }.onFailure { e ->
            _uiState.update {
                it.copy(
                    isLoading = false, isRefreshing = false,
                    hasUsagePermission = e !is SecurityException,
                    errorMessage = "Couldn't read usage stats. Check Usage Access."
                )
            }
        }
    }
}
```

Apply the identical pattern to `SettingsViewModel.loadInstalledApps` (which can throw from `PackageManager` on a `TransactionTooLargeException` with many installed apps).

---

### C3 — The dashboard compares **today's cumulative** usage against the **per-session** limit

**Where:** `ui/screens/DashboardViewModel.kt:49-52`, `ui/components/CircularProgress.kt:40-60`, `ui/components/UsageCard.kt:46-49`

`sessionTimeLimitMinutes` is documented and used everywhere else as a *continuous session* limit — `ScreenTimeTrackerService.kt:161-171` measures `now - sessionStartTime`, i.e. one uninterrupted sitting. But the dashboard does:

```kotlin
val totalMinutes = appUsages.sumOf { it.usageMinutes }   // today's TOTAL across ALL tracked apps
val limitMinutes = preferencesManager.sessionTimeLimitMinutes   // a per-session limit
```

and then renders `"of ${limitMinutes}m limit"` with an error-red ring once exceeded. With the default 10-minute session limit, the ring is pinned at 100% red for essentially every user, every day, before lunch. `UsageCard` repeats the same comparison per app.

**Why it matters:** this is not a cosmetic bug — the primary screen of the app reports a permanently-failed state, which destroys the signal the app exists to provide.

**Fix — separate the two concepts.** Add a distinct daily budget to `PreferencesManager`:

```kotlin
var dailyBudgetMinutes: Int
    get() = prefs.getInt(KEY_DAILY_BUDGET, DEFAULT_DAILY_BUDGET)   // e.g. 120
    set(value) = prefs.edit { putInt(KEY_DAILY_BUDGET, value) }
```

Then in `DashboardUiState` carry `dailyBudgetMinutes` and feed **that** to `CircularProgress` and `UsageCard`, keeping `sessionTimeLimitMinutes` exclusively for the service's nudge trigger. Add a second slider in `SettingsScreen` for the daily budget, and relabel the ring `"of ${budget}m today"`.

If you'd rather not add a setting, the minimum acceptable fix is to stop implying a limit: render the ring against the largest single app's usage, or drop the ring's limit semantics and show today's total as a plain figure.

---

### C4 — Bar-chart labels are painted with a garbage color

**Where:** `ui/components/UsageBarChart.kt:83-84`

```kotlin
android.graphics.Paint().apply {
    color = textColor.hashCode()      // ← BUG
    ...
}
```

`textColor` is `androidx.compose.ui.graphics.Color`, a value class wrapping a `ULong`. `Color.hashCode()` returns `ULong.hashCode()` — an arbitrary 32-bit hash, **not** the packed ARGB int that `Paint.color` expects. The label text therefore renders in a pseudo-random color (frequently near-transparent or the same tone as the background, making labels invisible), and it changes with the theme in ways that have nothing to do with the theme.

**Fix:**

```kotlin
import androidx.compose.ui.graphics.toArgb

color = textColor.toArgb()
```

While you're here, hoist the `Paint` out of the draw loop (see M10) — allocating a `Paint` per bar per frame is a needless allocation on the render path.

---

### C5 — The "survives task kill" restart path is broken in three independent ways

**Where:** `service/ScreenTimeTrackerService.kt:242-311`

The README advertises *"Survives Task Kills: Automatically re-spawns the monitoring service if swiped away from Recent Apps."* The implementation does not do this reliably:

1. **The exact-alarm branch is dead code.** Line 268 checks `alarmManager.canScheduleExactAlarms()`, but the manifest declares neither `SCHEDULE_EXACT_ALARM` nor `USE_EXACT_ALARM`. On Android 12+ that call always returns `false`, so the branch never executes and every restart falls through to `setAndAllowWhileIdle` — an inexact alarm that the system may defer by many minutes.

2. **The alarm type cannot wake the device.** All branches use `AlarmManager.ELAPSED_REALTIME`, the *non-wakeup* variant. Pairing `setExactAndAllowWhileIdle` with a non-wakeup type is self-contradictory: if the device is dozing when the alarm is due, it will not fire until the device happens to wake. `ELAPSED_REALTIME_WAKEUP` is what the intent requires.

3. **The foreground-service start can throw and is not caught.** The `try/catch` at 266-309 wraps only the *alarm scheduling*, not the eventual `PendingIntent.getForegroundService` delivery. When the alarm fires the app is in the background; on Android 12+ that start is blocked with `ForegroundServiceStartNotAllowedException` unless the app is exempt. `SYSTEM_ALERT_WINDOW` and a battery-optimization exemption are both exemptions — but the app lets users skip *both* (`PermissionScreen.kt:200-206`, the "Unrestrict" button is optional). For those users the restart throws, uncaught, in the app process.

**Fix:**

```kotlin
// 1 + 2 — declare USE_EXACT_ALARM only if you genuinely qualify under Play policy;
//         otherwise drop the exact branch and use a wakeup alarm.
alarmManager?.setAndAllowWhileIdle(
    AlarmManager.ELAPSED_REALTIME_WAKEUP,      // was ELAPSED_REALTIME
    triggerAtMillis,
    restartServicePendingIntent
)
```

```kotlin
// 3 — guard the start itself, at the single entry point
fun start(context: Context) {
    val intent = Intent(context, ScreenTimeTrackerService::class.java)
    try {
        ContextCompat.startForegroundService(context, intent)
    } catch (e: Exception) {                 // ForegroundServiceStartNotAllowedException (API 31+)
        if (BuildConfig.DEBUG) Log.w(TAG, "FGS start refused", e)
    }
}
```

**Better still:** replace this whole block with a `WorkManager` one-shot `OneTimeWorkRequest` that calls `start()` from an expedited worker, or accept `START_STICKY` + `BootReceiver` as the recovery path. The AlarmManager dance adds ~70 lines and buys very little over `START_STICKY`.

---

## 🟠 High

### H1 — The tracking loop re-scans a two-hour event window every 1.5 seconds

**Where:** `data/UsageRepository.kt:139-199`, `service/ScreenTimeTrackerService.kt:45,128-133`

```kotlin
private const val POLL_INTERVAL_MS = 1500L
...
val startTime = endTime - (1000 * 60 * 60 * 2) // 2 hours window
val events = usageStatsManager.queryEvents(startTime, endTime)
while (events.hasNextEvent()) { ... }
```

Every 1.5 seconds — 2,400 times an hour of screen-on time — the service makes a binder call that returns and parses **two hours of system-wide usage events** (routinely thousands of records on an active device), only to keep the last one. This is the single largest battery and CPU cost in the app, and it runs on `Dispatchers.Default` (the CPU pool) despite being pure blocking IPC, so it also starves the CPU-bound pool.

**Fix — three compounding improvements:**

```kotlin
// 1. Narrow the window and keep a cursor.
private var lastEventQueryTime = 0L

fun getCurrentForegroundPackage(): String? {
    val end = System.currentTimeMillis()
    val start = if (lastEventQueryTime == 0L) end - 60_000L else lastEventQueryTime - 1_000L
    lastEventQueryTime = end
    val events = runCatching { usageStatsManager.queryEvents(start, end) }.getOrNull()
    // ...scan only the delta; retain `lastForegroundApp` as a field across calls
}
```

```kotlin
// 2. Adaptive polling — only poll fast while a tracked app is actually in front.
private fun currentPollInterval(): Long =
    if (currentActivePackage != null) 2_000L else 8_000L
```

```kotlin
// 3. Use Dispatchers.IO for the binder call.
private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
```

**Best fix:** stop polling for the *timer*. Once a tracked app is detected in the foreground, you know exactly when the limit expires — `delay(limitMs - elapsed)` and then re-verify the app is still in front. That turns thousands of wakeups into one.

---

### H2 — Blocking `PackageManager` and SharedPreferences I/O on the main thread

Four separate sites do disk or binder I/O on the UI/main thread:

| Where | Call | Thread |
|---|---|---|
| `ui/screens/SettingsScreen.kt:435-436` | `remember(pkg) { viewModel.getAppName(pkg) }` / `getAppIcon(pkg)` | **composition** |
| `ui/screens/SettingsViewModel.kt:35-46` | 5 `SharedPreferences` reads + `isIgnoringBatteryOptimizations` in the property initializer | main |
| `MainActivity.kt:55-61` | `PreferencesManager(this)` in `onResume()` | main |
| `service/BootReceiver.kt:25` | `PreferencesManager(context)` | broadcast main thread (10 s budget) |

`SettingsScreen.kt:435` is the worst: it performs **two binder round-trips to `PackageManager` inside the composition phase**, for every visible tracked-app row, on every recomposition where `pkg` changes identity. Compose composition must be side-effect-free and fast; this makes list scrolling jank and will trip StrictMode.

**Fix:** resolve names and icons once, off the main thread, in the ViewModel — never in composition.

```kotlin
// SettingsViewModel
data class TrackedApp(val packageName: String, val appName: String, val enabled: Boolean)

private fun reloadTracked() = viewModelScope.launch(Dispatchers.IO) {
    val tracked = preferencesManager.getTrackedApps().map { pkg ->
        TrackedApp(pkg, usageRepository.getAppName(pkg), preferencesManager.isAppEnabled(pkg))
    }.sortedBy { it.appName.lowercase() }
    _uiState.update { it.copy(trackedApps = tracked) }
}
```

Then `SettingsScreen` just renders `uiState.trackedApps`, and `getAppName` / `getAppIcon` are deleted from the ViewModel's public surface. Move the `SettingsViewModel` initializer reads into an `init { viewModelScope.launch(Dispatchers.IO) { ... } }`, and in `BootReceiver` use `goAsync()` before touching preferences.

---

### H3 — Every installed app's `Drawable` is eagerly loaded and retained in ViewModel state

**Where:** `data/UsageRepository.kt:43-57`, `data/AppInfo.kt:5-9`, `ui/screens/SettingsViewModel.kt:26,56`

```kotlin
val icon = resolveInfo.loadIcon(packageManager)      // for EVERY launchable app
InstalledAppInfo(packageName = pkg, appName = label, icon = icon)
```

`getInstalledApps()` inflates the icon for *all* launchable packages up front — on a typical device that's 120–250 adaptive icons, each an `AdaptiveIconDrawable` backed by vector/bitmap layers. They are then held indefinitely in `SettingsUiState.installedApps`, which lives in a `ViewModel` and therefore **survives configuration changes and screen exits**. Opening Settings once permanently costs tens of megabytes for the process lifetime.

Compounding it, `AppPickerBottomSheet.kt:194-211` and `SettingsScreen.kt:505-522` and `UsageCard.kt:51-68` each rasterize a `Drawable` to a `Bitmap` **on the main thread** at `drawable.intrinsicWidth × intrinsicHeight` — commonly 432×432 px ≈ 750 KB per icon. This same 18-line block is duplicated verbatim in three files.

Note also: `intrinsicWidth` returns `-1` for some drawables, and `coerceAtLeast(1)` silently turns that into a **1×1 pixel bitmap** — the icon renders as a single stretched pixel rather than falling back to the letter placeholder.

**Fix — load icons lazily and cache them.** Add Coil, which has first-class Compose + `Drawable` support:

```kotlin
// libs.versions.toml
coil = "2.7.0"
coil-compose = { group = "io.coil-kt", name = "coil-compose", version.ref = "coil" }
```

```kotlin
// InstalledAppInfo drops the Drawable entirely
data class InstalledAppInfo(val packageName: String, val appName: String)

// one shared composable replaces all three duplicated blocks
@Composable
fun AppIcon(packageName: String, size: Dp, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    AsyncImage(
        model = ImageRequest.Builder(context)
            .data(packageName)
            .fetcherFactory(AppIconFetcher.Factory())   // small custom fetcher over PackageManager
            .size(with(LocalDensity.current) { size.roundToPx() })
            .build(),
        contentDescription = null,
        modifier = modifier.size(size).clip(RoundedCornerShape(size / 4))
    )
}
```

If you want to avoid a new dependency, at minimum: drop `icon` from `InstalledAppInfo`, extract the drawable→bitmap block into one `util/DrawableExt.kt`, rasterize at a **fixed** target size (e.g. 96 px) instead of `intrinsicWidth`, and return `null` when `intrinsicWidth <= 0`.

---

### H4 — The foreground service keeps polling when there is nothing to track

**Where:** `service/ScreenTimeTrackerService.kt:141-144`

```kotlin
private fun checkForegroundApp() {
    if (!preferencesManager.isTrackingEnabled) return
    val trackedPackages = preferencesManager.getEnabledTrackedApps()
    ...
}
```

When tracking is disabled the function returns immediately — but the `while (isActive && isScreenOn)` loop keeps waking every 1.5 seconds forever, and the persistent notification stays in the shade. The same happens when the user has zero tracked apps (the default state on first install, since the app now ships with no presets).

**Fix:**

```kotlin
private fun checkForegroundApp() {
    val trackedPackages = preferencesManager.getEnabledTrackedApps()
    if (!preferencesManager.isTrackingEnabled || trackedPackages.isEmpty()) {
        stopSelf()
        return
    }
    ...
}
```

and have `SettingsViewModel.addTrackedApp` call `ScreenTimeTrackerService.start(app)` so the service comes back as soon as there is something to watch.

**Related:** `isScreenOn` is hardcoded to `true` at line 38. If `BootReceiver` starts the service while the device is still locked and dark, the loop runs at full rate against a black screen until the first `ACTION_SCREEN_OFF` arrives — which never comes, because the screen was never on. Initialize it honestly:

```kotlin
override fun onCreate() {
    super.onCreate()
    isScreenOn = getSystemService(PowerManager::class.java)?.isInteractive ?: true
    ...
}
```

---

### H5 — The overlay toggle turns the feature "on" without the permission it needs

**Where:** `ui/screens/SettingsScreen.kt:189-201`

```kotlin
onCheckedChange = { enabled ->
    if (enabled && !hasOverlayAccessPermission(context)) {
        openOverlaySettings(context)
    }
    viewModel.setOverlayEnabled(enabled)     // runs unconditionally
}
```

The switch flips to "on" and persists `isOverlayEnabled = true` regardless of whether the user actually granted the permission in the Settings screen they were just thrown into. The UI then reads *"Floating card appears over apps"* while `OverlayManager.show()` silently bails at line 44. Nothing re-checks the permission when the user returns — `LifecycleEventEffect(ON_RESUME)` at line 85 refreshes only the battery status.

**Fix:** treat the permission as the source of truth.

```kotlin
// SettingsUiState
val canDrawOverlays: Boolean = false

// SettingsViewModel
fun refreshPermissionStatus() {
    _uiState.update {
        it.copy(
            isBatteryUnrestricted = isIgnoringBatteryOptimizations(getApplication()),
            canDrawOverlays = hasOverlayAccessPermission(getApplication())
        )
    }
}

// SettingsScreen
LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refreshPermissionStatus() }

Switch(
    checked = uiState.isOverlayEnabled && uiState.canDrawOverlays,
    onCheckedChange = { enabled ->
        if (enabled && !uiState.canDrawOverlays) openOverlaySettings(context)
        else viewModel.setOverlayEnabled(enabled)
    }
)
```

and render the subtitle as *"Permission required — tap to grant"* when `!canDrawOverlays`.

---

### H6 — `PermissionScreen` can trap the user in a dead end

**Where:** `ui/screens/PermissionScreen.kt:56-91, 210-249`

`hasNotificationPermission` is seeded once at line 56 and thereafter updated **only** by the `rememberLauncherForActivityResult` callback. The `ON_RESUME` observer at 77-91 refreshes usage and overlay state but never notification state.

Consequence: after the user denies `POST_NOTIFICATIONS` twice, Android stops showing the system dialog entirely. `notificationPermissionLauncher.launch(...)` then returns "denied" instantly with no visible UI. The user sees a button labelled *"Enable Notifications"* that does nothing when tapped, and the only escape is the "Skip for now" outlined button below it. If they grant the permission from system Settings instead, the screen never notices.

The gating is also inconsistent with `MainActivity`: `MainActivity.kt:31-36` picks the start destination from usage-stats permission **alone**, while `PermissionScreen` requires all three before showing "Get Started".

**Fix:**

```kotlin
DisposableEffect(lifecycleOwner) {
    val observer = LifecycleEventObserver { _, event ->
        if (event == Lifecycle.Event.ON_RESUME) {
            hasUsagePermission = hasUsageStatsPermission(context)
            hasOverlayPermission = hasOverlayAccessPermission(context)
            hasNotificationPermission = notificationPermissionGranted(context)   // ← add
            if (hasUsagePermission) onPermissionGranted()   // usage access is the only hard gate
        }
    }
    ...
}
```

and detect the permanently-denied case so you can route to app settings instead of re-launching a dialog that will never appear:

```kotlin
val activity = LocalContext.current as? Activity
val permanentlyDenied = activity != null &&
    !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.POST_NOTIFICATIONS) &&
    !hasNotificationPermission
// if permanentlyDenied -> button text "Open App Settings", intent ACTION_APPLICATION_DETAILS_SETTINGS
```

---

### H7 — There are effectively no tests

**Where:** `app/src/test/.../ExampleUnitTest.kt` (`assertEquals(4, 2 + 2)`), `app/src/androidTest/.../ExampleInstrumentedTest.kt`

The project ships the two Android Studio template tests and nothing else. Meanwhile the logic that actually determines whether the product works is all pure or near-pure and trivially testable:

- session start/expiry arithmetic and the snooze offset (`ScreenTimeTrackerService.kt:161-171, 213-219`)
- alert cooldown gating (`sendAlertIfNeeded`)
- the enabled/disabled/tracked set algebra in `PreferencesManager` (`isAppEnabled`, `getEnabledTrackedApps`, add/remove interaction)
- the event-scan state machine in `getCurrentForegroundPackage`

**Fix:** extract the decision logic from the Android framework and unit-test it. The single highest-value refactor is pulling the session state machine out of the `Service`:

```kotlin
// data/SessionTracker.kt — no Android imports, fully unit-testable
class SessionTracker(private val limitMinutesProvider: () -> Int) {
    sealed interface Action {
        data object None : Action
        data class Nudge(val packageName: String, val minutesUsed: Long) : Action
        data object Dismiss : Action
    }
    fun onTick(foregroundPackage: String?, tracked: Set<String>, now: Long): Action { ... }
    fun onSnooze(now: Long) { ... }
}
```

Add `robolectric` for `PreferencesManager` (or refactor it behind an interface with an in-memory fake), and add `kotlinx-coroutines-test` + Turbine for the ViewModels. Target the four bullets above first — they cover every 🔴 finding in this review.

---

### H8 — Permission state is read impurely during composition and the start destination is frozen

**Where:** `MainActivity.kt:24-44`

```kotlin
setContent {
    ...
    val hasPermission = hasUsageStatsPermission(this@MainActivity)   // binder call in composition
    val startDestination = if (hasPermission) Routes.DASHBOARD else Routes.PERMISSION
    AppNavigation(navController = navController, startDestination = startDestination)
}
```

Two problems. First, `hasUsageStatsPermission` is a non-composable function performing an `AppOpsManager` binder call, invoked directly in the composable body — it re-runs on every recomposition and Compose is free to recompose at any time. Second, `NavHost` reads `startDestination` **only on first composition**; changing it later has no effect, so the value can't be a `State` anyway. The app currently gets away with this only because `PermissionScreen` navigates manually.

**Fix:** decide the destination before composing, and keep composition pure.

```kotlin
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val startDestination =
            if (hasUsageStatsPermission(this)) Routes.DASHBOARD else Routes.PERMISSION
        setContent {
            MyApplicationTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AppNavigation(rememberNavController(), startDestination)
                }
            }
        }
    }
}
```

and move `ensureServiceStarted()` off the main thread (see H2).

---

## 🟡 Medium

### M1 — Multi-key preference writes are not atomic
`data/PreferencesManager.kt:31-47`. `addTrackedApp` issues two independent `edit().apply()` calls (tracked set, then disabled set); `removeTrackedApp` does the same. A process death between them leaves an app tracked-but-disabled, or removed-but-still-listed as disabled. Batch them:
```kotlin
fun addTrackedApp(packageName: String) {
    prefs.edit {
        putStringSet(KEY_TRACKED_APPS, getTrackedApps() + packageName)
        putStringSet(KEY_DISABLED_APPS, getDisabledApps() - packageName)
    }
}
```
(`edit { }` is the `androidx.core.content` KTX extension — already available via `core-ktx`.)

### M2 — `alert_cooldown_*` preference keys are never cleaned up
`data/PreferencesManager.kt:84-86` writes one key per package; `removeTrackedApp` (line 39) never removes it. Untracking and re-adding an app silently restores a stale cooldown, and the prefs file grows monotonically. Add `remove(KEY_ALERT_COOLDOWN_PREFIX + packageName)` to the batched edit in M1.

### M3 — "Snooze", "Dismiss" and "Take a Break" are functionally identical, and snooze math degenerates
`service/ScreenTimeTrackerService.kt:194, 208-222`. `sendAlertIfNeeded` already enforces `ALERT_COOLDOWN_MS = 5 min` for every path, so all three buttons produce the same next-nudge time. Worse, the snooze offset
```kotlin
sessionStartTime = now - (limitMs - 5 * 60 * 1000L).coerceAtLeast(0L)
```
collapses to `sessionStartTime = now` whenever the limit is ≤ 5 minutes — so a user on a 3-minute limit gets a 3-minute "5-minute snooze". Make the cooldown per-action instead of global:
```kotlin
private var cooldownOverrideMs: Long? = null   // set by onSnooze / onTakeBreak
// snooze: nextAlertAt = now + SNOOZE_MS  (SNOOZE_MS = 5 min, independent of limitMs)
// take a break: clear currentActivePackage AND the cooldown, so re-entry starts a fresh session
// dismiss: keep the standard cooldown
```

### M4 — The dashboard counts apps the user has toggled **off**
`ui/screens/DashboardViewModel.kt:47` uses `preferencesManager.getTrackedApps()`, while the service uses `getEnabledTrackedApps()` (`ScreenTimeTrackerService.kt:144`). Flipping an app's switch off in Settings stops nudges but leaves it in the dashboard total and breakdown. Use `getEnabledTrackedApps()` in the ViewModel, or render disabled apps in a visually muted "paused" section.

### M5 — Bar colors reshuffle on every refresh
`ui/screens/DashboardScreen.kt:217-221, 247` derives color from the **list index**, and the list is sorted by descending usage (`UsageRepository.kt:133`). Two apps trading places changes both their colors mid-session. Key the color to identity instead:
```kotlin
fun getBarColor(packageName: String): Color =
    UsageBarColors[(packageName.hashCode().absoluteValue) % UsageBarColors.size]
```

### M6 — Every user-facing string is hardcoded; `strings.xml` contains one entry
`res/values/strings.xml` holds only `app_name`. All ~45 strings live inline across `DashboardScreen`, `SettingsScreen`, `PermissionScreen`, `AppPickerBottomSheet`, `NotificationHelper` and `OverlayManager`. The app cannot be localized, and `android:supportsRtl="true"` in the manifest is currently a claim the app can't honor. Also `SettingsScreen.kt:461` hardcodes `"Nudge v1.0"` — use `BuildConfig.VERSION_NAME` so it can't drift from `versionName`. Move strings to `strings.xml` and use `stringResource(...)` / `context.getString(...)`.

### M7 — Launch theme is light-only, causing a white flash in dark mode
`res/values/themes.xml` sets `parent="android:Theme.Material.Light.NoActionBar"`. The window background is painted before Compose runs, so dark-mode users see a white flash on every cold start. Switch to `android:Theme.Material.DayNight.NoActionBar` and add a `values-night/themes.xml`, or adopt `androidx.core:core-splashscreen`.

### M8 — `lifecycle-runtime-compose` is used but not declared
`DashboardScreen.kt:45`, `SettingsScreen.kt:62` and `PermissionScreen.kt:42` import `androidx.lifecycle.compose.{LifecycleEventEffect, LocalLifecycleOwner}`, which live in `androidx.lifecycle:lifecycle-runtime-compose`. The version catalog declares only `lifecycle-runtime-ktx` and `lifecycle-viewmodel-compose`; the artifact arrives transitively through `navigation-compose:2.8.5`. A future navigation bump can silently break the build. Declare it explicitly:
```toml
androidx-lifecycle-runtime-compose = { group = "androidx.lifecycle", name = "lifecycle-runtime-compose", version.ref = "lifecycleRuntimeKtx" }
```

### M9 — Settings-intent fallbacks can still crash
`util/PermissionUtils.kt:45-52, 63-70`. The primary `startActivity` is wrapped in `try/catch`, but the **fallback** inside the catch block is not:
```kotlin
} catch (e: Exception) {
    val fallback = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)...
    context.startActivity(fallback)          // ← unguarded, throws on devices without the activity
}
```
Some OEM ROMs and most Android-TV/Go builds have no Usage Access settings activity at all. Wrap the fallback too and surface a message rather than crashing:
```kotlin
runCatching { context.startActivity(intent) }
    .recoverCatching { context.startActivity(fallback) }
    .onFailure { Toast.makeText(context, R.string.settings_unavailable, LENGTH_LONG).show() }
```
(`requestIgnoreBatteryOptimizations` at line 83 already does this correctly — make the other two match.)

### M10 — Allocation inside the chart draw loop, and zero-minute bars are drawn as non-zero
`ui/components/UsageBarChart.kt:83-92, 108`. A new `android.graphics.Paint` and `Typeface` are constructed per bar per frame. Hoist them into a `remember { }` outside the `Canvas`. Separately:
```kotlin
size = Size(barWidth.coerceAtLeast(barHeightPx), barHeightPx)
```
forces a minimum bar width of one bar-height, so an app with 0 minutes still renders a visible pill — misleading in a chart. Guard on `item.minutes > 0` before drawing the fill.

### M11 — Concurrent refreshes race each other
`ui/screens/DashboardViewModel.kt:39`. `refreshData` is called from `init`, from `LifecycleEventEffect(ON_RESUME)` (`DashboardScreen.kt:63`) and from pull-to-refresh, with no job management. Returning to the app and immediately pulling to refresh launches two overlapping coroutines whose `_uiState.update` calls interleave, producing a visible flicker and a possibly-stale final state. Keep a `refreshJob` and cancel the previous one (shown in the C2 fix). Also drop the artificial `delay(500L)` at line 56 — the indicator's retract animation is the UI layer's concern, not the data layer's.

### M12 — Unchecked system-service casts
`data/UsageRepository.kt:14-15` (`as UsageStatsManager`) and `util/PermissionUtils.kt:12` (`as AppOpsManager`) will throw `NullPointerException`/`ClassCastException` on devices where the service is absent (Android Go, some emulators, work profiles). `OverlayManager.kt:30` and `PermissionUtils.kt:76` already use `as?` — make all four consistent and handle `null` by degrading gracefully.

### M13 — Overlay accessibility: sub-minimum touch targets and no font scaling
`service/OverlayManager.kt:85-86, 239, 265`. The `dp()` helper multiplies by `displayMetrics.density` only, so the card ignores the user's font-scale setting, while the Snooze and Dismiss buttons are 42 dp tall — below the 48 dp minimum touch target in the Material and Android accessibility guidelines. The `ImageView` at line 112 has no `contentDescription`. Raise the secondary buttons to 48 dp, set `contentDescription` on the icon, and let the `TextView`s scale (they already use sp via `textSize`, which is correct — only the fixed heights need to grow).

### M14 — Alert notification IDs collide within a 1,000-slot space
`service/NotificationHelper.kt:89-91`: `2000 + (packageName.hashCode() and 0x7FFFFFFF) % 1000`. Two tracked apps colliding is unlikely but not negligible, and the effect is one nudge silently replacing another's notification. Since alerts are per-app and short-lived, map the tracked package list to stable small indices, or just use a single reusable notification ID and update its content — you never want two nudges visible at once anyway.

### M15 — Play Store distribution risk from the permission set
The manifest combines `PACKAGE_USAGE_STATS`, `SYSTEM_ALERT_WINDOW`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` and `FOREGROUND_SERVICE_SPECIAL_USE`. Each carries a Play Console declaration requirement, and `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` is restricted to a narrow set of app categories — digital-wellbeing apps are not clearly on that list, and it is a common rejection cause. `specialUse` FGS requires a written justification reviewed by Google. Not a code defect, but plan for it: prepare the declarations, and consider whether the exact-alarm/battery-exemption requests can be dropped entirely (see C5 — `START_STICKY` plus `BootReceiver` covers most of the need).

---

## 🔵 Low

| # | Finding | Location | Fix |
|---|---|---|---|
| L1 | `SocialMediaApp.isInstalled`, `getIcon` and `ALL_PACKAGE_NAMES` are dead — the app moved to a dynamic picker and only `findByPackageName` is still called | `data/SocialMediaApp.kt:22-44` | Delete the unused members, or delete the enum and inline a small `Map<String, String>` of display-name fallbacks |
| L2 | Unused import `com.nudge.app.data.SocialMediaApp` | `service/ScreenTimeTrackerService.kt:19` | Remove |
| L3 | Six unused imports: `AppOpsManager`, `Context`, `Intent`, `Uri`, `Process`, `Settings` | `ui/screens/PermissionScreen.kt:4-10` | Remove; enable `-Werror` for unused imports or run `./gradlew lint` in CI |
| L4 | Template naming survives throughout a project named "Nudge": `rootProject.name = "My Application"`, `Theme.MyApplication`, `MyApplicationTheme`, `class MyApplication` | `settings.gradle.kts:25`, `res/values/themes.xml`, `ui/theme/Theme.kt:42`, `MyApplication.kt` | Rename to `Nudge` / `NudgeTheme` / `NudgeApplication` |
| L5 | The 13 explicit `<package>` entries in `<queries>` are redundant — the `<intent>` LAUNCHER query above them already grants visibility to every launchable app | `AndroidManifest.xml:32-45` | Delete the `<package>` list |
| L6 | No release signing config, no CI, no lint baseline; `isMinifyEnabled = true` means release behavior has never been exercised | `app/build.gradle.kts:24-33` | Add a `signingConfigs` block reading from `local.properties`/env, and a GitHub Action running `assembleRelease` + `lint` + `testDebugUnitTest` |
| L7 | Java 11 source/target on AGP 9 | `app/build.gradle.kts:34-37` | Move to `JavaVersion.VERSION_17` and add a `kotlin { jvmToolchain(17) }` block |
| L8 | `docs/` is in `.gitignore:20`, so this review file is untracked | `.gitignore` | Change the entry to something narrower (e.g. `/docs/build/`) if review docs should be versioned |
| L9 | No dependency injection — `PreferencesManager` is constructed independently in 5 places and `UsageRepository` in 3 | across `MainActivity`, `BootReceiver`, both ViewModels, the service | For a module this size, a hand-rolled `AppContainer` on `MyApplication` is enough; it also makes H7's fakes injectable |
| L10 | `CircularProgress` routes `progress` through a `mutableFloatStateOf` + `LaunchedEffect` instead of animating it directly | `ui/components/CircularProgress.kt:47-56` | `val animated by animateFloatAsState(targetValue = progress, ...)` |
| L11 | `onDestroy()` calls `super.onDestroy()` before releasing the overlay and receiver | `service/ScreenTimeTrackerService.kt:313-321` | Release resources first, `super` last |

---

## Cross-cutting observations

**Architecture.** The layering (UI → ViewModel → Repository/Preferences) is sound and the Compose code is idiomatic. Two structural gaps hold it back:

1. **`PreferencesManager` is a synchronous, non-observable store.** Every consumer polls it — the service re-reads it 40 times a minute, the ViewModels read it on construction, and settings changes propagate only by luck of timing. Migrating to `DataStore` (or wrapping `SharedPreferences` in a `callbackFlow` over `OnSharedPreferenceChangeListener`) makes settings changes push-based, removes the main-thread I/O in H2, and gives the service a `Flow<Set<String>>` of tracked apps it can `collectLatest` on.

2. **`ScreenTimeTrackerService` mixes four responsibilities**: Android service lifecycle, the polling loop, session state machine, and alert dispatch. Extracting the state machine (H7) makes C1, C3 and M3 testable rather than reasoned-about.

**Duplication.** The drawable→bitmap conversion block appears verbatim in three files (`UsageCard.kt:51-68`, `AppPickerBottomSheet.kt:194-211`, `SettingsScreen.kt:505-522`). The screen-off/reset sequence appears twice in the service. The settings-intent try/fallback pattern appears three times in `PermissionUtils.kt` with two different shapes.

**What the codebase does well** — worth preserving through any refactor:
- No network permission and no analytics; the "100% on-device" claim in the README is genuinely true and verifiable from the manifest.
- Backup and data-extraction rules correctly exclude the preferences file.
- Every `Log` call is gated behind `BuildConfig.DEBUG`.
- API-level guards (`Build.VERSION.SDK_INT` checks) are consistently applied, including the `RECEIVER_NOT_EXPORTED` and `FOREGROUND_SERVICE_TYPE_SPECIAL_USE` paths.
- The version catalog is used properly, with no hardcoded coordinates in `build.gradle.kts`.

---

## Suggested order of work

1. **C4** — one-line fix (`toArgb()`), immediately visible.
2. **C2** — add error handling to both ViewModels; stops the crash and the stuck spinner.
3. **C3** — decide whether the ring shows a daily budget or drops the limit framing. Product decision, then a small change.
4. **C1 + H1 + H4** — refactor the service loop together: single dispatcher, narrowed event window, adaptive/absent polling, `stopSelf` when idle. This is the largest single piece of work and resolves the battery story.
5. **H7** — extract `SessionTracker` and unit-test it while the service refactor is fresh.
6. **H2 + H3** — move `PackageManager` work off composition and stop retaining drawables.
7. **H5 + H6** — permission UX; both are small and both are user-visible dead ends today.
8. **C5** — simplify or delete the AlarmManager restart path.
9. Medium and Low items as they come up in the surrounding code.
