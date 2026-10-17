package com.logisticsmanagement.app

import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.net.Uri
import android.os.SystemClock
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.platform.app.InstrumentationRegistry
import com.logisticsmanagement.app.model.AppFailure
import com.logisticsmanagement.app.model.FailureKind
import com.logisticsmanagement.app.model.Platform
import com.logisticsmanagement.app.model.OrderBatch
import com.logisticsmanagement.app.data.LogisticsDatabase
import com.logisticsmanagement.app.data.LogisticsRepository
import com.logisticsmanagement.app.platform.CapturedResponse
import com.logisticsmanagement.app.platform.PlatformWebView
import com.logisticsmanagement.app.platform.PlatformBrowserActivity
import com.logisticsmanagement.app.platform.platformResponse
import com.logisticsmanagement.app.platform.requiredArray
import com.logisticsmanagement.app.platform.requiredText
import com.logisticsmanagement.app.platform.text
import com.logisticsmanagement.app.platform.allowsOrigin
import com.logisticsmanagement.app.platform.requirePlatformLoginSession
import com.logisticsmanagement.app.platform.TaobaoAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class TaobaoOrderEvidenceTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun openRealDetailWindow(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val arguments = InstrumentationRegistry.getArguments()
        val container = (context.applicationContext as LogisticsApplication).container
        container.ready.await()
        val accountId = checkNotNull(arguments.getString("accountId"))
        val account = checkNotNull(container.repository.getAccount(accountId))
        assertEquals(Platform.TAOBAO, account.platform)
        val order = container.repository.getOrders().first { it.accountId == accountId }
        val remoteId = checkNotNull(arguments.getString("remoteOrderId"))
        require(remoteId.matches(Regex("[0-9]+")))
        val url = platformResponse(File(context.filesDir, checkNotNull(arguments.getString("expectationsPath"))).readText(), "淘宝详情来源").requiredText(remoteId)
        assertTrue(allowsOrigin(Uri.parse(url), TaobaoAdapter(container.platforms).allowedOrigins))
        val directory = File(context.filesDir, "taobao-order-evidence/interactive-${System.nanoTime()}").apply { check(mkdirs()) }
        val captures = mutableListOf<CapturedResponse>()
        var activity: PlatformBrowserActivity? = null
        try {
            var browser: WebView? = null
            withContext(Dispatchers.Main.immediate) { container.platforms.openOrder(context, account, order) }
            withTimeout(10_000) {
                while (browser == null) {
                    withContext(Dispatchers.Main.immediate) {
                        activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                            .filterIsInstance<PlatformBrowserActivity>().singleOrNull()
                        browser = activity?.let { findWebView(it.window.decorView) }
                    }
                    delay(200)
                }
            }
            withContext(Dispatchers.Main.immediate) { checkNotNull(browser).stopLoading(); checkNotNull(browser).loadUrl(url) }
            Log.i("TaobaoOrderEvidence", "interactive detail window opened")
            val deadline = SystemClock.elapsedRealtime() + 180_000
            while (SystemClock.elapsedRealtime() < deadline) {
                delay(3_000)
                val result = CompletableDeferred<String>()
                withContext(Dispatchers.Main.immediate) {
                    checkNotNull(browser).evaluateJavascript("""
                        (function() { return JSON.stringify({url:location.href,text:document.body ? document.body.innerText : '',html:document.documentElement.outerHTML,links:[],resources:performance.getEntriesByType('resource').map(function(entry) { return entry.name; })}); })();
                    """.trimIndent()) { result.complete(checkNotNull(it)) }
                }
                val raw = withTimeout(5_000) { result.await() }
                val snapshot = platformResponse(json.parseToJsonElement(raw).jsonPrimitive.content, "淘宝交互详情")
                captures.add(CapturedResponse("detailSnapshot", snapshot.requiredText("url"), snapshot.toString(), 200))
            }
            assertTrue(captures.isNotEmpty())
            assertTrue(container.repository.getOrders().any { it.id == order.id })
        } finally {
            File(directory, "captures.json").writeText(json.encodeToString(captures))
            withContext(Dispatchers.Main.immediate) { activity?.finish() }
        }
    }

    private fun findWebView(view: View): WebView? {
        if (view is WebView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) findWebView(view.getChildAt(index))?.let { return it }
        return null
    }

    @Test fun recordRealOrders(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val arguments = InstrumentationRegistry.getArguments()
        val container = (context.applicationContext as LogisticsApplication).container
        container.ready.await()
        val account = checkNotNull(container.repository.getAccount(checkNotNull(arguments.getString("accountId"))))
        assertEquals(Platform.TAOBAO, account.platform)
        val name = checkNotNull(arguments.getString("runName"))
        require(name.matches(Regex("[A-Za-z0-9-]+")))
        val directory = File(context.filesDir, "taobao-order-evidence/$name").apply { check(mkdirs()) }
        val before = container.repository.getOrders()
        val captures = mutableListOf<CapturedResponse>()
        val batches = mutableListOf<OrderBatch>()
        val adapter = TaobaoAdapter(container.platforms)
        try {
            adapter.sync(account, { captures.add(it) }) { batch ->
                assertTrue(batch.orders.none { it.remoteId in batch.excludedRemoteIds })
                batches.add(batch)
                if (arguments.getString("ingest") == "true") container.repository.ingest(account, batch)
            }
            assertTrue("需要完整真实分页响应", captures.isNotEmpty())
            assertTrue("需要真实订单样本", batches.sumOf { it.observedCount } > 0)
            assertTrue("末页必须确认分页结束", adapter.parseOrders(adapter.parseMtopResponse(captures.last().body)).complete)
            arguments.getString("expectationsPath")?.let { path ->
                val expectations = platformResponse(File(context.filesDir, path).readText(), "淘宝真实期望")
                assertEquals(expectations.requiredArray("excludedRemoteIds").map { it.jsonPrimitive.content }.toSet(),
                    batches.flatMap { it.excludedRemoteIds }.toSet())
                val retained = expectations.requiredArray("retainedRemoteIds").map { it.jsonPrimitive.content }.toSet()
                assertTrue(batches.flatMap { it.orders }.map { it.remoteId }.toSet().containsAll(retained))
                if (arguments.getString("ingest") == "true") {
                    val stored = container.repository.getOrders().filter { it.accountId == account.id }.map { it.remoteId }.toSet()
                    assertTrue(stored.containsAll(retained))
                    assertTrue(stored.intersect(batches.flatMap { it.excludedRemoteIds }.toSet()).isEmpty())
                }
            }
            assertEquals(before.filter { it.accountId != account.id }, container.repository.getOrders().filter { it.accountId != account.id })
            if (arguments.getString("ingest") != "true") assertEquals(before, container.repository.getOrders())
            Log.i("TaobaoOrderEvidence", "completed pages=${captures.size} observed=${batches.sumOf { it.observedCount }} excluded=${batches.flatMap { it.excludedRemoteIds }.toSet().size}")
        } finally {
            File(directory, "captures.json").writeText(json.encodeToString(captures))
            File(directory, "batches.json").writeText(JsonArray(batches.map { batch -> buildJsonObject {
                put("remoteIds", JsonArray(batch.orders.map { JsonPrimitive(it.remoteId) }))
                put("excludedRemoteIds", JsonArray(batch.excludedRemoteIds.map(::JsonPrimitive)))
                put("observedCount", batch.observedCount)
            } }).toString())
        }
    }

    @Test fun replayRealOrders(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val arguments = InstrumentationRegistry.getArguments()
        val container = (context.applicationContext as LogisticsApplication).container
        container.ready.await()
        val account = checkNotNull(container.repository.getAccount(checkNotNull(arguments.getString("accountId"))))
        assertEquals(Platform.TAOBAO, account.platform)
        val captures = json.decodeFromString<List<CapturedResponse>>(File(context.filesDir,
            checkNotNull(arguments.getString("capturesPath"))).readText())
        val expectations = platformResponse(File(context.filesDir,
            checkNotNull(arguments.getString("expectationsPath"))).readText(), "淘宝真实期望")
        val adapter = TaobaoAdapter(container.platforms)
        val pages = captures.map { adapter.parseOrders(adapter.parseMtopResponse(it.body)) }
        assertTrue(pages.isNotEmpty())
        assertTrue(pages.last().complete)
        assertEquals(expectations.requiredArray("excludedRemoteIds").map { it.jsonPrimitive.content }.toSet(),
            pages.flatMap { it.excludedRemoteIds }.toSet())
        val retained = pages.flatMap { it.orders }.map { it.remoteId }.toSet()
        assertEquals(expectations.requiredArray("retainedRemoteIds").map { it.jsonPrimitive.content }.toSet(), retained)
        for (page in pages) {
            assertTrue(page.remoteIds.containsAll(page.orders.map { it.remoteId } + page.excludedRemoteIds))
            assertTrue(page.orders.none { it.remoteId in page.excludedRemoteIds })
        }
        Log.i("TaobaoOrderEvidence", "replay completed pages=${pages.size} excluded=${pages.flatMap { it.excludedRemoteIds }.toSet().size} retained=${retained.size}")
    }

    @Test fun replayRealFailure(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val arguments = InstrumentationRegistry.getArguments()
        val container = (context.applicationContext as LogisticsApplication).container
        container.ready.await()
        val account = checkNotNull(container.repository.getAccount(checkNotNull(arguments.getString("accountId"))))
        assertEquals(Platform.TAOBAO, account.platform)
        val before = container.repository.getOrders()
        val captures = json.decodeFromString<List<CapturedResponse>>(File(context.filesDir,
            checkNotNull(arguments.getString("capturesPath"))).readText())
        val failures = captures.filter { capture ->
            capture.kind == "response" && platformResponse(capture.body, "淘宝真实失败").requiredArray("ret")
                .any { it.jsonPrimitive.content.startsWith("FAIL_SYS_ILLEGAL_ACCESS::") }
        }
        assertTrue("需要已保存的真实失败响应", failures.isNotEmpty())
        for (capture in failures) {
            val failure = org.junit.Assert.assertThrows(AppFailure::class.java) { TaobaoAdapter(container.platforms).parseMtopResponse(capture.body) }
            assertEquals(FailureKind.NEEDS_VERIFICATION, failure.kind)
        }
        assertEquals(before, container.repository.getOrders())
        Log.i("TaobaoOrderEvidence", "failure replay completed samples=${failures.size} kind=NEEDS_VERIFICATION")
    }

    @Test fun verifyRealDatabaseCopy(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val arguments = InstrumentationRegistry.getArguments()
        val container = (context.applicationContext as LogisticsApplication).container
        container.ready.await()
        val accountId = checkNotNull(arguments.getString("accountId"))
        val original = container.repository.getOrders()
        val captures = json.decodeFromString<List<CapturedResponse>>(File(context.filesDir,
            checkNotNull(arguments.getString("capturesPath"))).readText())
        val adapter = TaobaoAdapter(container.platforms)
        val excluded = captures.flatMap { adapter.parseOrders(adapter.parseMtopResponse(it.body)).excludedRemoteIds }.toSet()
        assertTrue("需要真实排除样本", excluded.isNotEmpty())
        val name = "T07-copy-${System.nanoTime()}.db"
        val database = Room.databaseBuilder(context, LogisticsDatabase::class.java, name)
            .createFromFile(File(context.filesDir, checkNotNull(arguments.getString("databasePath")))).build()
        try {
            val repository = LogisticsRepository(database)
            val account = checkNotNull(repository.getAccount(accountId))
            assertEquals(Platform.TAOBAO, account.platform)
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
                assertEquals("清理范围与全部保留字段", expected, snapshotRows(database))
            }
            database.openHelper.readableDatabase.query("PRAGMA integrity_check").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("ok", cursor.getString(0))
            }
            val directory = File(context.filesDir, "taobao-order-evidence").apply { check(mkdirs() || isDirectory) }
            File(directory, "copy-verification.json").writeText(buildJsonObject {
                put("databaseName", name)
                put("excluded", excluded.size)
                put("removedOrders", removedOrders.size)
                put("removedSources", removedSources.size)
                put("removedShipments", removedShipments.size)
                put("rounds", 2)
            }.toString())
            Log.i("TaobaoOrderEvidence", "copy completed removedOrders=${removedOrders.size} removedShipments=${removedShipments.size} rounds=2")
        } finally { database.close() }
        assertEquals(original, container.repository.getOrders())
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

    @Test fun recordRealDetail(): Unit = recordRealPage(false)

    @Test fun recordRealListPage(): Unit = recordRealPage(true)

    private fun recordRealPage(listPage: Boolean): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val arguments = InstrumentationRegistry.getArguments()
        val container = (context.applicationContext as LogisticsApplication).container
        container.ready.await()
        val account = checkNotNull(container.repository.getAccount(checkNotNull(arguments.getString("accountId"))))
        assertEquals(Platform.TAOBAO, account.platform)
        val remoteId = if (listPage) "list" else checkNotNull(arguments.getString("remoteOrderId"))
        require(listPage || remoteId.matches(Regex("[0-9]+")))
        val adapter = TaobaoAdapter(container.platforms)
        val url = if (listPage) adapter.orderListUrl else {
            val targets = platformResponse(File(context.filesDir, checkNotNull(arguments.getString("expectationsPath"))).readText(), "淘宝详情来源")
            targets.requiredText(remoteId)
        }
        assertTrue(allowsOrigin(Uri.parse(url), adapter.allowedOrigins))
        val directory = File(context.filesDir, "taobao-order-evidence/detail-${remoteId}-${System.nanoTime()}").apply { check(mkdirs()) }
        val captures = mutableListOf<CapturedResponse>()
        try {
            withContext(Dispatchers.Main.immediate) {
                requirePlatformLoginSession(container.platforms.profiles, account)
                val session = PlatformWebView(context, account, adapter, container.platforms.profiles, false)
                try {
                    session.load(url)
                    val deadline = SystemClock.elapsedRealtime() + if (listPage) 45_000 else 20_000
                    var nextCapture = SystemClock.elapsedRealtime() + 2_000
                    while (SystemClock.elapsedRealtime() < deadline) {
                        withTimeoutOrNull(500) { session.messages.receive() }?.let { captures.add(it) }
                        if (SystemClock.elapsedRealtime() >= nextCapture) {
                            val result = CompletableDeferred<String>()
                            session.view.evaluateJavascript("""
                                (function() {
                                  if (!window.__t07OrderInfoOpened) {
                                    const entry = Array.from(document.querySelectorAll('div,span,button,a')).find(function(e) {
                                      return !e.children.length && (e.innerText || '').trim() === '订单信息' && e.getBoundingClientRect().height > 0;
                                    });
                                    if (entry) { entry.click(); window.__t07OrderInfoOpened = true; }
                                  }
                                  return JSON.stringify({url:location.href,text:document.body ? document.body.innerText : '',html:document.documentElement.outerHTML,links:Array.from(document.querySelectorAll('a')).map(function(a) { return {text:a.innerText,url:a.href}; }),resources:performance.getEntriesByType('resource').map(function(entry) { return entry.name; })});
                                })();
                            """.trimIndent()) { result.complete(checkNotNull(it)) }
                            val raw = withTimeout(5_000) { result.await() }
                            val snapshot = platformResponse(json.parseToJsonElement(raw).jsonPrimitive.content, "淘宝详情")
                            captures.add(CapturedResponse("detailSnapshot", snapshot.requiredText("url"), snapshot.toString(), 200))
                            if (listPage) session.advanceOrders()
                            nextCapture = SystemClock.elapsedRealtime() + 4_000
                        }
                    }
                } finally { session.close() }
            }
            assertTrue("详情需要真实正文", captures.filter { it.kind == "detailSnapshot" }.any {
                platformResponse(it.body, "淘宝详情").text("text").isNotBlank()
            })
            if (listPage) {
                val pages = captures.filter { it.kind == "response" && Uri.parse(it.url).path.orEmpty().lowercase().contains("mtop.taobao.order.queryboughtlistv2") }
                    .map { adapter.parseOrders(adapter.parseMtopResponse(it.body)) }
                assertTrue("网页分页需要完整采集", pages.isNotEmpty() && pages.last().complete)
            } else {
                val expectedId = Uri.parse(url).getQueryParameter("orderId") ?: Uri.parse(url).getQueryParameter("orderNo") ?: Uri.parse(url).getQueryParameter("bizOrderId")
                    ?: throw IllegalArgumentException("真实详情来源需要明确订单编号")
                assertTrue("详情正文需要对应订单编号", captures.filter { it.kind == "detailSnapshot" }.any {
                    platformResponse(it.body, "淘宝详情").text("text").filterNot(Char::isWhitespace).contains(expectedId)
                })
            }
            Log.i("TaobaoOrderEvidence", "detail completed captures=${captures.size}")
        } finally { File(directory, "captures.json").writeText(json.encodeToString(captures)) }
    }

    @Test fun recordOrderListStructure(): Unit = runBlocking {
        val container = (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as LogisticsApplication).container
        container.ready.await()
        val account = checkNotNull(container.repository.getAccount(checkNotNull(InstrumentationRegistry.getArguments().getString("accountId"))))
        assertEquals(Platform.TAOBAO, account.platform)
        val root = TaobaoAdapter(container.platforms).diagnoseOrderQuery(account)
        val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "taobao-order-evidence").apply { check(mkdirs() || isDirectory) }
        File(directory, "structure-${System.nanoTime()}.json").writeText(root.toString())
        val parsed = TaobaoAdapter(container.platforms).parseOrders(root)
        assertTrue("需要真实订单", parsed.remoteIds.isNotEmpty())
        Log.i("TaobaoOrderEvidence", "structure completed observed=${parsed.remoteIds.size}")
    }
}
