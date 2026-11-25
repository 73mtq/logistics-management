package com.logisticsmanagement.app.platform

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import com.logisticsmanagement.app.model.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.security.MessageDigest

internal data class NativeListCard(val page: NativePage, val node: NativeNode, val row: Int, val fingerprint: String)

internal object NativePaginationRules {
    private val statuses get() = DouyinNativeOrders.ORDER_STATUSES
    fun activePages(scan: NativeScan) = scan.pages.filter { page -> scan.windows.any { it.id == page.windowId && it.active && it.accepted } }
    fun verticalList(page: NativePage) = page.nodes.filter { it.visible && it.enabled && it.scrollable &&
        !it.className.contains("Horizontal") && it.bounds[2] > it.bounds[0] && it.bounds[3] > it.bounds[1] }
        .firstOrNull { it.className.contains("RecyclerView") } ?: page.nodes.firstOrNull {
            it.visible && it.enabled && it.scrollable && !it.className.contains("Horizontal") && it.bounds[2] > it.bounds[0] && it.bounds[3] > it.bounds[1] }
    fun cards(scan: NativeScan): List<NativeListCard> = activePages(scan).flatMap(::cardsOnPage)
    fun cardsOnPage(page: NativePage): List<NativeListCard> {
        if (!DouyinNativeOrders.isList(page)) return emptyList()
        val list = verticalList(page) ?: return emptyList()
        val rows = page.nodes.filter { it.path.size > list.path.size && it.path.take(list.path.size) == list.path }
            .groupBy { it.path[list.path.size] }.toSortedMap()
        return rows.mapNotNull { (row, nodes) ->
                val followsHeader = rows[row - 1].orEmpty().any { it.visible && DouyinNativeOrders.label(it) in statuses }
                if (!followsHeader || nodes.any { it.visible && DouyinNativeOrders.label(it) in statuses }) return@mapNotNull null
                val images = nodes.filter { it.visible && it.className.contains("Image") && it.bounds.size == 4 &&
                    it.bounds[2] - it.bounds[0] >= 80 && it.bounds[3] - it.bounds[1] >= 80 }
                val title = nodes.filter { it.visible && it.enabled && it.childCount == 0 && it.text.length in 2..200 &&
                    it.className.contains("Text") && !NativeActionPolicy.forbidden(it) &&
                    !it.text.matches(Regex("[0-9\\s.,¥￥xX×-]+")) &&
                    images.any { image -> it.bounds[0] >= image.bounds[2] &&
                        kotlin.math.abs(it.bounds[1] - image.bounds[1]) <= 24 } }
                    .sortedWith(compareBy<NativeNode> { it.bounds[1] }.thenByDescending { it.text.length }).firstOrNull() ?: return@mapNotNull null
                val header = rows[row - 1].orEmpty().filter { it.visible }.joinToString("\u0000") { it.text + it.description }
                NativeListCard(page, title, row, digest(header + "\u0000" + title.text + "\u0000" + title.description))
            }
    }
    fun viewport(cards: List<NativeListCard>) = digest(cards.joinToString("\u0000") { "${it.row}:${it.fingerprint}" })
    fun allSelected(scan: NativeScan): Boolean = activePages(scan).any { page ->
        page.nodes.filter { it.visible && DouyinNativeOrders.label(it) == "全部" }.any { label ->
            page.nodes.any { node -> node.path.size >= label.path.size - 3 && node.path.size <= label.path.size &&
                label.path.take(node.path.size) == node.path && (node.selected || DouyinNativeOrders.textValues(node).any { (_, text) -> text in setOf("已选中", "全部，已选中", "全部 已选中") }) }
        }
    }
    fun terminal(scan: NativeScan): NativeFieldEvidence? {
        val labels = setOf("没有更多订单", "没有更多订单了", "没有更多订单信息", "没有更多了", "没有更多内容了", "暂无订单", "暂无相关订单", "到底了", "已经到底了")
        return activePages(scan).filter(DouyinNativeOrders::isList).firstNotNullOfOrNull { page ->
            page.nodes.firstOrNull { it.visible && DouyinNativeOrders.label(it) in labels }?.let {
                NativeFieldEvidence("listEnd", DouyinNativeOrders.label(it), page.windowId, it.path, scan.sequence)
            }
        }
    }
    private fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}

internal class NativePaginationRunner(private val service: DouyinCaptureService, private val account: Account,
    private val session: NativeOrderImportSession, private val focusOrderId: String? = null) {
    private class NavigationRestart : Exception()
    private val progress = checkNotNull(session.runProgress)
    private val captureId = session.captureId
    private var focusTitle: String? = null
    private var judge: NativeSemanticJudge? = null
    private var foregroundPackage = ""
    private var foregroundSince = 0L
    private suspend fun step(label: String) {
        progress.step(label)
        DouyinCaptureController.report(progress.value.message())
    }
    private suspend fun read(): NativeScan {
        if (DouyinCaptureController.navigationMode) {
            while (DouyinCaptureController.navigationMode && DouyinCaptureController.activeAccount() != null) delay(100)
            throw NavigationRestart()
        }
        check(DouyinCaptureController.captureId == captureId)
        val scan = service.captureRefreshed(emptyList())
        val applied = withTimeoutOrNull(15_000) { while (session.acceptedSequence < scan.sequence) delay(25); true }
        if (applied != true) throw AppFailure("当前采集资料尚未完成处理", FailureKind.UNSUPPORTED_RESPONSE)
        val foreground = service.rootInActiveWindow?.packageName?.toString().orEmpty()
        if (foreground == DouyinCaptureController.PACKAGE) {
            foregroundPackage = foreground
            foregroundSince = 0L
        } else if (foreground != foregroundPackage) {
            foregroundPackage = foreground
            foregroundSince = SystemClock.elapsedRealtime()
        } else if (SystemClock.elapsedRealtime() - foregroundSince > 10_000) {
            throw AppFailure("前台被其他应用占据：${foreground.ifBlank { "未知" }}", FailureKind.UNSUPPORTED_RESPONSE)
        }
        return scan
    }
    private suspend fun awaitPage(condition: (NativeScan) -> Boolean): NativeScan {
        val deadline = SystemClock.elapsedRealtime() + 30_000
        do {
            val scan = read()
            if (condition(scan)) return scan
            delay(350)
        } while (SystemClock.elapsedRealtime() < deadline)
        throw AppFailure("当前页面未提供所需节点：${progress.value.step}", FailureKind.UNSUPPORTED_RESPONSE)
    }
    private suspend fun ensureOfficialApp() {
        if (service.rootInActiveWindow?.packageName?.toString() == DouyinCaptureController.PACKAGE) return
        val launch = service.packageManager.getLaunchIntentForPackage(DouyinCaptureController.PACKAGE)
            ?: throw AppFailure("请安装抖音官方App", FailureKind.CONFIGURATION)
        try {
            service.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (failure: RuntimeException) {
            throw AppFailure("无法启动抖音：${failure.message}", FailureKind.CONFIGURATION)
        }
        val deadline = SystemClock.elapsedRealtime() + 12_000
        do {
            if (service.rootInActiveWindow?.packageName?.toString() == DouyinCaptureController.PACKAGE) return
            delay(300)
        } while (SystemClock.elapsedRealtime() < deadline)
        val current = service.rootInActiveWindow?.packageName?.toString().orEmpty()
        throw AppFailure("抖音没有进入前台，当前前台：${current.ifBlank { "未知" }}", FailureKind.UNSUPPORTED_RESPONSE)
    }
    private suspend fun awaitMaybe(timeoutMillis: Long, condition: (NativeScan) -> Boolean): NativeScan? {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        do {
            val scan = read()
            if (condition(scan)) return scan
            delay(300)
        } while (SystemClock.elapsedRealtime() < deadline)
        return null
    }
    private suspend fun ocrTap(label: String) {
        repeat(2) { attempt ->
            val tap = service.tapScreenText(label)
            if (tap.accepted) return
            if (attempt == 0) delay(1_500)
            else throw AppFailure("物流入口无法点击：${tap.reason}", FailureKind.UNSUPPORTED_RESPONSE)
        }
    }
    private fun anyPages(scan: NativeScan) = scan.pages.filter { page -> scan.windows.any { it.id == page.windowId && it.accepted } }
    private fun anyLabel(scan: NativeScan, label: String): Pair<NativePage, NativeNode>? = anyPages(scan)
        .flatMap { page -> page.nodes.filter { it.enabled && it.bounds.size == 4 && DouyinNativeOrders.label(it) == label }.map { page to it } }
        .singleOrNull()
    private suspend fun awaitAnyLabel(timeoutMillis: Long, label: String): Pair<NativePage, NativeNode>? {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        do {
            val scan = read()
            anyLabel(scan, label)?.let { return it }
            delay(300)
        } while (SystemClock.elapsedRealtime() < deadline)
        return null
    }
    private fun matches(scan: NativeScan, label: String) = NativePaginationRules.activePages(scan).flatMap { page ->
        page.nodes.filter { it.visible && it.enabled && (it.text == label || it.description == label) }.map { page to it }
    }
    private suspend fun activate(page: NativePage, node: NativeNode, intended: NativeActionPurpose = NativeActionPolicy.purpose(page, node)) {
        var currentPage = page
        var currentNode = node
        repeat(5) { attempt ->
            val scan = read()
            val fresh = NativePaginationRules.activePages(scan).singleOrNull { it.windowId == currentPage.windowId }
            val resolved = fresh?.nodes?.singleOrNull { it.path == currentNode.path && it.text == currentNode.text &&
                it.description == currentNode.description && it.bounds == currentNode.bounds }
                ?: fresh?.nodes?.singleOrNull { it.visible && it.enabled && it.text == currentNode.text &&
                    it.description == currentNode.description && it.bounds.size == 4 && currentNode.bounds.size == 4 &&
                    kotlin.math.abs(it.bounds[1] - currentNode.bounds[1]) <= 40 && kotlin.math.abs(it.bounds[0] - currentNode.bounds[0]) <= 40 }
            if (fresh == null || resolved == null) {
                if (attempt == 4) throw AppFailure("动作执行前目标页面已经变化：${progress.value.step}，目标 ${DouyinNativeOrders.label(currentNode)}", FailureKind.UNSUPPORTED_RESPONSE)
                delay(500)
                return@repeat
            }
            currentPage = fresh; currentNode = resolved
            val purpose = intended
            NativeActionPolicy.rejection(currentPage, currentNode, intended = purpose)?.let {
                throw AppFailure(it, FailureKind.UNSUPPORTED_RESPONSE)
            }
            judge?.takeIf { NativeActionPolicy.requiresSemantic(purpose) }?.let { semantic ->
                val candidates = if (purpose == NativeActionPurpose.ORDER_DETAIL) NativePaginationRules.cards(scan).map { it.page to it.node }
                    else listOf(currentPage to currentNode)
                val index = candidates.indexOfFirst { it.first == currentPage && it.second == currentNode }
                check(index >= 0)
                val decision = semantic.assess(scan, candidates)
                if (!semantic.accepted(decision, index, purpose)) {
                    progress.blocked("semantic-navigation", "Jev尚未确认当前页面或入口，请正常触摸导航后恢复采集")
                    DouyinCaptureController.normalNavigation()
                    read()
                }
                progress.resolved("semantic-navigation")
            }
            if (DouyinCaptureController.navigationMode) read()
            val result = service.activateChecked(currentPage, currentNode, purpose)
            if (result.accepted) { delay(700); return }
            if (result.reason !in setOf("当前节点路径已经变化", "目标节点文字或范围已经变化", "当前活动窗口已经变化"))
                throw AppFailure("当前节点操作未完成：${result.reason}", FailureKind.UNSUPPORTED_RESPONSE)
            val retryScan = read()
            val found = NativePaginationRules.activePages(retryScan).flatMap { candidate -> candidate.nodes.filter {
                it.visible && it.enabled && it.text == node.text && it.description == node.description && it.className == node.className && it.resourceId == node.resourceId
            }.map { candidate to it } }
            if (found.size != 1) throw AppFailure("当前页面无法重新唯一定位目标节点", FailureKind.UNSUPPORTED_RESPONSE)
            currentPage = found.single().first; currentNode = found.single().second
        }
        throw AppFailure("当前页面目标节点持续变化", FailureKind.UNSUPPORTED_RESPONSE)
    }
    private suspend fun activate(label: String, purpose: NativeActionPurpose) {
        val scan = awaitPage { matches(it, label).size == 1 }
        val value = matches(scan, label).single()
        activate(value.first, value.second, purpose)
    }
    private suspend fun back() {
        val scan = read()
        val active = scan.windows.singleOrNull { it.active && it.accepted }
            ?: throw AppFailure("返回前没有官方活动窗口", FailureKind.UNSUPPORTED_RESPONSE)
        if (service.rootInActiveWindow?.windowId != active.id || service.rootInActiveWindow?.packageName?.toString() != DouyinCaptureController.PACKAGE)
            throw AppFailure("返回前官方活动窗口已经变化", FailureKind.UNSUPPORTED_RESPONSE)
        if (!service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)) throw AppFailure("当前官方页面无法返回", FailureKind.UNSUPPORTED_RESPONSE)
        delay(700)
    }
    private suspend fun returnList(): NativeScan {
        repeat(8) {
            val scan = read()
            if (NativePaginationRules.activePages(scan).any(DouyinNativeOrders::isList)) return scan
            back()
        }
        throw AppFailure("无法返回当前账号的订单列表", FailureKind.UNSUPPORTED_RESPONSE)
    }
    suspend fun run() {
        val container = (service.application as com.logisticsmanagement.app.LogisticsApplication).container
        focusTitle = focusOrderId?.let { id -> container.repository.getOrders().firstOrNull { it.remoteId == id }?.title }
        if (container.settings.get().nativeJevEnabled) {
            val key = container.credentials.getSecret(NativeSemanticJudge.CREDENTIAL_NAME)?.takeIf { it.isNotBlank() }
                ?: throw AppFailure("请在设置中配置Jev API key", FailureKind.CONFIGURATION)
            judge = NativeSemanticJudge(key) { record -> service.paginationEvidence(buildJsonObject {
                put("action", "JEV_DECISION"); put("captureId", captureId); put("record", record)
            }) }
        }
        try {
            while (DouyinCaptureController.activeAccount() != null) {
                try { traverse(); return } catch (restart: NavigationRestart) { step("正常触摸结束，重新核查账号") }
            }
        } finally { withContext(NonCancellable + Dispatchers.IO) { judge?.close() } }
    }
    private suspend fun traverse() {
        step("订单入口")
        ensureOfficialApp()
        awaitPage { NativePaginationRules.activePages(it).isNotEmpty() }
        var home = false
        repeat(5) {
            if (!home) {
                val scan = read()
                home = matches(scan, "商城").isNotEmpty() || matches(scan, "我").isNotEmpty()
                if (!home) back()
            }
        }
        if (!home) throw AppFailure("无法回到抖音首页", FailureKind.UNSUPPORTED_RESPONSE)
        val known = account.externalIdentity
        if (known != null) {
            session.knownIdentity(known)
        } else {
            var main = false
            repeat(8) {
                if (!main) {
                    val scan = read()
                    if (matches(scan, "我").size == 1) main = true else back()
                }
            }
            if (!main) throw AppFailure("无法定位官方账号入口", FailureKind.UNSUPPORTED_RESPONSE)
            DouyinCaptureController.markPhase("账号")
            activate("我", NativeActionPurpose.ACCOUNT)
            awaitPage { DouyinNativeOrders.identity(it) != null && session.identity != null }
            step("首页")
            activate("首页", NativeActionPurpose.HOME)
        }
        step("商城")
        activate("商城", NativeActionPurpose.MALL)
        step("我的订单")
        val entry = awaitPage { scan -> matches(scan, "我的订单 按钮").size == 1 || matches(scan, "我的订单").size == 1 ||
            NativePaginationRules.activePages(scan).any(DouyinNativeOrders::isList) }
        DouyinCaptureController.markPhase("订单列表")
        if (NativePaginationRules.activePages(entry).none(DouyinNativeOrders::isList)) {
            val preferred = matches(entry, "我的订单 按钮")
            val targets = if (preferred.isNotEmpty()) preferred else matches(entry, "我的订单")
            if (targets.size != 1) throw AppFailure("商城订单入口无法唯一确认", FailureKind.UNSUPPORTED_RESPONSE)
            val target = targets.single()
            activate(target.first, target.second, NativeActionPurpose.ORDERS)
            awaitPage { NativePaginationRules.activePages(it).any(DouyinNativeOrders::isList) }
        }
        activate("全部", NativeActionPurpose.ALL)
        var list = awaitPage { NativePaginationRules.activePages(it).any(DouyinNativeOrders::isList) }
        repeat(3) {
            val page = NativePaginationRules.activePages(list).singleOrNull { DouyinNativeOrders.isList(it) } ?: return@repeat
            val node = NativePaginationRules.verticalList(page) ?: return@repeat
            service.scrollList(page, node, forward = false)
            delay(1_000)
            list = read()
        }
        list = read()
        if (NativePaginationRules.allSelected(list)) { progress.confirmScope(); progress.resolved("scope-selection") }
        else progress.blocked("scope-selection", "全部标签的选中状态没有提供，完整范围尚未确认")
        val focusId = focusOrderId
        val focusName = focusTitle
        if (focusId != null && focusName != null) {
            var foundPage: NativePage? = null
            var foundNode: NativeNode? = null
            var attempt = 0
            while (foundNode == null && attempt < 6) {
                val pages = NativePaginationRules.activePages(list).filter(DouyinNativeOrders::isList)
                val page = pages.singleOrNull() ?: pages.firstOrNull()
                val node = page?.nodes?.firstOrNull { it.bounds.size == 4 && it.bounds[3] > it.bounds[1] &&
                    DouyinNativeOrders.textValues(it).any { (_, value) -> sameTitle(value, focusName) } }
                if (page != null && node != null) {
                    foundPage = page
                    foundNode = node
                    break
                }
                val listNode = page?.let(NativePaginationRules::verticalList)
                if (page == null || listNode == null) break
                service.scrollList(page, listNode, forward = true)
                delay(700)
                list = read()
                attempt++
            }
            service.paginationEvidence(buildJsonObject {
                put("captureId", captureId); put("action", "FOCUS_LOOKUP"); put("focusTitle", focusName)
                put("matched", foundNode != null); put("lists", NativePaginationRules.activePages(list).count(DouyinNativeOrders::isList))
                put("labels", JsonArray(NativePaginationRules.activePages(list).filter(DouyinNativeOrders::isList)
                    .flatMap { page -> page.nodes.filter { it.visible }.map(DouyinNativeOrders::label) }
                    .filter(String::isNotBlank).distinct().take(20).map(::JsonPrimitive)))
            })
            val page = foundPage
            val node = foundNode
            if (page != null && node != null) {
                step("读取目标订单物流")
                readCardLogistics(NativeListCard(page, node, 0, ""), focusId)
                DouyinCaptureController.markPhase("订单列表")
                list = returnList()
            }
        }
        val visited = mutableSetOf<String>()
        var scrollPage = 0
        var unchanged = 0
        while (true) {
            val cards = NativePaginationRules.cards(list)
            val viewport = NativePaginationRules.viewport(cards)
            val next = cards.firstOrNull { "$scrollPage:${it.row}:${it.fingerprint}" !in visited }
            if (next != null) {
                val key = "$scrollPage:${next.row}:${next.fingerprint}"
                val focus = focusOrderId
                val title = focusTitle
                if (focus != null && title != null && sameTitle(next.node.text, title)) {
                    step("读取目标订单物流，已核查 ${progress.value.orders.size} 笔")
                    readCardLogistics(next, focus)
                    visited.add(key)
                    DouyinCaptureController.markPhase("订单列表")
                    list = returnList()
                    continue
                }
                step("读取订单详情，已核查 ${progress.value.orders.size} 笔")
                try {
                    DouyinCaptureController.markPhase("订单详情")
                    activate(next.page, next.node, NativeActionPurpose.ORDER_DETAIL)
                    val detail = awaitPage { DouyinNativeOrders.detail(it, next.node.text) != null }
                    val candidate = checkNotNull(DouyinNativeOrders.detail(detail, next.node.text))
                    service.paginationEvidence(buildJsonObject {
                        put("captureId", captureId); put("action", "DETAIL_IDENTIFIED"); put("orderId", candidate.remoteId)
                        put("card", key); put("sequence", detail.sequence)
                    })
                    progress.resolved(key)
                    if (progress.value.orders[candidate.remoteId]?.finished != true) readOrder(detail)
                } catch (failure: AppFailure) {
                    if (failure.kind != FailureKind.UNSUPPORTED_RESPONSE || judge != null) throw failure
                    progress.blocked(key, checkNotNull(failure.message))
                    service.paginationEvidence(buildJsonObject { put("captureId", captureId); put("action", "CARD_BLOCKED"); put("card", key); put("reason", failure.message) })
                }
                visited.add(key)
                DouyinCaptureController.markPhase("订单列表")
                list = returnList()
                continue
            }
            NativePaginationRules.terminal(list)?.let {
                progress.resolved("list-unchanged")
                progress.ended(it)
                return
            }
            step("读取后续订单")
            val page = NativePaginationRules.activePages(list).singleOrNull { DouyinNativeOrders.isList(it) }
                ?: throw AppFailure("当前没有唯一的官方订单列表", FailureKind.UNSUPPORTED_RESPONSE)
            val node = NativePaginationRules.verticalList(page)
            if (node == null || AccessibilityNodeInfo.ACTION_SCROLL_FORWARD !in node.actions) {
                step("列表没有可用滚动动作，请正常触摸滚动后恢复采集")
                DouyinCaptureController.normalNavigation()
                read()
                return
            }
            val root = service.rootInActiveWindow
            if (root?.windowId != page.windowId || root.packageName?.toString() != DouyinCaptureController.PACKAGE)
                throw AppFailure("滚动前官方活动窗口已经变化", FailureKind.UNSUPPORTED_RESPONSE)
            val result = service.performChecked(page, node, AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            delay(700)
            val after = read()
            val changed = NativePaginationRules.viewport(NativePaginationRules.cards(after)) != viewport
            service.paginationEvidence(buildJsonObject {
                put("captureId", captureId); put("action", "SCROLL"); put("beforeSequence", list.sequence); put("afterSequence", after.sequence)
                put("beforeViewport", viewport); put("afterViewport", NativePaginationRules.viewport(NativePaginationRules.cards(after)))
                put("accepted", result.accepted); put("reason", result.reason); put("changed", changed)
            })
            if (!result.accepted) {
                progress.blocked("list-scroll", "平台未接受列表滚动，请正常触摸导航后恢复采集")
                DouyinCaptureController.normalNavigation()
                read()
            }
            progress.resolved("list-scroll")
            unchanged = if (changed) 0 else unchanged + 1
            if (changed) scrollPage++
            list = after
            if (unchanged >= 2 && NativePaginationRules.terminal(list) == null) {
                progress.blocked("list-unchanged", "列表未变化且没有平台结束标志，请继续核查")
                return
            }
        }
    }
    private fun sameTitle(card: String, known: String): Boolean {
        fun normalize(value: String) = value.filterNot { it == '\uFEFF' || it.isWhitespace() }.trimEnd('…', '.', '。')
        val left = normalize(card)
        val right = normalize(known)
        return left.isNotEmpty() && right.isNotEmpty() && left.take(10) == right.take(10)
    }
    private fun cardMore(card: NativeListCard): NativeNode? {
        val top = card.node.bounds.getOrNull(1) ?: return null
        return card.page.nodes.filter { it.enabled && DouyinNativeOrders.label(it) == "更多" && it.bounds.size == 4 }
            .filter { it.bounds[1] > top + 100 && it.bounds[1] - top <= 1_000 }
            .minByOrNull { it.bounds[1] }
    }
    private suspend fun readCardLogistics(card: NativeListCard, orderId: String) {
        val more = cardMore(card) ?: throw AppFailure("目标卡片没有“更多”入口", FailureKind.UNSUPPORTED_RESPONSE)
        activate(card.page, more, NativeActionPurpose.LOGISTICS_MENU)
        (service.application as com.logisticsmanagement.app.LogisticsApplication).container.repository.getOrders()
            .firstOrNull { it.remoteId == orderId }?.let { session.registerOrder(it) }
        session.prepareOcrLogistics(orderId)
        val entry = awaitAnyLabel(5_000, "查看物流")
        if (entry != null) service.tapNode(entry.first, entry.second) else ocrTap("查看物流")
        val page = awaitLogistics()
        if (page != null) readNodeLogistics(page) else readOcrLogistics()
    }
    private suspend fun readOrder(initial: NativeScan) {
        var scan = initial
        if (matches(scan, "更多信息").isNotEmpty() && matches(scan, "展开").size == 1) {
            val expand = matches(scan, "展开").single()
            activate(expand.first, expand.second, NativeActionPurpose.ORDER_INFORMATION)
        }
        read(); scan = read()
        val candidate = session.currentCandidate() ?: return
        if (candidate.excluded || candidate.remoteId in session.excluded || candidate.status == ParcelStatus.WAITING_SHIPMENT) return
        DouyinCaptureController.markPhase("物流")
        val tracking = matches(scan, "查看物流")
        if (tracking.size == 1) {
            activate(tracking.single().first, tracking.single().second, NativeActionPurpose.LOGISTICS)
        } else {
            val more = matches(scan, "更多")
            service.paginationEvidence(buildJsonObject {
                put("captureId", captureId); put("action", "LOGISTICS_ENTRY"); put("orderId", candidate.remoteId)
                put("moreCount", more.size)
                put("labels", JsonArray(NativePaginationRules.activePages(scan).flatMap { page -> page.nodes.filter { it.visible }.map(DouyinNativeOrders::label) }
                    .filter(String::isNotBlank).distinct().map(::JsonPrimitive)))
            })
            if (more.size != 1) return
            session.prepareOcrLogistics(candidate.remoteId)
            activate(more.single().first, more.single().second, NativeActionPurpose.LOGISTICS_MENU)
            val menu = awaitMaybe(3_000) { matches(it, "查看物流").size == 1 }
            val target = menu?.let { matches(it, "查看物流").single() }?.takeIf { entry ->
                service.rootInActiveWindow?.windowId == entry.first.windowId &&
                    NativeActionPolicy.rejection(entry.first, entry.second, intended = NativeActionPurpose.LOGISTICS) == null
            }
            if (target != null) activate(target.first, target.second, NativeActionPurpose.LOGISTICS)
            else ocrTap("查看物流")
        }
        val page = awaitLogistics()
        if (page != null) {
            readNodeLogistics(page)
            return
        }
        readOcrLogistics()
    }
    private fun logisticsText(text: String) = listOf("物流", "运单", "轨迹", "快递").any(text::contains)
    private suspend fun awaitLogistics(): NativeScan? {
        val deadline = SystemClock.elapsedRealtime() + 25_000
        var previous: Pair<String, String>? = null
        do {
            val scan = read()
            if (DouyinNativeOrders.carrier(scan) != null) return scan
            val screen = withTimeoutOrNull(8_000) { service.captureScreen() }
            val parsed = screen?.let { DouyinOcrLogistics.parse(it.text, System.currentTimeMillis()) }
            if (screen != null && parsed != null && logisticsText(screen.text)) {
                val key = parsed.carrier to parsed.trackingNumber
                if (key == previous) return null
                previous = key
            } else previous = null
            delay(900)
        } while (SystemClock.elapsedRealtime() < deadline)
        throw AppFailure("物流页没有提供承运商与运单号：${progress.value.step}", FailureKind.UNSUPPORTED_RESPONSE)
    }
    private suspend fun readNodeLogistics(initial: NativeScan) {
        var scan = initial
        val more = matches(scan, "查看更多物流信息")
        if (more.size == 1) {
            activate(more.single().first, more.single().second, NativeActionPurpose.LOGISTICS_INFORMATION)
            scan = awaitPage { DouyinNativeOrders.carrier(it) != null }
        }
        var expanded = 0
        while (true) {
            val body = NativePaginationRules.activePages(scan).flatMap { page -> page.nodes.filter { it.visible && it.enabled &&
                DouyinNativeOrders.label(it).endsWith("展开") && DouyinNativeOrders.label(it).length > 10 }.map { page to it } }
            if (body.isEmpty()) break
            if (++expanded > 12) throw AppFailure("当前物流正文展开未完成", FailureKind.UNSUPPORTED_RESPONSE)
            activate(body.first().first, body.first().second, NativeActionPurpose.LOGISTICS_BODY)
            scan = read()
        }
        read(); read()
    }
    private suspend fun readOcrLogistics() {
        val first = service.captureScreen(record = true) ?: throw AppFailure("物流页截图识别失败", FailureKind.UNSUPPORTED_RESPONSE)
        delay(900)
        val second = service.captureScreen(record = true)
        val one = DouyinOcrLogistics.parse(first.text, System.currentTimeMillis())
        val two = second?.let { DouyinOcrLogistics.parse(it.text, System.currentTimeMillis()) }
        var parsed = one ?: two
            ?: throw AppFailure("物流页识别没有取得承运商与运单号，识别行数 ${first.lines.size}", FailureKind.UNSUPPORTED_RESPONSE)
        val readings = if (one != null && two != null && one.carrier == two.carrier && one.trackingNumber == two.trackingNumber) 2 else 1
        var text = first.text + (second?.let { "\n" + it.text } ?: "")
        var latest = (second ?: first).text
        var expanded = 0
        while (expanded < 3) {
            val label = listOf("展开全部物流信息", "查看更多物流信息").firstOrNull { latest.filterNot(Char::isWhitespace).contains(it) } ?: break
            val tap = service.tapScreenText(label)
            if (!tap.accepted) break
            delay(1_500)
            val next = service.captureScreen(record = true) ?: break
            latest = next.text
            text = text + "\n" + latest
            DouyinOcrLogistics.parse(text, System.currentTimeMillis())?.let { parsed = it }
            expanded++
        }
        service.paginationEvidence(buildJsonObject {
            put("captureId", captureId); put("action", "LOGISTICS_OCR"); put("carrier", parsed.carrier)
            put("trackingNumber", parsed.trackingNumber); put("status", parsed.status.name)
            put("events", parsed.events.size); put("expanded", expanded); put("readings", readings)
            put("lines", first.lines.size)
        })
        session.acceptOcrLogistics(parsed, readings)
        back()
        read(); read()
    }
}
