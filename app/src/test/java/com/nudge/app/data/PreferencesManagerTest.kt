package com.nudge.app.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PreferencesManagerTest {

    private lateinit var preferencesManager: PreferencesManager
    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("screen_time_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        preferencesManager = PreferencesManager(context)
    }

    @Test
    fun defaults_matchSpecification() {
        assertTrue(preferencesManager.isTrackingEnabled)
        assertTrue(preferencesManager.isOverlayEnabled)
        assertEquals(PreferencesManager.DEFAULT_TIME_LIMIT, preferencesManager.sessionTimeLimitMinutes)
        assertEquals(PreferencesManager.DEFAULT_DAILY_BUDGET, preferencesManager.dailyBudgetMinutes)
        assertTrue(preferencesManager.getTrackedApps().isEmpty())
    }

    @Test
    fun dailyBudget_canBeUpdated() {
        preferencesManager.dailyBudgetMinutes = 90
        assertEquals(90, preferencesManager.dailyBudgetMinutes)
    }

    @Test
    fun addAndRemoveTrackedApp_updatesTrackedSet() {
        val customPkg = "com.example.custom"
        preferencesManager.addTrackedApp(customPkg)
        assertTrue(preferencesManager.getTrackedApps().contains(customPkg))
        assertTrue(preferencesManager.isAppEnabled(customPkg))

        preferencesManager.removeTrackedApp(customPkg)
        assertFalse(preferencesManager.getTrackedApps().contains(customPkg))
    }

    @Test
    fun setAppEnabled_togglesTrackingForApp() {
        val pkg = "com.instagram.android"
        preferencesManager.addTrackedApp(pkg)
        assertTrue(preferencesManager.isAppEnabled(pkg))
        assertTrue(preferencesManager.getEnabledTrackedApps().contains(pkg))

        preferencesManager.setAppEnabled(pkg, false)
        assertFalse(preferencesManager.isAppEnabled(pkg))
        assertFalse(preferencesManager.getEnabledTrackedApps().contains(pkg))
    }

    @Test
    fun lastAlertTime_persistsCorrectly() {
        val pkg = "com.instagram.android"
        val timestamp = 123456789L
        preferencesManager.setLastAlertTime(pkg, timestamp)
        assertEquals(timestamp, preferencesManager.getLastAlertTime(pkg))
    }

    @Test
    fun removeTrackedApp_cleansUpAlertCooldownKey() {
        val pkg = "com.instagram.android"
        preferencesManager.addTrackedApp(pkg)
        preferencesManager.setLastAlertTime(pkg, 987654321L)
        assertEquals(987654321L, preferencesManager.getLastAlertTime(pkg))

        preferencesManager.removeTrackedApp(pkg)
        assertEquals(0L, preferencesManager.getLastAlertTime(pkg))
    }

    @Test
    fun setLastAlertTime_zeroOrNegative_removesKey() {
        val pkg = "com.instagram.android"
        preferencesManager.setLastAlertTime(pkg, 12345L)
        assertEquals(12345L, preferencesManager.getLastAlertTime(pkg))

        preferencesManager.setLastAlertTime(pkg, 0L)
        assertEquals(0L, preferencesManager.getLastAlertTime(pkg))
    }
}
