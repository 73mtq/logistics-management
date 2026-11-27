package com.logisticsmanagement.app

import android.app.Application
import androidx.webkit.WebViewFeature
import com.logisticsmanagement.app.model.AccountState
import com.logisticsmanagement.app.platform.NativeEvidenceStore
import com.logisticsmanagement.app.model.AppFailure
import com.logisticsmanagement.app.model.Platform
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
        if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE) &&
            WebViewFeature.isFeatureSupported(WebViewFeature.DELETE_BROWSING_DATA)) {
            container.platforms.prepareProfiles()
        }
        applicationScope.launch {
            container.repository.recoverInterruptedSyncs()
            container.repository.normalizeDouyinCaptureModes()
            NativeEvidenceStore.cleanup(filesDir)
            recoverNativeProgress()
            container.platforms.cleanupRemovedProfiles()
            container.syncCoordinator.cleanupRemovedAccounts()
            container.syncCoordinator.updateSchedules()
            container.ready.complete(Unit)
        }
    }

    private suspend fun recoverNativeProgress() {
        val store = container.syncCoordinator.nativeProgressStore
        for (account in container.repository.getAccounts().filter { it.platform == Platform.DOUYIN }) {
            try {
                if (store.read(account)?.running != true) continue
                val progress = checkNotNull(store.recover(account))
                container.repository.setSyncState(account.id, AccountState.PARTIAL, progress.message())
            } catch (failure: AppFailure) {
                container.repository.setSyncState(account.id, AccountState.PARTIAL, failure.message)
            }
        }
    }
}
