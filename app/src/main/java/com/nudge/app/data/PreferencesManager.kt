package com.nudge.app.data

import android.content.Context
import android.content.SharedPreferences

class PreferencesManager(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(
        PREFS_NAME, Context.MODE_PRIVATE
    )

    var sessionTimeLimitMinutes: Int
        get() = prefs.getInt(KEY_TIME_LIMIT, DEFAULT_TIME_LIMIT)
        set(value) = prefs.edit().putInt(KEY_TIME_LIMIT, value).apply()

    var isTrackingEnabled: Boolean
        get() = prefs.getBoolean(KEY_TRACKING_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_TRACKING_ENABLED, value).apply()

    var isOverlayEnabled: Boolean
        get() = prefs.getBoolean(KEY_OVERLAY_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_OVERLAY_ENABLED, value).apply()

    /**
     * All package names currently in the user's tracked list.
     */
    fun getTrackedApps(): Set<String> {
        return prefs.getStringSet(KEY_TRACKED_APPS, null) ?: emptySet()
    }

    fun addTrackedApp(packageName: String) {
        val current = getTrackedApps().toMutableSet()
        current.add(packageName)
        prefs.edit().putStringSet(KEY_TRACKED_APPS, current).apply()
        // Ensure it is enabled by default when added
        setAppEnabled(packageName, true)
    }

    fun removeTrackedApp(packageName: String) {
        val current = getTrackedApps().toMutableSet()
        current.remove(packageName)
        prefs.edit().putStringSet(KEY_TRACKED_APPS, current).apply()

        val disabled = getDisabledApps().toMutableSet()
        disabled.remove(packageName)
        prefs.edit().putStringSet(KEY_DISABLED_APPS, disabled).apply()
    }

    fun isAppTracked(packageName: String): Boolean {
        return packageName in getTrackedApps()
    }

    private fun getDisabledApps(): Set<String> {
        return prefs.getStringSet(KEY_DISABLED_APPS, null) ?: emptySet()
    }

    fun isAppEnabled(packageName: String): Boolean {
        return isAppTracked(packageName) && (packageName !in getDisabledApps())
    }

    fun setAppEnabled(packageName: String, enabled: Boolean) {
        val disabled = getDisabledApps().toMutableSet()
        if (enabled) {
            disabled.remove(packageName)
        } else {
            disabled.add(packageName)
        }
        prefs.edit().putStringSet(KEY_DISABLED_APPS, disabled).apply()
    }

    /**
     * Returns only the tracked apps that are actively enabled for monitoring.
     */
    fun getEnabledTrackedApps(): Set<String> {
        val allTracked = getTrackedApps()
        val disabled = getDisabledApps()
        return allTracked.filterNot { it in disabled }.toSet()
    }

    fun getLastAlertTime(packageName: String): Long {
        return prefs.getLong(KEY_ALERT_COOLDOWN_PREFIX + packageName, 0L)
    }

    fun setLastAlertTime(packageName: String, timestamp: Long) {
        prefs.edit().putLong(KEY_ALERT_COOLDOWN_PREFIX + packageName, timestamp).apply()
    }

    companion object {
        private const val PREFS_NAME = "screen_time_prefs"
        private const val KEY_TIME_LIMIT = "session_time_limit_minutes"
        private const val KEY_TRACKING_ENABLED = "tracking_enabled"
        private const val KEY_OVERLAY_ENABLED = "overlay_enabled"
        private const val KEY_TRACKED_APPS = "tracked_apps"
        private const val KEY_DISABLED_APPS = "disabled_apps"
        private const val KEY_ALERT_COOLDOWN_PREFIX = "alert_cooldown_"
        const val DEFAULT_TIME_LIMIT = 10
    }
}

