package com.nudge.app.ui.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nudge.app.data.AppUsageInfo
import com.nudge.app.data.PreferencesManager
import com.nudge.app.data.UsageRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DashboardUiState(
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val totalMinutes: Long = 0,
    val limitMinutes: Int = 10,
    val appUsages: List<AppUsageInfo> = emptyList(),
    val isTrackingEnabled: Boolean = true,
    val hasConfiguredApps: Boolean = false
)

class DashboardViewModel(application: Application) : AndroidViewModel(application) {

    private val usageRepository = UsageRepository(application)
    private val preferencesManager = PreferencesManager(application)

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    init {
        refreshData(showLoading = true)
    }

    fun refreshData(showLoading: Boolean = false, isPullToRefresh: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) {
            if (showLoading) {
                _uiState.update { it.copy(isLoading = true) }
            } else if (isPullToRefresh) {
                _uiState.update { it.copy(isRefreshing = true) }
            }

            val trackedPackages = preferencesManager.getTrackedApps()
            val hasConfigured = trackedPackages.isNotEmpty()
            val appUsages = usageRepository.getTodayUsageForTrackedApps(trackedPackages)

            val totalMinutes = appUsages.sumOf { it.usageMinutes }
            val limitMinutes = preferencesManager.sessionTimeLimitMinutes

            if (isPullToRefresh) {
                // Ensure a smooth rotation animation before retracting
                delay(500L)
            }

            _uiState.update {
                it.copy(
                    isLoading = false,
                    isRefreshing = false,
                    totalMinutes = totalMinutes,
                    limitMinutes = limitMinutes,
                    appUsages = appUsages,
                    isTrackingEnabled = preferencesManager.isTrackingEnabled,
                    hasConfiguredApps = hasConfigured
                )
            }
        }
    }
}

