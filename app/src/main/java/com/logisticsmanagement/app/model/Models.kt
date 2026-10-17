package com.logisticsmanagement.app.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.ColumnInfo

enum class Platform(val displayName: String) {
    JD("京东"), TAOBAO("淘宝／菜鸟"), PDD("拼多多"), DOUYIN("抖音")
}

enum class ParcelStatus(val displayName: String) {
    WAITING_SHIPMENT("待发货"), IN_TRANSIT("运输中"), DELIVERING("派送中"), PICKUP("待取件"), SIGNED("已签收"), EXCEPTION("物流异常"), UNKNOWN("状态未提供")
}

enum class AccountState { NEEDS_LOGIN, READY, SYNCING, NEEDS_VERIFICATION, PARTIAL, FAILED, REMOVED }
enum class CaptureMode { WEB, APP_PAGE }
enum class FailureKind { NETWORK, NEEDS_LOGIN, NEEDS_VERIFICATION, UNSUPPORTED_RESPONSE, CONFIGURATION, RATE_LIMITED, SERVICE_ERROR, UNSUPPORTED_WEBVIEW }
class AppFailure(message: String, val kind: FailureKind = FailureKind.SERVICE_ERROR) : RuntimeException(message)

@Entity(indices = [Index("platform")])
data class Account(
    @PrimaryKey val id: String,
    val platform: Platform,
    val label: String,
    val profileName: String,
    val enabled: Boolean = true,
    val state: AccountState = AccountState.NEEDS_LOGIN,
    val error: String? = null,
    val lastSyncedAt: Long? = null,
    val deleted: Boolean = false,
    @ColumnInfo(defaultValue = "'WEB'") val captureMode: CaptureMode = CaptureMode.WEB,
    val externalIdentity: String? = null,
    val lastAttemptAt: Long? = null,
)

@Entity(tableName = "orders", indices = [Index(value = ["accountId", "remoteId"], unique = true)])
data class Order(
    @PrimaryKey val id: String,
    val accountId: String,
    val remoteId: String,
    val title: String,
    val status: ParcelStatus,
    val createdAt: Long?,
    val orderUrl: String,
    val imageUrl: String? = null,
)

@Entity(indices = [Index(value = ["carrierCode", "trackingNumber"]), Index("status")])
data class Shipment(
    @PrimaryKey val id: String,
    val title: String,
    val carrier: String,
    val carrierCode: String,
    val trackingNumber: String?,
    val status: ParcelStatus,
    val pickupCode: String? = null,
    val pickupAddress: String? = null,
    val estimatedDeliveryAt: Long? = null,
    val updatedAt: Long,
    val notes: String = "",
    val archived: Boolean = false,
    val imageUrl: String? = null,
)

@Entity(indices = [Index("shipmentId"), Index("accountId"), Index("orderId")])
data class SourceRef(
    @PrimaryKey val id: String,
    val shipmentId: String,
    val orderId: String,
    val accountId: String,
    val platform: Platform,
    val remoteOrderId: String,
    val remoteShipmentId: String,
    val orderUrl: String,
    val accountLabel: String,
)

@Entity(indices = [Index("shipmentId")])
data class TrackingEvent(
    @PrimaryKey val id: String,
    val shipmentId: String,
    val time: Long,
    val text: String,
    val status: String = "",
)

@Entity(indices = [Index("shipmentId")])
data class ShipmentAlias(@PrimaryKey val id: String, val shipmentId: String)

@Entity
data class PendingProfileCleanup(@PrimaryKey val accountId: String, val profileName: String)

@Entity(indices = [Index("accountId")])
data class TrackingQueue(
    @PrimaryKey val sourceId: String,
    val accountId: String,
    val requestedAt: Long,
    val lastAttemptAt: Long? = null,
    val error: String? = null,
)

data class TrackingEventData(val time: Long, val text: String, val status: String = "")
data class ImportedShipment(
    val remoteId: String,
    val trackingNumber: String? = null,
    val carrier: String = "",
    val carrierCode: String = "",
    val status: ParcelStatus = ParcelStatus.UNKNOWN,
    val pickupCode: String? = null,
    val pickupAddress: String? = null,
    val estimatedDeliveryAt: Long? = null,
    val events: List<TrackingEventData> = emptyList(),
)
data class ImportedOrder(
    val remoteId: String,
    val title: String,
    val status: ParcelStatus,
    val createdAt: Long?,
    val orderUrl: String,
    val imageUrl: String? = null,
    val shipments: List<ImportedShipment>,
)

data class OrderBatch(
    val orders: List<ImportedOrder>,
    val excludedRemoteIds: Set<String> = emptySet(),
    val observedCount: Int = orders.size + excludedRemoteIds.size,
)

data class IngestResult(val inserted: Int = 0, val updated: Int = 0, val excluded: Int = 0) {
    operator fun plus(other: IngestResult) = IngestResult(inserted + other.inserted, updated + other.updated, excluded + other.excluded)
    val description: String get() = "新增 $inserted 个，更新 $updated 个，排除 $excluded 个"
}
