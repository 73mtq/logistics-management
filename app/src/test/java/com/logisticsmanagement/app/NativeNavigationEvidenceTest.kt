package com.logisticsmanagement.app

import com.logisticsmanagement.app.platform.*
import com.logisticsmanagement.app.model.ParcelStatus
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class NativeNavigationEvidenceTest {
    private fun evidenceRoot(): File {
        val path = System.getenv("T13_EVIDENCE_DIR")
        assumeTrue("需要本机真实T13资料目录", !path.isNullOrBlank())
        return File(checkNotNull(path)).apply { check(isDirectory) }
    }
    private fun fixtures() = platformJson.parseToJsonElement(File(evidenceRoot(), "inputs/T13-replay.json").readText()).jsonArray.map { it.jsonObject }
    private fun scan(fixture: JsonObject, name: String) = platformJson.decodeFromJsonElement<NativeScan>(fixture.getValue(name))
    private fun report(name: String, value: JsonElement) {
        val directory = File(evidenceRoot(), "jev-validation").apply { check(isDirectory || mkdirs()) }
        File(directory, name).writeText(value.toString())
    }

    @Test fun realListsAndActionBoundaries() {
        val results = buildJsonArray {
            for ((index, fixture) in fixtures().withIndex()) {
                val listing = scan(fixture, "list")
                val target = fixture.getValue("target").jsonObject
                val cards = NativePaginationRules.cards(listing)
                assertTrue("真实列表至少提供两个商品区域", cards.size >= 2)
                assertEquals(1, cards.count { it.node.text == target.getValue("title").jsonPrimitive.content })
                assertTrue(cards.all { it.row != 0 && it.row != 3 })
                val page = NativePaginationRules.activePages(listing).single()
                for (card in cards) {
                    assertEquals(NativeActionPurpose.ORDER_DETAIL, NativeActionPolicy.purpose(page, card.node))
                    assertNull(NativeActionPolicy.rejection(page, card.node))
                    val targetNode = page.nodes.filter { NativeActionPolicy.descendant(card.node.path, it.path) && 16 in it.actions &&
                        NativeActionPolicy.rejection(page, card.node, it) == null }.maxByOrNull { it.path.size }
                    assertNotNull("真实商品入口应提供范围内可点击父节点", targetNode)
                }
                val transactionNodes = page.nodes.filter { it.visible && NativeActionPolicy.forbidden(it) }
                assertTrue(transactionNodes.isNotEmpty())
                transactionNodes.forEach { assertNotNull(NativeActionPolicy.rejection(page, it)) }
                val collapsed = scan(fixture, "collapsed")
                val expands = NativePaginationRules.activePages(collapsed).flatMap { p -> p.nodes.filter {
                    it.visible && DouyinNativeOrders.label(it) == "展开"
                }.map { p to it } }
                assertTrue(expands.isNotEmpty())
                assertTrue("详情更多信息展开应具有同区域依据", expands.any { NativeActionPolicy.purpose(it.first, it.second) == NativeActionPurpose.ORDER_INFORMATION })
                add(buildJsonObject { put("fixture", index); put("cards", cards.size); put("transactionNodesRejected", transactionNodes.size); put("informationExpands", expands.size) })
            }
        }
        report("rules-results.json", results)
    }

    @Test fun allRecordedUnsafeActionsRejected() {
        val root = File(evidenceRoot(), "collected-T13-continuous-20261016-03/files/douyin-order-evidence/T13-continuous-20261016-03")
        var transactions = 0
        var recommendations = 0
        val results = buildJsonArray {
            for (round in checkNotNull(root.listFiles()).filter { it.isDirectory }) {
                val scans = checkNotNull(round.listFiles()).filter { it.name.startsWith("scan-") && it.extension == "json" }
                    .map { platformJson.decodeFromString<NativeScan>(it.readText()) }
                for (file in checkNotNull(round.listFiles()).filter { it.name.startsWith("action-") && it.extension == "json" }) {
                    val action = platformJson.parseToJsonElement(file.readText()).jsonObject
                    val anchor = platformJson.decodeFromJsonElement<NativeNode>(action.getValue("target"))
                    val unsafe = NativeActionPolicy.forbidden(anchor)
                    val recommendation = DouyinNativeOrders.textValues(anchor).any { (_, text) -> text.contains("好评率") || text.contains("搜索") }
                    if (!unsafe && !recommendation) continue
                    val timestamp = action.getValue("capturedAt").jsonPrimitive.long
                    val matching = scans.filter { it.capturedAt <= timestamp }.sortedByDescending { it.capturedAt }.firstNotNullOfOrNull { value ->
                        NativePaginationRules.activePages(value).firstOrNull { page -> page.nodes.any { it.path == anchor.path && it.text == anchor.text && it.description == anchor.description } }
                    }
                    assertNotNull("动作需要对应真实页面", matching)
                    assertNotNull(NativeActionPolicy.rejection(checkNotNull(matching), anchor))
                    if (unsafe) transactions++ else recommendations++
                    add(buildJsonObject { put("round", round.name); put("action", file.name); put("transaction", unsafe); put("rejected", true) })
                }
            }
        }
        assertTrue(transactions > 0)
        assertTrue(recommendations > 0)
        report("unsafe-action-results.json", results)
    }

    @Test fun realHistoryViewportAndPrivacy() {
        val root = File(evidenceRoot(), "collected-T13-continuous-20261016-03/files/douyin-order-evidence/T13-continuous-20261016-03")
        val viewports = mutableMapOf<String, MutableSet<String>>()
        var lists = 0
        var cards = 0
        for (file in root.walkTopDown().filter { it.name.startsWith("scan-") && it.extension == "json" }) {
            val value = platformJson.decodeFromString<NativeScan>(file.readText())
            val candidates = NativePaginationRules.cards(value)
            if (candidates.isEmpty()) continue
            lists++; cards += candidates.size
            val signature = candidates.joinToString("|") { "${it.row}:${it.node.text}" }
            viewports.getOrPut(signature) { mutableSetOf() }.add(NativePaginationRules.viewport(candidates))
            val state = NativeSemanticJudge.state(value, candidates.map { it.page to it.node }).toString()
            assertFalse(state.contains(value.accountId))
            assertFalse(state.contains(value.captureId))
            candidates.filter { it.node.text.length >= 6 }.forEach { assertFalse(state.contains(it.node.text)) }
        }
        assertTrue(lists > 1)
        assertTrue("相同商品区域不能被推荐变化重置", viewports.values.all { it.size == 1 })
        report("history-results.json", buildJsonObject { put("lists", lists); put("cards", cards); put("stableViews", viewports.size) })
    }

    @Test fun realJevRequestsAndCache(): Unit = runBlocking {
        val key = System.getenv("TYPESAFE_API_KEY")
        assumeTrue("需要有效Jev凭据", !key.isNullOrBlank())
        val records = mutableListOf<JsonObject>()
        val judge = NativeSemanticJudge(checkNotNull(key)) { records.add(it) }
        judge.checkConnection()
        for (fixture in fixtures()) {
            val listing = scan(fixture, "list")
            val page = NativePaginationRules.activePages(listing).single()
            val cards = NativePaginationRules.cards(listing)
            val transaction = page.nodes.first { it.visible && DouyinNativeOrders.label(it) == "确认收货" }
            val candidates = cards.map { it.page to it.node } + (page to transaction)
            val decision = judge.assess(listing, candidates)
            report("jev-responses.json", JsonArray(records))
            assertEquals("ORDER_LIST", decision.page)
            assertEquals("TRANSACTION", decision.roles.getValue("c${cards.size}"))
            assertFalse(judge.accepted(decision, cards.size, NativeActionPurpose.ORDER_DETAIL))
            for (index in cards.indices) {
                if (judge.accepted(decision, index, NativeActionPurpose.ORDER_DETAIL)) assertEquals("PRODUCT", decision.roles.getValue("c$index"))
            }
            val targetIndex = cards.indexOfFirst { it.node.text == fixture.getValue("target").jsonObject.getValue("title").jsonPrimitive.content }
            assertTrue(targetIndex >= 0)
            assertTrue("真实目标商品入口应通过Jev采用门槛", judge.accepted(decision, targetIndex, NativeActionPurpose.ORDER_DETAIL))
            val requests = judge.requests
            assertEquals(decision, judge.assess(listing, candidates))
            assertEquals(requests, judge.requests)
        }
        assertTrue(judge.requests in 1..2)
        report("jev-responses.json", JsonArray(records))
    }

    @Test fun cancelRealJevRequest(): Unit = runBlocking {
        val key = System.getenv("TYPESAFE_API_KEY")
        assumeTrue("需要有效Jev凭据", !key.isNullOrBlank())
        val fixture = fixtures().first()
        val listing = scan(fixture, "list")
        val judge = NativeSemanticJudge(checkNotNull(key))
        val cards = NativePaginationRules.cards(listing)
        val started = System.nanoTime()
        val task = launch(start = CoroutineStart.UNDISPATCHED) { judge.assess(listing, cards.map { it.page to it.node }) }
        assertEquals(1, judge.requests)
        task.cancelAndJoin()
        assertTrue(task.isCancelled)
        withTimeout(2_000) { while (judge.inFlightRequests != 0) delay(10) }
        assertTrue((System.nanoTime() - started) / 1_000_000 < 10_000)
        judge.close()
        report("cancel-result.json", buildJsonObject { put("requestsStarted", judge.requests); put("cancelled", task.isCancelled); put("elapsedMillis", (System.nanoTime() - started) / 1_000_000) })
    }

    @Test fun realFixedFlowJudgments(): Unit = runBlocking {
        val key = System.getenv("TYPESAFE_API_KEY")
        assumeTrue("需要有效Jev凭据", !key.isNullOrBlank())
        val root = File(evidenceRoot().parentFile, "T12/collected-T12-fixed-20261016-02/files/douyin-order-evidence/T12-fixed-20261016-02/round-1")
        val files = checkNotNull(root.listFiles())
        val scans = files.filter { it.name.startsWith("read-") && it.extension == "json" }.map { platformJson.decodeFromString<NativeScan>(it.readText()) }
        val seen = mutableSetOf<NativeActionPurpose>()
        val records = mutableListOf<JsonObject>()
        val outcomes = mutableListOf<JsonElement>()
        val judge = NativeSemanticJudge(checkNotNull(key)) { records.add(it); report("fixed-flow-requests.json", JsonArray(records)) }
        for (file in files.filter { it.name.startsWith("action-") && it.extension == "json" }.sortedBy { it.name }) {
            val action = platformJson.parseToJsonElement(file.readText()).jsonObject
            if (!action.getValue("accepted").jsonPrimitive.boolean) continue
            val target = platformJson.decodeFromJsonElement<NativeNode>(action.getValue("target"))
            val timestamp = action.getValue("capturedAt").jsonPrimitive.long
            val source = scans.filter { it.capturedAt <= timestamp }.sortedByDescending { it.capturedAt }.firstNotNullOfOrNull { scan ->
                NativePaginationRules.activePages(scan).firstOrNull { page -> page.nodes.any { it.path == target.path && it.text == target.text && it.description == target.description && it.bounds == target.bounds } }?.let { scan to it }
            } ?: continue
            val (scan, page) = source
            val node = page.nodes.single { it.path == target.path }
            val purpose = NativeActionPolicy.purpose(page, node)
            if (purpose == NativeActionPurpose.UNKNOWN || !seen.add(purpose)) continue
            assertNull("当前真实读取动作应符合程序范围：${file.name}", NativeActionPolicy.rejection(page, node))
            val clickable = page.nodes.filter { NativeActionPolicy.descendant(node.path, it.path) && it.visible && it.enabled && 16 in it.actions }.maxByOrNull { it.path.size }
            if (clickable != null) assertNull("实际父节点需要符合读取范围：${file.name}", NativeActionPolicy.rejection(page, node, clickable))
            val decision = judge.assess(scan, listOf(page to node))
            val accepted = judge.accepted(decision, 0, purpose)
            outcomes.add(buildJsonObject { put("action", file.name); put("purpose", purpose.name); put("page", decision.page)
                put("role", checkNotNull(decision.roles["c0"])); put("probability", decision.probabilities.getValue("c0")); put("accepted", accepted)
                put("semanticRequired", NativeActionPolicy.requiresSemantic(purpose)) })
            report("fixed-flow-results.json", JsonArray(outcomes))
            if (accepted) assertEquals(NativeSemanticJudge.expected(purpose), decision.roles.getValue("c0"))
            if (NativeActionPolicy.requiresSemantic(purpose)) assertTrue("真实语义候选需要达到采用门槛：${file.name}", accepted)
        }
        assertTrue(seen.containsAll(setOf(NativeActionPurpose.ACCOUNT, NativeActionPurpose.HOME, NativeActionPurpose.MALL,
            NativeActionPurpose.ORDERS, NativeActionPurpose.ORDER_DETAIL, NativeActionPurpose.ORDER_INFORMATION, NativeActionPurpose.LOGISTICS)))
        assertTrue(outcomes.any { it.jsonObject.getValue("accepted").jsonPrimitive.boolean })
    }

    @Test fun realCompletedOrderStatus() {
        val root = File(evidenceRoot(), "collected-T13-Jev-device-20261016-03/files/douyin-order-evidence/T13-Jev-device-20261016-03/round-2")
        assumeTrue("需要本轮正式采集的订单详情", root.isDirectory)
        var checked = 0
        for (file in checkNotNull(root.listFiles()).filter { it.name.startsWith("scan-") && it.extension == "json" }) {
            val scan = platformJson.decodeFromString<NativeScan>(file.readText())
            val pages = NativePaginationRules.activePages(scan)
            if (pages.none { page -> page.nodes.any { it.visible && DouyinNativeOrders.label(it) == "订单编号" } &&
                page.nodes.any { it.visible && DouyinNativeOrders.label(it) == "交易完成" } }) continue
            val candidate = checkNotNull(DouyinNativeOrders.detail(scan, null))
            assertEquals(ParcelStatus.SIGNED, candidate.status)
            assertTrue(candidate.fields.any { it.name == "status" && it.value == "交易完成" })
            checked++
        }
        assertTrue(checked > 0)
        report("completed-status-results.json", buildJsonObject { put("realDetailsChecked", checked); put("status", "SIGNED") })
    }
}
