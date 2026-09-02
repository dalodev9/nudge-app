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
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.nudge.app.BuildConfig
import com.nudge.app.data.PreferencesManager
import com.nudge.app.data.UsageRepository
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class ScreenTimeTrackerService : Service() {

    private val trackerDispatcher = Dispatchers.Default.limitedParallelism(1)
    private val serviceScope = CoroutineScope(SupervisorJob() + trackerDispatcher)
    private var trackingJob: Job? = null
    private lateinit var usageRepository: UsageRepository
    private lateinit var preferencesManager: PreferencesManager
    private var overlayManager: OverlayManager? = null

    @Volatile
    private var isScreenOn = true
    private var currentActivePackage: String? = null // only touched on trackerDispatcher
    private var sessionStartTime: Long = 0L // only touched on trackerDispatcher
    private val alertCooldownMap = ConcurrentHashMap<String, Long>()

    companion object {
        private const val ALERT_COOLDOWN_MS = 5 * 60 * 1000L // 5 minutes
        private const val POLL_INTERVAL_MS = 1500L // 1.5 seconds
        private const val RESTART_DELAY_MS = 1000L // 1 second
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
                        currentActivePackage = null
                        sessionStartTime = 0L
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

        // Populate cooldown cache from persisted preferences
        preferencesManager.getTrackedApps().forEach { pkg ->
            val lastAlert = preferencesManager.getLastAlertTime(pkg)
            if (lastAlert > 0L) {
                alertCooldownMap[pkg] = lastAlert
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
        val notification = NotificationHelper.buildServiceNotification(
            this, "Monitoring social media usage..."
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
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private fun stopTrackingLoop() {
        trackingJob?.cancel()
        trackingJob = null
    }

    private fun checkForegroundApp() {
        if (!preferencesManager.isTrackingEnabled) return

        val trackedPackages = preferencesManager.getEnabledTrackedApps()
        val fgPackage = usageRepository.getCurrentForegroundPackage()
        val currentTime = System.currentTimeMillis()

        if (fgPackage != null && fgPackage in trackedPackages) {
            if (fgPackage != currentActivePackage) {
                // New tracked app session started
                if (overlayManager?.isShowing() == true) {
                    overlayManager?.dismiss()
                }
                currentActivePackage = fgPackage
                sessionStartTime = currentTime
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "Detected tracked app opened: $fgPackage")
                }
            } else {
                // Continuing existing session — check if limit exceeded
                val sessionDurationMs = currentTime - sessionStartTime
                val limitMs = preferencesManager.sessionTimeLimitMinutes * 60 * 1000L

                if (BuildConfig.DEBUG) {
                    Log.d(
                        TAG,
                        "Session in progress for $fgPackage: ${sessionDurationMs / 1000}s / ${limitMs / 1000}s"
                    )
                }

                if (sessionDurationMs >= limitMs) {
                    sendAlertIfNeeded(fgPackage, sessionDurationMs)
                }
            }
        } else {
            // Not on a tracked app
            if (currentActivePackage != null) {
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "Exited tracked app: $currentActivePackage")
                }
            }
            if (overlayManager?.isShowing() == true) {
                overlayManager?.dismiss()
            }
            currentActivePackage = null
            sessionStartTime = 0L
        }
    }

    private fun sendAlertIfNeeded(packageName: String, sessionDurationMs: Long) {
        val now = System.currentTimeMillis()
        val lastAlert = alertCooldownMap[packageName] ?: 0L

        if (now - lastAlert > ALERT_COOLDOWN_MS) {
            alertCooldownMap[packageName] = now
            preferencesManager.setLastAlertTime(packageName, now)

            val appName = usageRepository.getAppName(packageName)
            val appIcon = usageRepository.getAppIcon(packageName)
            val minutesUsed = sessionDurationMs / (60 * 1000L)

            // 1. Show soft-nudge floating popup window if enabled and permitted
            if (preferencesManager.isOverlayEnabled && OverlayManager.canDrawOverlays(this)) {
                overlayManager?.show(
                    appName = appName,
                    appIcon = appIcon,
                    minutesUsed = minutesUsed,
                    onTakeBreak = {
                        serviceScope.launch {
                            currentActivePackage = null
                            sessionStartTime = 0L
                        }
                    },
                    onSnooze = {
                        serviceScope.launch {
                            // Push session start time so next alert triggers in 5 minutes
                            val limitMs = preferencesManager.sessionTimeLimitMinutes * 60 * 1000L
                            sessionStartTime = System.currentTimeMillis() - (limitMs - ALERT_COOLDOWN_MS).coerceAtLeast(0L)
                            val snoozeTime = System.currentTimeMillis()
                            alertCooldownMap[packageName] = snoozeTime
                            preferencesManager.setLastAlertTime(packageName, snoozeTime)
                        }
                    },
                    onDismiss = {
                        // Cooldown is set, user acknowledged
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
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (preferencesManager.isTrackingEnabled) {
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
