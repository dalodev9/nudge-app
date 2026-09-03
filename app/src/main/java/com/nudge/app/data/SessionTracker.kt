package com.nudge.app.data

/**
 * Pure Kotlin state machine managing tracked app sessions, continuous usage timers,
 * snooze offsets, and alert cooldowns. Contains zero Android framework dependencies
 * for complete testability.
 */
class SessionTracker(
    private val limitMinutesProvider: () -> Int,
    private val alertCooldownMs: Long = DEFAULT_ALERT_COOLDOWN_MS,
    private val takeBreakMinutesProvider: () -> Int = { DEFAULT_TAKE_BREAK_MINUTES }
) {

    sealed interface Action {
        data object None : Action
        data class Nudge(
            val packageName: String,
            val minutesUsed: Long,
            val remainingBreakMs: Long = 0L
        ) : Action
        data object Dismiss : Action
    }

    var currentActivePackage: String? = null
        private set

    var sessionStartTime: Long = 0L
        private set

    private val nextAllowedAlertMap = mutableMapOf<String, Long>()
    private val breakUntilMap = mutableMapOf<String, Long>()
    private val breakMinutesUsedMap = mutableMapOf<String, Long>()

    /**
     * Called on each polling tick with the currently detected foreground package.
     */
    fun onTick(foregroundPackage: String?, tracked: Set<String>, now: Long): Action {
        if (foregroundPackage != null && foregroundPackage in tracked) {
            if (foregroundPackage != currentActivePackage) {
                // New tracked app session started
                currentActivePackage = foregroundPackage
                sessionStartTime = now

                val breakUntil = breakUntilMap[foregroundPackage] ?: 0L
                if (now < breakUntil) {
                    val remaining = breakUntil - now
                    val carriedMinutes = breakMinutesUsedMap[foregroundPackage] ?: 0L
                    breakUntilMap.remove(foregroundPackage)
                    breakMinutesUsedMap.remove(foregroundPackage)
                    nextAllowedAlertMap[foregroundPackage] = now + alertCooldownMs
                    return Action.Nudge(foregroundPackage, carriedMinutes, remaining)
                }

                return Action.None
            } else {
                // Continuing existing session — check if limit exceeded
                val sessionDurationMs = now - sessionStartTime
                val limitMs = limitMinutesProvider() * 60 * 1000L

                if (sessionDurationMs >= limitMs) {
                    val nextAllowed = nextAllowedAlertMap[foregroundPackage] ?: 0L
                    if (now >= nextAllowed) {
                        nextAllowedAlertMap[foregroundPackage] = now + alertCooldownMs
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
     * Snooze action: keeps the session active and schedules the next nudge after [snoozeMs].
     * Independent of limitMs, so short limits (e.g. <= 5 min) snooze for the full [snoozeMs].
     */
    fun onSnooze(
        now: Long,
        packageName: String? = currentActivePackage,
        snoozeMs: Long = SNOOZE_MS
    ) {
        if (packageName != null) {
            nextAllowedAlertMap[packageName] = now + snoozeMs
        }
    }

    /**
     * Take-a-break action: resets session and enforces a minimum break duration
     * (provided by [takeBreakMinutesProvider]) before the tracked app can be reopened
     * without immediately re-triggering the nudge.
     */
    fun onTakeBreak(
        now: Long,
        packageName: String? = currentActivePackage,
        minutesUsed: Long = 0L
    ) {
        currentActivePackage = null
        sessionStartTime = 0L
        if (packageName != null) {
            nextAllowedAlertMap.remove(packageName)
            val takeBreakMs = takeBreakMinutesProvider() * 60 * 1000L
            breakUntilMap[packageName] = now + takeBreakMs
            breakMinutesUsedMap[packageName] = minutesUsed
        } else {
            nextAllowedAlertMap.clear()
            breakUntilMap.clear()
            breakMinutesUsedMap.clear()
        }
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
            nextAllowedAlertMap[packageName] = timestamp + alertCooldownMs
        } else {
            nextAllowedAlertMap.remove(packageName)
        }
    }

    fun getLastAlertTime(packageName: String): Long {
        val nextAllowed = nextAllowedAlertMap[packageName] ?: 0L
        return if (nextAllowed > alertCooldownMs) nextAllowed - alertCooldownMs else 0L
    }

    fun getNextAllowedAlertTime(packageName: String): Long {
        return nextAllowedAlertMap[packageName] ?: 0L
    }

    companion object {
        const val DEFAULT_ALERT_COOLDOWN_MS = 5 * 60 * 1000L // 5 minutes
        const val SNOOZE_MS = 5 * 60 * 1000L // 5 minutes
        const val DEFAULT_TAKE_BREAK_MINUTES = 5
    }
}
