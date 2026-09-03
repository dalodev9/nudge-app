package com.nudge.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.nudge.app.BuildConfig
import com.nudge.app.data.appContainer
import com.nudge.app.util.hasUsageStatsPermission
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return

        val action = intent.action
        if (BuildConfig.DEBUG) {
            Log.d("ScreenTimeTracker", "BootReceiver received action: $action")
        }

        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == "android.intent.action.QUICKBOOT_POWERON" ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            val pendingResult = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val preferencesManager = context.appContainer.preferencesManager
                    if (preferencesManager.isTrackingEnabled &&
                        preferencesManager.getEnabledTrackedApps().isNotEmpty() &&
                        hasUsageStatsPermission(context)
                    ) {
                        if (BuildConfig.DEBUG) {
                            Log.d("ScreenTimeTracker", "Auto-starting ScreenTimeTrackerService from BootReceiver")
                        }
                        ScreenTimeTrackerService.start(context)
                    }
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }
}
