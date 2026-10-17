package com.logisticsmanagement.app

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.content.Intent
import android.os.ParcelFileDescriptor
import android.view.accessibility.AccessibilityNodeInfo
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.logisticsmanagement.app.data.*
import com.logisticsmanagement.app.model.*
import com.logisticsmanagement.app.platform.*
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@Serializable
internal data class NativeOrderInstruction(val sequence: Int, val action: String, val label: String = "", val path: List<Int>? = null,
    val captureId: String = "")

@RunWith(AndroidJUnit4::class)
class DouyinOrderEvidenceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val arguments = InstrumentationRegistry.getArguments()
    private val container get() = (context.applicationContext as LogisticsApplication).container
    private fun argument(name: String) = checkNotNull(arguments.getString(name))
    private fun input(name: String) = File(context.filesDir, argument(name)).apply { check(isFile) }
    private fun output() = File(context.filesDir, "douyin-order-evidence/${argument("runName")}").apply { check(mkdirs()) }
    internal suspend fun tables(): String = container.database.withTransaction {
        buildJsonObject {
            for (table in listOf("Account", "orders", "Shipment", "SourceRef", "TrackingEvent", "ShipmentAlias", "TrackingQueue", "PendingProfileCleanup")) {
                val rows = mutableListOf<JsonElement>()
                container.database.openHelper.readableDatabase.query("SELECT * FROM $table").use { cursor ->
                    while (cursor.moveToNext()) rows.add(buildJsonObject {
                        for (index in 0 until cursor.columnCount) put(cursor.getColumnName(index), when (cursor.getType(index)) {
                            android.database.Cursor.FIELD_TYPE_NULL -> JsonNull
                            android.database.Cursor.FIELD_TYPE_INTEGER -> JsonPrimitive(cursor.getLong(index))
                            else -> JsonPrimitive(cursor.getString(index))
                        })
                    })
                }
                put(table, JsonArray(rows))
            }
        }.toString()
    }

    private fun export(directory: File, captureId: String) {
        val source = File(context.filesDir, "native-entry-evidence/$captureId")
        for (file in checkNotNull(source.listFiles()).filter { it.extension == "enc" }.sortedBy { it.name }) {
            val raw = container.credentials.decrypt(file.readText())
            platformJson.parseToJsonElement(raw)
            File(directory, file.name.removeSuffix(".enc") + ".json").writeText(raw)
        }
    }

    @Test fun fixedFlow(): Unit = runBlocking {
        DouyinFixedFlowRunner(instrumentation, container, output(), argument("accountId"), input("capturesPath"),
            snapshot = { tables() }, export = { directory, captureId -> export(directory, captureId) }).run()
    }

    @Test fun replayFixedOrder(): Unit = runBlocking {
        container.ready.await()
        val directory = output()
        val fixture = platformJson.parseToJsonElement(input("capturesPath").readText()).jsonObject
        fun scan(name: String) = platformJson.decodeFromJsonElement<NativeScan>(fixture.getValue(name))
        val target = platformJson.decodeFromJsonElement<FixedFlowTarget>(fixture.getValue("target"))
        val expanded = fixture.getValue("expanded").jsonArray.map { platformJson.decodeFromJsonElement<NativeScan>(it) }
        val titleAction = fixture.getValue("titleAction").jsonObject
        val logisticsAction = fixture.getValue("logisticsAction").jsonObject
        assertTrue(titleAction.getValue("accepted").jsonPrimitive.boolean)
        assertTrue(logisticsAction.getValue("accepted").jsonPrimitive.boolean)
        val titleNode = platformJson.decodeFromJsonElement<NativeNode>(titleAction.getValue("target"))
        val logisticsNode = platformJson.decodeFromJsonElement<NativeNode>(logisticsAction.getValue("target"))
        val database = Room.databaseBuilder(context, LogisticsDatabase::class.java, "T11-fixed-replay-${System.nanoTime()}.db")
            .createFromFile(input("databasePath")).build()
        try {
            val repository = LogisticsRepository(database)
            val id = argument("accountId")
            val beforeShipments = repository.getShipments()
            val beforeQueue = repository.pendingTracking(id)
            var storedId: String? = null
            repeat(2) { round ->
                val session = NativeOrderImportSession(id, scan("identity").captureId,
                    bind = { repository.bindNativeIdentity(id, it) },
                    existing = { remote -> repository.getOrders().firstOrNull { it.accountId == id && it.remoteId == remote } },
                    save = { owner, order -> repository.ingestNativeOrders(id, owner, OrderBatch(listOf(order))) }, progress = {})
                session.accept(scan("identity"))
                val list = scan("list").pages.single { page -> page.nodes.any { it.path == titleNode.path && it.text == titleNode.text } }
                session.navigation(list, titleNode)
                session.accept(scan("collapsed"))
                expanded.forEach { session.accept(it) }
                val detail = expanded.last().pages.single { page -> page.nodes.any { it.path == logisticsNode.path && DouyinNativeOrders.label(it) == "查看物流" } }
                session.navigation(detail, logisticsNode)
                session.accept(scan("logistics"))
                val order = repository.getOrders().single { it.accountId == id && it.remoteId == target.orderId }
                assertEquals(ParcelStatus.SIGNED, order.status)
                assertEquals(DouyinNativeOrders.orderTime(target.expectedCreatedAtText), order.createdAt)
                assertTrue(session.missing.isEmpty())
                assertEquals(if (round == 0) 1 else 0, session.totals.inserted)
                if (round > 0) assertEquals(storedId, order.id)
                storedId = order.id
                assertEquals(beforeShipments, repository.getShipments())
                assertEquals(beforeQueue, repository.pendingTracking(id))
                assertTrue(database.dao().orderSources(order.id).isEmpty())
                File(directory, "round-${round + 1}-order-import.json").writeText(session.evidence())
            }
            File(directory, "result.json").writeText(buildJsonObject {
                put("rounds", 2); put("savedSignedOrder", true); put("correctOrderTime", true)
                put("stableId", true); put("shipmentsAndQueuePreserved", true)
            }.toString())
        } finally { database.close() }
    }

    @Test fun captureDisplayedPage(): Unit = runBlocking {
        instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        container.ready.await()
        val account = checkNotNull(container.repository.getAccount(argument("accountId")))
        context.startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val connected = withTimeout(120_000) {
            while (DouyinCaptureController.service == null) delay(100)
            checkNotNull(DouyinCaptureController.service)
        }
        val directory = output()
        val runName = argument("runName")
        val taskId = argument("taskId")
        require(taskId.matches(Regex("[0-9]+")))
        withContext(Dispatchers.Main.immediate) { DouyinCaptureController.startWithEvidence(account, runName) }
        try {
            val descriptor = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
                .executeShellCommand("am stack move-task $taskId $taskId true")
            File(directory, "restore.txt").writeText(ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() })
            withContext(Dispatchers.Main.immediate) { DouyinCaptureController.normalNavigation() }
            File(directory, "ready.marker").writeText("等待返回抖音当前页面")
            withTimeout(90_000) {
                while (connected.captureNow().pages.isEmpty()) delay(500)
            }
            withContext(Dispatchers.Main.immediate) { DouyinCaptureController.resumeCapture() }
            delay(1_000)
            val scans = List(2) { withTimeout(30_000) { connected.captureNow() } }
            assertTrue(scans.all { it.pages.isNotEmpty() })
            File(directory, "step-000.json").writeText(buildJsonObject {
                put("sequence", 0); put("action", "record"); put("actionResult", true); put("actionReason", ""); put("status", "当前真实页面读取")
                put("scans", JsonArray(scans.map { platformJson.parseToJsonElement(platformJson.encodeToString(it)) }))
            }.toString())
            File(directory, "result.json").writeText(buildJsonObject { put("scans", 2); put("displayedPageCaptured", true) }.toString())
        } finally {
            withContext(NonCancellable + Dispatchers.Main.immediate) { DouyinCaptureController.stop() }
            export(directory, runName)
        }
    }

    @Test fun replayT10MissingFields(): Unit = runBlocking {
        container.ready.await()
        val directory = output()
        val steps = platformJson.parseToJsonElement(input("capturesPath").readText()).jsonArray.associateBy {
            it.jsonObject.getValue("sequence").jsonPrimitive.int
        }
        fun scan(number: Int) = platformJson.decodeFromJsonElement<NativeScan>(steps.getValue(number).jsonObject.getValue("scans").jsonArray.last())
        val database = Room.databaseBuilder(context, LogisticsDatabase::class.java, "T11-replay-${System.nanoTime()}.db")
            .createFromFile(input("databasePath")).build()
        try {
            val repository = LogisticsRepository(database)
            val id = argument("accountId")
            val beforeOrders = repository.getOrders()
            val beforeShipments = repository.getShipments()
            val beforeQueue = repository.pendingTracking(id)
            val session = NativeOrderImportSession(id, scan(4).captureId,
                bind = { repository.bindNativeIdentity(id, it) },
                existing = { remote -> repository.getOrders().firstOrNull { it.accountId == id && it.remoteId == remote } },
                save = { identity, order -> repository.ingestNativeOrders(id, identity, OrderBatch(listOf(order))) }, progress = {})
            repeat(2) {
                session.accept(scan(4))
                val page = scan(9).pages.single()
                val titlePath = listOf(0,0,0,0,0,0,0,2,0,1,0,1,0,0,1,0,2,0,0,0,0,0)
                assertTrue(steps.getValue(10).jsonObject.getValue("actionResult").jsonPrimitive.boolean)
                session.navigation(page, page.nodes.single { it.path == titlePath })
                session.accept(scan(10))
                val returned = scan(11).pages.single()
                val logisticsPath = listOf(0,0,0,0,0,0,0,2,0,1,0,1,0,0,3,0,0,1,0,1,0)
                assertTrue(steps.getValue(12).jsonObject.getValue("actionResult").jsonPrimitive.boolean)
                session.navigation(returned, returned.nodes.single { it.path == logisticsPath })
                session.accept(scan(12))
            }
            assertFalse(session.identity.isNullOrBlank())
            assertEquals(session.identity, repository.getAccount(id)?.externalIdentity)
            assertEquals(0, session.totals.inserted)
            assertTrue(session.missing.values.any { "包含年份的下单时间" in it })
            assertEquals(beforeOrders, repository.getOrders())
            assertEquals(beforeShipments, repository.getShipments())
            assertEquals(beforeQueue, repository.pendingTracking(id))
            File(directory, "order-import.json").writeText(session.evidence())
            File(directory, "result.json").writeText(buildJsonObject {
                put("rounds", 2); put("identityBound", true); put("missingDateRejected", true); put("businessPreserved", true)
            }.toString())
        } finally { database.close() }
    }

    @Test fun replayCurrentMissingDate(): Unit = runBlocking {
        container.ready.await()
        val directory = output()
        val steps = platformJson.parseToJsonElement(input("capturesPath").readText()).jsonArray.associateBy {
            it.jsonObject.getValue("sequence").jsonPrimitive.int
        }
        fun scan(number: Int) = platformJson.decodeFromJsonElement<NativeScan>(steps.getValue(number).jsonObject.getValue("scans").jsonArray.last())
        val database = Room.databaseBuilder(context, LogisticsDatabase::class.java, "T11-current-replay-${System.nanoTime()}.db")
            .createFromFile(input("databasePath")).build()
        try {
            val repository = LogisticsRepository(database)
            val id = argument("accountId")
            val beforeOrders = repository.getOrders()
            val beforeShipments = repository.getShipments()
            val beforeQueue = repository.pendingTracking(id)
            val identity = checkNotNull(DouyinNativeOrders.identity(scan(1)))
            val titlePath = listOf(0,0,0,0,0,0,0,2,0,1,0,1,0,0,2,0,2,0,0,0,0,0)
            val logisticsPath = listOf(0,0,0,0,0,0,0,2,0,1,0,1,0,0,4,0,0,1,0,1,0)
            val session = NativeOrderImportSession(id, scan(1).captureId,
                bind = { repository.bindNativeIdentity(id, it) },
                existing = { remote -> repository.getOrders().firstOrNull { it.accountId == id && it.remoteId == remote } },
                save = { owner, order -> repository.ingestNativeOrders(id, owner, OrderBatch(listOf(order))) }, progress = {})
            repeat(2) {
                session.accept(scan(1))
                val list = scan(5).pages.single()
                assertTrue(steps.getValue(6).jsonObject.getValue("actionResult").jsonPrimitive.boolean)
                session.navigation(list, list.nodes.single { it.path == titlePath })
                session.accept(scan(6))
                val returned = scan(7).pages.single()
                assertTrue(steps.getValue(8).jsonObject.getValue("actionResult").jsonPrimitive.boolean)
                session.navigation(returned, returned.nodes.single { it.path == logisticsPath })
                session.accept(scan(8))
                assertEquals(identity.value, repository.getAccount(id)?.externalIdentity)
                assertEquals(listOf(listOf("包含年份的下单时间")), session.missing.values.toList())
                assertEquals(beforeOrders, repository.getOrders())
                assertEquals(beforeShipments, repository.getShipments())
                assertEquals(beforeQueue, repository.pendingTracking(id))
            }
            assertEquals(0, session.totals.inserted)
            assertEquals(0, session.totals.updated)
            File(directory, "order-import.json").writeText(session.evidence())
            File(directory, "result.json").writeText(buildJsonObject {
                put("rounds", 2); put("identityBound", true); put("ordinaryCarrierConfirmed", true)
                put("missingDateRejected", true); put("businessPreserved", true)
            }.toString())
        } finally { database.close() }
    }

    @Test fun replayExpandedReadback(): Unit = runBlocking {
        container.ready.await()
        val directory = output()
        val steps = platformJson.parseToJsonElement(input("capturesPath").readText()).jsonArray.associateBy {
            it.jsonObject.getValue("sequence").jsonPrimitive.int
        }
        fun scans(number: Int) = steps.getValue(number).jsonObject.getValue("scans").jsonArray.map {
            platformJson.decodeFromJsonElement<NativeScan>(it)
        }
        fun scan(number: Int) = scans(number).last()
        val database = Room.databaseBuilder(context, LogisticsDatabase::class.java, "T11-expanded-replay-${System.nanoTime()}.db")
            .createFromFile(input("databasePath")).build()
        try {
            val repository = LogisticsRepository(database)
            val id = argument("accountId")
            val beforeOrders = repository.getOrders()
            val beforeShipments = repository.getShipments()
            val beforeQueue = repository.pendingTracking(id)
            val list = scan(4).pages.single()
            val titlePath = listOf(0,0,0,0,0,0,0,2,0,1,0,1,0,0,2,0,2,0,0,0,0,0)
            val title = list.nodes.single { it.path == titlePath }
            val original = checkNotNull(DouyinNativeOrders.detail(scan(5), title.text))
            assertNotNull(DouyinNativeOrders.informationRoot(scan(5).pages.single()))
            assertFalse(steps.getValue(6).jsonObject.getValue("actionResult").jsonPrimitive.boolean)
            for (refreshed in scans(9)) {
                assertEquals(scan(5).pages.single().windowId, refreshed.pages.single().windowId)
                assertEquals(refreshed.pages.sumOf { it.nodes.size }, refreshed.refreshResults.size)
                assertTrue(refreshed.refreshResults.all { it.refreshed })
                assertNull(DouyinNativeOrders.orderDate(refreshed.pages.single(), refreshed.sequence))
            }
            val restored = checkNotNull(DouyinNativeOrders.detail(scan(16), title.text))
            assertEquals(original.remoteId, restored.remoteId)
            repeat(2) {
                val session = NativeOrderImportSession(id, scan(1).captureId,
                    bind = { repository.bindNativeIdentity(id, it) },
                    existing = { remote -> repository.getOrders().firstOrNull { it.accountId == id && it.remoteId == remote } },
                    save = { owner, order -> repository.ingestNativeOrders(id, owner, OrderBatch(listOf(order))) }, progress = {})
                session.accept(scan(1))
                session.navigation(list, title)
                session.accept(scan(5))
                session.beginNavigation()
                scans(7).forEach { session.accept(it) }
                scans(8).forEach { session.accept(it) }
                scans(9).forEach { session.accept(it) }
                session.resumeCapture()
                session.accept(scan(12))
                val returned = scan(15).pages.single()
                session.navigation(returned, returned.nodes.single { it.path == titlePath })
                session.accept(scan(16))
                assertEquals(0, session.totals.inserted)
                assertEquals(0, session.totals.updated)
                assertTrue(session.missing.values.any { "包含年份的下单时间" in it })
                assertEquals(beforeOrders, repository.getOrders())
                assertEquals(beforeShipments, repository.getShipments())
                assertEquals(beforeQueue, repository.pendingTracking(id))
                File(directory, "round-${it + 1}-order-import.json").writeText(session.evidence())
            }
            File(directory, "result.json").writeText(buildJsonObject {
                put("rounds", 2); put("refreshedNodesPerScan", scan(9).refreshResults.size)
                put("sameOrderAfterResume", true); put("missingDateRejected", true); put("businessPreserved", true)
            }.toString())
        } finally { database.close() }
    }

    @Test fun liveSession(): Unit = runBlocking { liveRounds(1) }

    @Test fun expandedSessions(): Unit = runBlocking { liveRounds(2) }

    private suspend fun liveRounds(rounds: Int) {
        val instructions = input("instructionsPath")
        instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        container.ready.await()
        val account = checkNotNull(container.repository.getAccount(argument("accountId")))
        assertEquals(CaptureMode.APP_PAGE, account.captureMode)
        if (DouyinCaptureController.service == null) {
            context.startActivity(Intent(context, DouyinCaptureActivity::class.java).putExtra("accountId", account.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            context.startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        val connected = withTimeout(300_000) {
            while (DouyinCaptureController.service == null) delay(100)
            checkNotNull(DouyinCaptureController.service)
        }
        val rootDirectory = output()
        val baseName = argument("runName")
        var sequence = -1
        val originalSettings = container.settings.get()
        File(rootDirectory, "settings.json").writeText(platformJson.encodeToString(originalSettings))
        var firstOrders: List<Order>? = null
        val results = mutableListOf<JsonObject>()
        repeat(rounds) { round ->
        val directory = if (rounds == 1) rootDirectory else File(rootDirectory, "round-${round + 1}").apply { check(mkdirs()) }
        val runName = if (rounds == 1) baseName else "$baseName-round-${round + 1}"
        withTimeout(60_000) { while (container.syncCoordinator.hasActiveAccounts()) delay(100) }
        File(directory, "before-tables.json").writeText(tables())
        val beforeShipments = container.repository.getShipments()
        val beforeQueue = container.repository.pendingTracking(account.id)
        var finished = false
        var steps = 0
        withContext(Dispatchers.Main.immediate) {
            DouyinCaptureController.startOrdersWithEvidence(account, runName)
            assertTrue(connected.serviceInfo.flags and AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE != 0)
        }
        try {
            val component = checkNotNull(context.packageManager.getLaunchIntentForPackage(DouyinCaptureController.PACKAGE)?.component)
            val descriptor = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
                .executeShellCommand("am start -n ${component.flattenToShortString()}")
            File(directory, "launch.txt").writeText(ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() })
            delay(2_000)
            File(rootDirectory, "ready.marker").writeText(buildJsonObject { put("round", round + 1); put("captureId", runName) }.toString())
            while (withContext(Dispatchers.Main.immediate) { DouyinCaptureController.activeAccount() } != null) {
                val instruction = platformJson.decodeFromString<NativeOrderInstruction>(instructions.readText())
                if (instruction.sequence > sequence) {
                    sequence = instruction.sequence
                    if (instruction.captureId != runName) {
                        File(rootDirectory, "rejected-${sequence.toString().padStart(3, '0')}.json").writeText(buildJsonObject {
                            put("sequence", sequence); put("expectedCaptureId", runName); put("captureId", instruction.captureId)
                            put("reason", "指令采集编号不匹配")
                        }.toString())
                        continue
                    }
                    steps++
                    var accepted = true
                    var reason = ""
                    withContext(Dispatchers.Main.immediate) {
                        when (instruction.action) {
                            "record", "refresh" -> Unit
                            "normal" -> {
                                DouyinCaptureController.normalNavigation()
                                assertEquals(0, connected.serviceInfo.flags and AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE)
                            }
                            "resume" -> DouyinCaptureController.resumeCapture()
                            "finish" -> finished = true
                            "launch" -> {
                                val component = checkNotNull(context.packageManager.getLaunchIntentForPackage(DouyinCaptureController.PACKAGE)?.component)
                                val descriptor = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
                                    .executeShellCommand("am start -n ${component.flattenToShortString()}")
                                File(directory, "launch-$sequence.txt").writeText(ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() })
                            }
                            "back" -> accepted = connected.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                            "click" -> {
                                val scan = connected.captureNow()
                                val anchors = scan.pages.flatMap { page -> page.nodes.filter {
                                    it.visible && it.enabled && (it.text == instruction.label || it.description == instruction.label) &&
                                        (instruction.path == null || instruction.path == it.path)
                                }.map { page to it } }
                                accepted = if (anchors.size != 1) { reason = "当前文字与路径匹配数量为${anchors.size}"; false } else {
                                    val (page, anchor) = anchors.single()
                                    val target = page.nodes.filter { it.visible && it.enabled && AccessibilityNodeInfo.ACTION_CLICK in it.actions &&
                                        anchor.path.take(it.path.size) == it.path }.maxByOrNull { it.path.size }
                                    if (target == null) { reason = "目标及父节点没有点击动作"; false }
                                    else connected.performChecked(page, target, AccessibilityNodeInfo.ACTION_CLICK, anchor).let { reason = it.reason; it.accepted }
                                }
                            }
                            else -> error("采集指令尚未支持")
                        }
                    }
                    if (instruction.action in setOf("click", "back", "resume", "launch")) delay(1_500)
                    val scanCount = if (instruction.action in setOf("record", "refresh")) 2 else 1
                    val scans = List(scanCount) { withTimeout(30_000) {
                        if (instruction.action == "refresh") connected.captureRefreshed(checkNotNull(instruction.path)) else connected.captureNow()
                    } }
                    File(rootDirectory, "step-${sequence.toString().padStart(3,'0')}.json").writeText(buildJsonObject {
                        put("sequence", sequence); put("action", instruction.action); put("actionResult", accepted); put("actionReason", reason)
                        put("scans", JsonArray(scans.map { platformJson.parseToJsonElement(platformJson.encodeToString(it)) }))
                        put("status", DouyinCaptureController.status.value)
                    }.toString())
                    export(directory, runName)
                    if (finished) break
                }
                delay(250)
            }
        } finally {
            withContext(NonCancellable + Dispatchers.Main.immediate) { DouyinCaptureController.stop() }
            connected.awaitOrdersFinished()
            assertEquals(0, connected.serviceInfo.flags and AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE)
            export(directory, runName)
            File(directory, "after-tables.json").writeText(tables())
        }
        val storedAccount = checkNotNull(container.repository.getAccount(account.id))
        val orders = container.repository.getOrders().filter { it.accountId == account.id }
        val proof = platformJson.parseToJsonElement(File(directory, "order-import.json").readText()).jsonObject.getValue("fields").jsonArray
        val result = buildJsonObject {
            put("steps", steps); put("explicitFinish", finished); put("orders", orders.size)
            put("identityBound", !storedAccount.externalIdentity.isNullOrBlank()); put("state", storedAccount.state.name)
            put("round", round + 1); put("captureId", runName)
            put("identityRead", proof.any { it.jsonObject.getValue("name").jsonPrimitive.content == "externalIdentity" })
            put("dateRead", proof.any { field -> field.jsonObject.getValue("name").jsonPrimitive.content in setOf("下单时间", "创建时间", "订单时间") &&
                DouyinNativeOrders.orderTime(field.jsonObject.getValue("value").jsonPrimitive.content) != null })
        }
        File(directory, "result.json").writeText(result.toString())
        results.add(result)
        assertFalse("需要真实抖音号绑定", storedAccount.externalIdentity.isNullOrBlank())
        assertEquals(orders.size, orders.map { it.remoteId }.toSet().size)
        assertEquals(beforeShipments, container.repository.getShipments())
        assertEquals(beforeQueue, container.repository.pendingTracking(account.id))
        assertFalse(container.syncCoordinator.hasActiveAccounts())
        assertEquals(originalSettings, container.settings.get())
        assertEquals(account.lastSyncedAt, storedAccount.lastSyncedAt)
        if (round == 0) firstOrders = orders else assertEquals(firstOrders, orders)
        }
        if (rounds > 1) File(rootDirectory, "result.json").writeText(buildJsonObject { put("rounds", JsonArray(results)) }.toString())
        assertTrue("需要至少一个符合条件的真实订单", checkNotNull(firstOrders).isNotEmpty())
        assertTrue("每轮需要当前账号及完整日期的真实资料", results.all { it.getValue("identityRead").jsonPrimitive.boolean && it.getValue("dateRead").jsonPrimitive.boolean })
    }
}
