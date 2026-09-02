package com.nudge.app.data

data class InstalledAppInfo(
    val packageName: String,
    val appName: String
)

data class AppUsageInfo(
    val packageName: String,
    val appName: String,
    val usageMinutes: Long = 0L
)

