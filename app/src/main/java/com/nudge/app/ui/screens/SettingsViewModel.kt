package com.nudge.app.ui.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nudge.app.data.InstalledAppInfo
import com.nudge.app.data.PreferencesManager
import com.nudge.app.data.UsageRepository
import com.nudge.app.service.ScreenTimeTrackerService
import com.nudge.app.util.hasOverlayAccessPermission
import com.nudge.app.util.isIgnoringBatteryOptimizations
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class TrackedApp(
    val packageName: String,
    val appName: String,
    val enabled: Boolean
)

data class SettingsUiState(
    val isTrackingEnabled: Boolean = true,
    val isOverlayEnabled: Boolean = true,
    val timeLimitMinutes: Int = PreferencesManager.DEFAULT_TIME_LIMIT,
    val dailyBudgetMinutes: Int = PreferencesManager.DEFAULT_DAILY_BUDGET,
    val isBatteryUnrestricted: Boolean = true,
    val canDrawOverlays: Boolean = false,
    val trackedApps: List<TrackedApp> = emptyList(),
    val installedApps: List<InstalledAppInfo> = emptyList(),
    val isLoadingApps: Boolean = true,
    val errorMessage: String? = null
)

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val preferencesManager = PreferencesManager(application)
    private val usageRepository = UsageRepository(application)

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    private var loadAppsJob: Job? = null

    init {
        loadSettings()
        loadInstalledApps()
    }

    private fun loadSettings() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val isTracking = preferencesManager.isTrackingEnabled
                val isOverlay = preferencesManager.isOverlayEnabled
                val timeLimit = preferencesManager.sessionTimeLimitMinutes
                val dailyBudget = preferencesManager.dailyBudgetMinutes
                val isBattery = isIgnoringBatteryOptimizations(getApplication())
                val canDraw = hasOverlayAccessPermission(getApplication())
                val tracked = preferencesManager.getTrackedApps()
                val trackedList = tracked.map { pkg ->
                    TrackedApp(
                        packageName = pkg,
                        appName = usageRepository.getAppName(pkg),
                        enabled = preferencesManager.isAppEnabled(pkg)
                    )
                }.sortedBy { it.appName.lowercase() }

                _uiState.update {
                    it.copy(
                        isTrackingEnabled = isTracking,
                        isOverlayEnabled = isOverlay,
                        timeLimitMinutes = timeLimit,
                        dailyBudgetMinutes = dailyBudget,
                        isBatteryUnrestricted = isBattery,
                        canDrawOverlays = canDraw,
                        trackedApps = trackedList
                    )
                }
            }
        }
    }

    private fun reloadTrackedApps() {
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) {
                val tracked = preferencesManager.getTrackedApps()
                tracked.map { pkg ->
                    TrackedApp(
                        packageName = pkg,
                        appName = usageRepository.getAppName(pkg),
                        enabled = preferencesManager.isAppEnabled(pkg)
                    )
                }.sortedBy { it.appName.lowercase() }
            }
            _uiState.update { it.copy(trackedApps = list) }
        }
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
        if (enabled && preferencesManager.getEnabledTrackedApps().isNotEmpty()) {
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
        reloadTrackedApps()
        val app = getApplication<Application>()
        if (preferencesManager.isTrackingEnabled) {
            ScreenTimeTrackerService.start(app)
        }
    }

    fun removeTrackedApp(packageName: String) {
        preferencesManager.removeTrackedApp(packageName)
        reloadTrackedApps()
    }

    fun setAppEnabled(packageName: String, enabled: Boolean) {
        preferencesManager.setAppEnabled(packageName, enabled)
        reloadTrackedApps()
        val app = getApplication<Application>()
        if (enabled && preferencesManager.isTrackingEnabled) {
            ScreenTimeTrackerService.start(app)
        }
    }

    fun refreshPermissionStatus() {
        val app = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            val unrestricted = isIgnoringBatteryOptimizations(app)
            val canDraw = hasOverlayAccessPermission(app)
            _uiState.update {
                it.copy(
                    isBatteryUnrestricted = unrestricted,
                    canDrawOverlays = canDraw
                )
            }
        }
    }
}
