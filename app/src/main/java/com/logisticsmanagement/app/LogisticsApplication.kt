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
        applicationScope.launch {
            container.repository.recoverInterruptedSyncs()
            for (account in container.repository.getAccounts().filter { it.platform == com.logisticsmanagement.app.model.Platform.DOUYIN }) {
                try {
                    val store = container.syncCoordinator.nativeProgressStore
                    if (store.read(account)?.running == true) {
                        val value = checkNotNull(store.recover(account))
                        container.repository.setSyncState(account.id, com.logisticsmanagement.app.model.AccountState.PARTIAL, value.message())
                    }
                } catch (failure: com.logisticsmanagement.app.model.AppFailure) {
                    container.repository.setSyncState(account.id, com.logisticsmanagement.app.model.AccountState.PARTIAL, failure.message)
                }
            }
            container.platforms.cleanupRemovedProfiles()
            container.syncCoordinator.updateSchedules()
            container.ready.complete(Unit)
        }
    }
}
