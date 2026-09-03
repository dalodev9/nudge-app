package com.nudge.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

class PreferencesManager(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(
        PREFS_NAME, Context.MODE_PRIVATE
    )

    var sessionTimeLimitMinutes: Int
        get() = prefs.getInt(KEY_TIME_LIMIT, DEFAULT_TIME_LIMIT)
        set(value) = prefs.edit { putInt(KEY_TIME_LIMIT, value) }

    var dailyBudgetMinutes: Int
        get() = prefs.getInt(KEY_DAILY_BUDGET, DEFAULT_DAILY_BUDGET)
        set(value) = prefs.edit { putInt(KEY_DAILY_BUDGET, value) }

    var takeBreakMinutes: Int
        get() = prefs.getInt(KEY_TAKE_BREAK_MINUTES, DEFAULT_TAKE_BREAK_MINUTES)
        set(value) = prefs.edit { putInt(KEY_TAKE_BREAK_MINUTES, value) }

    var isTrackingEnabled: Boolean
        get() = prefs.getBoolean(KEY_TRACKING_ENABLED, true)
        set(value) = prefs.edit { putBoolean(KEY_TRACKING_ENABLED, value) }

    var isOverlayEnabled: Boolean
        get() = prefs.getBoolean(KEY_OVERLAY_ENABLED, true)
        set(value) = prefs.edit { putBoolean(KEY_OVERLAY_ENABLED, value) }

    /**
     * All package names currently in the user's tracked list.
     */
    fun getTrackedApps(): Set<String> {
        return prefs.getStringSet(KEY_TRACKED_APPS, null) ?: emptySet()
    }

    fun addTrackedApp(packageName: String) {
        prefs.edit {
            putStringSet(KEY_TRACKED_APPS, getTrackedApps() + packageName)
            putStringSet(KEY_DISABLED_APPS, getDisabledApps() - packageName)
        }
    }

    fun removeTrackedApp(packageName: String) {
        prefs.edit {
            putStringSet(KEY_TRACKED_APPS, getTrackedApps() - packageName)
            putStringSet(KEY_DISABLED_APPS, getDisabledApps() - packageName)
            remove(KEY_ALERT_COOLDOWN_PREFIX + packageName)
        }
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
        val disabled = if (enabled) {
            getDisabledApps() - packageName
        } else {
            getDisabledApps() + packageName
        }
        prefs.edit { putStringSet(KEY_DISABLED_APPS, disabled) }
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
        prefs.edit {
            if (timestamp <= 0L) {
                remove(KEY_ALERT_COOLDOWN_PREFIX + packageName)
            } else {
                putLong(KEY_ALERT_COOLDOWN_PREFIX + packageName, timestamp)
            }
        }
    }

    companion object {
        private const val PREFS_NAME = "screen_time_prefs"
        private const val KEY_TIME_LIMIT = "session_time_limit_minutes"
        private const val KEY_DAILY_BUDGET = "daily_budget_minutes"
        private const val KEY_TAKE_BREAK_MINUTES = "take_break_minutes"
        private const val KEY_TRACKING_ENABLED = "tracking_enabled"
        private const val KEY_OVERLAY_ENABLED = "overlay_enabled"
        private const val KEY_TRACKED_APPS = "tracked_apps"
        private const val KEY_DISABLED_APPS = "disabled_apps"
        private const val KEY_ALERT_COOLDOWN_PREFIX = "alert_cooldown_"
        const val DEFAULT_TIME_LIMIT = 10
        const val DEFAULT_DAILY_BUDGET = 120
        const val DEFAULT_TAKE_BREAK_MINUTES = 5
    }
}

