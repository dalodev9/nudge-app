package com.nudge.app.data

import android.graphics.drawable.Drawable

data class InstalledAppInfo(
    val packageName: String,
    val appName: String,
    val icon: Drawable? = null
)

data class AppUsageInfo(
    val packageName: String,
    val appName: String,
    val icon: Drawable? = null,
    val usageMinutes: Long = 0L
)

