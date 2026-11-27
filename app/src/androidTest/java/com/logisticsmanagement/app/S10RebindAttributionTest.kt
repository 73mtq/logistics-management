package com.logisticsmanagement.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.logisticsmanagement.app.data.LogisticsRepository
import com.logisticsmanagement.app.model.ImportedOrder
import com.logisticsmanagement.app.model.ImportedShipment
import com.logisticsmanagement.app.model.OrderBatch
import com.logisticsmanagement.app.model.Platform
import com.logisticsmanagement.app.model.Shipment
import com.logisticsmanagement.app.model.SourceRef
import com.logisticsmanagement.app.model.TrackingEventData
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class S10RebindAttributionTest {
    @Test fun rebindKeepsAttributionAndMergesShipment(): Unit = runBlocking {
        val directory = S10Evidence.directory("rebind")
        val name = S10Evidence.copyInputDatabase()
        val database = S10Evidence.open(name)
        try {
            val repository = LogisticsRepository(database)
            val shipmentsBefore = repository.getShipments().size
            val eventsBefore = S10Evidence.scalar(database, "SELECT count(*) FROM TrackingEvent")
            val orders = repository.getOrders()
            val removed = repository.getAllAccounts().firstOrNull { account ->
                account.platform == Platform.JD && account.deleted && orders.any { it.accountId == account.id }
            } ?: error("真实副本缺少已移除且带订单的京东账号")
            val oldOrder = orders.first { it.accountId == removed.id }
            var oldShipment: Shipment? = null
            var oldSource: SourceRef? = null
            for (shipment in repository.getShipments()) {
                val source = repository.getSources(shipment.id).firstOrNull { it.accountId == removed.id }
                if (source != null) {
                    oldShipment = shipment
                    oldSource = source
                    break
                }
            }
            val shipment = checkNotNull(oldShipment) { "已移除的京东账号缺少包裹" }
            val source = checkNotNull(oldSource) { "已移除的京东账号缺少来源" }
            check(!shipment.carrierCode.isBlank() && !shipment.trackingNumber.isNullOrBlank()) { "真实包裹缺少承运商编码或运单号" }
            val events = repository.getEvents(shipment.id)
            check(events.isNotEmpty()) { "真实包裹缺少轨迹" }

            val incoming = ImportedOrder(
                remoteId = oldOrder.remoteId,
                title = oldOrder.title,
                status = oldOrder.status,
                createdAt = oldOrder.createdAt,
                orderUrl = oldOrder.orderUrl,
                imageUrl = oldOrder.imageUrl,
                shipments = listOf(
                    ImportedShipment(
                        remoteId = source.remoteShipmentId,
                        trackingNumber = shipment.trackingNumber,
                        carrier = shipment.carrier,
                        carrierCode = shipment.carrierCode,
                        status = shipment.status,
                        pickupCode = shipment.pickupCode,
                        pickupAddress = shipment.pickupAddress,
                        estimatedDeliveryAt = shipment.estimatedDeliveryAt,
                        events = events.map { TrackingEventData(it.time, it.text, it.status) },
                    )
                ),
            )
            val rebound = repository.createAccount(Platform.JD, "S10 重新绑定")
            val result = repository.ingest(rebound, OrderBatch(listOf(incoming)))
            assertEquals("重新绑定应导入同一真实订单", 1, result.inserted)

            val newOrder = repository.getOrders().firstOrNull { it.accountId == rebound.id && it.remoteId == oldOrder.remoteId }
                ?: error("新账号缺少目标订单")
            assertNotEquals("订单标识按账号隔离", oldOrder.id, newOrder.id)
            assertEquals("包裹总数应保持", shipmentsBefore, repository.getShipments().size)
            val merged = repository.getSources(shipment.id)
            assertTrue("原包裹应保留旧账号来源", merged.any { it.id == source.id && it.accountId == removed.id })
            val newSource = merged.firstOrNull { it.accountId == rebound.id } ?: error("原包裹缺少新账号来源")
            assertEquals("新来源应指向原包裹", shipment.id, newSource.shipmentId)
            assertEquals("轨迹不应重复", events.size, repository.getEvents(shipment.id).size)
            assertEquals("轨迹总数应保持", eventsBefore, S10Evidence.scalar(database, "SELECT count(*) FROM TrackingEvent"))
            assertTrue("旧订单应保留", repository.getOrders().any { it.id == oldOrder.id })
            S10Evidence.assertRelations(database)

            S10Evidence.save(directory, "result.json", buildJsonObject {
                put("removedAccountId", removed.id)
                put("reboundAccountId", rebound.id)
                put("remoteOrderId", oldOrder.remoteId)
                put("shipmentId", shipment.id)
                put("shipmentCountBefore", shipmentsBefore)
                put("shipmentCountAfter", repository.getShipments().size)
                put("eventsBefore", eventsBefore)
                put("eventsAfter", S10Evidence.scalar(database, "SELECT COUNT(*) FROM TrackingEvent"))
                put("sourcesForShipment", merged.size)
                put("pendingQueue", repository.pendingTracking(rebound.id).size)
            })
        } finally {
            database.close()
            S10Evidence.context.deleteDatabase(name)
        }
    }
}
