package com.nudge.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.nudge.app.ui.screens.DashboardScreen
import com.nudge.app.ui.screens.PermissionScreen
import com.nudge.app.ui.screens.SettingsScreen

object Routes {
    const val PERMISSION = "permission"
    const val DASHBOARD = "dashboard"
    const val SETTINGS = "settings"
}

@Composable
fun AppNavigation(
    navController: NavHostController,
    startDestination: String
) {
    NavHost(
        navController = navController,
        startDestination = startDestination
    ) {
        composable(Routes.PERMISSION) {
            PermissionScreen(
                onPermissionGranted = {
                    navController.navigate(Routes.DASHBOARD) {
                        popUpTo(Routes.PERMISSION) { inclusive = true }
                    }
                }
            )
        }
        composable(Routes.DASHBOARD) {
            DashboardScreen(
                onNavigateToSettings = {
                    navController.navigate(Routes.SETTINGS)
                }
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }
    }
}

