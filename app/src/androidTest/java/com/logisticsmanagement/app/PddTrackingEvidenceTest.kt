package com.logisticsmanagement.app

import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.logisticsmanagement.app.model.AppFailure
import com.logisticsmanagement.app.model.AccountState
import com.logisticsmanagement.app.model.FailureKind
import com.logisticsmanagement.app.model.Platform
import com.logisticsmanagement.app.platform.CapturedResponse
import com.logisticsmanagement.app.platform.PddAdapter
import com.logisticsmanagement.app.platform.PlatformWebView
import com.logisticsmanagement.app.platform.CaptureScripts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PddTrackingEvidenceTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun recordRealPage(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        val accountId = checkNotNull(arguments.getString("accountId"))
        val remoteOrderId = checkNotNull(arguments.getString("remoteOrderId"))
        val expandOrderInfo = arguments.getString("expandOrderInfo") == "true"
        val context = instrumentation.targetContext
        val container = (context.applicationContext as LogisticsApplication).container
        container.ready.await()
        val account = checkNotNull(container.repository.getAccount(accountId))
        val source = container.repository.getShipments().flatMap { container.repository.getSources(it.id) }
            .single { it.accountId == account.id && it.remoteOrderId == remoteOrderId }
        val captures = mutableListOf<CapturedResponse>()
        val directory = File(context.filesDir, "pdd-tracking-evidence").apply { check(mkdirs() || isDirectory) }
        try {
            withContext(Dispatchers.Main.immediate) {
                val adapter = PddAdapter(container.platforms)
                val session = PlatformWebView(context, account, adapter, container.platforms.profiles, false)
                try {
                    session.load(source.orderUrl)
                    val deadline = SystemClock.elapsedRealtime() + 20_000
                    var nextAction = SystemClock.elapsedRealtime() + 2_000
                    while (SystemClock.elapsedRealtime() < deadline) {
                        withTimeoutOrNull(500) { session.messages.receive() }?.let { captures.add(it) }
                        if (SystemClock.elapsedRealtime() >= nextAction) {
                            session.evaluate("""
                                (function() {
                                  if ($expandOrderInfo && !window.__pddOrderInfoExpanded) {
                                    const matches = Array.from(document.querySelectorAll('a,button,div,span')).filter(function(e) { return (e.innerText||'').trim()==='查看更多订单信息' && e.getBoundingClientRect().height>0; }).sort(function(a,b) { return a.getBoundingClientRect().height-b.getBoundingClientRect().height; });
                                    if (matches.length) {
                                      LogisticsCapture.postMessage(JSON.stringify({kind:'orderInfoAction',url:location.href,pageUrl:location.href,body:'clicked',status:200}));
                                      matches[0].click(); window.__pddOrderInfoExpanded = true;
                                    }
                                  }
                                  const clicked = ${CaptureScripts.pddTracking}
                                  LogisticsCapture.postMessage(JSON.stringify({kind:'trackingAction',url:location.href,pageUrl:location.href,body:JSON.stringify({clicked:clicked,buttons:Array.from(document.querySelectorAll('a,button,div,span')).filter(function(e){return e.getBoundingClientRect().height>0 && (e.innerText||'').trim().length<30 && /物流/.test(e.innerText||'');}).map(function(e){return e.innerText;})})}));
                                  LogisticsCapture.postMessage(JSON.stringify({kind:'dom',url:location.href,pageUrl:location.href,body:document.body.innerText,status:200}));
                                  LogisticsCapture.postMessage(JSON.stringify({kind:'html',url:location.href,pageUrl:location.href,body:document.documentElement.outerHTML,status:200}));
                                  const elements = Array.from(document.querySelectorAll('a,button,div,span')).filter(function(e) { const text=(e.innerText||'').trim(); return text.length<80 && /签收|物流|订单.*信息/.test(text); }).map(function(e) {
                                    const ancestry=[];
                                    for (let node=e; node && ancestry.length<6; node=node.parentElement) {
                                      const propsKey=Object.keys(node).find(function(k) { return k.startsWith('__reactProps'); });
                                      const props=propsKey && node[propsKey];
                                      ancestry.push({tag:node.tagName,classes:node.className,id:node.id,onClick:!!(props && props.onClick),handler:props && props.onClick ? String(props.onClick) : ''});
                                    }
                                    return {text:e.innerText,ancestry:ancestry};
                                  });
                                  LogisticsCapture.postMessage(JSON.stringify({kind:'pageElements',url:location.href,pageUrl:location.href,body:JSON.stringify(elements),status:200}));
                                })();
                            """.trimIndent())
                            nextAction = SystemClock.elapsedRealtime() + 2_000
                        }
                    }
                } finally { session.close() }
            }
        } finally {
            File(directory, "$remoteOrderId-page-captures.json").writeText(json.encodeToString(captures))
            Log.i("PddTrackingEvidence", "pageCaptures=${captures.size}")
        }
        assertTrue("真实页面未提供采集资料", captures.isNotEmpty())
    }

    @Test fun replayCapturedOrder(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        val accountId = checkNotNull(arguments.getString("accountId"))
        val remoteOrderId = checkNotNull(arguments.getString("remoteOrderId"))
        val capturesPath = checkNotNull(arguments.getString("capturesPath"))
        val container = (instrumentation.targetContext.applicationContext as LogisticsApplication).container
        container.ready.await()
        val source = container.repository.getShipments().flatMap { container.repository.getSources(it.id) }
            .single { it.accountId == accountId && it.remoteOrderId == remoteOrderId }
        val captures = json.decodeFromString<List<CapturedResponse>>(File(capturesPath).readText())
        val capture = captures.last { it.kind == "dom" && Uri.parse(it.url).getQueryParameter("order_sn") == remoteOrderId }
        val parsed = PddAdapter(container.platforms).parseTracking(capture, source)
        assertTrue("真实物流 DOM 应当解析出轨迹", parsed != null && parsed.events.isNotEmpty())
        assertEquals(Uri.parse(capture.url).getQueryParameter("tracking_number"), checkNotNull(parsed).trackingNumber)
        assertTrue(parsed.carrier.isNotBlank())
        arguments.getString("expectedStatus")?.let { assertEquals(it, parsed.status.name) }
        assertTrue("物流网址中的 needs_login 参数不能表示登录失效", !PlatformWebView.isLoginUrl(capture.url))
        val differentSource = container.repository.getShipments().flatMap { container.repository.getSources(it.id) }
            .first { it.accountId == accountId && it.remoteOrderId != remoteOrderId }
        assertEquals("其他订单的物流资料不能写入当前来源", null, PddAdapter(container.platforms).parseTracking(capture, differentSource))
    }

    @Test fun queryRealOrder(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        val accountId = checkNotNull(arguments.getString("accountId"))
        val remoteOrderId = checkNotNull(arguments.getString("remoteOrderId"))
        val expectedFailure = arguments.getString("expectedFailure")
        val expectedStatus = arguments.getString("expectedStatus")
        val context = instrumentation.targetContext
        val container = (context.applicationContext as LogisticsApplication).container
        container.ready.await()
        val repository = container.repository
        val account = checkNotNull(repository.getAccount(accountId))
        assertEquals(Platform.PDD, account.platform)
        val source = repository.getShipments().flatMap { repository.getSources(it.id) }
            .single { it.accountId == account.id && it.remoteOrderId == remoteOrderId }
        val before = checkNotNull(repository.getShipment(source.shipmentId))
        val beforeEvents = repository.getEvents(before.id)
        val directory = File(context.filesDir, "pdd-tracking-evidence").apply { check(mkdirs() || isDirectory) }
        val evidence = File(directory, "$remoteOrderId-${System.currentTimeMillis()}")
        val captures = mutableListOf<CapturedResponse>()
        try {
            val incoming = withContext(Dispatchers.Main.immediate) {
                val adapter = container.platforms.adapter(Platform.PDD) as PddAdapter
                adapter.openTrackingSession(account) { captures.add(it) }.use { it.query(source) }
            }
            assertEquals("预期服务失败的订单返回了物流", null, expectedFailure)
            assertEquals(source.remoteShipmentId, incoming.remoteId)
            assertTrue("真实物流缺少运单号码", !incoming.trackingNumber.isNullOrBlank())
            assertTrue("真实物流缺少承运商", incoming.carrier.isNotBlank())
            assertTrue("真实物流缺少轨迹", incoming.events.isNotEmpty())
            assertTrue(incoming.events.all { it.time > 0 && it.text.isNotBlank() })
            expectedStatus?.let { assertEquals(it, incoming.status.name) }
            val page = captures.last { it.kind == "dom" && Uri.parse(it.url).path == "/goods_express.html" }
            assertEquals(remoteOrderId, Uri.parse(page.url).getQueryParameter("order_sn"))
            assertEquals(incoming.trackingNumber, Uri.parse(page.url).getQueryParameter("tracking_number"))
            repository.applyTracking(source, incoming)
            repository.finishTracking(source.id)
            val saved = checkNotNull(repository.getShipment(before.id))
            assertEquals(incoming.trackingNumber, saved.trackingNumber)
            assertEquals(incoming.carrier, saved.carrier)
            expectedStatus?.let { assertEquals(it, saved.status.name) }
            assertEquals(before.notes, saved.notes)
            assertEquals(before.archived, saved.archived)
            val events = repository.getEvents(saved.id)
            assertTrue(incoming.events.all { event -> events.any { it.time == event.time && it.text == event.text } })
            assertEquals(events.size, events.map { it.time to it.text }.toSet().size)
            File("${evidence.path}-result.txt").writeText("SUCCESS\nevents=${incoming.events.size}\n")
            Log.i("PddTrackingEvidence", "result=SUCCESS events=${incoming.events.size} state=${incoming.status}")
        } catch (failure: AppFailure) {
            File("${evidence.path}-result.txt").writeText("${failure.kind}\n${failure.message}\n")
            Log.i("PddTrackingEvidence", "result=${failure.kind}")
            if (expectedFailure == null) throw failure
            assertEquals(expectedFailure, failure.kind.name)
            assertEquals(before, repository.getShipment(before.id))
            assertEquals(beforeEvents, repository.getEvents(before.id))
        } finally {
            File("${evidence.path}-captures.json").writeText(json.encodeToString(captures))
        }
    }

    @Test fun syncRealAccount(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        val accountId = checkNotNull(arguments.getString("accountId"))
        val expectedOrders = checkNotNull(arguments.getString("remoteOrderId")).split(',').toSet()
        val container = (instrumentation.targetContext.applicationContext as LogisticsApplication).container
        container.ready.await()
        val repository = container.repository
        assertEquals(Platform.PDD, checkNotNull(repository.getAccount(accountId)).platform)
        val before = repository.getShipments().associateBy { it.id }
        val expectedSources = before.values.flatMap { repository.getSources(it.id) }
            .filter { it.accountId == accountId && it.remoteOrderId in expectedOrders }
        assertEquals(expectedOrders, expectedSources.map { it.remoteOrderId }.toSet())
        val beforeEvents = expectedSources.associate { it.shipmentId to repository.getEvents(it.shipmentId) }
        withContext(Dispatchers.Main.immediate) { container.syncCoordinator.syncAccount(accountId) }
        val account = checkNotNull(repository.getAccount(accountId))
        assertEquals(AccountState.PARTIAL, account.state)
        val pending = repository.pendingTracking(accountId)
        assertEquals(expectedSources.map { it.id }.toSet(), pending.map { it.sourceId }.toSet())
        assertTrue(pending.all { it.error == "拼多多该订单页面未提供物流入口" })
        val progress = checkNotNull(container.syncCoordinator.progress.value[accountId])
        assertEquals(expectedSources.map { it.id }.toSet(), progress.trackingFailures.map { it.sourceId }.toSet())
        assertTrue(progress.trackingFailures.all { it.kind == FailureKind.SERVICE_ERROR })
        for (source in expectedSources) {
            assertEquals(before[source.shipmentId], repository.getShipment(source.shipmentId))
            assertEquals(beforeEvents[source.shipmentId], repository.getEvents(source.shipmentId))
        }
        for (shipment in repository.getShipments()) {
            val prior = checkNotNull(before[shipment.id])
            assertEquals(prior.notes, shipment.notes)
            assertEquals(prior.archived, shipment.archived)
        }
        Log.i("PddTrackingEvidence", "syncState=${account.state} serviceFailures=${pending.size}")
    }
}
