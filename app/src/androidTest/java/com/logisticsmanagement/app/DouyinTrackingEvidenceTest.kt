package com.logisticsmanagement.app

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.logisticsmanagement.app.data.*
import com.logisticsmanagement.app.model.*
import com.logisticsmanagement.app.platform.*
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class DouyinTrackingEvidenceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val arguments = InstrumentationRegistry.getArguments()
    private val container get() = (context.applicationContext as LogisticsApplication).container
    private fun argument(name: String) = checkNotNull(arguments.getString(name))
    private fun input(name: String) = File(context.filesDir, argument(name)).apply { check(isFile) }
    private fun output() = File(context.filesDir, "douyin-order-evidence/${argument("runName")}").apply { check(mkdirs()) }
    private fun export(directory: File, captureId: String) {
        val source = File(context.filesDir, "native-entry-evidence/$captureId")
        for (file in checkNotNull(source.listFiles()).filter { it.extension == "enc" }) {
            val body = container.credentials.decrypt(file.readText())
            platformJson.parseToJsonElement(body)
            File(directory, file.name.removeSuffix(".enc") + ".json").writeText(body)
        }
    }

    @Test fun fixedFlow(): Unit = runBlocking {
        DouyinFixedFlowRunner(instrumentation, container, output(), argument("accountId"), input("capturesPath"),
            snapshot = { DouyinOrderEvidenceTest().tables() }, export = { directory, captureId -> export(directory, captureId) }, tracking = true).run()
    }

    @Test fun replayLogistics(): Unit = runBlocking {
        container.ready.await()
        val directory = output()
        val fixture = platformJson.parseToJsonElement(input("capturesPath").readText()).jsonObject
        fun scan(name: String) = platformJson.decodeFromJsonElement<NativeScan>(fixture.getValue(name))
        val target = platformJson.decodeFromJsonElement<FixedFlowTarget>(fixture.getValue("target"))
        val expanded = fixture.getValue("expanded").jsonArray.map { platformJson.decodeFromJsonElement<NativeScan>(it) }
        val logistics = fixture.getValue("logistics").jsonArray.map { platformJson.decodeFromJsonElement<NativeScan>(it) }
        assertEquals(2, logistics.size)
        val titleAction = fixture.getValue("titleAction").jsonObject
        val trackingAction = fixture.getValue("logisticsAction").jsonObject
        assertTrue(titleAction.getValue("accepted").jsonPrimitive.boolean)
        assertTrue(trackingAction.getValue("accepted").jsonPrimitive.boolean)
        val titleNode = platformJson.decodeFromJsonElement<NativeNode>(titleAction.getValue("target"))
        val trackingNode = platformJson.decodeFromJsonElement<NativeNode>(trackingAction.getValue("target"))
        val expectComplete = fixture.getValue("expectComplete").jsonPrimitive.boolean
        val database = Room.databaseBuilder(context, LogisticsDatabase::class.java, "T12-replay-${System.nanoTime()}.db")
            .createFromFile(input("databasePath")).build()
        try {
            val repository = LogisticsRepository(database)
            val id = argument("accountId")
            val beforeShipments = repository.getShipments()
            val queue = repository.pendingTracking(id)
            var previousSource: SourceRef? = null
            var previousEvents: List<TrackingEvent>? = null
            repeat(2) { round ->
                val session = NativeOrderImportSession(id, scan("identity").captureId,
                    bind = { repository.bindNativeIdentity(id, it) },
                    existing = { remote -> repository.getOrders().firstOrNull { it.accountId == id && it.remoteId == remote } },
                    save = { owner, order -> repository.ingestNativeOrders(id, owner, OrderBatch(listOf(order))) }, progress = {},
                    saveLogistics = { owner, remote, shipment -> repository.ingestNativeLogistics(id, owner, remote, shipment) })
                session.accept(scan("identity"))
                val list = scan("list").pages.single { it.nodes.any { node -> node.path == titleNode.path && node.text == titleNode.text } }
                session.navigation(list, titleNode)
                session.accept(scan("collapsed"))
                expanded.forEach { session.accept(it) }
                val detail = expanded.last().pages.single { it.nodes.any { node -> node.path == trackingNode.path && DouyinNativeOrders.label(node) == "查看物流" } }
                session.navigation(detail, trackingNode)
                session.accept(logistics.first())
                assertEquals(0, checkNotNull(session.logistics).savedSources.size)
                session.accept(logistics.last())
                val order = repository.getOrders().single { it.accountId == id && it.remoteId == target.orderId }
                val parsed = checkNotNull(DouyinNativeLogistics.parse(logistics.last(), order))
                val expected = checkNotNull(parsed.shipment)
                val shipment = repository.getShipments().single { it.carrierCode == expected.carrierCode && it.trackingNumber == expected.trackingNumber }
                val source = repository.getSources(shipment.id).single { it.accountId == id && it.remoteOrderId == target.orderId }
                val events = repository.getEvents(shipment.id)
                assertEquals(order.id, source.orderId)
                assertEquals(expected.remoteId, source.remoteShipmentId)
                assertEquals(ParcelStatus.SIGNED, shipment.status)
                assertTrue(parsed.times.isNotEmpty() && parsed.times.all { it.time != null && it.candidateYears.size == 1 && it.inferredYear })
                assertTrue(parsed.times.all { it.precision == "minute" })
                if (expectComplete) {
                    assertTrue(parsed.missing.isEmpty() && events.isNotEmpty())
                    assertEquals(expected.events.map { Triple(it.time, it.text, it.status) }.toSet(), events.map { Triple(it.time, it.text, it.status) }.toSet())
                } else {
                    assertTrue(checkNotNull(session.logistics).missing.isNotEmpty())
                    assertTrue(events.isEmpty())
                }
                val rawTime = parsed.times.first().raw
                assertNull(DouyinNativeLogistics.resolveTime(rawTime, null, logistics.last().capturedAt).time)
                previousSource?.let { assertEquals(it, source) }
                previousEvents?.let { assertEquals(it, events) }
                previousSource = source; previousEvents = events
                assertEquals(queue, repository.pendingTracking(id))
                beforeShipments.forEach { assertEquals(it, repository.getShipment(it.id)) }
                assertEquals(beforeShipments.size + 1, repository.getShipments().size)
                File(directory, "round-${round + 1}.json").writeText(session.evidence())
            }
            File(directory, "result.json").writeText(buildJsonObject {
                put("rounds", 2); put("correctSource", true); put("stableSource", true); put("noDuplicateEvents", true)
                put("uniqueYearBasis", true); put("preservedOtherShipments", true); put("preservedQueue", true)
                put("completeEvents", expectComplete); put("missingFieldsPreserved", !expectComplete)
            }.toString())
        } finally { database.close() }
    }
}
