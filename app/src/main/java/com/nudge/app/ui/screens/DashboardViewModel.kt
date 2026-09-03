package com.nudge.app.ui.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nudge.app.data.AppUsageInfo
import com.nudge.app.data.PreferencesManager
import com.nudge.app.data.UsageRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class DashboardUiState(
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val totalMinutes: Long = 0,
    val dailyBudgetMinutes: Int = PreferencesManager.DEFAULT_DAILY_BUDGET,
    val appUsages: List<AppUsageInfo> = emptyList(),
    val isTrackingEnabled: Boolean = true,
    val hasConfiguredApps: Boolean = false,
    val errorMessage: String? = null,
    val hasUsagePermission: Boolean = true
)

class DashboardViewModel @JvmOverloads constructor(
    application: Application,
    private val usageRepository: UsageRepository = UsageRepository(application),
    private val preferencesManager: PreferencesManager = PreferencesManager(application),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    private var refreshJob: Job? = null

    init {
        refreshData(showLoading = true)
    }

    fun refreshData(showLoading: Boolean = false, isPullToRefresh: Boolean = false) {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isLoading = if (showLoading) true else (if (isPullToRefresh) false else it.isLoading),
                    isRefreshing = isPullToRefresh,
                    errorMessage = null
                )
            }

            runCatching {
                withContext(ioDispatcher) {
                    val allTracked = preferencesManager.getTrackedApps()
                    val enabledPackages = preferencesManager.getEnabledTrackedApps()
                    val hasConfigured = allTracked.isNotEmpty()
                    val appUsages = usageRepository.getTodayUsageForTrackedApps(enabledPackages)
                    val totalMinutes = appUsages.sumOf { it.usageMinutes }
                    val dailyBudget = preferencesManager.dailyBudgetMinutes
                    val isTracking = preferencesManager.isTrackingEnabled
                    DataResult(appUsages, totalMinutes, dailyBudget, hasConfigured, isTracking)
                }
            }.onSuccess { result ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isRefreshing = false,
                        totalMinutes = result.totalMinutes,
                        dailyBudgetMinutes = result.dailyBudgetMinutes,
                        appUsages = result.appUsages,
                        isTrackingEnabled = result.isTrackingEnabled,
                        hasConfiguredApps = result.hasConfiguredApps,
                        hasUsagePermission = true,
                        errorMessage = null
                    )
                }
            }.onFailure { e ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isRefreshing = false,
                        hasUsagePermission = e !is SecurityException,
                        errorMessage = if (e is SecurityException) {
                            "Usage Access permission required to track app usage."
                        } else {
                            "Couldn't read usage stats. Check Usage Access."
                        }
                    )
                }
            }
        }
    }

    private data class DataResult(
        val appUsages: List<AppUsageInfo>,
        val totalMinutes: Long,
        val dailyBudgetMinutes: Int,
        val hasConfiguredApps: Boolean,
        val isTrackingEnabled: Boolean
    )
}

