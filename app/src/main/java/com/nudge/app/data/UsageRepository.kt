package com.nudge.app.data

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import java.util.Calendar

open class UsageRepository(private val context: Context) {

    private val usageStatsManager =
        context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
    private val packageManager: PackageManager = context.packageManager

    private var lastEventQueryTime = 0L
    private var lastForegroundApp: String? = null

    /**
     * Queries all launchable apps on the device, sorted alphabetically.
     * Excludes this app itself. Does not eagerly load icon drawables.
     */
    fun getInstalledApps(): List<InstalledAppInfo> {
        val launcherIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }

        val resolveInfos = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.queryIntentActivities(
                    launcherIntent,
                    PackageManager.ResolveInfoFlags.of(0L)
                )
            } else {
                @Suppress("DEPRECATION")
                packageManager.queryIntentActivities(launcherIntent, 0)
            }
        } catch (_: Exception) {
            emptyList()
        }

        val selfPackageName = context.packageName

        return resolveInfos
            .map { resolveInfo ->
                val pkg = resolveInfo.activityInfo.packageName
                val label = resolveInfo.loadLabel(packageManager)?.toString() ?: pkg
                InstalledAppInfo(
                    packageName = pkg,
                    appName = label
                )
            }
            .filter { it.packageName != selfPackageName }
            .distinctBy { it.packageName }
            .sortedBy { it.appName.lowercase() }
    }

    /**
     * Resolves human-readable app name for a package.
     */
    fun getAppName(packageName: String): String {
        return try {
            val appInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getApplicationInfo(
                    packageName,
                    PackageManager.ApplicationInfoFlags.of(0L)
                )
            } else {
                @Suppress("DEPRECATION")
                packageManager.getApplicationInfo(packageName, 0)
            }
            packageManager.getApplicationLabel(appInfo).toString()
        } catch (_: Exception) {
            // Fallback: Check preset enum or return formatted package name
            SocialMediaApp.findByPackageName(packageName)?.appName ?: packageName
        }
    }

    /**
     * Resolves app icon for a package.
     */
    fun getAppIcon(packageName: String): Drawable? {
        return try {
            packageManager.getApplicationIcon(packageName)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Returns today's aggregated usage stats for all user-tracked packages.
     */
    open fun getTodayUsageForTrackedApps(trackedPackages: Set<String>): List<AppUsageInfo> {
        if (trackedPackages.isEmpty()) return emptyList()

        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val startTime = calendar.timeInMillis
        val endTime = System.currentTimeMillis()

        val allStats = usageStatsManager?.queryAndAggregateUsageStats(startTime, endTime) ?: emptyMap()

        val results = mutableListOf<AppUsageInfo>()

        trackedPackages.forEach { pkg ->
            val stats = allStats[pkg]
            val totalTimeMs = stats?.totalTimeInForeground ?: 0L
            val usageMinutes = totalTimeMs / (1000 * 60)

            val name = getAppName(pkg)

            results.add(
                AppUsageInfo(
                    packageName = pkg,
                    appName = name,
                    usageMinutes = usageMinutes
                )
            )
        }

        // Sort by highest usage first
        return results.sortedByDescending { it.usageMinutes }
    }

    /**
     * Detects the currently active foreground app by scanning a narrow delta of recent usage events.
     */
    fun getCurrentForegroundPackage(): String? {
        val endTime = System.currentTimeMillis()
        val startTime = if (lastEventQueryTime == 0L) {
            endTime - 60_000L
        } else {
            (lastEventQueryTime - 1_000L).coerceAtMost(endTime)
        }
        lastEventQueryTime = endTime

        val events = try {
            usageStatsManager?.queryEvents(startTime, endTime)
        } catch (_: Exception) {
            null
        }

        if (events != null) {
            val event = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                when (event.eventType) {
                    UsageEvents.Event.ACTIVITY_RESUMED,
                    @Suppress("DEPRECATION")
                    UsageEvents.Event.MOVE_TO_FOREGROUND -> {
                        lastForegroundApp = event.packageName
                    }
                    UsageEvents.Event.ACTIVITY_PAUSED,
                    UsageEvents.Event.ACTIVITY_STOPPED,
                    @Suppress("DEPRECATION")
                    UsageEvents.Event.MOVE_TO_BACKGROUND -> {
                        if (event.packageName == lastForegroundApp) {
                            lastForegroundApp = null
                        }
                    }
                    UsageEvents.Event.SCREEN_NON_INTERACTIVE,
                    UsageEvents.Event.KEYGUARD_SHOWN -> {
                        lastForegroundApp = null
                    }
                }
            }
        }

        // Fallback on initial cold scan if events were empty
        if (lastForegroundApp == null && startTime <= endTime - 30_000L) {
            val recentStats = try {
                usageStatsManager?.queryUsageStats(
                    UsageStatsManager.INTERVAL_DAILY,
                    endTime - (1000 * 60 * 10),
                    endTime
                )
            } catch (_: Exception) {
                null
            }

            val mostRecent = recentStats
                ?.filter { it.lastTimeUsed > 0 && it.totalTimeInForeground > 0 }
                ?.maxByOrNull { it.lastTimeUsed }

            if (mostRecent != null && (endTime - mostRecent.lastTimeUsed) < 20_000) {
                lastForegroundApp = mostRecent.packageName
            }
        }

        return lastForegroundApp
    }

    fun resetForegroundCursor() {
        lastEventQueryTime = 0L
        lastForegroundApp = null
    }
}

