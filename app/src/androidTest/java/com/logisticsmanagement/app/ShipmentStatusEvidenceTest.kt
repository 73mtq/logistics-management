package com.logisticsmanagement.app

import android.util.Log
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.logisticsmanagement.app.data.LogisticsDatabase
import com.logisticsmanagement.app.data.LogisticsRepository
import com.logisticsmanagement.app.model.*
import com.logisticsmanagement.app.platform.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ShipmentStatusEvidenceTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val arguments = InstrumentationRegistry.getArguments()
    private val container get() = (context.applicationContext as LogisticsApplication).container
    private fun input(key: String) = File(context.filesDir, checkNotNull(arguments.getString(key)))
    private fun output(): File = File(context.filesDir, "shipment-status-evidence/${checkNotNull(arguments.getString("runName"))}")
        .apply { check(mkdirs()) }

    @Test fun recordRealOrders(): Unit = runBlocking {
        container.ready.await()
        val account = checkNotNull(container.repository.getAccount(checkNotNull(arguments.getString("accountId"))))
        assertEquals(Platform.PDD, account.platform)
        val directory = output()
        val captures = mutableListOf<CapturedResponse>()
        val orders = mutableMapOf<String, ImportedOrder>()
        val before = container.repository.getOrders()
        try {
            PddAdapter(container.platforms).syncWithEvidence(account, { captures.add(it) }) { batch ->
                for (order in batch.orders) orders[order.remoteId] = order
            }
            assertTrue("当前账号需要提供真实订单", orders.isNotEmpty())
            assertTrue("需要完整原始列表响应", captures.any { it.kind == "response" && it.url.contains("order_list_v4") })
            assertEquals(before, container.repository.getOrders())
            Log.i("ShipmentStatusEvidence", "read completed orders=${orders.size} signed=${orders.values.count { it.status == ParcelStatus.SIGNED }}")
        } finally { File(directory, "captures.json").writeText(json.encodeToString(captures)) }
    }

    @Test fun replayRealOrdersAndTracking(): Unit = runBlocking {
        container.ready.await()
        val original = container.repository.getOrders()
        val accountId = checkNotNull(arguments.getString("accountId"))
        val captures = json.decodeFromString<List<CapturedResponse>>(input("capturesPath").readText())
        val adapter = PddAdapter(container.platforms)
        val orders = captures.mapNotNull { adapter.parseOrders(it) }.flatMap { it.orders }.associateBy { it.remoteId }
        assertTrue("需要真实列表的已收货订单", orders.values.any { it.status == ParcelStatus.SIGNED })
        val database = Room.databaseBuilder(context, LogisticsDatabase::class.java, "T08-copy-${System.nanoTime()}.db")
            .createFromFile(input("databasePath")).build()
        try {
            val repository = LogisticsRepository(database)
            val account = checkNotNull(repository.getAccount(accountId))
            val beforeOrders = repository.getOrders()
            val beforeShipments = repository.getShipments().associateBy { it.id }
            val beforeEvents = beforeShipments.keys.associateWith { repository.getEvents(it) }
            val beforeSources = beforeShipments.keys.associateWith { repository.getSources(it) }
            val existing = beforeOrders.filter { it.accountId == accountId }.map { it.remoteId }.toSet()
            val selected = orders.values.filter { it.remoteId in existing }
            val signedIds = selected.filter { it.status == ParcelStatus.SIGNED }.map { it.remoteId }.toSet()
            val signedSources = beforeSources.values.flatten().filter { it.accountId == accountId && it.remoteOrderId in signedIds }
            assertTrue("需要已经保存的已收货来源", signedSources.isNotEmpty())
            val changed = signedSources.count { beforeShipments.getValue(it.shipmentId).status != ParcelStatus.SIGNED }
            val changedOrders = selected.count { it.status == ParcelStatus.SIGNED && beforeOrders.single { stored -> stored.accountId == accountId && stored.remoteId == it.remoteId }.status != ParcelStatus.SIGNED }
            assertTrue("需要真实收货前后订单资料", changedOrders > 0)
            repeat(2) {
                repository.ingest(account, selected)
                for (source in signedSources) assertEquals(ParcelStatus.SIGNED, checkNotNull(repository.getShipment(source.shipmentId)).status)
            }
            var replayed = 0
            var older = 0
            var serviceFailures = 0
            val trackingDirectory = File(input("capturesPath").parentFile, "tracking")
            for (file in checkNotNull(trackingDirectory.listFiles()).sortedBy { it.name }) {
                val tracking = json.decodeFromString<List<CapturedResponse>>(file.readText())
                for (source in beforeSources.values.flatten().filter { it.accountId == accountId }) {
                    val beforeFailure = checkNotNull(repository.getShipment(source.shipmentId))
                    for (capture in tracking.filter { it.kind == "trackingUnavailable" && android.net.Uri.parse(it.url).getQueryParameter("order_sn") == source.remoteOrderId }) {
                        val failure = assertThrows(AppFailure::class.java) { adapter.parseTracking(capture, source) }
                        assertEquals(FailureKind.SERVICE_ERROR, failure.kind)
                        assertEquals(beforeFailure, repository.getShipment(source.shipmentId))
                        serviceFailures++
                    }
                    val parsed = tracking.filter { it.kind == "dom" }.mapNotNull { adapter.parseTracking(it, source) }.lastOrNull() ?: continue
                    val before = checkNotNull(repository.getShipment(source.shipmentId))
                    val eventTime = repository.getEvents(before.id).maxOfOrNull { it.time }
                    if (parsed.events.isNotEmpty() && eventTime != null && parsed.events.maxOf { it.time } < eventTime) older++
                    repeat(2) {
                        repository.applyTracking(source, parsed)
                        if (before.status == ParcelStatus.SIGNED && parsed.status != ParcelStatus.EXCEPTION) {
                            assertEquals("真实物流回放保持签收", ParcelStatus.SIGNED, checkNotNull(repository.getShipment(before.id)).status)
                        }
                    }
                    replayed++
                }
            }
            assertTrue("需要真实物流回放", replayed > 0)
            var storedOlderEvents = 0
            for (source in signedSources) {
                val shipment = checkNotNull(repository.getShipment(source.shipmentId))
                val events = repository.getEvents(shipment.id)
                val latestTime = events.maxOfOrNull { it.time } ?: continue
                val event = events.firstOrNull { it.time < latestTime && parcelStatus(it.status + " " + it.text) in setOf(ParcelStatus.IN_TRANSIT, ParcelStatus.DELIVERING, ParcelStatus.PICKUP) } ?: continue
                val incoming = ImportedShipment(source.remoteShipmentId, shipment.trackingNumber, shipment.carrier, shipment.carrierCode,
                    parcelStatus(event.status + " " + event.text), events = listOf(TrackingEventData(event.time, event.text, event.status)))
                repeat(2) { repository.applyTracking(source, incoming); assertEquals(ParcelStatus.SIGNED, repository.getShipment(shipment.id)?.status) }
                storedOlderEvents++
            }
            assertEquals(beforeOrders.filter { it.accountId != accountId }, repository.getOrders().filter { it.accountId != accountId })
            for ((id, before) in beforeShipments) {
                val after = checkNotNull(repository.getShipment(id))
                assertEquals(before.notes, after.notes)
                assertEquals(before.archived, after.archived)
                assertEquals(beforeSources.getValue(id), repository.getSources(id))
                assertTrue(repository.getEvents(id).containsAll(beforeEvents.getValue(id)))
                assertEquals(repository.getEvents(id).size, repository.getEvents(id).map { it.id }.toSet().size)
            }
            database.openHelper.readableDatabase.query("PRAGMA integrity_check").use { assertTrue(it.moveToFirst()); assertEquals("ok", it.getString(0)) }
            File(output(), "result.json").writeText(buildJsonObject {
                put("signedSources", signedSources.size); put("changedOrders", changedOrders); put("changedShipments", changed); put("trackingReplayed", replayed)
                put("olderTracking", older); put("storedOlderEvents", storedOlderEvents); put("serviceFailures", serviceFailures); put("rounds", 2)
            }.toString())
            Log.i("ShipmentStatusEvidence", "copy completed signedSources=${signedSources.size} changed=$changed tracking=$replayed older=$older storedOlder=$storedOlderEvents serviceFailures=$serviceFailures rounds=2")
        } finally { database.close() }
        assertEquals(original, container.repository.getOrders())
    }

    @Test fun replayEarlierStoredOrders(): Unit = runBlocking {
        container.ready.await()
        val accountId = checkNotNull(arguments.getString("accountId"))
        val earlier = Room.databaseBuilder(context, LogisticsDatabase::class.java, "T08-earlier-${System.nanoTime()}.db")
            .createFromFile(input("earlierDatabasePath")).build()
        val current = Room.databaseBuilder(context, LogisticsDatabase::class.java, "T08-current-${System.nanoTime()}.db")
            .createFromFile(input("databasePath")).build()
        try {
            val repository = LogisticsRepository(current)
            val account = checkNotNull(repository.getAccount(accountId))
            val earlierOrders = earlier.dao().orders().filter { it.accountId == accountId }.associateBy { it.remoteId }
            val changed = repository.getOrders().filter { it.accountId == accountId && it.status == ParcelStatus.SIGNED && earlierOrders[it.remoteId]?.status?.let { status -> status != ParcelStatus.SIGNED } == true }
            assertTrue("需要真实副本中的收货状态变化", changed.isNotEmpty())
            repeat(2) {
                for (order in changed) {
                    val previous = earlierOrders.getValue(order.remoteId)
                    val sources = earlier.dao().orderSources(previous.id)
                    val incoming = ImportedOrder(previous.remoteId, previous.title, previous.status, previous.createdAt, previous.orderUrl, previous.imageUrl,
                        sources.map { source ->
                            val shipment = checkNotNull(earlier.dao().shipment(source.shipmentId))
                            ImportedShipment(source.remoteShipmentId, shipment.trackingNumber, shipment.carrier, shipment.carrierCode,
                                shipment.status, shipment.pickupCode, shipment.pickupAddress, shipment.estimatedDeliveryAt,
                                earlier.dao().events(shipment.id).map { TrackingEventData(it.time, it.text, it.status) })
                        })
                    repository.ingest(account, listOf(incoming))
                    assertEquals("较早保存的订单状态保持签收", ParcelStatus.SIGNED, current.dao().order(order.id)?.status)
                    for (source in repository.getShipments().flatMap { repository.getSources(it.id) }.filter { it.orderId == order.id }) {
                        assertEquals(ParcelStatus.SIGNED, repository.getShipment(source.shipmentId)?.status)
                    }
                }
            }
            File(output(), "result.json").writeText(buildJsonObject { put("changedOrders", changed.size); put("rounds", 2) }.toString())
            Log.i("ShipmentStatusEvidence", "stored replay completed changes=${changed.size} rounds=2")
        } finally { earlier.close(); current.close() }
    }

    @Test fun syncRealAccount(): Unit = runBlocking {
        container.ready.await()
        val accountId = checkNotNull(arguments.getString("accountId"))
        val repository = container.repository
        val before = repository.getOrders()
        val signed = before.filter { it.accountId == accountId && it.status == ParcelStatus.SIGNED }.map { it.id }.toSet()
        assertTrue("需要当前账号的已收货订单", signed.isNotEmpty())
        withContext(Dispatchers.Main.immediate) { container.syncCoordinator.syncAccount(accountId) }
        val after = repository.getOrders()
        assertEquals(before.filter { it.accountId != accountId }, after.filter { it.accountId != accountId })
        assertTrue(after.filter { it.id in signed }.all { it.status == ParcelStatus.SIGNED })
        for (shipment in repository.getShipments()) {
            if (repository.getSources(shipment.id).any { it.accountId == accountId && it.orderId in signed }) assertEquals(ParcelStatus.SIGNED, shipment.status)
        }
        val account = checkNotNull(repository.getAccount(accountId))
        assertTrue(account.state in setOf(AccountState.READY, AccountState.PARTIAL))
        assertTrue(account.lastAttemptAt != null)
        File(output(), "result.json").writeText(buildJsonObject {
            put("signedOrders", signed.size); put("state", account.state.name); put("pending", repository.pendingTracking(accountId).size)
        }.toString())
        Log.i("ShipmentStatusEvidence", "sync completed signed=${signed.size} state=${account.state} pending=${repository.pendingTracking(accountId).size}")
    }

    @Test fun replayOtherPlatformStatuses(): Unit = runBlocking {
        container.ready.await()
        val accountId = checkNotNull(arguments.getString("accountId"))
        val captures = json.decodeFromString<List<CapturedResponse>>(input("capturesPath").readText())
        val database = Room.databaseBuilder(context, LogisticsDatabase::class.java, "T08-platform-${System.nanoTime()}.db")
            .createFromFile(input("databasePath")).build()
        try {
            val repository = LogisticsRepository(database)
            val account = checkNotNull(repository.getAccount(accountId))
            val pages = when (account.platform) {
                Platform.JD -> captures.mapNotNull { JdAdapter(container.platforms).parseOrders(it) }
                Platform.TAOBAO -> captures.filter { it.kind == "response" && it.url.contains("mtop.taobao.order.queryboughtlistv2") }
                    .map { TaobaoAdapter(container.platforms).parseOrders(TaobaoAdapter(container.platforms).parseMtopResponse(it.body)) }
                else -> error("本测试需要京东或淘宝真实资料")
            }
            val before = repository.getOrders()
            val savedIds = before.filter { it.accountId == accountId }.map { it.remoteId }.toSet()
            val selected = pages.flatMap { it.orders }.associateBy { it.remoteId }.values.filter { it.remoteId in savedIds }
            assertEquals("真实响应需要覆盖当前账号全部保留订单", savedIds, selected.map { it.remoteId }.toSet())
            assertTrue(selected.isNotEmpty())
            assertTrue("当前保存普通订单均已完成", selected.all { it.status == ParcelStatus.SIGNED })
            val shipments = repository.getShipments()
            repeat(2) {
                repository.ingest(account, selected)
                assertTrue(repository.getOrders().filter { it.accountId == accountId }.all { it.status == ParcelStatus.SIGNED })
                for (shipment in repository.getShipments()) {
                    if (repository.getSources(shipment.id).any { it.accountId == accountId }) assertEquals(ParcelStatus.SIGNED, shipment.status)
                }
            }
            assertEquals(before.filter { it.accountId != accountId }, repository.getOrders().filter { it.accountId != accountId })
            for (shipment in shipments) {
                val after = checkNotNull(repository.getShipment(shipment.id))
                assertEquals(shipment.notes, after.notes); assertEquals(shipment.archived, after.archived)
            }
            File(output(), "result.json").writeText(buildJsonObject { put("platform", account.platform.name); put("signedOrders", selected.size); put("rounds", 2) }.toString())
            Log.i("ShipmentStatusEvidence", "platform replay completed platform=${account.platform} signed=${selected.size} rounds=2")
        } finally { database.close() }
    }
}
