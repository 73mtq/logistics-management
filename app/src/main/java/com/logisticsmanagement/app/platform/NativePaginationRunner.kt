package com.logisticsmanagement.app.platform

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import com.logisticsmanagement.app.model.*
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
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
    private val scopeLabels = setOf("全部", "待支付", "待付款", "待发货", "待收货", "待收货/使用", "售后", "退款/售后", "评价", "待评价")
    private val checkedScopeProperties = listOf("selected", "checked（可访问性）", "选中描述", "选中指示节点")
    fun scopeEvidence(scan: NativeScan): NativeScopeEvidence? {
        val page = activePages(scan).firstOrNull(DouyinNativeOrders::isList) ?: return null
        val tabs = page.nodes.filter { it.visible && DouyinNativeOrders.label(it) in scopeLabels }
            .map { node -> NativeScopeTab(DouyinNativeOrders.label(node), node.selected, node.description,
                node.supplementalTexts.values.toList(), node.path, node.bounds) }
            .distinctBy { it.label }
        if (tabs.isEmpty()) return null
        val top = tabs.mapNotNull { it.bounds.getOrNull(1) }.minOrNull() ?: return NativeScopeEvidence(null, tabs, checkedScopeProperties)
        val title = page.nodes.filter { it.visible && it.bounds.size == 4 && it.bounds[1] < top }
            .map(DouyinNativeOrders::label).firstOrNull { it.endsWith("订单") && it.length <= 12 }
        return NativeScopeEvidence(title, tabs, checkedScopeProperties)
    }
    fun scopeSelected(scan: NativeScan): Boolean = activePages(scan).any { page ->
        page.nodes.filter { it.visible && DouyinNativeOrders.label(it) == "全部" }.any { label ->
            label.selected || page.nodes.any { node -> node.selected && (node.path == label.path ||
                node.path.size < label.path.size && label.path.take(node.path.size) == node.path ||
                node.path.size > label.path.size && node.path.take(label.path.size) == label.path) } ||
                page.nodes.any { node -> node.path.size >= label.path.size - 3 && node.path.size <= label.path.size &&
                    label.path.take(node.path.size) == node.path &&
                    DouyinNativeOrders.textValues(node).any { (_, text) -> text in setOf("已选中", "全部，已选中", "全部 已选中") } }
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
    private suspend fun screen(scan: NativeScan, index: Int) {
        val page = NativePaginationRules.activePages(scan).firstOrNull(DouyinNativeOrders::isList) ?: return
        val node = NativePaginationRules.verticalList(page)
        progress.screenRead(index, page.nodes.count { it.visible }, node?.actions.orEmpty())
    }
    private suspend fun endListWithoutTerminal(scan: NativeScan, screens: Int, reason: String) {
        val page = NativePaginationRules.activePages(scan).firstOrNull(DouyinNativeOrders::isList)
        val node = page?.let(NativePaginationRules::verticalList)
        service.paginationEvidence(buildJsonObject {
            put("captureId", captureId); put("action", "LIST_END"); put("terminal", false)
            put("screensRead", screens); put("nodeCount", page?.nodes?.count { it.visible } ?: 0)
            put("actions", JsonArray(node?.actions.orEmpty().map(::JsonPrimitive)))
            put("lastViewport", NativePaginationRules.viewport(NativePaginationRules.cards(scan)))
        })
        progress.blocked("list-end", reason)
        progress.endWithoutTerminal(reason)
        DouyinCaptureController.report(reason)
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
                home = matches(scan, "商城").isNotEmpty() && matches(scan, "我").isNotEmpty()
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
        var entry = awaitMaybe(10_000) { scan -> matches(scan, "我的订单 按钮").size == 1 || matches(scan, "我的订单").size == 1 ||
            NativePaginationRules.activePages(scan).any(DouyinNativeOrders::isList) }
        if (entry == null) {
            ocrTap("我的订单")
            entry = awaitMaybe(20_000) { scan -> NativePaginationRules.activePages(scan).any(DouyinNativeOrders::isList) }
        }
        if (entry == null) throw AppFailure("商城订单入口无法确认", FailureKind.UNSUPPORTED_RESPONSE)
        DouyinCaptureController.markPhase("订单列表")
        if (NativePaginationRules.activePages(entry).none(DouyinNativeOrders::isList)) {
            val preferred = matches(entry, "我的订单 按钮")
            val targets = if (preferred.isNotEmpty()) preferred else matches(entry, "我的订单")
            if (targets.size != 1) throw AppFailure("商城订单入口无法唯一确认", FailureKind.UNSUPPORTED_RESPONSE)
            val target = targets.single()
            activate(target.first, target.second, NativeActionPurpose.ORDERS)
            entry = awaitPage { NativePaginationRules.activePages(it).any(DouyinNativeOrders::isList) }
        }
        val listPage = NativePaginationRules.activePages(entry).firstOrNull(DouyinNativeOrders::isList)
        val mallTab = listPage?.nodes?.firstOrNull { it.visible && it.enabled && DouyinNativeOrders.label(it) == "商城" }
        if (listPage != null && mallTab != null) {
            step("商城订单")
            activate(listPage, mallTab, NativeActionPurpose.ALL)
        }
        var list = awaitPage { NativePaginationRules.activePages(it).any(DouyinNativeOrders::isList) }
        list = read()
        var screens = 1
        screen(list, screens)
        NativePaginationRules.scopeEvidence(list)?.let { evidence ->
            progress.scope(evidence)
            service.paginationEvidence(buildJsonObject {
                put("captureId", captureId); put("action", "SCOPE"); put("title", evidence.title)
                put("selected", NativePaginationRules.scopeSelected(list))
                put("tabs", platformJson.parseToJsonElement(platformJson.encodeToString(evidence.tabs)))
                put("checked", JsonArray(evidence.checkedProperties.map(::JsonPrimitive)))
            })
        }
        if (NativePaginationRules.scopeSelected(list)) { progress.confirmScope(); progress.resolved("scope-selection") }
        else progress.blocked("scope-selection", "全部标签未提供 selected、checked、选中描述与选中指示节点；页面标题为“全部订单”，未取得选中判据")
        val readThisRound = mutableSetOf<String>()
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
                readThisRound.add(focusId)
                DouyinCaptureController.markPhase("订单列表")
                list = returnList()
                screen(list, screens)
            }
        }
        val visited = mutableSetOf<String>()
        val knownOrders = (service.application as com.logisticsmanagement.app.LogisticsApplication).container.repository.getOrders()
            .filter { it.accountId == account.id }
        var scrollPage = 0
        var unchanged = 0
        var missStreak = 0
        while (true) {
            val knownIds = knownOrders.map { it.remoteId }
            if (knownIds.isNotEmpty() && knownIds.all { it in readThisRound }) {
                val nodeCount = NativePaginationRules.activePages(list).firstOrNull(DouyinNativeOrders::isList)?.nodes?.count { it.visible } ?: 0
                endListWithoutTerminal(list, screens,
                    "已读取 $screens 个屏幕，最后一个屏幕有 $nodeCount 个节点；已处理全部 ${knownIds.size} 笔已导入订单，平台结束标志未出现，完整范围保持未确认")
                return
            }
            if (missStreak >= 12) {
                val nodeCount = NativePaginationRules.activePages(list).firstOrNull(DouyinNativeOrders::isList)?.nodes?.count { it.visible } ?: 0
                endListWithoutTerminal(list, screens,
                    "已读取 $screens 个屏幕，最后一个屏幕有 $nodeCount 个节点；连续 12 张卡片未匹配已导入订单，平台结束标志未出现，完整范围保持未确认")
                return
            }
            val cards = NativePaginationRules.cards(list)
            val viewport = NativePaginationRules.viewport(cards)
            val next = cards.firstOrNull { "$scrollPage:${it.row}:${it.fingerprint}" !in visited }
            if (next != null) {
                val key = "$scrollPage:${next.row}:${next.fingerprint}"
                val focus = focusOrderId
                val title = focusTitle
                val stored = (service.application as com.logisticsmanagement.app.LogisticsApplication).container.repository.getOrders()
                    .filter { it.accountId == account.id }
                    .firstOrNull { sameTitle(it.title.orEmpty(), next.node.text) }
                val orderId = when {
                    focus != null && title != null && sameTitle(next.node.text, title) -> focus
                    stored != null -> stored.remoteId
                    else -> null
                }
                if (orderId != null && orderId !in readThisRound) {
                    step("读取订单物流，已核查 ${progress.value.orders.size} 笔")
                    try {
                        readCardLogistics(next, orderId)
                        readThisRound.add(orderId)
                        missStreak = 0
                    } catch (failure: AppFailure) {
                        progress.blocked(key, checkNotNull(failure.message))
                        service.paginationEvidence(buildJsonObject {
                            put("captureId", captureId); put("action", "CARD_BLOCKED"); put("card", key); put("reason", failure.message)
                        })
                    }
                } else if (orderId == null) {
                    missStreak++
                } else {
                    missStreak = 0
                }
                visited.add(key)
                DouyinCaptureController.markPhase("订单列表")
                list = returnList()
                screen(list, screens)
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
            if (node == null) {
                val nodeCount = page.nodes.count { it.visible }
                endListWithoutTerminal(list, screens,
                    "已读取 $screens 个屏幕，最后一个屏幕有 $nodeCount 个节点；当前页面没有可滚动列表容器，未出现平台结束标志，完整范围保持未确认")
                return
            }
            val root = service.rootInActiveWindow
            if (root?.windowId != page.windowId || root.packageName?.toString() != DouyinCaptureController.PACKAGE)
                throw AppFailure("滚动前官方活动窗口已经变化", FailureKind.UNSUPPORTED_RESPONSE)
            val result = if (AccessibilityNodeInfo.ACTION_SCROLL_FORWARD in node.actions)
                service.performChecked(page, node, AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            else service.scrollList(page, node, forward = true)
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
            if (changed) { scrollPage++; screens = scrollPage + 1 }
            list = after
            screen(list, screens)
            if (unchanged >= 2 && NativePaginationRules.terminal(list) == null) {
                val nodeCount = NativePaginationRules.activePages(list).firstOrNull(DouyinNativeOrders::isList)
                    ?.nodes?.count { it.visible } ?: 0
                endListWithoutTerminal(list, screens,
                    "已读取 $screens 个屏幕，最后一个屏幕有 $nodeCount 个节点；最后一个屏幕连续两次未变化且未出现平台结束标志，完整范围保持未确认")
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
            .filter { it.bounds[1] > top + 50 && it.bounds[1] - top <= 1_600 }
            .minByOrNull { it.bounds[1] }
    }
    private suspend fun readCardLogistics(card: NativeListCard, orderId: String) {
        val more = cardMore(card) ?: throw AppFailure("目标卡片没有“更多”入口", FailureKind.UNSUPPORTED_RESPONSE)
        service.paginationEvidence(buildJsonObject {
            put("captureId", captureId); put("action", "LOGISTICS_DIRECT"); put("orderId", orderId)
            put("row", card.row)
        })
        activate(card.page, more, NativeActionPurpose.LOGISTICS_MENU)
        val stored = (service.application as com.logisticsmanagement.app.LogisticsApplication).container.repository.getOrders()
            .firstOrNull { it.remoteId == orderId }
        stored?.let { session.registerOrder(it) }
        session.prepareOcrLogistics(orderId, stored?.createdAt)
        val entry = awaitAnyLabel(5_000, "查看物流")
        if (entry != null) service.tapNode(entry.first, entry.second) else ocrTap("查看物流")
        val page = awaitLogistics()
        if (page != null) readNodeLogistics(page) else readOcrLogistics()
    }
    private suspend fun readOrder(initial: NativeScan) {
        var scan = initial
        val detailPage = NativePaginationRules.activePages(scan).firstOrNull { page ->
            page.nodes.any { it.visible && DouyinNativeOrders.label(it) == "订单编号" }
        }
        val toggle = detailPage?.let(DouyinNativeOrders::informationToggle)
        if (detailPage != null && toggle != null && DouyinNativeOrders.label(toggle) == "展开") {
            activate(detailPage, toggle, NativeActionPurpose.ORDER_INFORMATION)
        }
        read(); scan = read()
        val candidate = session.currentCandidate() ?: return
        val fieldsPage = DouyinNativeOrders.servicePages(scan).firstOrNull { page ->
            page.nodes.any { it.visible && DouyinNativeOrders.label(it) == "订单编号" }
        }
        if (fieldsPage != null) service.paginationEvidence(buildJsonObject {
            put("captureId", captureId); put("action", "DETAIL_FIELDS"); put("orderId", candidate.remoteId)
            put("missing", JsonArray(candidate.missing().map(::JsonPrimitive)))
            put("fields", platformJson.parseToJsonElement(platformJson.encodeToString(DouyinNativeOrders.detailFields(fieldsPage))))
        })
        if (candidate.excluded || candidate.remoteId in session.excluded || candidate.status == ParcelStatus.WAITING_SHIPMENT) return
        if (candidate.status == ParcelStatus.SIGNED && candidate.createdAt == null) {
            service.paginationEvidence(buildJsonObject {
                put("captureId", captureId); put("action", "DETAIL_INCOMPLETE"); put("orderId", candidate.remoteId)
                put("missing", JsonArray(candidate.missing().map(::JsonPrimitive)))
            })
            return
        }
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
            session.prepareOcrLogistics(candidate.remoteId, candidate.createdAt)
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
            val parsed = screen?.let { DouyinOcrLogistics.parse(it.text, System.currentTimeMillis(), session.expectedCreatedAt()) }
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
        val createdAt = session.expectedCreatedAt()
        var text = ""
        var latest: OcrScreen? = null
        var expanded = 0
        while (expanded <= 12) {
            val screen = service.captureScreen(record = true) ?: break
            latest = screen
            text = if (text.isEmpty()) screen.text else text + "\n" + screen.text
            val compact = screen.text.filterNot(Char::isWhitespace)
            val label = listOf("展开全部物流信息", "查看更多物流信息", "查看全部物流信息").firstOrNull(compact::contains) ?: break
            if (expanded == 12) break
            val tap = service.tapScreenText(label)
            if (!tap.accepted) break
            delay(1_500)
            expanded++
        }
        val last = latest ?: throw AppFailure("物流页截图识别失败", FailureKind.UNSUPPORTED_RESPONSE)
        var parsed = DouyinOcrLogistics.parse(text, System.currentTimeMillis(), createdAt)
            ?: throw AppFailure("物流页识别没有取得承运商与运单号，识别行数 ${last.lines.size}", FailureKind.UNSUPPORTED_RESPONSE)
        var readings = 1
        var stable = false
        var attempts = 0
        while (!stable && attempts < 2) {
            attempts++
            val next = service.captureScreen(record = true) ?: break
            text = text + "\n" + next.text
            val again = DouyinOcrLogistics.parse(text, System.currentTimeMillis(), createdAt)
            if (again != null) {
                if (again.parcels == parsed.parcels && again.status == parsed.status && again.events == parsed.events) {
                    stable = true
                    readings = 2
                }
                parsed = again
            }
            delay(900)
        }
        service.paginationEvidence(buildJsonObject {
            put("captureId", captureId); put("action", "LOGISTICS_OCR"); put("carrier", parsed.carrier)
            put("trackingNumber", parsed.trackingNumber); put("status", parsed.status.name)
            put("events", parsed.events.size); put("expanded", expanded); put("readings", readings); put("stable", stable)
            put("parcels", JsonArray(parsed.parcels.map { parcel -> buildJsonObject {
                put("carrier", parcel.carrier); put("trackingNumber", parcel.trackingNumber)
            } }))
            put("times", JsonArray(parsed.times.map { platformJson.parseToJsonElement(platformJson.encodeToString(it)) }))
            put("unresolved", JsonArray(parsed.unresolved.map(::JsonPrimitive)))
            put("lines", last.lines.size)
        })
        session.acceptOcrLogistics(parsed, readings)
        back()
        read(); read()
    }
}
