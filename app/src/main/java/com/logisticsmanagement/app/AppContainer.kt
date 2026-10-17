package com.logisticsmanagement.app

import android.content.Context
import com.logisticsmanagement.app.data.*
import com.logisticsmanagement.app.network.*
import com.logisticsmanagement.app.platform.PlatformRegistry
import com.logisticsmanagement.app.sync.*
import kotlinx.coroutines.CompletableDeferred

class AppContainer private constructor(context: Context, val database: LogisticsDatabase, internal val automaticUpdatesEnabled: Boolean) {
    constructor(context: Context) : this(context, LogisticsDatabase.open(context), true)
    internal constructor(context: Context, database: LogisticsDatabase) : this(context, database, false)
    val repository = LogisticsRepository(database)
    val settings = SettingsStore(context)
    val credentials = CredentialStore(context)
    val platforms = PlatformRegistry(context, repository, credentials)
    val ready = CompletableDeferred<Unit>()
    val syncCoordinator = SyncCoordinator(context, repository, platforms, settings, credentials).apply {
        awaitInitialization = { ready.await() }
    }
}
