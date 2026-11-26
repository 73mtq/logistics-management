package com.logisticsmanagement.app.sync

import com.logisticsmanagement.app.platform.CapturedResponse
import com.logisticsmanagement.app.model.ImportedShipment
import com.logisticsmanagement.app.model.SourceRef

internal enum class SyncCheckpointStage { BATCH_SAVED, TRACKING_STARTED, TRACKING_SAVED, TRACKING_FAILED }

internal data class SyncCheckpoint(val stage: SyncCheckpointStage, val sourceId: String? = null)

internal data class SyncEvidence(
    val onCapture: (String?, CapturedResponse) -> Unit = { _, _ -> },
    val onCheckpoint: suspend (SyncCheckpoint) -> Unit = {},
    val onTrackingSaved: suspend (SourceRef, ImportedShipment) -> Unit = { _, _ -> },
    val onNote: (String) -> Unit = {},
)
