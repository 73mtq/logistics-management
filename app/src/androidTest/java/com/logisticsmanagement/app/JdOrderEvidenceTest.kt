package com.logisticsmanagement.app

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.room.Room
import com.logisticsmanagement.app.data.LogisticsDatabase
import com.logisticsmanagement.app.data.LogisticsRepository
import com.logisticsmanagement.app.model.OrderBatch
import com.logisticsmanagement.app.model.Platform
import com.logisticsmanagement.app.model.AppFailure
import com.logisticsmanagement.app.model.FailureKind
import com.logisticsmanagement.app.model.AccountState
import com.logisticsmanagement.app.platform.CapturedResponse
import com.logisticsmanagement.app.platform.JdAdapter
import com.logisticsmanagement.app.platform.PlatformWebView
import com.logisticsmanagement.app.platform.platformResponse
import com.logisticsmanagement.app.platform.requiredArray
import com.logisticsmanagement.app.platform.requiredText
import com.logisticsmanagement.app.platform.requireObject
import com.logisticsmanagement.app.platform.objectOrNull
import com.logisticsmanagement.app.platform.requirePlatformLoginSession
import com.logisticsmanagement.app.platform.text
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class JdOrderEvidenceTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun openRealOrderWindow(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val arguments = InstrumentationRegistry.getArguments()
        val container = (context.applicationContext as LogisticsApplication).container
        container.ready.await()
        val account = checkNotNull(container.repository.getAccount(checkNotNull(arguments.getString("accountId"))))
        val remoteId = checkNotNull(arguments.getString("remoteOrderId"))
        val order = container.repository.getOrders().single { it.accountId == account.id && it.remoteId == remoteId }
        assertEquals(Platform.JD, account.platform)
        withContext(Dispatchers.Main.immediate) { container.platforms.openOrder(context, account, order) }
        Log.i("JdOrderEvidence", "interactive real order window opened")
        delay(90_000)
        assertTrue(container.repository.getOrders().any { it.id == order.id })
    }

    @Test fun recordRealSyncOutcome(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val arguments = InstrumentationRegistry.getArguments()
        val container = (context.applicationContext as LogisticsApplication).container
        container.ready.await()
        val accountId = checkNotNull(arguments.getString("accountId"))
        val before = container.repository.getOrders()
        var failure: AppFailure? = null
        try {
            container.syncCoordinator.syncAccount(accountId)
        } catch (error: AppFailure) {
            failure = error
        }
        val account = checkNotNull(container.repository.getAccount(accountId))
        assertEquals(Platform.JD, account.platform)
        val after = container.repository.getOrders()
        assertEquals(before.filter { it.accountId != accountId }, after.filter { it.accountId != accountId })
        if (failure != null) {
            assertEquals(FailureKind.SERVICE_ERROR, failure.kind)
            assertEquals(AccountState.FAILED, account.state)
            assertTrue(checkNotNull(account.error).contains("601"))
        } else {
            assertTrue(account.state in setOf(AccountState.READY, AccountState.PARTIAL))
            val expectations = platformResponse(File(context.filesDir,
                checkNotNull(arguments.getString("expectationsPath"))).readText(), "京东真实样本期望")
            val excluded = expectations.requiredArray("excludedRemoteIds").map { it.jsonPrimitive.content }.toSet()
            val retained = expectations.requiredArray("retainedRemoteIds").map { it.jsonPrimitive.content }.toSet()
            val stored = after.filter { it.accountId == accountId }.map { it.remoteId }.toSet()
            assertTrue(stored.intersect(excluded).isEmpty())
            assertTrue(stored.containsAll(retained))
        }
        val directory = File(context.filesDir, "jd-order-evidence").apply { check(mkdirs() || isDirectory) }
        File(directory, "sync-outcome.json").writeText(buildJsonObject {
            put("state", account.state.name)
            put("failureKind", failure?.kind?.name)
            put("error", account.error)
            put("ordersBefore", before.size)
            put("ordersAfter", after.size)
        }.toString())
        Log.i("JdOrderEvidence", "sync completed state=${account.state} failureKind=${failure?.kind}")
    }

    @Test fun replayRealFailure(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val arguments = InstrumentationRegistry.getArguments()
        val container = (context.applicationContext as LogisticsApplication).container
        container.ready.await()
        val before = container.repository.getOrders()
        val captures = json.decodeFromString<List<CapturedResponse>>(File(context.filesDir,
            checkNotNull(arguments.getString("capturesPath"))).readText())
        val failures = captures.filter { it.kind == "response" && it.url.contains("functionId=order_list_m") &&
            platformResponse(it.body, "京东").objectOrNull("body")?.text("errorCode") == "601" }
        assertTrue("需要保存的真实平台失败响应", failures.isNotEmpty())
        for (capture in failures) {
            val failure = org.junit.Assert.assertThrows(AppFailure::class.java) {
                JdAdapter(container.platforms).parseOrders(capture)
            }
            assertEquals(FailureKind.SERVICE_ERROR, failure.kind)
            assertTrue(checkNotNull(failure.message).contains("601"))
        }
        assertEquals(before, container.repository.getOrders())
        Log.i("JdOrderEvidence", "real service failure replay completed samples=${failures.size}")
    }

    @Test fun replayRealOrders(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val arguments = InstrumentationRegistry.getArguments()
        val container = (context.applicationContext as LogisticsApplication).container
        container.ready.await()
        val captures = json.decodeFromString<List<CapturedResponse>>(File(context.filesDir,
            checkNotNull(arguments.getString("capturesPath"))).readText())
        val expectations = platformResponse(File(context.filesDir,
            checkNotNull(arguments.getString("expectationsPath"))).readText(), "京东真实样本期望")
        val pages = captures.mapNotNull { JdAdapter(container.platforms).parseOrders(it) }
        assertTrue(pages.isNotEmpty())
        val excluded = pages.flatMap { it.excludedRemoteIds }.toSet()
        val retained = pages.flatMap { it.orders }.map { it.remoteId }.toSet()
        assertEquals(expectations.requiredArray("excludedRemoteIds").map { it.jsonPrimitive.content }.toSet(), excluded)
        assertTrue(retained.containsAll(expectations.requiredArray("retainedRemoteIds").map { it.jsonPrimitive.content }))
        assertTrue(retained.intersect(excluded).isEmpty())
        for (page in pages) assertTrue(page.remoteIds.containsAll(page.orders.map { it.remoteId } + page.excludedRemoteIds))
        Log.i("JdOrderEvidence", "replay completed pages=${pages.size} excluded=${excluded.size} retained=${retained.size}")
    }

    @Test fun verifyRealDatabaseCopy(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val arguments = InstrumentationRegistry.getArguments()
        val container = (context.applicationContext as LogisticsApplication).container
        container.ready.await()
        val accountId = checkNotNull(arguments.getString("accountId"))
        val originalOrders = container.repository.getOrders()
        val captures = json.decodeFromString<List<CapturedResponse>>(File(context.filesDir,
            checkNotNull(arguments.getString("capturesPath"))).readText())
        val excluded = captures.mapNotNull { JdAdapter(container.platforms).parseOrders(it) }
            .flatMap { it.excludedRemoteIds }.toSet()
        assertTrue("真实响应应包含已确认排除的订单", excluded.isNotEmpty())
        val name = "T06-copy-${System.nanoTime()}.db"
        val database = Room.databaseBuilder(context, LogisticsDatabase::class.java, name)
            .createFromFile(File(context.filesDir, checkNotNull(arguments.getString("databasePath")))).build()
        try {
            val repository = LogisticsRepository(database)
            val account = checkNotNull(repository.getAccount(accountId))
            assertEquals(Platform.JD, account.platform)
            val before = snapshotRows(database)
            val removedOrders = before.getValue("orders").filter { it["accountId"] == accountId && it["remoteId"] in excluded }.map { it.getValue("id") }.toSet()
            assertTrue("副本需要实际存量排除样本", removedOrders.isNotEmpty())
            val removedSources = before.getValue("SourceRef").filter { it["orderId"] in removedOrders }
            val survivingSources = before.getValue("SourceRef") - removedSources.toSet()
            val removedShipments = removedSources.map { it.getValue("shipmentId") }.toSet() - survivingSources.map { it.getValue("shipmentId") }.toSet()
            val expected = before.toMutableMap()
            expected["orders"] = before.getValue("orders").filterNot { it["id"] in removedOrders }
            expected["SourceRef"] = survivingSources
            expected["Shipment"] = before.getValue("Shipment").filterNot { it["id"] in removedShipments }
            expected["TrackingEvent"] = before.getValue("TrackingEvent").filterNot { it["shipmentId"] in removedShipments }
            expected["ShipmentAlias"] = before.getValue("ShipmentAlias").filterNot { it["shipmentId"] in removedShipments }
            val survivingSourceIds = survivingSources.map { it.getValue("id") }.toSet()
            expected["TrackingQueue"] = before.getValue("TrackingQueue").filter { it["sourceId"] in survivingSourceIds }
            repeat(2) {
                repository.ingest(account, OrderBatch(emptyList(), excluded))
                assertEquals("清理范围及全部保留字段", expected, snapshotRows(database))
            }
            database.openHelper.readableDatabase.query("PRAGMA integrity_check").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("ok", cursor.getString(0))
            }
            val directory = File(context.filesDir, "jd-order-evidence").apply { check(mkdirs() || isDirectory) }
            File(directory, "copy-verification.json").writeText(buildJsonObject {
                put("databaseName", name)
                put("excluded", excluded.size)
                put("removedOrders", removedOrders.size)
                put("removedSources", removedSources.size)
                put("removedShipments", removedShipments.size)
                put("rounds", 2)
            }.toString())
            Log.i("JdOrderEvidence", "copy completed removedOrders=${removedOrders.size} removedShipments=${removedShipments.size} rounds=2")
        } finally { database.close() }
        assertEquals("副本验收应保留正式数据库", originalOrders, container.repository.getOrders())
    }

    private fun snapshotRows(database: LogisticsDatabase): Map<String, List<Map<String, String?>>> =
        listOf("Account", "orders", "SourceRef", "Shipment", "TrackingEvent", "ShipmentAlias", "TrackingQueue", "PendingProfileCleanup").associateWith { table ->
            database.openHelper.readableDatabase.query("SELECT * FROM $table ORDER BY 1").use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add(cursor.columnNames.mapIndexed { index, column ->
                        column to if (cursor.isNull(index)) null else cursor.getString(index)
                    }.toMap())
                }
            }
        }

    @Test fun recordRealOrders(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val arguments = InstrumentationRegistry.getArguments()
        val container = (context.applicationContext as LogisticsApplication).container
        container.ready.await()
        val account = checkNotNull(container.repository.getAccount(checkNotNull(arguments.getString("accountId"))))
        assertEquals(Platform.JD, account.platform)
        val name = checkNotNull(arguments.getString("runName"))
        require(name.matches(Regex("[A-Za-z0-9-]+")))
        val directory = File(context.filesDir, "jd-order-evidence").apply { check(mkdirs() || isDirectory) }
        val captures = mutableListOf<CapturedResponse>()
        val batches = mutableListOf<OrderBatch>()
        val before = container.repository.getOrders()
        try {
            val adapter = JdAdapter(container.platforms)
            adapter.sync(account, { captures.add(it) }) { batch ->
                batches.add(batch)
                if (arguments.getString("ingest") == "true") container.repository.ingest(account, batch)
            }
            assertTrue("需要真实订单响应", captures.any { JdAdapter(container.platforms).parseOrders(it) != null })
            arguments.getString("expectationsPath")?.let { path ->
                val expectations = platformResponse(File(context.filesDir, path).readText(), "京东真实样本期望")
                assertEquals(expectations.requiredArray("excludedRemoteIds").map { it.jsonPrimitive.content }.toSet(),
                    batches.flatMap { it.excludedRemoteIds }.toSet())
                val expectedRetained = expectations.requiredArray("retainedRemoteIds").map { it.jsonPrimitive.content }.toSet()
                assertTrue(batches.flatMap { it.orders }.map { it.remoteId }.toSet().containsAll(expectedRetained))
                if (arguments.getString("ingest") == "true") {
                    assertTrue(container.repository.getOrders().filter { it.accountId == account.id }
                        .map { it.remoteId }.toSet().containsAll(expectedRetained))
                }
            }
            if (arguments.getString("ingest") != "true") assertEquals(before, container.repository.getOrders())
            for (batch in batches) {
                assertTrue(batch.orders.none { it.remoteId in batch.excludedRemoteIds })
                if (arguments.getString("ingest") == "true") {
                    val stored = container.repository.getOrders().filter { it.accountId == account.id }
                    assertTrue(stored.none { it.remoteId in batch.excludedRemoteIds })
                }
            }
            Log.i("JdOrderEvidence", "completed captures=${captures.size} batches=${batches.size} excluded=${batches.flatMap { it.excludedRemoteIds }.toSet().size}")
        } finally {
            File(directory, "$name-captures.json").writeText(json.encodeToString(captures))
            File(directory, "$name-batches.json").writeText(JsonArray(batches.map { batch ->
                buildJsonObject {
                    put("remoteIds", JsonArray(batch.orders.map { JsonPrimitive(it.remoteId) }))
                    put("excludedRemoteIds", JsonArray(batch.excludedRemoteIds.map(::JsonPrimitive)))
                    put("observedCount", batch.observedCount)
                }
            }).toString())
        }
    }

    @Test fun recordRealDetail(): Unit = recordRealPage(false)

    @Test fun recordRealListPage(): Unit = recordRealPage(true)

    private fun recordRealPage(listPage: Boolean): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val arguments = InstrumentationRegistry.getArguments()
        val container = (context.applicationContext as LogisticsApplication).container
        container.ready.await()
        val account = checkNotNull(container.repository.getAccount(checkNotNull(arguments.getString("accountId"))))
        assertEquals(Platform.JD, account.platform)
        val remoteId = if (listPage) "list" else checkNotNull(arguments.getString("remoteOrderId"))
        require(listPage || remoteId.matches(Regex("[0-9]+")))
        val order = if (listPage) JsonObject(emptyMap()) else {
            val capturesPath = File(context.filesDir, checkNotNull(arguments.getString("capturesPath")))
            val inputs = json.decodeFromString<List<CapturedResponse>>(capturesPath.readText())
            inputs.filter { it.kind == "response" && it.url.contains("functionId=order_list_m") }
                .flatMap { checkNotNull(platformResponse(it.body, "京东").objectOrNull("body")).requiredArray("orderList") }
                .map { it.requireObject("京东订单") }.last { it.requiredText("orderId") == remoteId }
        }
        val url = if (listPage) JdAdapter(container.platforms).orderListUrl else
            checkNotNull(order.objectOrNull("orderDetailLink")).requiredText("url")
        val captures = mutableListOf<CapturedResponse>()
        val directory = File(context.filesDir, "jd-order-evidence").apply { check(mkdirs() || isDirectory) }
        try {
            withContext(Dispatchers.Main.immediate) {
                requirePlatformLoginSession(container.platforms.profiles, account)
                val session = PlatformWebView(context, account, JdAdapter(container.platforms), container.platforms.profiles, false)
                try {
                    session.load(url)
                    val deadline = SystemClock.elapsedRealtime() + 18_000
                    var nextCapture = SystemClock.elapsedRealtime() + 2_000
                    while (SystemClock.elapsedRealtime() < deadline) {
                        withTimeoutOrNull(500) { session.messages.receive() }?.let { captures.add(it) }
                        if (SystemClock.elapsedRealtime() >= nextCapture) {
                            val result = CompletableDeferred<String>()
                            session.view.evaluateJavascript("""
                                (function() {
                                  return JSON.stringify({url:location.href,text:document.body ? document.body.innerText : '',html:document.documentElement.outerHTML,resources:performance.getEntriesByType('resource').map(function(entry) { return entry.name; })});
                                })();
                            """.trimIndent()) { result.complete(checkNotNull(it)) }
                            val rawSnapshot = withTimeout(5_000) { result.await() }
                            val snapshot = platformResponse(json.parseToJsonElement(rawSnapshot).jsonPrimitive.content, "京东详情")
                            val pageUrl = snapshot.requiredText("url")
                            captures.add(CapturedResponse("detailDom", pageUrl, snapshot.text("text"), 200))
                            captures.add(CapturedResponse("detailHtml", pageUrl, snapshot.requiredText("html"), 200))
                            captures.add(CapturedResponse("detailResources", pageUrl, snapshot.requiredArray("resources").toString(), 200))
                            nextCapture = SystemClock.elapsedRealtime() + 4_000
                        }
                    }
                    assertTrue("需要对应详情的真实正文", captures.any { it.kind == "detailDom" && it.body.isNotBlank() })
                    Log.i("JdOrderEvidence", "detail completed captures=${captures.size}")
                } finally { session.close() }
            }
        } finally {
            File(directory, "detail-$remoteId-captures.json").writeText(json.encodeToString(captures))
            File(directory, "detail-$remoteId-order.json").writeText(order.toString())
        }
    }
}
