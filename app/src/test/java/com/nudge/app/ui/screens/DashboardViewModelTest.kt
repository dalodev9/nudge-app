package com.nudge.app.ui.screens

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.nudge.app.data.AppUsageInfo
import com.nudge.app.data.PreferencesManager
import com.nudge.app.data.UsageRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class DashboardViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var application: Application
    private lateinit var preferencesManager: PreferencesManager

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        application = ApplicationProvider.getApplicationContext()
        preferencesManager = PreferencesManager(application)
        preferencesManager.addTrackedApp("com.instagram.android")
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun defaultFactory_instantiatesReflectivelyWithoutError() {
        val factory = androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.getInstance(application)
        val viewModel = factory.create(DashboardViewModel::class.java)
        assertNotNull(viewModel)
    }

    @Test
    fun initialState_hasDefaultBudget() = runTest(testDispatcher) {
        val viewModel = DashboardViewModel(
            application = application,
            preferencesManager = preferencesManager,
            ioDispatcher = testDispatcher
        )
        viewModel.uiState.test {
            val state = awaitItem()
            assertEquals(PreferencesManager.DEFAULT_DAILY_BUDGET, state.dailyBudgetMinutes)
        }
    }

    @Test
    fun refreshData_onSecurityException_setsHasUsagePermissionFalseAndErrorMessage() = runTest(testDispatcher) {
        val failingRepo = object : UsageRepository(application) {
            override fun getTodayUsageForTrackedApps(trackedPackages: Set<String>): List<AppUsageInfo> {
                throw SecurityException("Usage Access revoked")
            }
        }

        val viewModel = DashboardViewModel(
            application = application,
            usageRepository = failingRepo,
            preferencesManager = preferencesManager,
            ioDispatcher = testDispatcher
        )

        viewModel.uiState.test {
            awaitItem() // initial state
            testDispatcher.scheduler.advanceUntilIdle()
            val errorState = awaitItem()

            assertFalse(errorState.isLoading)
            assertFalse(errorState.isRefreshing)
            assertFalse(errorState.hasUsagePermission)
            assertNotNull(errorState.errorMessage)
            assertEquals("Usage Access permission required to track app usage.", errorState.errorMessage)
        }
    }

    @Test
    fun refreshData_onGenericException_retainsUsagePermissionAndSetsErrorMessage() = runTest(testDispatcher) {
        val failingRepo = object : UsageRepository(application) {
            override fun getTodayUsageForTrackedApps(trackedPackages: Set<String>): List<AppUsageInfo> {
                throw RuntimeException("Unexpected I/O failure")
            }
        }

        val viewModel = DashboardViewModel(
            application = application,
            usageRepository = failingRepo,
            preferencesManager = preferencesManager,
            ioDispatcher = testDispatcher
        )

        viewModel.uiState.test {
            awaitItem() // initial state
            testDispatcher.scheduler.advanceUntilIdle()
            val errorState = awaitItem()

            assertFalse(errorState.isLoading)
            assertFalse(errorState.isRefreshing)
            assertTrue(errorState.hasUsagePermission)
            assertNotNull(errorState.errorMessage)
            assertEquals("Couldn't read usage stats. Check Usage Access.", errorState.errorMessage)
        }
    }

    @Test
    fun refreshData_onSuccess_updatesStateAndClearsErrors() = runTest(testDispatcher) {
        val successRepo = object : UsageRepository(application) {
            override fun getTodayUsageForTrackedApps(trackedPackages: Set<String>): List<AppUsageInfo> {
                return listOf(
                    AppUsageInfo(
                        packageName = "com.instagram.android",
                        appName = "Instagram",
                        usageMinutes = 42L
                    )
                )
            }
        }

        val viewModel = DashboardViewModel(
            application = application,
            usageRepository = successRepo,
            preferencesManager = preferencesManager,
            ioDispatcher = testDispatcher
        )

        viewModel.uiState.test {
            awaitItem() // initial state
            testDispatcher.scheduler.advanceUntilIdle()
            val successState = awaitItem()

            assertFalse(successState.isLoading)
            assertFalse(successState.isRefreshing)
            assertTrue(successState.hasUsagePermission)
            assertNull(successState.errorMessage)
            assertEquals(42L, successState.totalMinutes)
            assertEquals(1, successState.appUsages.size)
            assertEquals("com.instagram.android", successState.appUsages[0].packageName)
        }
    }

    @Test
    fun refreshData_disabledApp_excludedFromUsageAndTotalMinutes() = runTest(testDispatcher) {
        preferencesManager.addTrackedApp("com.twitter.android")
        preferencesManager.setAppEnabled("com.twitter.android", false)

        val repo = object : UsageRepository(application) {
            override fun getTodayUsageForTrackedApps(trackedPackages: Set<String>): List<AppUsageInfo> {
                assertFalse(trackedPackages.contains("com.twitter.android"))
                assertTrue(trackedPackages.contains("com.instagram.android"))
                return listOf(
                    AppUsageInfo(
                        packageName = "com.instagram.android",
                        appName = "Instagram",
                        usageMinutes = 30L
                    )
                )
            }
        }

        val viewModel = DashboardViewModel(
            application = application,
            usageRepository = repo,
            preferencesManager = preferencesManager,
            ioDispatcher = testDispatcher
        )

        viewModel.uiState.test {
            awaitItem()
            testDispatcher.scheduler.advanceUntilIdle()
            val state = awaitItem()

            assertEquals(30L, state.totalMinutes)
            assertEquals(1, state.appUsages.size)
            assertEquals("com.instagram.android", state.appUsages[0].packageName)
        }
    }
}
