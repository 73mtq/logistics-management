package com.logisticsmanagement.app

import android.app.Application
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class LogisticsApplication : Application() {
    lateinit var container: AppContainer
        private set
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE) && WebViewFeature.isFeatureSupported(WebViewFeature.DELETE_BROWSING_DATA)) {
            container.platforms.prepareProfiles()
        }
        container.notifications.createChannels()
        applicationScope.launch {
            container.repository.recoverInterruptedSyncs()
            container.syncCoordinator.updateSchedules()
        }
    }
}
