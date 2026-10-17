package com.logisticsmanagement.app.sync

import com.logisticsmanagement.app.model.FailureKind
import com.logisticsmanagement.app.model.ImportedOrder
import com.logisticsmanagement.app.model.IngestResult

enum class SyncStage { IMPORTING, TRACKING, COMPLETED, PARTIAL, FAILED, CANCELLED }

data class TrackingFailure(val sourceId: String, val message: String, val kind: FailureKind)

data class SyncProgress(
    val accountId: String,
    val stage: SyncStage,
    val ordersImported: Int = 0,
    val shipmentsUpdated: Int = 0,
    val shipmentsTotal: Int = 0,
    val message: String,
    val failure: FailureKind? = null,
    val result: IngestResult = IngestResult(),
    val trackingFailures: List<TrackingFailure> = emptyList(),
    val pending: Int = 0,
)

sealed interface SyncEvent {
    val accountId: String
    data class Progress(val value: SyncProgress) : SyncEvent { override val accountId = value.accountId }
    data class Data(override val accountId: String, val orders: List<ImportedOrder>) : SyncEvent
}
