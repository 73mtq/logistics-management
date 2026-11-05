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
    private val statuses = setOf("已签收", "交易成功", "交易完成", "交易关闭", "已关闭", "已取消", "已退款", "待发货", "待收货", "待收货/使用", "待付款", "待评价", "退款成功", "充值成功", "服务完成", "待使用", "退款中", "售后中")
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
        val labels = setOf("没有更多订单", "没有更多订单了", "没有更多了", "暂无订单", "暂无相关订单")
        return activePages(scan).filter(DouyinNativeOrders::isList).firstNotNullOfOrNull { page ->
            page.nodes.firstOrNull { it.visible && DouyinNativeOrders.label(it) in labels }?.let {
                NativeFieldEvidence("listEnd", DouyinNativeOrders.label(it), page.windowId, it.path, scan.sequence)
            }
        }
    }
    private fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}

internal class NativePaginationRunner(private val service: DouyinCaptureService, private val account: Account,
    private val session: NativeOrderImportSession) {
    private class NavigationRestart : Exception()
    private val progress = checkNotNull(session.runProgress)
    private val captureId = session.captureId
    private var judge: NativeSemanticJudge? = null
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
    private fun matches(scan: NativeScan, label: String) = NativePaginationRules.activePages(scan).flatMap { page ->
        page.nodes.filter { it.visible && it.enabled && (it.text == label || it.description == label) }.map { page to it }
    }
    private suspend fun activate(page: NativePage, node: NativeNode, intended: NativeActionPurpose = NativeActionPolicy.purpose(page, node)) {
        var currentPage = page
        var currentNode = node
        repeat(3) {
            val scan = read()
            val fresh = NativePaginationRules.activePages(scan).singleOrNull { it.windowId == currentPage.windowId }
            val resolved = fresh?.nodes?.singleOrNull { it.path == currentNode.path && it.text == currentNode.text &&
                it.description == currentNode.description && it.bounds == currentNode.bounds }
            if (fresh == null || resolved == null) throw AppFailure("动作执行前目标页面已经变化，请重新核查", FailureKind.UNSUPPORTED_RESPONSE)
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
        step("账号核查")
        val launch = service.packageManager.getLaunchIntentForPackage(DouyinCaptureController.PACKAGE)
            ?: throw AppFailure("请安装抖音官方App", FailureKind.CONFIGURATION)
        if (service.rootInActiveWindow?.packageName?.toString() != DouyinCaptureController.PACKAGE)
            service.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        awaitPage { NativePaginationRules.activePages(it).isNotEmpty() }
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
        step("商城")
        activate("商城", NativeActionPurpose.MALL)
        step("我的订单")
        val entry = awaitPage { matches(it, "我的订单 按钮").size == 1 || matches(it, "我的订单").size == 1 }
        val preferred = matches(entry, "我的订单 按钮")
        val targets = if (preferred.isNotEmpty()) preferred else matches(entry, "我的订单")
        if (targets.size != 1) throw AppFailure("商城订单入口无法唯一确认", FailureKind.UNSUPPORTED_RESPONSE)
        val target = targets.single()
        DouyinCaptureController.markPhase("订单列表")
        activate(target.first, target.second, NativeActionPurpose.ORDERS)
        awaitPage { NativePaginationRules.activePages(it).any(DouyinNativeOrders::isList) }
        activate("全部", NativeActionPurpose.ALL)
        var list = awaitPage { NativePaginationRules.activePages(it).any(DouyinNativeOrders::isList) }
        list = read()
        if (NativePaginationRules.allSelected(list)) { progress.confirmScope(); progress.resolved("scope-selection") }
        else progress.blocked("scope-selection", "全部标签的选中状态没有提供，完整范围尚未确认")
        val visited = mutableSetOf<String>()
        var scrollPage = 0
        var unchanged = 0
        while (true) {
            val cards = NativePaginationRules.cards(list)
            val viewport = NativePaginationRules.viewport(cards)
            val next = cards.firstOrNull { "$scrollPage:${it.row}:${it.fingerprint}" !in visited }
            if (next != null) {
                val key = "$scrollPage:${next.row}:${next.fingerprint}"
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
    private suspend fun readOrder(initial: NativeScan) {
        var scan = initial
        if (matches(scan, "更多信息").isNotEmpty() && matches(scan, "展开").size == 1) {
            val expand = matches(scan, "展开").single()
            activate(expand.first, expand.second, NativeActionPurpose.ORDER_INFORMATION)
        }
        read(); scan = read()
        val candidate = session.currentCandidate() ?: return
        if (candidate.excluded || candidate.remoteId in session.excluded || candidate.status == ParcelStatus.WAITING_SHIPMENT) return
        val tracking = matches(scan, "查看物流")
        if (tracking.size != 1) return
        DouyinCaptureController.markPhase("物流")
        activate(tracking.single().first, tracking.single().second, NativeActionPurpose.LOGISTICS)
        scan = awaitPage { DouyinNativeOrders.carrier(it) != null }
        val more = matches(scan, "查看更多物流信息")
        if (more.size == 1) { activate(more.single().first, more.single().second, NativeActionPurpose.LOGISTICS_INFORMATION); scan = awaitPage { DouyinNativeOrders.carrier(it) != null } }
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
}
