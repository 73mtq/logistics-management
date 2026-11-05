package com.logisticsmanagement.app.ui

import com.logisticsmanagement.app.model.Account
import com.logisticsmanagement.app.model.Order
import com.logisticsmanagement.app.model.ParcelStatus
import com.logisticsmanagement.app.model.Platform
import com.logisticsmanagement.app.model.Shipment
import com.logisticsmanagement.app.model.SourceRef

internal data class ShipmentFilter(
    val query: String = "",
    val status: String = STATUS_ALL,
    val platform: String? = null,
    val account: String? = null,
    val archived: Boolean = false,
) {
    val hasCriteria: Boolean
        get() = query.isNotBlank() || platform != null || account != null || archived || status != STATUS_ALL

    companion object {
        const val STATUS_ALL = "全部"
        val STATUS_OPTIONS = listOf(
            "全部", "待取件", "物流异常", "在途", "待发货", "运输中", "派送中", "已签收", "状态未提供",
        )
    }
}

internal fun Shipment.matches(filter: ShipmentFilter, sources: List<SourceRef>): Boolean {
    val searchable = listOf(
        title,
        carrier,
        trackingNumber.orEmpty(),
        notes,
        sources.joinToString(" ") { "${it.platform.displayName} ${it.accountLabel}" },
    )
    return archived == filter.archived &&
        (filter.platform == null || sources.any { it.platform.name == filter.platform }) &&
        (filter.account == null || sources.any { it.accountId == filter.account }) &&
        matchesStatus(filter.status) &&
        (filter.query.isBlank() || searchable.any { it.contains(filter.query.trim(), ignoreCase = true) })
}

private fun Shipment.matchesStatus(value: String): Boolean = when (value) {
    "待取件" -> status == ParcelStatus.PICKUP
    "在途" -> status != ParcelStatus.SIGNED && status != ParcelStatus.PICKUP
    "已签收" -> status == ParcelStatus.SIGNED
    ShipmentFilter.STATUS_ALL -> true
    else -> status.displayName == value
}

internal fun Order.matchesUnshipped(filter: ShipmentFilter, account: Account?): Boolean {
    val searchable = listOf(title, remoteId, account?.label.orEmpty(), account?.platform?.displayName.orEmpty())
    return !filter.archived &&
        filter.status in setOf(ShipmentFilter.STATUS_ALL, "在途", "待发货") &&
        (filter.platform == null || account?.platform?.name == filter.platform) &&
        (filter.account == null || accountId == filter.account) &&
        (filter.query.isBlank() || searchable.any { it.contains(filter.query.trim(), ignoreCase = true) })
}

internal fun Order.matchesNativeUnlinked(filter: ShipmentFilter, account: Account?): Boolean {
    val searchable = listOf(title, remoteId, account?.label.orEmpty())
    return !filter.archived &&
        (filter.platform == null || filter.platform == Platform.DOUYIN.name) &&
        (filter.account == null || accountId == filter.account) &&
        (filter.status == ShipmentFilter.STATUS_ALL || filter.status == status.displayName ||
            filter.status == "在途" && status != ParcelStatus.SIGNED) &&
        (filter.query.isBlank() || searchable.any { it.contains(filter.query.trim(), ignoreCase = true) })
}
