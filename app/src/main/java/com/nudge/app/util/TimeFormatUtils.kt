package com.nudge.app.util

import android.content.Context
import com.nudge.app.R

fun formatRemainingBreakTime(context: Context, remainingMs: Long): String {
    val totalSeconds = (remainingMs / 1000L).coerceAtLeast(0L)
    return if (totalSeconds < 60) {
        context.getString(R.string.break_remaining_seconds, totalSeconds.toInt())
    } else {
        val minutes = ((totalSeconds + 59) / 60).toInt() // ceiling, never under-reports
        context.getString(R.string.break_remaining_minutes, minutes)
    }
}
