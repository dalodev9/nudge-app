package com.nudge.app.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.nudge.app.BuildConfig
import com.nudge.app.R
import com.nudge.app.data.PreferencesManager
import com.nudge.app.data.SessionTracker
import com.nudge.app.data.UsageRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class ScreenTimeTrackerService : Service() {

    private val trackerDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val serviceScope = CoroutineScope(SupervisorJob() + trackerDispatcher)
    private var trackingJob: Job? = null
    private lateinit var usageRepository: UsageRepository
    private lateinit var preferencesManager: PreferencesManager
    private lateinit var sessionTracker: SessionTracker
    private var overlayManager: OverlayManager? = null

    @Volatile
    private var isScreenOn = true

    companion object {
        private const val FAST_POLL_INTERVAL_MS = 1500L
        private const val SLOW_POLL_INTERVAL_MS = 5000L
        private const val RESTART_DELAY_MS = 1000L
        private const val TAG = "ScreenTimeTracker"

        fun start(context: Context) {
            val intent = Intent(context, ScreenTimeTrackerService::class.java)
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) {
                    Log.w(TAG, "FGS start refused", e)
                }
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ScreenTimeTrackerService::class.java))
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON -> {
                    isScreenOn = true
                    startTrackingLoop()
                }
                Intent.ACTION_SCREEN_OFF -> {
                    isScreenOn = false
                    stopTrackingLoop()
                    serviceScope.launch {
                        usageRepository.resetForegroundCursor()
                        sessionTracker.onScreenOff()
                    }
                    overlayManager?.dismiss()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        usageRepository = UsageRepository(this)
        preferencesManager = PreferencesManager(this)
        overlayManager = OverlayManager(this)
        sessionTracker = SessionTracker(
            limitMinutesProvider = { preferencesManager.sessionTimeLimitMinutes }
        )

        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
        isScreenOn = powerManager?.isInteractive ?: true

        // Populate cooldown cache from persisted preferences
        preferencesManager.getTrackedApps().forEach { pkg ->
            val lastAlert = preferencesManager.getLastAlertTime(pkg)
            if (lastAlert > 0L) {
                sessionTracker.setLastAlertTime(pkg, lastAlert)
            }
        }

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(screenReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(screenReceiver, filter)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!preferencesManager.isTrackingEnabled || preferencesManager.getEnabledTrackedApps().isEmpty()) {
            stopSelf()
            return START_NOT_STICKY
        }

        val notification = NotificationHelper.buildServiceNotification(
            this, getString(R.string.notification_service_monitoring)
        )

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceCompat.startForeground(
                    this,
                    NotificationHelper.SERVICE_NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NotificationHelper.SERVICE_NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) {
                Log.w(TAG, "Failed to start foreground service", e)
            }
            stopSelf()
            return START_NOT_STICKY
        }

        startTrackingLoop()
        return START_STICKY
    }

    private fun startTrackingLoop() {
        if (trackingJob?.isActive == true) return

        trackingJob = serviceScope.launch {
            while (isActive && isScreenOn) {
                checkForegroundApp()
                val delayMs = getNextPollDelay()
                delay(delayMs)
            }
        }
    }

    private fun stopTrackingLoop() {
        trackingJob?.cancel()
        trackingJob = null
    }

    private fun getNextPollDelay(): Long {
        val activePkg = sessionTracker.currentActivePackage
        return if (activePkg != null) {
            val limitMs = preferencesManager.sessionTimeLimitMinutes * 60 * 1000L
            val elapsed = System.currentTimeMillis() - sessionTracker.sessionStartTime
            val remaining = limitMs - elapsed
            if (remaining > FAST_POLL_INTERVAL_MS) {
                remaining.coerceIn(FAST_POLL_INTERVAL_MS, SLOW_POLL_INTERVAL_MS)
            } else {
                FAST_POLL_INTERVAL_MS
            }
        } else {
            SLOW_POLL_INTERVAL_MS
        }
    }

    private fun checkForegroundApp() {
        val trackedPackages = preferencesManager.getEnabledTrackedApps()
        if (!preferencesManager.isTrackingEnabled || trackedPackages.isEmpty()) {
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "No enabled tracked apps or tracking disabled, stopping service")
            }
            stopSelf()
            return
        }

        val fgPackage = usageRepository.getCurrentForegroundPackage()
        val now = System.currentTimeMillis()
        val action = sessionTracker.onTick(fgPackage, trackedPackages, now)

        when (action) {
            is SessionTracker.Action.Nudge -> {
                preferencesManager.setLastAlertTime(action.packageName, now)
                triggerNudge(action.packageName, action.minutesUsed)
            }
            is SessionTracker.Action.Dismiss -> {
                if (overlayManager?.isShowing() == true) {
                    overlayManager?.dismiss()
                }
            }
            is SessionTracker.Action.None -> {
                // No action needed
            }
        }
    }

    private fun triggerNudge(packageName: String, minutesUsed: Long) {
        val appName = usageRepository.getAppName(packageName)
        val appIcon = usageRepository.getAppIcon(packageName)

        // 1. Show soft-nudge floating popup window if enabled and permitted
        if (preferencesManager.isOverlayEnabled && OverlayManager.canDrawOverlays(this)) {
            overlayManager?.show(
                appName = appName,
                appIcon = appIcon,
                minutesUsed = minutesUsed,
                onTakeBreak = {
                    serviceScope.launch {
                        sessionTracker.onTakeBreak(System.currentTimeMillis(), packageName, minutesUsed)
                        preferencesManager.setLastAlertTime(packageName, 0L)
                    }
                },
                onSnooze = {
                    serviceScope.launch {
                        val snoozeTime = System.currentTimeMillis()
                        sessionTracker.onSnooze(snoozeTime, packageName)
                        preferencesManager.setLastAlertTime(packageName, snoozeTime)
                    }
                }
            )
        }

        // 2. Also send backup high-priority notification
        val notification = NotificationHelper.buildAlertNotification(
            this, appName, minutesUsed
        )

        try {
            NotificationManagerCompat.from(this).notify(
                NotificationHelper.getAlertNotificationId(packageName),
                notification
            )
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS not granted
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (preferencesManager.isTrackingEnabled && preferencesManager.getEnabledTrackedApps().isNotEmpty()) {
            val restartServiceIntent = Intent(applicationContext, ScreenTimeTrackerService::class.java).also {
                it.setPackage(packageName)
            }
            val restartServicePendingIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                PendingIntent.getForegroundService(
                    applicationContext,
                    1,
                    restartServiceIntent,
                    PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
                )
            } else {
                PendingIntent.getService(
                    applicationContext,
                    1,
                    restartServiceIntent,
                    PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
                )
            }
            val alarmManager = applicationContext.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            val triggerAtMillis = SystemClock.elapsedRealtime() + RESTART_DELAY_MS

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    alarmManager?.setAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        triggerAtMillis,
                        restartServicePendingIntent
                    )
                } else {
                    alarmManager?.set(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        triggerAtMillis,
                        restartServicePendingIntent
                    )
                }
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) {
                    Log.w(TAG, "Failed to schedule restart alarm", e)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        overlayManager?.dismiss()
        overlayManager = null
        try {
            unregisterReceiver(screenReceiver)
        } catch (_: IllegalArgumentException) { }
        serviceScope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
