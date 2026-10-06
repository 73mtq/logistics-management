package com.logisticsmanagement.app.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

enum class Platform(val displayName: String) {
    XIAOMI("小米智能助理"), JD("京东"), TAOBAO("淘宝／菜鸟"), PDD("拼多多"), DOUYIN("抖音"), MANUAL("手动添加")
}

enum class ParcelStatus(val displayName: String) {
    WAITING_SHIPMENT("待发货"), IN_TRANSIT("运输中"), DELIVERING("派送中"), PICKUP("待取件"), SIGNED("已签收"), EXCEPTION("物流异常"), UNKNOWN("状态未提供")
}

enum class AccountState { NEEDS_LOGIN, READY, SYNCING, NEEDS_VERIFICATION, FAILED, REMOVED }
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
    val phoneCipher: String? = null,
    val lastQueryAt: Long? = null,
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

@Entity
data class Report(@PrimaryKey val id: String, val text: String, val createdAt: Long, val kind: String)

@Entity(indices = [Index("shipmentId")])
data class ShipmentAlias(@PrimaryKey val id: String, val shipmentId: String)

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
