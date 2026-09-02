package com.nudge.app.data

/**
 * Pure Kotlin state machine managing tracked app sessions, continuous usage timers,
 * snooze offsets, and alert cooldowns. Contains zero Android framework dependencies
 * for complete testability.
 */
class SessionTracker(
    private val limitMinutesProvider: () -> Int,
    private val alertCooldownMs: Long = DEFAULT_ALERT_COOLDOWN_MS
) {

    sealed interface Action {
        data object None : Action
        data class Nudge(val packageName: String, val minutesUsed: Long) : Action
        data object Dismiss : Action
    }

    var currentActivePackage: String? = null
        private set

    var sessionStartTime: Long = 0L
        private set

    private val alertCooldownMap = mutableMapOf<String, Long>()

    /**
     * Called on each polling tick with the currently detected foreground package.
     */
    fun onTick(foregroundPackage: String?, tracked: Set<String>, now: Long): Action {
        if (foregroundPackage != null && foregroundPackage in tracked) {
            if (foregroundPackage != currentActivePackage) {
                // New tracked app session started
                currentActivePackage = foregroundPackage
                sessionStartTime = now
                return Action.None
            } else {
                // Continuing existing session — check if limit exceeded
                val sessionDurationMs = now - sessionStartTime
                val limitMs = limitMinutesProvider() * 60 * 1000L

                if (sessionDurationMs >= limitMs) {
                    val lastAlert = alertCooldownMap[foregroundPackage] ?: 0L
                    if (now - lastAlert > alertCooldownMs) {
                        alertCooldownMap[foregroundPackage] = now
                        val minutesUsed = sessionDurationMs / (60 * 1000L)
                        return Action.Nudge(foregroundPackage, minutesUsed)
                    }
                }
                return Action.None
            }
        } else {
            // Not on a tracked app
            if (currentActivePackage != null) {
                currentActivePackage = null
                sessionStartTime = 0L
                return Action.Dismiss
            }
            return Action.None
        }
    }

    /**
     * Adjusts the session start time so the next alert triggers after [alertCooldownMs].
     */
    fun onSnooze(now: Long, packageName: String? = currentActivePackage) {
        val limitMs = limitMinutesProvider() * 60 * 1000L
        sessionStartTime = now - (limitMs - alertCooldownMs).coerceAtLeast(0L)
        if (packageName != null) {
            alertCooldownMap[packageName] = now
        }
    }

    /**
     * Resets current active session when taking a break.
     */
    fun onTakeBreak() {
        currentActivePackage = null
        sessionStartTime = 0L
    }

    /**
     * Resets current active session when the screen turns off.
     */
    fun onScreenOff() {
        currentActivePackage = null
        sessionStartTime = 0L
    }

    fun setLastAlertTime(packageName: String, timestamp: Long) {
        if (timestamp > 0L) {
            alertCooldownMap[packageName] = timestamp
        }
    }

    fun getLastAlertTime(packageName: String): Long {
        return alertCooldownMap[packageName] ?: 0L
    }

    companion object {
        const val DEFAULT_ALERT_COOLDOWN_MS = 5 * 60 * 1000L // 5 minutes
    }
}
