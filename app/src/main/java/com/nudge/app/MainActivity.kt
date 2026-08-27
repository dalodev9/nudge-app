package com.nudge.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.navigation.compose.rememberNavController
import com.nudge.app.data.PreferencesManager
import com.nudge.app.service.ScreenTimeTrackerService
import com.nudge.app.ui.navigation.AppNavigation
import com.nudge.app.ui.navigation.Routes
import com.nudge.app.ui.theme.MyApplicationTheme
import com.nudge.app.util.hasUsageStatsPermission

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            MyApplicationTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val navController = rememberNavController()
                    val hasPermission = hasUsageStatsPermission(this@MainActivity)
                    val startDestination = if (hasPermission) {
                        Routes.DASHBOARD
                    } else {
                        Routes.PERMISSION
                    }

                    AppNavigation(
                        navController = navController,
                        startDestination = startDestination
                    )
                }
            }
        }

        // Start service if tracking is enabled and permissions are granted
        ensureServiceStarted()
    }

    override fun onResume() {
        super.onResume()
        ensureServiceStarted()
    }

    private fun ensureServiceStarted() {
        if (hasUsageStatsPermission(this)) {
            val prefs = PreferencesManager(this)
            if (prefs.isTrackingEnabled) {
                ScreenTimeTrackerService.start(this)
            }
        }
    }
}
