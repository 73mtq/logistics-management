package com.logisticsmanagement.app

import android.content.Context
import com.logisticsmanagement.app.data.*
import com.logisticsmanagement.app.network.*
import com.logisticsmanagement.app.platform.PlatformRegistry
import com.logisticsmanagement.app.sync.*

class AppContainer(context: Context) {
    val database = LogisticsDatabase.open(context)
    val repository = LogisticsRepository(database)
    val settings = SettingsStore(context)
    val credentials = CredentialStore(context)
    val platforms = PlatformRegistry(context, repository, credentials)
    val manualTracking = ManualTrackingService(repository, credentials, Kuaidi100Client())
    val aiService = AiService(repository, settings, credentials)
    val notifications = LogisticsNotifications(context)
    val syncCoordinator = SyncCoordinator(context, repository, platforms, manualTracking, settings, credentials, notifications)
}
