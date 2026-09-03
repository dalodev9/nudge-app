package com.nudge.app.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TimeFormatUtilsTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun formatRemainingBreakTime_underOneMinute_formatsInSeconds() {
        assertEquals("45s left", formatRemainingBreakTime(context, 45_000L))
        assertEquals("0s left", formatRemainingBreakTime(context, 0L))
        assertEquals("59s left", formatRemainingBreakTime(context, 59_999L))
    }

    @Test
    fun formatRemainingBreakTime_oneMinuteOrMore_formatsInCeilingRoundedMinutes() {
        assertEquals("1m left", formatRemainingBreakTime(context, 60_000L))
        assertEquals("2m left", formatRemainingBreakTime(context, 61_000L))
        assertEquals("3m left", formatRemainingBreakTime(context, 180_000L))
    }
}
