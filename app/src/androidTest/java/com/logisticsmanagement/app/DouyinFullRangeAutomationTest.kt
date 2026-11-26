package com.logisticsmanagement.app

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.logisticsmanagement.app.model.Account
import com.logisticsmanagement.app.model.AccountState
import com.logisticsmanagement.app.model.ParcelStatus
import com.logisticsmanagement.app.model.Platform
import com.logisticsmanagement.app.platform.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class DouyinFullRangeAutomationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val container get() = (context.applicationContext as LogisticsApplication).container
    private val arguments = InstrumentationRegistry.getArguments()

    private suspend fun tables(): String = DouyinOrderEvidenceTest().tables()

    private fun tableRows(raw: String, name: String): List<String> =
        platformJson.parseToJsonElement(raw).jsonObject.getValue(name).jsonArray.map { row ->
            val value = row.jsonObject
            val filtered = if (name == "Shipment") JsonObject(value.filterKeys { it != "updatedAt" }) else value
            filtered.toString()
        }.sorted()

    private fun export(directory: File, captureId: String, automation: UiAutomation) =
        DouyinDeviceHarness.export(directory, captureId, automation, context, container)

    private fun roundEvidence(directory: File): List<JsonObject> =
        File(directory, "evidence").listFiles().orEmpty()
            .filter { it.name.startsWith("pagination-") && it.extension == "json" }
            .map { platformJson.parseToJsonElement(it.readText()).jsonObject }

    private suspend fun assertRound(service: DouyinCaptureService, automation: UiAutomation, roundDirectory: File,
        captureId: String, progress: NativeSyncProgress, account: Account) {
        assertFalse("本轮仍在运行", progress.running)
        assertTrue("没有记录屏幕数量", progress.screensRead >= 1)
        assertTrue("没有记录最后一个屏幕的节点数量", progress.lastScreenNodeCount > 0)
        val scope = checkNotNull(progress.scopeEvidence) { "没有写入范围证据" }
        assertTrue("范围证据没有标签", scope.tabs.isNotEmpty())
        assertTrue("全部标签既没有确认也没有写明缺失字段",
            progress.scopeConfirmed || progress.blockedCards.values.any { it.contains("全部标签未提供") })
        assertTrue("列表结束没有证据或明确原因",
            progress.listEnded && progress.terminalEvidence != null ||
                progress.reason.contains("已读取") && progress.reason.contains("最后一个屏幕"))
        val current = checkNotNull(container.repository.getAccount(account.id))
        assertNotEquals(AccountState.FAILED, current.state)
        assertNotEquals(AccountState.SYNCING, current.state)
        assertEquals("采集结束时前台不是抖音", DouyinCaptureController.PACKAGE, DouyinDeviceHarness.topPackage(automation))
        assertEquals(0, service.serviceInfo.flags and AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE)
        val evidence = roundEvidence(roundDirectory)
        val details = evidence.filter { it["action"]?.jsonPrimitive?.content == "DETAIL_FIELDS" }
        val importEvidence = platformJson.parseToJsonElement(File(roundDirectory, "evidence/order-import.json").readText()).jsonObject
        val logisticsRecords = importEvidence["logistics"]?.jsonObject?.get("records")?.jsonArray.orEmpty().map { it.jsonObject }
        val orders = container.repository.getOrders().filter { it.accountId == account.id }
        for (entry in progress.orders.values.filter { it.evidenceCaptureId == captureId && it.orderSaved }) {
            val order = orders.firstOrNull { it.remoteId == entry.remoteId }
            assertNotNull("进度中的订单没有保存：${entry.remoteId}", order)
            val stored = checkNotNull(order)
            assertTrue("订单编号为空", stored.remoteId.isNotBlank())
            if (entry.status == ParcelStatus.SIGNED) assertNotNull("已签收订单缺少下单时间：${entry.remoteId}", stored.createdAt)
            assertTrue("订单没有详情字段或直接物流记录：${entry.remoteId}",
                details.any { it["orderId"]?.jsonPrimitive?.content == entry.remoteId } ||
                    evidence.any { it["action"]?.jsonPrimitive?.content == "LOGISTICS_DIRECT" &&
                        it["orderId"]?.jsonPrimitive?.content == entry.remoteId })
            if (!entry.logisticsSaved) continue
            val shipments = container.repository.getShipments().filter { shipment ->
                container.repository.getSources(shipment.id).any { it.accountId == account.id && it.orderId == stored.id }
            }
            assertTrue("已保存物流的订单没有包裹：${entry.remoteId}", shipments.isNotEmpty())
            val shipment = shipments.first()
            assertTrue("包裹缺少承运商", shipment.carrier.isNotBlank())
            assertTrue("包裹缺少运单", !shipment.trackingNumber.isNullOrBlank())
            val events = container.repository.getEvents(shipment.id)
            assertTrue("包裹没有轨迹：${entry.remoteId}", events.isNotEmpty())
            assertEquals("轨迹存在重复", events.size, events.map { Triple(it.time, it.text, it.status) }.toSet().size)
            val record = logisticsRecords.lastOrNull { candidate ->
                candidate["orderId"]?.jsonPrimitive?.content == entry.remoteId &&
                    candidate["events"]?.jsonArray?.isNotEmpty() == true
            }
            assertNotNull("物流保存没有轨迹记录：${entry.remoteId}", record)
            val saved = checkNotNull(record)
            if (saved["source"]?.jsonPrimitive?.content == "ocr") {
                assertEquals("识别只允许一个包裹", 1, saved.getValue("parcels").jsonArray.size)
                assertEquals("两次识别结果不一致", 2, saved.getValue("confirmedReadings").jsonPrimitive.int)
                assertTrue("识别结果存在未定年时间", saved.getValue("unresolved").jsonArray.isEmpty())
                assertTrue("识别轨迹没有时间证据", saved.getValue("times").jsonArray.isNotEmpty())
                assertTrue("本轮保存了识别物流但没有识别原文",
                    File(roundDirectory, "ocr").listFiles().orEmpty().any { it.extension == "txt" })
            } else {
                assertTrue("节点物流缺少两次读取", saved.getValue("confirmedReadings").jsonPrimitive.int >= 2)
                val fields = saved.getValue("fields").jsonArray.map { it.jsonObject }
                assertTrue("节点物流缺少承运商字段", fields.any { it["name"]?.jsonPrimitive?.content == "carrier" })
                assertTrue("节点物流缺少轨迹原文", fields.any { it["name"]?.jsonPrimitive?.content == "eventText" })
                assertTrue("节点物流缺少轨迹时间", fields.any { it["name"]?.jsonPrimitive?.content == "eventTime" })
            }
        }
    }

    @Test fun fullRangeTwoRounds(): Unit = runBlocking {
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val service = DouyinDeviceHarness.awaitService(automation, context, container)
        val account = container.repository.getAccounts().firstOrNull { it.platform == Platform.DOUYIN && !it.deleted }
            ?: error("缺少抖音账号")
        val settings = container.settings.get()
        val runDirectory = File(checkNotNull(context.getExternalFilesDir(null)), "s2/run-${System.currentTimeMillis()}")
            .apply { check(mkdirs()) }
        File(runDirectory, "accessibility-before.txt").writeText(
            DouyinDeviceHarness.shell(automation, "settings get secure enabled_accessibility_services").trim())
        File(runDirectory, "settings.json").writeText(platformJson.encodeToString(settings))
        container.settings.save(settings.copy(nativeEvidenceVerbose = true))
        DouyinCaptureController.clearOcrEvidence()
        val timeout = arguments.getString("captureTimeoutMillis")?.toLongOrNull() ?: 480_000L
        val after = mutableListOf<String>()
        try {
            for (round in 1..2) {
                val roundDirectory = File(runDirectory, "round-$round").apply { check(mkdirs()) }
                val captureId = "S2R${round}T${System.currentTimeMillis()}"
                File(roundDirectory, "before-tables.json").writeText(tables())
                DouyinDeviceHarness.startDouyin(automation)
                val started = SystemClock.elapsedRealtime()
                withContext(Dispatchers.Main.immediate) {
                    DouyinCaptureController.startWithEvidence(account, captureId, timeout, importOrders = true, pagination = true)
                }
                withTimeout(timeout + 120_000) { while (DouyinCaptureController.account != null) delay(1_000) }
                service.awaitOrdersFinished()
                val elapsed = SystemClock.elapsedRealtime() - started
                delay(1_000)
                export(roundDirectory, captureId, automation)
                val afterTables = tables()
                File(roundDirectory, "after-tables.json").writeText(afterTables)
                val progress = checkNotNull(container.syncCoordinator.nativeProgressStore.read(account))
                val shipments = container.repository.getShipments()
                var eventCount = 0
                for (shipment in shipments) eventCount += container.repository.getEvents(shipment.id).size
                File(roundDirectory, "summary.json").writeText(buildJsonObject {
                    put("round", round); put("captureId", captureId); put("elapsedMillis", elapsed)
                    put("orders", container.repository.getOrders().count { it.accountId == account.id })
                    put("shipments", shipments.size); put("events", eventCount)
                    put("accountState", container.repository.getAccount(account.id)?.state?.name ?: AccountState.FAILED.name)
                    put("reason", progress.reason); put("screensRead", progress.screensRead)
                    put("lastScreenNodeCount", progress.lastScreenNodeCount)
                    put("scopeConfirmed", progress.scopeConfirmed)
                    put("blocked", JsonArray(progress.blockedCards.values.map(::JsonPrimitive)))
                    put("progress", platformJson.parseToJsonElement(platformJson.encodeToString(progress)))
                    put("topPackage", DouyinDeviceHarness.topPackage(automation))
                }.toString())
                assertRound(service, automation, roundDirectory, captureId, progress, account)
                after.add(afterTables)
            }
            for (name in listOf("orders", "Shipment", "SourceRef", "TrackingEvent")) {
                assertEquals("第二轮在 $name 上新增了行", tableRows(after[0], name), tableRows(after[1], name))
            }
            File(runDirectory, "result.json").writeText(buildJsonObject {
                put("rounds", 2)
                put("stableTables", JsonArray(listOf("orders", "Shipment", "SourceRef", "TrackingEvent").map(::JsonPrimitive)))
            }.toString())
        } finally {
            withContext(NonCancellable + Dispatchers.Main.immediate) {
                if (DouyinCaptureController.account != null) DouyinCaptureController.stop("S2 验证结束")
            }
            service.awaitOrdersFinished()
            container.settings.save(settings)
            File(runDirectory, "accessibility-after.txt").writeText(
                DouyinDeviceHarness.shell(automation, "settings get secure enabled_accessibility_services").trim())
        }
    }
}
