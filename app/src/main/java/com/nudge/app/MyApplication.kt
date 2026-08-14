package com.nudge.app

import android.app.Application
import com.nudge.app.service.NotificationHelper

class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        NotificationHelper.createChannels(this)
    }
}

