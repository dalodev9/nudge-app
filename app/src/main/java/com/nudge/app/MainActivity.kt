package com.nudge.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.rememberNavController
import com.nudge.app.data.PreferencesManager
import com.nudge.app.service.ScreenTimeTrackerService
import com.nudge.app.ui.navigation.AppNavigation
import com.nudge.app.ui.navigation.Routes
import com.nudge.app.ui.theme.MyApplicationTheme
import com.nudge.app.util.hasUsageStatsPermission
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val startDestination = if (hasUsageStatsPermission(this)) {
            Routes.DASHBOARD
        } else {
            Routes.PERMISSION
        }

        setContent {
            MyApplicationTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val navController = rememberNavController()
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
        lifecycleScope.launch(Dispatchers.IO) {
            if (hasUsageStatsPermission(this@MainActivity)) {
                val prefs = PreferencesManager(this@MainActivity)
                if (prefs.isTrackingEnabled && prefs.getEnabledTrackedApps().isNotEmpty()) {
                    ScreenTimeTrackerService.start(this@MainActivity)
                }
            }
        }
    }
}
