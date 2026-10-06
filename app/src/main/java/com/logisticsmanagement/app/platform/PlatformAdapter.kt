package com.logisticsmanagement.app.platform

import com.logisticsmanagement.app.model.Account
import com.logisticsmanagement.app.model.ImportedOrder
import com.logisticsmanagement.app.model.ImportedShipment
import com.logisticsmanagement.app.model.Platform
import com.logisticsmanagement.app.model.SourceRef

interface PlatformAdapter {
    val platform: Platform
    val loginUrl: String
    val orderListUrl: String
    val allowedOrigins: Set<String>
    suspend fun sync(account: Account, onBatch: suspend (List<ImportedOrder>) -> Unit)
    suspend fun tracking(account: Account, source: SourceRef): ImportedShipment
}
