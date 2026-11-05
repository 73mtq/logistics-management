package com.logisticsmanagement.app

import com.logisticsmanagement.app.model.Account
import com.logisticsmanagement.app.model.Order
import com.logisticsmanagement.app.model.ParcelStatus
import com.logisticsmanagement.app.model.Platform
import com.logisticsmanagement.app.model.Shipment
import com.logisticsmanagement.app.model.SourceRef
import com.logisticsmanagement.app.ui.ShipmentFilter
import com.logisticsmanagement.app.ui.matches
import com.logisticsmanagement.app.ui.matchesNativeUnlinked
import com.logisticsmanagement.app.ui.matchesUnshipped
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShipmentFilterTest {
    private val jdAccount = Account("jd-1", Platform.JD, "京东主账号", "account_jd")
    private val douyinAccount = Account("dy-1", Platform.DOUYIN, "抖音账号", "account_dy")

    private fun shipment(
        status: ParcelStatus = ParcelStatus.IN_TRANSIT,
        archived: Boolean = false,
        notes: String = "",
    ) = Shipment(
        id = "shipment-1",
        title = "机械键盘",
        carrier = "顺丰",
        carrierCode = "shunfeng",
        trackingNumber = "SF123456789",
        status = status,
        updatedAt = 1_000L,
        notes = notes,
        archived = archived,
    )

    private fun source(platform: Platform = Platform.JD, accountId: String = "jd-1") = SourceRef(
        id = "source-1",
        shipmentId = "shipment-1",
        orderId = "order-1",
        accountId = accountId,
        platform = platform,
        remoteOrderId = "remote-order-1",
        remoteShipmentId = "remote-shipment-1",
        orderUrl = "https://example.com/order",
        accountLabel = "京东主账号",
    )

    @Test
    fun matchesShipmentQueryAcrossFieldsAndSource() {
        val value = shipment(notes = "放门口")
        assertTrue(value.matches(ShipmentFilter(query = "SF123"), listOf(source())))
        assertTrue(value.matches(ShipmentFilter(query = "放门口"), listOf(source())))
        assertTrue(value.matches(ShipmentFilter(query = "京东"), listOf(source())))
        assertFalse(value.matches(ShipmentFilter(query = "淘宝"), listOf(source())))
    }

    @Test
    fun filtersShipmentsByStatusAndArchive() {
        assertTrue(shipment().matches(ShipmentFilter(status = "在途"), listOf(source())))
        assertFalse(shipment(ParcelStatus.PICKUP).matches(ShipmentFilter(status = "在途"), listOf(source())))
        assertFalse(shipment(ParcelStatus.SIGNED).matches(ShipmentFilter(status = "在途"), listOf(source())))
        assertTrue(shipment(ParcelStatus.SIGNED).matches(ShipmentFilter(status = "已签收"), listOf(source())))
        assertTrue(shipment(archived = true).matches(ShipmentFilter(archived = true), emptyList()))
        assertFalse(shipment(archived = true).matches(ShipmentFilter(), emptyList()))
    }

    @Test
    fun filtersShipmentsByPlatformAndAccount() {
        val value = shipment()
        assertTrue(value.matches(ShipmentFilter(platform = Platform.JD.name), listOf(source())))
        assertFalse(value.matches(ShipmentFilter(platform = Platform.PDD.name), listOf(source())))
        assertTrue(value.matches(ShipmentFilter(account = "jd-1"), listOf(source())))
        assertFalse(value.matches(ShipmentFilter(account = "dy-1"), listOf(source())))
    }

    @Test
    fun unshippedOrdersOnlyAppearForRelevantStatuses() {
        val order = Order("order-1", "jd-1", "remote-1", "显示器", ParcelStatus.WAITING_SHIPMENT, 1L, "")
        assertTrue(order.matchesUnshipped(ShipmentFilter(status = "待发货"), jdAccount))
        assertFalse(order.matchesUnshipped(ShipmentFilter(status = "已签收"), jdAccount))
        assertFalse(order.matchesUnshipped(ShipmentFilter(status = "待发货", archived = true), jdAccount))
        assertTrue(order.matchesUnshipped(ShipmentFilter(status = "全部", query = "显示器"), jdAccount))
    }

    @Test
    fun nativeUnlinkedOrdersRespectPlatformAndStatus() {
        val order = Order("order-2", "dy-1", "remote-2", "耳机", ParcelStatus.WAITING_SHIPMENT, 1L, "")
        assertTrue(order.matchesNativeUnlinked(ShipmentFilter(), douyinAccount))
        assertFalse(order.matchesNativeUnlinked(ShipmentFilter(platform = Platform.JD.name), douyinAccount))
        assertTrue(order.matchesNativeUnlinked(ShipmentFilter(status = "在途"), douyinAccount))
        assertFalse(
            order.copy(status = ParcelStatus.SIGNED)
                .matchesNativeUnlinked(ShipmentFilter(status = "在途"), douyinAccount),
        )
    }
}
