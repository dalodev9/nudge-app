package com.nudge.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SessionTrackerTest {

    private lateinit var tracker: SessionTracker
    private var limitMinutes: Int = 15
    private val alertCooldownMs: Long = 5 * 60 * 1000L // 5 minutes

    @Before
    fun setup() {
        limitMinutes = 15
        tracker = SessionTracker(
            limitMinutesProvider = { limitMinutes },
            alertCooldownMs = alertCooldownMs
        )
    }

    @Test
    fun onTick_untrackedApp_returnsNoneAndDoesNotStartSession() {
        val tracked = setOf("com.instagram.android")
        val action = tracker.onTick("com.example.other", tracked, 1000L)

        assertEquals(SessionTracker.Action.None, action)
        assertNull(tracker.currentActivePackage)
        assertEquals(0L, tracker.sessionStartTime)
    }

    @Test
    fun onTick_trackedApp_startsNewSession() {
        val tracked = setOf("com.instagram.android")
        val startTime = 10_000L
        val action = tracker.onTick("com.instagram.android", tracked, startTime)

        assertEquals(SessionTracker.Action.None, action)
        assertEquals("com.instagram.android", tracker.currentActivePackage)
        assertEquals(startTime, tracker.sessionStartTime)
    }

    @Test
    fun onTick_exceedsLimit_triggersNudgeAction() {
        val tracked = setOf("com.instagram.android")
        val startTime = 10_000L
        tracker.onTick("com.instagram.android", tracked, startTime)

        val limitMs = 15 * 60 * 1000L
        val expiryTime = startTime + limitMs

        // Before limit
        val beforeAction = tracker.onTick("com.instagram.android", tracked, expiryTime - 1000L)
        assertEquals(SessionTracker.Action.None, beforeAction)

        // At limit
        val atLimitAction = tracker.onTick("com.instagram.android", tracked, expiryTime)
        assertTrue(atLimitAction is SessionTracker.Action.Nudge)
        val nudge = atLimitAction as SessionTracker.Action.Nudge
        assertEquals("com.instagram.android", nudge.packageName)
        assertEquals(15L, nudge.minutesUsed)
    }

    @Test
    fun onTick_cooldownSuppressesRepeatedNudges() {
        val tracked = setOf("com.instagram.android")
        val startTime = 10_000L
        tracker.onTick("com.instagram.android", tracked, startTime)

        val limitMs = 15 * 60 * 1000L
        val expiryTime = startTime + limitMs
        val firstAction = tracker.onTick("com.instagram.android", tracked, expiryTime)
        assertTrue(firstAction is SessionTracker.Action.Nudge)

        // 1 minute later — still within 5 min cooldown
        val insideCooldown = tracker.onTick("com.instagram.android", tracked, expiryTime + 60_000L)
        assertEquals(SessionTracker.Action.None, insideCooldown)

        // 6 minutes later — past 5 min cooldown
        val afterCooldown = tracker.onTick("com.instagram.android", tracked, expiryTime + alertCooldownMs + 1000L)
        assertTrue(afterCooldown is SessionTracker.Action.Nudge)
    }

    @Test
    fun onSnooze_snoozesForExactDurationEvenOnShortLimits() {
        limitMinutes = 3 // 3 minute limit
        val tracked = setOf("com.instagram.android")
        val startTime = 100_000L
        tracker.onTick("com.instagram.android", tracked, startTime)

        val limitMs = 3 * 60 * 1000L
        val alertTime = startTime + limitMs
        val alertAction = tracker.onTick("com.instagram.android", tracked, alertTime)
        assertTrue(alertAction is SessionTracker.Action.Nudge)
        assertEquals(3L, (alertAction as SessionTracker.Action.Nudge).minutesUsed)

        // User taps snooze at alertTime
        tracker.onSnooze(alertTime, "com.instagram.android")

        // 4 minutes after snooze (total 7 min): no alert (snooze is 5 minutes)
        val fourMinLater = tracker.onTick("com.instagram.android", tracked, alertTime + 4 * 60 * 1000L)
        assertEquals(SessionTracker.Action.None, fourMinLater)

        // 5 minutes and 1 sec after snooze: alert triggers with accurate total minutes
        val fiveMinLater = tracker.onTick("com.instagram.android", tracked, alertTime + alertCooldownMs + 1000L)
        assertTrue(fiveMinLater is SessionTracker.Action.Nudge)
        assertEquals(8L, (fiveMinLater as SessionTracker.Action.Nudge).minutesUsed)
    }

    @Test
    fun onTakeBreak_resetsActiveSession() {
        val tracked = setOf("com.instagram.android")
        tracker.onTick("com.instagram.android", tracked, 10_000L)
        assertEquals("com.instagram.android", tracker.currentActivePackage)

        tracker.onTakeBreak()
        assertNull(tracker.currentActivePackage)
        assertEquals(0L, tracker.sessionStartTime)
    }

    @Test
    fun onScreenOff_resetsActiveSession() {
        val tracked = setOf("com.instagram.android")
        tracker.onTick("com.instagram.android", tracked, 10_000L)

        tracker.onScreenOff()
        assertNull(tracker.currentActivePackage)
        assertEquals(0L, tracker.sessionStartTime)
    }

    @Test
    fun exitingTrackedApp_emitsDismissActionAndResetsSession() {
        val tracked = setOf("com.instagram.android")
        tracker.onTick("com.instagram.android", tracked, 10_000L)

        val action = tracker.onTick("com.android.launcher", tracked, 20_000L)
        assertEquals(SessionTracker.Action.Dismiss, action)
        assertNull(tracker.currentActivePackage)
        assertEquals(0L, tracker.sessionStartTime)
    }
}
