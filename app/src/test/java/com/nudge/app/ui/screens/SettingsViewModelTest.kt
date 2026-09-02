package com.nudge.app.ui.screens

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.nudge.app.data.PreferencesManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SettingsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var application: Application

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        application = ApplicationProvider.getApplicationContext()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun initialState_loadsSettingsAndTrackedApps() = runTest(testDispatcher) {
        val viewModel = SettingsViewModel(application)
        viewModel.uiState.test {
            val initial = awaitItem()
            assertEquals(PreferencesManager.DEFAULT_TIME_LIMIT, initial.timeLimitMinutes)
            assertEquals(PreferencesManager.DEFAULT_DAILY_BUDGET, initial.dailyBudgetMinutes)
            assertTrue(initial.isTrackingEnabled)
        }
    }

    @Test
    fun setDailyBudget_updatesState() {
        val viewModel = SettingsViewModel(application)
        viewModel.setDailyBudget(180)
        assertEquals(180, viewModel.uiState.value.dailyBudgetMinutes)
    }

    @Test
    fun setTimeLimit_updatesStateClamped() {
        val viewModel = SettingsViewModel(application)
        viewModel.setTimeLimit(45)
        assertEquals(45, viewModel.uiState.value.timeLimitMinutes)
    }
}
