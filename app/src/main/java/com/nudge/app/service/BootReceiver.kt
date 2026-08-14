package com.nudge.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.nudge.app.data.PreferencesManager
import com.nudge.app.ui.screens.hasUsageStatsPermission

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return

        val action = intent.action
        Log.d("ScreenTimeTracker", "BootReceiver received action: $action")

        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == "android.intent.action.QUICKBOOT_POWERON" ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            val preferencesManager = PreferencesManager(context)
            if (preferencesManager.isTrackingEnabled && hasUsageStatsPermission(context)) {
                Log.d("ScreenTimeTracker", "Auto-starting ScreenTimeTrackerService from BootReceiver")
                ScreenTimeTrackerService.start(context)
            }
        }
    }
}

