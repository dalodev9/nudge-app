package com.nudge.app.ui.screens

import android.app.Application
import android.graphics.drawable.Drawable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nudge.app.data.InstalledAppInfo
import com.nudge.app.data.PreferencesManager
import com.nudge.app.data.UsageRepository
import com.nudge.app.service.ScreenTimeTrackerService
import com.nudge.app.util.isIgnoringBatteryOptimizations
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SettingsUiState(
    val isTrackingEnabled: Boolean = true,
    val isOverlayEnabled: Boolean = true,
    val timeLimitMinutes: Int = PreferencesManager.DEFAULT_TIME_LIMIT,
    val dailyBudgetMinutes: Int = PreferencesManager.DEFAULT_DAILY_BUDGET,
    val isBatteryUnrestricted: Boolean = true,
    val trackedPackages: List<String> = emptyList(),
    val appEnabledMap: Map<String, Boolean> = emptyMap(),
    val installedApps: List<InstalledAppInfo> = emptyList(),
    val isLoadingApps: Boolean = true,
    val errorMessage: String? = null
)

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val preferencesManager = PreferencesManager(application)
    private val usageRepository = UsageRepository(application)

    private val _uiState = MutableStateFlow(
        SettingsUiState(
            isTrackingEnabled = preferencesManager.isTrackingEnabled,
            isOverlayEnabled = preferencesManager.isOverlayEnabled,
            timeLimitMinutes = preferencesManager.sessionTimeLimitMinutes,
            dailyBudgetMinutes = preferencesManager.dailyBudgetMinutes,
            isBatteryUnrestricted = isIgnoringBatteryOptimizations(application),
            trackedPackages = preferencesManager.getTrackedApps().toList(),
            appEnabledMap = preferencesManager.getTrackedApps().associateWith { pkg ->
                preferencesManager.isAppEnabled(pkg)
            }
        )
    )
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    private var loadAppsJob: Job? = null

    init {
        loadInstalledApps()
    }

    fun loadInstalledApps() {
        loadAppsJob?.cancel()
        loadAppsJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoadingApps = true, errorMessage = null) }
            runCatching {
                withContext(Dispatchers.IO) {
                    usageRepository.getInstalledApps()
                }
            }.onSuccess { apps ->
                _uiState.update {
                    it.copy(
                        installedApps = apps,
                        isLoadingApps = false,
                        errorMessage = null
                    )
                }
            }.onFailure { e ->
                _uiState.update {
                    it.copy(
                        isLoadingApps = false,
                        errorMessage = "Failed to load installed apps. Please try again."
                    )
                }
            }
        }
    }

    fun setTrackingEnabled(enabled: Boolean) {
        preferencesManager.isTrackingEnabled = enabled
        _uiState.update { it.copy(isTrackingEnabled = enabled) }
        val app = getApplication<Application>()
        if (enabled) {
            ScreenTimeTrackerService.start(app)
        } else {
            ScreenTimeTrackerService.stop(app)
        }
    }

    fun setOverlayEnabled(enabled: Boolean) {
        preferencesManager.isOverlayEnabled = enabled
        _uiState.update { it.copy(isOverlayEnabled = enabled) }
    }

    fun setTimeLimit(minutes: Int) {
        val clamped = minutes.coerceIn(1, 60)
        preferencesManager.sessionTimeLimitMinutes = clamped
        _uiState.update { it.copy(timeLimitMinutes = clamped) }
    }

    fun setDailyBudget(minutes: Int) {
        val clamped = minutes.coerceIn(15, 480)
        preferencesManager.dailyBudgetMinutes = clamped
        _uiState.update { it.copy(dailyBudgetMinutes = clamped) }
    }

    fun addTrackedApp(packageName: String) {
        preferencesManager.addTrackedApp(packageName)
        val updatedTracked = preferencesManager.getTrackedApps().toList()
        val updatedMap = _uiState.value.appEnabledMap.toMutableMap().apply {
            this[packageName] = true
        }
        _uiState.update {
            it.copy(
                trackedPackages = updatedTracked,
                appEnabledMap = updatedMap
            )
        }
    }

    fun removeTrackedApp(packageName: String) {
        preferencesManager.removeTrackedApp(packageName)
        val updatedTracked = preferencesManager.getTrackedApps().toList()
        val updatedMap = _uiState.value.appEnabledMap.toMutableMap().apply {
            remove(packageName)
        }
        _uiState.update {
            it.copy(
                trackedPackages = updatedTracked,
                appEnabledMap = updatedMap
            )
        }
    }

    fun setAppEnabled(packageName: String, enabled: Boolean) {
        preferencesManager.setAppEnabled(packageName, enabled)
        val updatedMap = _uiState.value.appEnabledMap.toMutableMap().apply {
            this[packageName] = enabled
        }
        _uiState.update {
            it.copy(appEnabledMap = updatedMap)
        }
    }

    fun refreshBatteryOptimizationStatus() {
        val unrestricted = isIgnoringBatteryOptimizations(getApplication())
        _uiState.update { it.copy(isBatteryUnrestricted = unrestricted) }
    }

    fun getAppName(packageName: String): String {
        return usageRepository.getAppName(packageName)
    }

    fun getAppIcon(packageName: String): Drawable? {
        return usageRepository.getAppIcon(packageName)
    }
}
