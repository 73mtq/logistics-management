package com.logisticsmanagement.app.sync

import com.logisticsmanagement.app.model.FailureKind
import com.logisticsmanagement.app.model.ImportedOrder

enum class SyncStage { IMPORTING, TRACKING, COMPLETED, FAILED, CANCELLED }

data class SyncProgress(
    val accountId: String,
    val stage: SyncStage,
    val ordersImported: Int = 0,
    val shipmentsUpdated: Int = 0,
    val shipmentsTotal: Int = 0,
    val message: String,
    val failure: FailureKind? = null,
)

sealed interface SyncEvent {
    val accountId: String
    data class Progress(val value: SyncProgress) : SyncEvent { override val accountId = value.accountId }
    data class Data(override val accountId: String, val orders: List<ImportedOrder>) : SyncEvent
}
