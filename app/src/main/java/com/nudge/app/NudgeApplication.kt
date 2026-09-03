package com.nudge.app

import android.app.Application
import com.nudge.app.data.AppContainer
import com.nudge.app.data.DefaultAppContainer
import com.nudge.app.service.NotificationHelper

class NudgeApplication : Application() {

    lateinit var container: AppContainer

    override fun onCreate() {
        super.onCreate()
        container = DefaultAppContainer(this)
        NotificationHelper.createChannels(this)
    }
}
