package com.logisticsmanagement.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.logisticsmanagement.app.data.LogisticsRepository
import com.logisticsmanagement.app.model.OrderBatch
import com.logisticsmanagement.app.model.Platform
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class S6SharedSourceCleanupTest {
    @Test fun excludedSharedAndIndependentSourcesAreCleaned(): Unit = runBlocking {
        val directory = S6Evidence.directory("shared")
        val name = S6Evidence.copyInputDatabase()
        val database = S6Evidence.open(name)
        try {
            val repository = LogisticsRepository(database)
            val before = S6Evidence.snapshot(database)
            S6Evidence.save(directory, "before.json", before)
            val shipments = repository.getShipments()
            val sources = shipments.associate { it.id to repository.getSources(it.id) }
            val sharedEntry = sources.entries.firstOrNull { it.value.size > 1 } ?: error("真实副本缺少共享包裹样本")
            assertEquals("共享包裹需要两个来源", 2, sharedEntry.value.size)
            val sharedId = sharedEntry.key
            val sharedSources = sharedEntry.value.sortedBy { it.id }
            assertEquals("共享包裹来源需要属于同一账号", 1, sharedSources.map { it.accountId }.distinct().size)
            assertEquals(Platform.TAOBAO, sharedSources.first().platform)
            val sharedAccount = checkNotNull(repository.getAccount(sharedSources.first().accountId))
            assertTrue(sharedAccount.enabled && !sharedAccount.deleted)
            val sharedShipment = checkNotNull(repository.getShipment(sharedId))
            assertNotNull(sharedShipment.trackingNumber)
            val orders = repository.getOrders().associateBy { it.id }
            val orderA = checkNotNull(orders[sharedSources[0].orderId])
            val orderB = checkNotNull(orders[sharedSources[1].orderId])
            assertNotEquals(orderA.remoteId, orderB.remoteId)
            val eventsBefore = repository.getEvents(sharedId).size
            assertTrue("共享包裹需要有真实轨迹", eventsBefore > 0)
            val aliasesBefore = S6Evidence.rows(database.openHelper.readableDatabase, "ShipmentAlias")
            assertTrue(aliasesBefore.any { it["shipmentId"] == sharedId })

            repository.setNotes(sharedId, "S6 共享来源备注")
            repository.setArchived(sharedId, true)
            val edited = checkNotNull(repository.getShipment(sharedId))
            assertEquals("S6 共享来源备注", edited.notes)
            assertTrue(edited.archived)

            val sharedResult = repository.ingest(sharedAccount, OrderBatch(emptyList(), setOf(orderB.remoteId)))
            assertEquals(1, sharedResult.excluded)
            assertNull(repository.getOrders().firstOrNull { it.id == orderB.id })
            val retainedSources = repository.getSources(sharedId)
            assertEquals(1, retainedSources.size)
            assertEquals(orderA.id, retainedSources.single().orderId)
            val retained = checkNotNull(repository.getShipment(sharedId))
            assertEquals("S6 共享来源备注", retained.notes)
            assertTrue(retained.archived)
            assertEquals("共享包裹轨迹需要保持", eventsBefore, repository.getEvents(sharedId).size)
            val aliasesAfter = S6Evidence.rows(database.openHelper.readableDatabase, "ShipmentAlias")
            assertTrue(aliasesAfter.any { it["shipmentId"] == sharedId })

            val independent = shipments.firstOrNull { shipment ->
                shipment.id != sharedId && sources[shipment.id]?.size == 1 &&
                    !shipment.trackingNumber.isNullOrBlank() && repository.getEvents(shipment.id).isNotEmpty() &&
                    sources.getValue(shipment.id).single().let { source ->
                        repository.getAccount(source.accountId)?.let { it.enabled && !it.deleted } == true
                    }
            } ?: error("真实副本缺少单来源包裹样本")
            val independentSource = sources.getValue(independent.id).single()
            val independentAccount = checkNotNull(repository.getAccount(independentSource.accountId))
            val independentOrder = checkNotNull(orders[independentSource.orderId])
            val independentEvents = repository.getEvents(independent.id).size
            assertTrue("单来源包裹需要有真实轨迹", independentEvents > 0)
            val independentResult = repository.ingest(independentAccount, OrderBatch(emptyList(), setOf(independentOrder.remoteId)))
            assertEquals(1, independentResult.excluded)
            assertNull(repository.getShipment(independent.id))
            assertTrue(repository.getEvents(independent.id).isEmpty())
            assertNull(repository.getOrders().firstOrNull { it.id == independentOrder.id })
            assertTrue(repository.getSources(independent.id).isEmpty())

            S6Evidence.assertRelations(database)
            val after = S6Evidence.snapshot(database)
            S6Evidence.save(directory, "after.json", after)
            val db = database.openHelper.readableDatabase
            val orphanQueues = S6Evidence.scalar(db, "SELECT count(*) FROM TrackingQueue WHERE sourceId NOT IN (SELECT id FROM SourceRef)")
            val orphanAliases = S6Evidence.scalar(db, "SELECT count(*) FROM ShipmentAlias WHERE shipmentId NOT IN (SELECT id FROM Shipment)")
            assertEquals(0L, orphanQueues)
            assertEquals(0L, orphanAliases)
            File(directory, "result.json").writeText(buildJsonObject {
                put("sharedShipmentId", sharedId)
                put("sharedSourcesBefore", 2)
                put("retainedSourcesAfter", retainedSources.size)
                put("retainedNotes", retained.notes)
                put("retainedArchived", retained.archived)
                put("retainedEvents", repository.getEvents(sharedId).size)
                put("aliasRowsBefore", aliasesBefore.size)
                put("aliasRowsAfter", aliasesAfter.size)
                put("independentShipmentId", independent.id)
                put("independentEventsBefore", independentEvents)
                put("independentShipmentDeleted", repository.getShipment(independent.id) == null)
                put("shipmentsBefore", before.getValue("Shipment").size)
                put("shipmentsAfter", after.getValue("Shipment").size)
                put("ordersBefore", before.getValue("orders").size)
                put("ordersAfter", after.getValue("orders").size)
                put("sourcesBefore", before.getValue("SourceRef").size)
                put("sourcesAfter", after.getValue("SourceRef").size)
                put("orphanQueues", orphanQueues)
                put("orphanAliases", orphanAliases)
            }.toString())
        } finally {
            database.close()
        }
    }
}
