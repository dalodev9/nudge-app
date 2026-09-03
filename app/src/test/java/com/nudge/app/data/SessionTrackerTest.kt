package com.nudge.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SessionTrackerTest {

    private lateinit var tracker: SessionTracker
    private var limitMinutes: Int = 15
    private var takeBreakMinutes: Int = 5
    private val alertCooldownMs: Long = 5 * 60 * 1000L // 5 minutes

    @Before
    fun setup() {
        limitMinutes = 15
        takeBreakMinutes = 5
        tracker = SessionTracker(
            limitMinutesProvider = { limitMinutes },
            alertCooldownMs = alertCooldownMs,
            takeBreakMinutesProvider = { takeBreakMinutes }
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
        assertEquals(0L, nudge.remainingBreakMs)
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
    fun onTakeBreak_earlyReopenWithinFiveMinutes_immediatelyReTriggersNudgeWithCarriedMinutes() {
        val tracked = setOf("com.instagram.android")
        val startTime = 10_000L
        tracker.onTick("com.instagram.android", tracked, startTime)
        assertEquals("com.instagram.android", tracker.currentActivePackage)

        val alertTime = startTime + 45 * 60 * 1000L
        val alertAction = tracker.onTick("com.instagram.android", tracked, alertTime)
        assertTrue(alertAction is SessionTracker.Action.Nudge)
        val minutesUsed = (alertAction as SessionTracker.Action.Nudge).minutesUsed
        assertEquals(45L, minutesUsed)

        // User taps Take a Break at alertTime with 45 minutes used
        tracker.onTakeBreak(now = alertTime, packageName = "com.instagram.android", minutesUsed = 45L)
        assertNull(tracker.currentActivePackage)
        assertEquals(0L, tracker.sessionStartTime)

        // Reopen app 2 minutes later (< 5 min break window)
        val earlyReopenTime = alertTime + 2 * 60 * 1000L
        val reopenAction = tracker.onTick("com.instagram.android", tracked, earlyReopenTime)

        // Must immediately re-trigger nudge carrying forward the 45 minutes
        assertTrue(reopenAction is SessionTracker.Action.Nudge)
        val earlyNudge = reopenAction as SessionTracker.Action.Nudge
        assertEquals("com.instagram.android", earlyNudge.packageName)
        assertEquals(45L, earlyNudge.minutesUsed)
        assertEquals(180_000L, earlyNudge.remainingBreakMs)
        assertTrue(earlyNudge.remainingBreakMs > 0L)
    }

    @Test
    fun onTakeBreak_reopenAfterFiveMinutes_startsFreshSessionWithoutImmediateNudge() {
        val tracked = setOf("com.instagram.android")
        val startTime = 10_000L
        tracker.onTick("com.instagram.android", tracked, startTime)

        val alertTime = startTime + 15 * 60 * 1000L
        val alertAction = tracker.onTick("com.instagram.android", tracked, alertTime)
        assertTrue(alertAction is SessionTracker.Action.Nudge)

        // User taps Take a Break at alertTime
        tracker.onTakeBreak(now = alertTime, packageName = "com.instagram.android", minutesUsed = 15L)
        assertNull(tracker.currentActivePackage)
        assertEquals(0L, tracker.sessionStartTime)

        // User reopens the app 5 minutes and 1 second later (break window expired)
        val validReopenTime = alertTime + (5 * 60 * 1000L) + 1000L
        val reopenAction = tracker.onTick("com.instagram.android", tracked, validReopenTime)

        // Should start a normal fresh session (Action.None)
        assertEquals(SessionTracker.Action.None, reopenAction)
        assertEquals("com.instagram.android", tracker.currentActivePackage)
        assertEquals(validReopenTime, tracker.sessionStartTime)

        // 14 minutes into the new session: no alert
        val fourteenMin = tracker.onTick("com.instagram.android", tracked, validReopenTime + 14 * 60 * 1000L)
        assertEquals(SessionTracker.Action.None, fourteenMin)

        // 15 minutes into the new session: fresh alert triggers
        val fifteenMin = tracker.onTick("com.instagram.android", tracked, validReopenTime + 15 * 60 * 1000L)
        assertTrue(fifteenMin is SessionTracker.Action.Nudge)
        assertEquals(15L, (fifteenMin as SessionTracker.Action.Nudge).minutesUsed)
    }

    @Test
    fun onTakeBreak_dynamicProviderValue_enforcesConfiguredBreakDuration() {
        // Set dynamic break minutes to custom 12 minutes
        takeBreakMinutes = 12
        val tracked = setOf("com.instagram.android")
        val startTime = 10_000L
        tracker.onTick("com.instagram.android", tracked, startTime)

        val alertTime = startTime + 15 * 60 * 1000L
        tracker.onTick("com.instagram.android", tracked, alertTime)

        // User triggers Take a Break at alertTime with 15 minutes used
        tracker.onTakeBreak(now = alertTime, packageName = "com.instagram.android", minutesUsed = 15L)

        // Reopening just before 12 minutes (12m - 1s): must still force-nudge
        val earlyReopenTime = alertTime + (12 * 60 * 1000L) - 1000L
        val earlyAction = tracker.onTick("com.instagram.android", tracked, earlyReopenTime)
        assertTrue(earlyAction is SessionTracker.Action.Nudge)
        val earlyNudge = earlyAction as SessionTracker.Action.Nudge
        assertEquals(15L, earlyNudge.minutesUsed)
        assertEquals(1000L, earlyNudge.remainingBreakMs)
        assertTrue(earlyNudge.remainingBreakMs > 0L)

        // Reset with another take break
        tracker.onTakeBreak(now = alertTime, packageName = "com.instagram.android", minutesUsed = 15L)

        // Reopening after 12 minutes (12m + 1s): must NOT force-nudge, starts fresh session
        val validReopenTime = alertTime + (12 * 60 * 1000L) + 1000L
        val validAction = tracker.onTick("com.instagram.android", tracked, validReopenTime)
        assertEquals(SessionTracker.Action.None, validAction)
        assertEquals("com.instagram.android", tracker.currentActivePackage)
        assertEquals(validReopenTime, tracker.sessionStartTime)
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
