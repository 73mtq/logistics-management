package com.logisticsmanagement.app

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Instrumentation
import android.app.UiAutomation
import android.content.Intent
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import com.logisticsmanagement.app.model.*
import com.logisticsmanagement.app.platform.*
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import java.io.File

@Serializable
internal data class FixedFlowTarget(val orderId: String, val title: String, val expectedCreatedAtText: String)

internal class DouyinFixedFlowRunner(
    private val instrumentation: Instrumentation, private val container: AppContainer,
    private val directory: File, private val accountId: String, input: File,
    private val snapshot: suspend () -> String, private val export: (File, String) -> Unit,
    private val tracking: Boolean = false,
) {
    private val context = instrumentation.targetContext
    private val target = platformJson.decodeFromString<FixedFlowTarget>(input.readText())
    private lateinit var service: DouyinCaptureService
    private lateinit var roundDirectory: File
    private var captureId = ""
    private var phase = ""
    private var sequence = 0
    private val automation get() = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)

    private fun write(name: String, value: JsonElement) { File(roundDirectory, name).writeText(value.toString()) }
    private fun stage(value: String) {
        phase = value
        File(directory, "stage.json").writeText(buildJsonObject { put("captureId", captureId); put("stage", value) }.toString())
    }
    private suspend fun read(): NativeScan {
        if (DouyinCaptureController.captureId != captureId || withContext(Dispatchers.Main.immediate) { DouyinCaptureController.activeAccount() } == null)
            throw AppFailure("当前采集会话已经结束", FailureKind.UNSUPPORTED_RESPONSE)
        val scan = service.captureNow()
        write("read-${sequence++.toString().padStart(4, '0')}.json", platformJson.parseToJsonElement(platformJson.encodeToString(scan)))
        return scan
    }
    private suspend fun awaitPage(condition: (NativeScan) -> Boolean): NativeScan {
        val deadline = SystemClock.elapsedRealtime() + 30_000
        do {
            val scan = read()
            if (condition(scan)) return scan
            delay(350)
        } while (SystemClock.elapsedRealtime() < deadline)
        throw AppFailure("当前阶段未取得所需页面资料：$phase", FailureKind.UNSUPPORTED_RESPONSE)
    }
    private fun matches(scan: NativeScan, label: String): List<Pair<NativePage, NativeNode>> = scan.pages.flatMap { page ->
        val active = scan.windows.filter { it.active }.map { it.id }
        page.nodes.filter { page.windowId in active && it.visible && it.enabled && (it.text == label || it.description == label) }.map { page to it }
    }
    private suspend fun prepareMain() {
        repeat(5) {
            val scan = read()
            if (matches(scan, "我").size == 1) return
            if (scan.pages.isEmpty() || scan.windows.none { it.active && it.accepted })
                throw AppFailure("账号准备时没有可读取的官方活动窗口", FailureKind.UNSUPPORTED_RESPONSE)
            val accepted = withContext(Dispatchers.Main.immediate) { service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK) }
            write("back-${sequence++}.json", buildJsonObject { put("captureId", captureId); put("stage", phase); put("accepted", accepted) })
            if (!accepted) throw AppFailure("无法从当前官方页面返回账号入口", FailureKind.UNSUPPORTED_RESPONSE)
            delay(700)
        }
        throw AppFailure("返回官方主界面后仍未取得账号入口", FailureKind.UNSUPPORTED_RESPONSE)
    }
    private suspend fun activate(scan: NativeScan, page: NativePage, node: NativeNode) {
        check(scan.captureId == captureId)
        var currentPage = page
        var currentNode = node
        var reason = ""
        repeat(3) { attempt ->
            if (attempt > 0) {
                val current = read()
                val matches = current.pages.flatMap { candidate -> candidate.nodes.filter {
                    it.visible && it.enabled && DouyinNativeOrders.label(it) == DouyinNativeOrders.label(node) &&
                        it.className == node.className && it.resourceId == node.resourceId
                }.map { candidate to it } }
                if (matches.size != 1) throw AppFailure("当前阶段无法重新唯一定位节点：$phase", FailureKind.UNSUPPORTED_RESPONSE)
                currentPage = matches.single().first
                currentNode = matches.single().second
            }
            val result = service.activateChecked(currentPage, currentNode)
            write("operation-${sequence++.toString().padStart(4, '0')}.json", buildJsonObject {
                put("stage", phase); put("captureId", captureId); put("accepted", result.accepted)
                put("reason", result.reason); put("method", result.method); put("attempt", attempt + 1)
                put("target", platformJson.parseToJsonElement(platformJson.encodeToString(currentNode)))
            })
            if (result.accepted) { delay(700); return }
            reason = result.reason
            if (reason !in setOf("当前节点路径已经变化", "目标节点文字或范围已经变化", "当前活动窗口已经变化"))
                throw AppFailure("当前阶段操作未完成：$phase；$reason", FailureKind.UNSUPPORTED_RESPONSE)
            delay(350)
        }
        throw AppFailure("当前阶段节点持续变化：$phase；$reason", FailureKind.UNSUPPORTED_RESPONSE)
    }
    private suspend fun activateLabel(label: String) {
        val scan = awaitPage { matches(it, label).size == 1 }
        val (page, node) = matches(scan, label).single()
        activate(scan, page, node)
    }
    private suspend fun verifyLogistics() {
        stage("物流展开")
        val initial = read()
        val more = matches(initial, "查看更多物流信息")
        if (more.size == 1) activate(initial, more.single().first, more.single().second)
        var scan = awaitPage { DouyinNativeOrders.carrier(it) != null }
        val order = container.repository.getOrders().single { it.accountId == accountId && it.remoteId == target.orderId }
        var parsed = checkNotNull(DouyinNativeLogistics.parse(scan, order))
        var expandedCount = 0
        while (parsed.missing.any { it.contains("需要展开") }) {
            val body = scan.pages.flatMap { page -> page.nodes.filter { it.visible && it.enabled &&
                DouyinNativeOrders.label(it).endsWith("展开") && DouyinNativeOrders.label(it).length > 10 }.map { page to it } }
            if (body.isEmpty()) throw AppFailure("物流正文尚未完整显示，当前没有可定位的展开节点", FailureKind.UNSUPPORTED_RESPONSE)
            if (++expandedCount > 12) throw AppFailure("当前物流页面的展开操作未完成", FailureKind.UNSUPPORTED_RESPONSE)
            activate(scan, body.first().first, body.first().second)
            scan = read()
            parsed = checkNotNull(DouyinNativeLogistics.parse(scan, order))
        }
        scan = awaitPage { current -> DouyinNativeLogistics.parse(current, order)?.let {
            it.missing.isEmpty() && it.shipment?.events?.isNotEmpty() == true
        } == true }
        val scans = listOf(scan, read())
        compare("logistics", scans)
        val parsedScans = scans.map { checkNotNull(DouyinNativeLogistics.parse(it, order)) }
        assertTrue(parsedScans.all { it.missing.isEmpty() && it.shipment?.events?.isNotEmpty() == true })
        assertEquals(parsedScans.first().shipment, parsedScans.last().shipment)
        parsed = parsedScans.last()
        val expected = checkNotNull(parsed.shipment)
        withTimeout(15_000) {
            while (true) {
                val matching = container.repository.getShipments().firstOrNull { it.trackingNumber == expected.trackingNumber && it.carrierCode == expected.carrierCode }
                if (matching != null && container.repository.getEvents(matching.id).any { it.time == expected.events.first().time && it.text == expected.events.first().text }) break
                delay(100)
            }
        }
        val shipment = container.repository.getShipments().single { it.trackingNumber == expected.trackingNumber && it.carrierCode == expected.carrierCode }
        val source = container.repository.getSources(shipment.id).single { it.accountId == accountId && it.remoteOrderId == target.orderId }
        assertEquals(order.id, source.orderId)
        assertEquals(expected.remoteId, source.remoteShipmentId)
        assertEquals(ParcelStatus.SIGNED, shipment.status)
        val events = container.repository.getEvents(shipment.id)
        assertEquals(events.size, events.map { Triple(it.time, it.text, it.status) }.toSet().size)
        expected.events.forEach { event -> assertTrue(events.any { it.time == event.time && it.text == event.text && it.status == event.status }) }
        write("tracking-saved.json", buildJsonObject {
            put("orderId", order.id); put("shipmentId", shipment.id); put("sourceId", source.id)
            put("events", events.size); put("yearBasisVerified", parsed.times.all { it.time != null && it.candidateYears.size == 1 })
        })
    }
    private suspend fun launch() {
        val component = checkNotNull(context.packageManager.getLaunchIntentForPackage(DouyinCaptureController.PACKAGE)?.component)
        val descriptor = automation.executeShellCommand("am start -n ${component.flattenToShortString()}")
        File(roundDirectory, "launch-${sequence++}.txt").writeText(ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() })
        delay(1_000)
    }
    private fun compare(name: String, scans: List<NativeScan>) {
        val root = automation.rootInActiveWindow
        val body = buildJsonObject {
            put("captureId", captureId); put("stage", phase)
            put("service", JsonArray(scans.map { platformJson.parseToJsonElement(platformJson.encodeToString(it)) }))
            if (root?.packageName?.toString() == DouyinCaptureController.PACKAGE) {
                val nodes = mutableListOf<NativeNode>()
                fun visit(node: AccessibilityNodeInfo, path: List<Int>) {
                    nodes.add(readNativeNode(node, path))
                    for (index in 0 until node.childCount) node.getChild(index)?.let { visit(it, path + index) }
                }
                visit(root, emptyList())
                val page = NativePage(DouyinCaptureController.PACKAGE, nodes, System.currentTimeMillis(), captureId,
                    accountId, root.windowId, reader = "uiAutomation")
                put("uiAutomation", platformJson.parseToJsonElement(platformJson.encodeToString(page)))
            } else put("uiAutomationReason", "当前自动化窗口没有提供官方抖音根节点")
        }
        write("comparison-$name.json", body)
    }
    suspend fun run() {
        automation
        container.ready.await()
        val account = checkNotNull(container.repository.getAccount(accountId))
        assertEquals(CaptureMode.APP_PAGE, account.captureMode)
        assertFalse(account.externalIdentity.isNullOrBlank())
        if (DouyinCaptureController.service == null) {
            context.startActivity(Intent(context, DouyinCaptureActivity::class.java).putExtra("accountId", accountId).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            context.startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        File(directory, "waiting.marker").writeText("等待服务连接")
        service = withTimeout(300_000) {
            while (DouyinCaptureController.service == null) delay(100)
            checkNotNull(DouyinCaptureController.service)
        }
        withTimeout(60_000) { while (container.syncCoordinator.hasActiveAccounts()) delay(100) }
        val beforeSettings = container.settings.get()
        File(directory, "settings.json").writeText(platformJson.encodeToString(beforeSettings))
        File(directory, "before-tables.json").writeText(snapshot())
        val beforeShipments = container.repository.getShipments()
        val targetShipmentIds = beforeShipments.filter { shipment -> container.repository.getSources(shipment.id).any {
            it.accountId == accountId && it.remoteOrderId == target.orderId
        } }.map { it.id }.toSet()
        val beforeQueue = container.repository.pendingTracking(accountId)
        var previousOrders: List<Order>? = null
        var previousLogistics: JsonElement? = null
        val rounds = mutableListOf<JsonElement>()
        for (round in 1..2) {
            captureId = "${directory.name}-round-$round"
            roundDirectory = File(directory, "round-$round").apply { check(mkdirs()) }
            sequence = 0
            withContext(Dispatchers.Main.immediate) { DouyinCaptureController.startOrdersWithEvidence(account, captureId) }
            try {
                stage("账号准备")
                withContext(Dispatchers.Main.immediate) { DouyinCaptureController.markPhase("账号") }
                launch()
                prepareMain()
                activateLabel("我")
                val identityScan = awaitPage { DouyinNativeOrders.identity(it) != null }
                assertEquals(account.externalIdentity, checkNotNull(DouyinNativeOrders.identity(identityScan)).value)

                stage("首页")
                launch()
                activateLabel("首页")
                awaitPage { matches(it, "商城").isNotEmpty() }
                stage("商城")
                activateLabel("商城")
                awaitPage { matches(it, "我的订单 按钮").size == 1 }
                stage("我的订单")
                withContext(Dispatchers.Main.immediate) { DouyinCaptureController.markPhase("订单列表") }
                activateLabel("我的订单 按钮")
                val list = awaitPage { scan -> scan.pages.any(DouyinNativeOrders::isList) &&
                    scan.pages.sumOf { page -> page.nodes.count { it.visible && it.enabled && it.text == target.title } } == 1 }
                val title = list.pages.flatMap { page -> page.nodes.filter { it.visible && it.enabled && it.text == target.title }.map { page to it } }
                if (title.size != 1) throw AppFailure("当前列表无法唯一定位目标商品", FailureKind.UNSUPPORTED_RESPONSE)
                stage("目标订单详情")
                activate(list, title.single().first, title.single().second)
                withContext(Dispatchers.Main.immediate) { DouyinCaptureController.markPhase("订单详情") }
                val detail = awaitPage { DouyinNativeOrders.detail(it, target.title) != null }
                assertEquals(target.orderId, checkNotNull(DouyinNativeOrders.detail(detail, target.title)).remoteId)
                compare("collapsed", listOf(detail))

                stage("更多信息展开")
                val expanded = matches(detail, "收起")
                if (expanded.isEmpty()) {
                    val expand = matches(detail, "展开")
                    if (expand.size != 1) throw AppFailure("当前详情无法唯一定位展开节点", FailureKind.UNSUPPORTED_RESPONSE)
                    activate(detail, expand.single().first, expand.single().second)
                }
                delay(500)
                val scans = List(2) { service.captureRefreshed(emptyList()) }
                compare("expanded", scans)
                val candidates = scans.map { DouyinNativeOrders.detail(it, target.title) }
                if (candidates.any { it?.createdAt == null })
                    throw AppFailure("展开后的服务节点未提供可关联的完整下单时间", FailureKind.UNSUPPORTED_RESPONSE)
                assertTrue(candidates.all { it?.remoteId == target.orderId })
                assertEquals(candidates.first()?.createdAt, candidates.last()?.createdAt)
                assertEquals(DouyinNativeOrders.orderTime(target.expectedCreatedAtText), candidates.last()?.createdAt)

                stage("同一订单配送依据")
                withContext(Dispatchers.Main.immediate) { DouyinCaptureController.markPhase("物流") }
                activateLabel("查看物流")
                awaitPage { DouyinNativeOrders.carrier(it) != null }
                if (tracking) verifyLogistics()
                val deadline = SystemClock.elapsedRealtime() + 10_000
                var orders = container.repository.getOrders().filter { it.accountId == accountId }
                while (orders.none { it.remoteId == target.orderId } && SystemClock.elapsedRealtime() < deadline) {
                    delay(100)
                    orders = container.repository.getOrders().filter { it.accountId == accountId }
                }
                assertTrue("需要通过实际Coordinator保存目标订单", orders.any { it.remoteId == target.orderId })
                assertEquals(orders.size, orders.map { it.remoteId }.toSet().size)
                previousOrders?.let { assertEquals(it, orders) }
                previousOrders = orders
                if (tracking) {
                    val tables = platformJson.parseToJsonElement(snapshot()).jsonObject
                    val current = buildJsonObject {
                        for (name in listOf("Shipment", "SourceRef", "TrackingEvent")) put(name, JsonArray(tables.getValue(name).jsonArray.map { row ->
                            JsonObject(row.jsonObject.filterKeys { it != "updatedAt" })
                        }))
                    }
                    previousLogistics?.let { assertEquals(it, current) }
                    previousLogistics = current
                }
                rounds.add(buildJsonObject { put("captureId", captureId); put("orders", orders.size); put("saved", true) })
            } catch (failure: AppFailure) {
                write("failure.json", buildJsonObject { put("stage", phase); put("reason", failure.message); put("kind", failure.kind.name) })
                if (withContext(Dispatchers.Main.immediate) { DouyinCaptureController.activeAccount() } != null && DouyinCaptureController.captureId == captureId)
                    compare("blocked", listOf(read()))
                throw failure
            } finally {
                withContext(NonCancellable + Dispatchers.Main.immediate) { DouyinCaptureController.stop() }
                service.awaitOrdersFinished()
                export(roundDirectory, captureId)
                File(directory, "after-tables.json").writeText(snapshot())
                assertFalse(container.syncCoordinator.hasActiveAccounts())
                assertEquals(0, service.serviceInfo.flags and AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE)
                if (!tracking) assertEquals(beforeShipments, container.repository.getShipments())
                else beforeShipments.forEach { previous ->
                    val current = checkNotNull(container.repository.getShipment(previous.id))
                    if (previous.id in targetShipmentIds) {
                        assertEquals(previous.notes, current.notes)
                        assertEquals(previous.archived, current.archived)
                        assertEquals(previous.trackingNumber, current.trackingNumber)
                        assertEquals(previous.carrierCode, current.carrierCode)
                    } else assertEquals(previous, current)
                }
                assertEquals(beforeQueue, container.repository.pendingTracking(accountId))
                assertEquals(beforeSettings, container.settings.get())
                assertEquals(account.lastSyncedAt, container.repository.getAccount(accountId)?.lastSyncedAt)
            }
        }
        for (name in listOf("cancel", "expiry")) {
            captureId = "${directory.name}-lifecycle-$name"
            roundDirectory = File(directory, "lifecycle-$name").apply { check(mkdirs()) }
            sequence = 0
            stage("采集资源释放-$name")
            withContext(Dispatchers.Main.immediate) { DouyinCaptureController.startWithEvidence(account, captureId,
                if (name == "expiry") 5_000 else 8 * 60_000L, importOrders = tracking) }
            try {
                read()
                if (tracking) {
                    withTimeout(2_000) { while (!container.syncCoordinator.hasActiveAccounts()) delay(25) }
                    withContext(Dispatchers.Main.immediate) { DouyinCaptureController.normalNavigation() }
                    assertFalse(withContext(Dispatchers.Main.immediate) { service.serviceInfo.flags and AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE != 0 })
                    withContext(Dispatchers.Main.immediate) { DouyinCaptureController.resumeCapture() }
                    assertNull(service.orderSession?.identity)
                }
                if (name == "expiry") {
                    delay(5_100)
                    assertNull(withContext(Dispatchers.Main.immediate) { DouyinCaptureController.activeAccount() })
                }
            } finally {
                withContext(NonCancellable + Dispatchers.Main.immediate) { DouyinCaptureController.stop() }
                service.awaitOrdersFinished()
                export(roundDirectory, captureId)
            }
            assertEquals(0, service.serviceInfo.flags and AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE)
            val manager = context.getSystemService(android.view.accessibility.AccessibilityManager::class.java)
            withTimeout(2_000) { while (manager.isTouchExplorationEnabled) delay(25) }
            assertFalse(container.syncCoordinator.hasActiveAccounts())
        }
        File(directory, "after-tables.json").writeText(snapshot())
        File(directory, "result.json").writeText(buildJsonObject { put("rounds", JsonArray(rounds)); put("cancelAndExpiryReleased", true) }.toString())
    }
}
