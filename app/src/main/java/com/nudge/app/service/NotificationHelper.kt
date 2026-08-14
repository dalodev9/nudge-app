package com.nudge.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.nudge.app.MainActivity

object NotificationHelper {

    const val CHANNEL_SERVICE = "channel_screen_time_service"
    const val CHANNEL_ALERTS = "channel_usage_alerts"
    const val SERVICE_NOTIFICATION_ID = 1001
    private const val ALERT_NOTIFICATION_ID_BASE = 2000

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return

            val serviceChannel = NotificationChannel(
                CHANNEL_SERVICE,
                "Screen Time Monitoring",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Ongoing notification while monitoring screen time"
                setShowBadge(false)
            }

            val alertChannel = NotificationChannel(
                CHANNEL_ALERTS,
                "Usage Limit Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alerts when you exceed your social media time limit"
                enableVibration(true)
                setShowBadge(true)
            }

            manager.createNotificationChannels(listOf(serviceChannel, alertChannel))
        }
    }

    fun buildServiceNotification(context: Context, contentText: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(context, CHANNEL_SERVICE)
            .setSmallIcon(android.R.drawable.ic_menu_recent_history)
            .setContentTitle("Nudge")
            .setContentText(contentText)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .build()
    }

    fun buildAlertNotification(
        context: Context,
        appName: String,
        minutesUsed: Long
    ): Notification {
        val pendingIntent = PendingIntent.getActivity(
            context,
            1,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("Time's up! \u23F0")
            .setContentText("You've been on $appName for $minutesUsed min. Take a break!")
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setVibrate(longArrayOf(0, 300, 200, 300))
            .build()
    }

    fun getAlertNotificationId(packageName: String): Int {
        return ALERT_NOTIFICATION_ID_BASE + packageName.hashCode().and(0x7FFFFFFF) % 1000
    }
}

