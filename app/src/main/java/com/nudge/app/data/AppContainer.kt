package com.nudge.app.data

import android.content.Context
import com.nudge.app.NudgeApplication

/**
 * Lightweight application-level dependency container holding shared singletons.
 */
interface AppContainer {
    val preferencesManager: PreferencesManager
    val usageRepository: UsageRepository
}

class DefaultAppContainer(private val context: Context) : AppContainer {
    override val preferencesManager: PreferencesManager by lazy {
        PreferencesManager(context.applicationContext)
    }

    override val usageRepository: UsageRepository by lazy {
        UsageRepository(context.applicationContext)
    }
}

/**
 * Helper extension to access AppContainer from any Context.
 */
val Context.appContainer: AppContainer
    get() = (applicationContext as? NudgeApplication)?.container
        ?: DefaultAppContainer(applicationContext)
