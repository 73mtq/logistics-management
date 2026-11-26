package com.logisticsmanagement.app.platform

import android.net.Uri
import android.os.SystemClock
import android.util.Log
import com.logisticsmanagement.app.model.Account
import com.logisticsmanagement.app.model.AppFailure
import com.logisticsmanagement.app.model.FailureKind
import com.logisticsmanagement.app.model.ImportedOrder
import com.logisticsmanagement.app.model.ImportedShipment
import com.logisticsmanagement.app.model.ParcelStatus
import com.logisticsmanagement.app.model.Platform
import com.logisticsmanagement.app.model.SourceRef
import com.logisticsmanagement.app.model.TrackingEventData
import com.logisticsmanagement.app.model.OrderBatch
import com.logisticsmanagement.app.data.LogisticsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.net.URI
import java.net.URISyntaxException

// 接口与字段依据 ExpressAssistant，Copyright (c) 2026 Halo0sama，MIT 许可。
// 固定参考版本：5adcfa780017ab0fb9888e5aaeef7b6a8d311209。
internal data class OrderPage(
    val orders: List<ImportedOrder>,
    val remoteIds: Set<String>,
    val total: Int? = null,
    val complete: Boolean = false,
    val trackingUrls: Map<String, String> = emptyMap(),
    val excludedRemoteIds: Set<String> = emptySet(),
)

internal abstract class BrowserAdapter(protected val registry: PlatformRegistry) : PlatformAdapter {
    abstract fun parseOrders(capture: CapturedResponse): OrderPage?
    abstract fun parseTracking(capture: CapturedResponse, source: SourceRef): ImportedShipment?
    open suspend fun trackingUrl(account: Account, source: SourceRef): String = source.orderUrl
    open fun trackingAction(session: PlatformWebView, source: SourceRef) = Unit
    open fun orderAction(session: PlatformWebView) = Unit

    suspend fun rememberOrderPage(account: Account, page: OrderPage) {
        for ((remoteId, url) in page.trackingUrls) registry.saveAccountSecret(account, trackingKey(remoteId), url)
    }

    protected fun trackingKey(remoteId: String): String = "${platform.name.lowercase()}.tracking.${LogisticsRepository.stableId(remoteId)}"

    protected fun resolveOrderUrl(value: String, platformName: String): String {
        val url = try { URI(orderListUrl).resolve(URI(value)).toString() }
        catch (failure: URISyntaxException) {
            throw AppFailure("${platformName}订单链接格式尚未支持", FailureKind.UNSUPPORTED_RESPONSE)
        }
        if (!allowsOrigin(Uri.parse(url), allowedOrigins)) {
            throw AppFailure("${platformName}订单链接没有指向已支持的官方网页", FailureKind.UNSUPPORTED_RESPONSE)
        }
        return url
    }

    override suspend fun sync(account: Account, onBatch: suspend (OrderBatch) -> Unit) = sync(account, {}, onBatch)

    internal suspend fun sync(account: Account, onCapture: (CapturedResponse) -> Unit, onBatch: suspend (OrderBatch) -> Unit) = withContext(Dispatchers.Main.immediate) {
        requirePlatformLoginSession(registry.profiles, account)
        val session = PlatformWebView(registry.context, account, this@BrowserAdapter, registry.profiles, false)
        val received = mutableSetOf<String>()
        val emitted = mutableMapOf<String, ImportedOrder>()
        val excluded = mutableSetOf<String>()
        val deadline = SystemClock.elapsedRealtime() + 90_000
        var nextScroll = SystemClock.elapsedRealtime() + 4_000
        var total: Int? = null
        var responseAt = 0L
        var endAt: Long? = null
        var initialized = false
        try {
            session.load(orderListUrl)
            while (SystemClock.elapsedRealtime() < deadline) {
                val capture = withTimeoutOrNull(1_000) { session.messages.receive() }
                if (capture != null) {
                    onCapture(capture)
                    registry.rememberCapture(account, capture)
                    if (capture.kind == "end") endAt = SystemClock.elapsedRealtime()
                    parseOrders(capture)?.let { page ->
                        rememberOrderPage(account, page)
                        responseAt = SystemClock.elapsedRealtime()
                        received.addAll(page.remoteIds)
                        total = page.total ?: total
                        val fresh = page.orders.filter { emitted[it.remoteId] != it }
                        fresh.forEach { emitted[it.remoteId] = it }
                        val freshExcluded = page.excludedRemoteIds.filter { excluded.add(it) }.toSet()
                        if (fresh.isNotEmpty() || freshExcluded.isNotEmpty() || page.complete) onBatch(OrderBatch(fresh, freshExcluded, page.remoteIds.size))
                        if (page.complete || total != null && received.size >= checkNotNull(total)) return@withContext
                    }
                }
                val now = SystemClock.elapsedRealtime()
                if (endAt != null && responseAt > 0 && now - maxOf(checkNotNull(endAt), responseAt) >= 3_000) return@withContext
                if (now >= nextScroll && session.currentUrl.isNotBlank()) {
                    if (!initialized) { orderAction(session); initialized = true }
                    session.advanceOrders()
                    nextScroll = now + 3_000
                }
            }
            if (responseAt == 0L) throw AppFailure("${platform.displayName}没有提供可识别的订单响应，请在订单网页确认加载结果", FailureKind.UNSUPPORTED_RESPONSE)
            throw AppFailure("${platform.displayName}订单分页未完成，已取得的订单已经保存，请在来源网页完成加载", FailureKind.NETWORK)
        } finally { session.close() }
    }

    override suspend fun tracking(account: Account, source: SourceRef): ImportedShipment = withContext(Dispatchers.Main.immediate) {
        openTrackingSession(account).use { it.query(source) }
    }

    override suspend fun openTrackingSession(account: Account): TrackingSession = openTrackingSession(account) { }

    internal suspend fun openTrackingSession(account: Account, onCapture: (CapturedResponse) -> Unit): TrackingSession = withContext(Dispatchers.Main.immediate) {
        requirePlatformLoginSession(registry.profiles, account)
        object : TrackingSession {
            private var session = PlatformWebView(registry.context, account, this@BrowserAdapter, registry.profiles, false)
            override suspend fun query(source: SourceRef): ImportedShipment {
                require(source.accountId == account.id && source.platform == platform)
                if (session.isClosed) session = PlatformWebView(registry.context, account, this@BrowserAdapter, registry.profiles, false)
                return readTracking(account, source, session, onCapture)
            }
            override fun close() = session.close()
        }
    }

    private suspend fun readTracking(account: Account, source: SourceRef, session: PlatformWebView, onCapture: (CapturedResponse) -> Unit): ImportedShipment {
        val deadline = SystemClock.elapsedRealtime() + 45_000
        var nextAction = SystemClock.elapsedRealtime() + 3_000
        session.load(trackingUrl(account, source))
        while (SystemClock.elapsedRealtime() < deadline) {
            val capture = withTimeoutOrNull(1_000) { session.messages.receive() }
            if (capture != null) {
                onCapture(capture)
                registry.rememberCapture(account, capture)
                parseTracking(capture, source)?.let { return it }
            }
            val now = SystemClock.elapsedRealtime()
            if (now >= nextAction) {
                onCapture(CapturedResponse("trackingState", session.view.url.orEmpty(), "finished=${session.currentUrl.isNotBlank()}"))
                trackingAction(session, source)
                nextAction = now + 3_000
            }
        }
        throw AppFailure("${platform.displayName}物流更新超时，请打开来源网页检查登录与物流记录", FailureKind.NETWORK)
    }
}

internal class JdAdapter(registry: PlatformRegistry) : BrowserAdapter(registry) {
    override val platform = Platform.JD
    override val loginUrl = "https://plogin.m.jd.com/login/login?appid=300&returnurl=https%3A%2F%2Fhome.m.jd.com%2FmyJd%2Fhome.action"
    override val orderListUrl = "https://trade.m.jd.com/order/orderlist_jdm.shtml?orderType=all&source=m_outer_jx_order"
    override val allowedOrigins = setOf("https://plogin.m.jd.com", "https://home.m.jd.com", "https://trade.m.jd.com", "https://wqs.jd.com", "https://www.jd.com", "https://api.m.jd.com", "https://m.jd.com", "https://my.m.jd.com", "https://recharge.m.jd.com", "https://locdetails.jd.com")

    override fun parseOrders(capture: CapturedResponse): OrderPage? {
        if (capture.kind != "response" || !Uri.parse(capture.url).getQueryParameter("functionId").equals("order_list_m")) return null
        val root = platformResponse(capture.body, "京东")
        checkJdEnvelope(root, "京东")
        val body = root.objectOrNull("body") ?: throw AppFailure("京东订单响应缺少 body", FailureKind.UNSUPPORTED_RESPONSE)
        val bodyError = body.text("errorCode")
        if (bodyError.isNotBlank() && bodyError != "0") {
            val reason = body.text("errorReason")
            val kind = when {
                bodyError == "302" -> FailureKind.NEEDS_LOGIN
                listOf("安全验证", "验证码", "滑动验证").any(reason::contains) -> FailureKind.NEEDS_VERIFICATION
                else -> FailureKind.SERVICE_ERROR
            }
            throw AppFailure("京东返回业务错误 $bodyError：${reason.ifBlank { "平台暂时无法提供订单" }}", kind)
        }
        val list = body.requiredArray("orderList").map { it.requireObject("京东订单") }
        val trackingUrls = mutableMapOf<String, String>()
        val excluded = mutableSetOf<String>()
        val orders = list.mapNotNull { order ->
            val id = order.requiredText("orderId")
            if (excludedService(order)) {
                excluded.add(id)
                return@mapNotNull null
            }
            val statusInfo = order.objectOrNull("orderStatusInfo")
            val statusText = statusInfo?.text("orderStatusName").orEmpty()
            if (closedOrder(statusText)) return@mapNotNull null
            val wares = order.requiredArray("wareInfoList").map { it.requireObject("京东商品") }
            val goods = wares.firstOrNull()
            val progress = order.objectOrNull("progressInfo")
            val detailLink = order.objectOrNull("orderDetailLink")?.requiredText("url")
                ?: throw AppFailure("京东订单响应缺少 orderDetailLink.url", FailureKind.UNSUPPORTED_RESPONSE)
            val orderUrl = officialUrl(detailLink)
            progress?.text("progressLink")?.takeIf(String::isNotBlank)?.let { trackingUrls[id] = officialUrl(it) }
            val status = when (statusText) {
                "完成", "充值成功" -> ParcelStatus.SIGNED
                else -> parcelStatus(statusText)
            }
            val isRecharge = statusText.split(',', '，').any { it.trim() == "充值成功" }
            val submittedAt = order.requiredText("submitDate").let(::parsePlatformTime)
            ImportedOrder(id, wares.map { it.text("wareName") }.filter(String::isNotBlank).joinToString("、").ifBlank { "京东订单" },
                status, submittedAt, orderUrl, imageUrl(goods?.text("imageUrl").orEmpty()),
                if (status == ParcelStatus.WAITING_SHIPMENT || isRecharge) emptyList()
                else listOf(ImportedShipment(id, status = status)))
        }
        val total = body.text("totalNum").toIntOrNull()
        return OrderPage(orders, list.map { it.requiredText("orderId") }.toSet(), total, total == 0, trackingUrls, excluded)
    }

    private fun excludedService(order: JsonObject): Boolean {
        if (!order.containsKey("orderType")) return false
        val type = order.requiredText("orderType").toIntOrNull()
            ?: throw AppFailure("京东订单类型格式尚未支持", FailureKind.UNSUPPORTED_RESPONSE)
        if (type !in setOf(22, 34, 37, 75)) return false
        if (!order.containsKey("specialDealList")) return false
        val tags = order.requiredArray("specialDealList").map { value ->
            val tag = value as? JsonPrimitive
            if (tag == null || !tag.isString) throw AppFailure("京东订单业务标志格式尚未支持", FailureKind.UNSUPPORTED_RESPONSE)
            tag.content
        }.toSet()
        val link = order.objectOrNull("orderDetailLink")?.requiredText("url")
            ?: throw AppFailure("京东订单响应缺少 orderDetailLink.url", FailureKind.UNSUPPORTED_RESPONSE)
        val uri = Uri.parse(officialUrl(link))
        // 已确认即时配送样本、充值响应与券码详情对应的业务组合。
        return when (type) {
            22 -> "isStore2Home" in tags && uri.host == "trade.m.jd.com" && uri.path == "/order/n_detail_jdm.shtml"
            34, 37 -> "isVirtual" in tags && uri.host == "recharge.m.jd.com" && uri.path == "/orderDetail"
            75 -> tags.containsAll(setOf("isLoc", "isLocCom")) && uri.host == "locdetails.jd.com" && uri.path == "/h5/index.html"
            else -> false
        }
    }

    private fun officialUrl(value: String): String = resolveOrderUrl(value, "京东")

    private fun checkJdEnvelope(root: JsonObject, label: String) {
        val error = root.text("errorCode")
        if (error == "302") throw AppFailure("京东账号需要重新登录", FailureKind.NEEDS_LOGIN)
        if (error == "601") throw AppFailure("京东要求完成安全验证", FailureKind.NEEDS_VERIFICATION)
        if (error.isNotBlank() && error != "0") throw AppFailure("${label}返回业务错误 $error", FailureKind.SERVICE_ERROR)
    }

    override suspend fun trackingUrl(account: Account, source: SourceRef): String =
        registry.accountSecret(account, trackingKey(source.remoteOrderId)) ?: source.orderUrl

    override fun trackingAction(session: PlatformWebView, source: SourceRef) {
        val current = Uri.parse(session.currentUrl)
        val order = Uri.parse(source.orderUrl)
        if (current.host == order.host && current.path == order.path && !current.path.orEmpty().contains("deal_wuliu")) {
            session.evaluate(CaptureScripts.jdTracking)
        }
    }

    override fun parseTracking(capture: CapturedResponse, source: SourceRef): ImportedShipment? {
        if (capture.kind == "response" && Uri.parse(capture.url).getQueryParameter("functionId") == "get_logistics_list_by_order_m") {
            val root = platformResponse(capture.body, "京东物流")
            checkJdEnvelope(root, "京东物流")
            val body = root.objectOrNull("body") ?: throw AppFailure("京东物流响应缺少 body", FailureKind.UNSUPPORTED_RESPONSE)
            val points = body.requiredArray("tracePointVOList")
            if (points.isEmpty()) throw AppFailure("京东没有提供该订单的物流轨迹", FailureKind.SERVICE_ERROR)
        }
        if (capture.kind != "dom" || !Uri.parse(capture.url).path.orEmpty().contains("deal_wuliu")) return null
        val lines = capture.body.lines().map(String::trim)
        val points = mutableListOf<TrackingEventData>()
        var description = ""
        for (line in lines) {
            if (line.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}\\s+[0-9]{2}:[0-9]{2}(?::[0-9]{2})?"))) {
                if (description.isNotBlank()) points.add(trackingEvent(line, description))
                description = ""
            } else if (line.contains("运单号") || line.contains("国内承运人") || line.contains("联系电话") || line.contains("订单编号")) {
                continue
            } else if (line.contains("【") || listOf("快件", "包裹", "揽收", "签收", "发往", "到达", "转运", "投递", "取件", "出库", "配送", "妥投", "物流", "运输").any(line::contains)) {
                if (line.length >= 6) description = listOf(description, line).filter(String::isNotBlank).joinToString(" ")
            }
        }
        if (points.isEmpty()) return null
        val latest = points.maxBy { it.time }
        val number = Regex("运单号\\s*[:：]?\\s*([A-Za-z0-9-]{6,40})").find(capture.body)?.groupValues?.get(1)
        val carrier = Regex("国内承运人\\s*[:：]?\\s*([^\\n]{2,30})").find(capture.body)?.groupValues?.get(1)?.trim().orEmpty()
        return ImportedShipment(source.remoteShipmentId, number, carrier, status = parcelStatus(latest.text),
            pickupCode = pickupCode(latest.text), events = points)
    }
}

internal class PddAdapter(registry: PlatformRegistry) : BrowserAdapter(registry) {
    override val platform = Platform.PDD
    override val loginUrl = "https://mobile.yangkeduo.com/login.html"
    override val orderListUrl = "https://mobile.yangkeduo.com/orders.html"
    override val allowedOrigins = setOf("https://mobile.yangkeduo.com", "https://yangkeduo.com", "https://pinduoduo.com", "https://mobile.pinduoduo.com", "https://passport.pinduoduo.com")
    override fun orderAction(session: PlatformWebView) = session.evaluate(CaptureScripts.pddOrders)

    override suspend fun sync(account: Account, onBatch: suspend (OrderBatch) -> Unit) = syncWithEvidence(account, {}, onBatch = onBatch)

    internal suspend fun syncWithEvidence(account: Account, onCapture: (CapturedResponse) -> Unit,
        onNote: (String) -> Unit = {}, onBatch: suspend (OrderBatch) -> Unit) = withContext(Dispatchers.Main.immediate) {
        requirePlatformLoginSession(registry.profiles, account)
        val session = PlatformWebView(registry.context, account, this@PddAdapter, registry.profiles, false)
        val emitted = mutableMapOf<String, ImportedOrder>()
        val deadline = SystemClock.elapsedRealtime() + ORDER_BUDGET
        val summaries = mutableListOf<PddPageSummary>()
        var responses = 0
        try {
            session.load(orderListUrl)
            val all = PddTab("全部", "all")
            val allLedger = PddPageLedger(all)
            var result = drain(session, account, onCapture, onBatch, emitted, allLedger, minOf(10_000L, deadline - SystemClock.elapsedRealtime()))
            responses += result.responses
            if (allLedger.size == 0) {
                session.evaluate(CaptureScripts.pddOrders)
                delay(1_500)
                result = drain(session, account, onCapture, onBatch, emitted, allLedger, minOf(8_000L, deadline - SystemClock.elapsedRealtime()))
                responses += result.responses
            }
            if (allLedger.size > 0) scrollTab(session, account, onCapture, onBatch, emitted, allLedger, deadline)
            summaries += allLedger.summary()
            for (tab in tabs) {
                if (deadline - SystemClock.elapsedRealtime() < MIN_TAB_MILLIS) {
                    onNote("执行期限不足，未读取标签 ${tab.label}")
                    break
                }
                if (!clickTab(session, tab.label)) {
                    onNote("标签「${tab.label}」不存在")
                    continue
                }
                delay(1_500)
                val ledger = PddPageLedger(tab)
                result = drain(session, account, onCapture, onBatch, emitted, ledger, minOf(TAB_WAIT_MILLIS, deadline - SystemClock.elapsedRealtime()))
                responses += result.responses
                if (ledger.size == 0) {
                    onNote("标签「${tab.label}」没有取得 type=${tab.type} 的第一页")
                    summaries += ledger.summary()
                    continue
                }
                scrollTab(session, account, onCapture, onBatch, emitted, ledger, deadline)
                summaries += ledger.summary()
            }
            if (responses == 0) throw AppFailure("拼多多没有提供可识别的订单响应，请在订单网页确认加载结果", FailureKind.UNSUPPORTED_RESPONSE)
            Log.i("PddPages", platformJson.encodeToString(summaries))
            summaries.forEach { summary ->
                summary.anomalies.take(2).forEach { onNote(summary.label + "：" + it) }
                if (summary.endedBy == "no-progress") onNote(summary.label + "：连续滚动没有新增订单")
            }
            val recorded = summaries.map { it.label }.toSet()
            val missing = (listOf(all) + tabs).map { it.label }.filterNot { it in recorded }
            if (missing.isNotEmpty()) onNote("未读取标签 " + missing.joinToString("、"))
        } finally { session.close() }
    }

    private suspend fun clickTab(session: PlatformWebView, label: String): Boolean {
        val raw = withTimeoutOrNull(4_000) { session.evaluateAwait(CaptureScripts.pddTab(label)) } ?: return false
        return raw.contains("true")
    }

    private suspend fun scrollTab(session: PlatformWebView, account: Account, onCapture: (CapturedResponse) -> Unit,
        onBatch: suspend (OrderBatch) -> Unit, emitted: MutableMap<String, ImportedOrder>, ledger: PddPageLedger, deadline: Long) {
        var idle = 0
        while (SystemClock.elapsedRealtime() < deadline && idle < MAX_IDLE_SCROLLS && !ledger.empty) {
            scroll(session)
            val result = drain(session, account, onCapture, onBatch, emitted, ledger, SCROLL_WAIT_MILLIS)
            if (result.newPages > 0 || result.orders > 0) idle = 0 else idle++
            if (result.endSeen) {
                ledger.finish("end-marker")
                return
            }
        }
        ledger.finish(if (ledger.empty) "empty-page" else "no-progress")
    }

    private suspend fun drain(session: PlatformWebView, account: Account, onCapture: (CapturedResponse) -> Unit,
        onBatch: suspend (OrderBatch) -> Unit, emitted: MutableMap<String, ImportedOrder>, ledger: PddPageLedger, windowMillis: Long): PddDrain {
        val until = SystemClock.elapsedRealtime() + windowMillis
        var responses = 0
        var newPages = 0
        var orders = 0
        var endSeen = false
        while (SystemClock.elapsedRealtime() < until) {
            val capture = withTimeoutOrNull(300) { session.messages.receive() } ?: continue
            onCapture(capture)
            registry.rememberCapture(account, capture)
            if (capture.kind == "end") {
                if (capture.url.contains("/orders.html")) endSeen = true
                continue
            }
            if (capture.kind != "response" || Uri.parse(capture.url).path != ORDER_PATH) continue
            val page = parseOrders(capture) ?: continue
            responses++
            val fresh = page.orders.filter { emitted[it.remoteId] != it }
            fresh.forEach { emitted[it.remoteId] = it }
            orders += fresh.size
            if (fresh.isNotEmpty()) onBatch(OrderBatch(fresh, observedCount = page.remoteIds.size))
            if (capture.requestType == ledger.type) {
                if (ledger.record(capture.requestPage, page.remoteIds.toList(), page.remoteIds.size)) newPages++
            }
        }
        return PddDrain(responses, newPages, orders, endSeen)
    }

    private suspend fun scroll(session: PlatformWebView) {
        val raw = withTimeoutOrNull(3_000) { session.evaluateAwait(CaptureScripts.pddScroll) } ?: return
        val inner = (platformJson.parseToJsonElement(raw) as? JsonPrimitive)?.contentOrNull.orEmpty()
        if (inner.isBlank()) return
        val info = platformJson.decodeFromString<PddScrollInfo>(inner)
        if (info.moved) return
        val vw = info.vw.toFloat().takeIf { it > 0f } ?: 1200f
        val vh = info.vh.toFloat().takeIf { it > 0f } ?: 2670f
        if (info.w > 4.0 && info.h > 4.0) {
            val cx = (info.x + info.w / 2).toFloat()
            session.swipePage(cx, (info.y + info.h * 0.78).toFloat(), cx, (info.y + info.h * 0.22).toFloat(), vw, vh)
        } else {
            session.swipePage(vw / 2, vh * 0.8f, vw / 2, vh * 0.2f, vw, vh)
        }
    }

    override fun parseOrders(capture: CapturedResponse): OrderPage? {
        if (capture.kind != "response" || Uri.parse(capture.url).path != ORDER_PATH) return null
        val root = platformResponse(capture.body, "拼多多")
        val code = root.text("error_code")
        if (code.isNotBlank() && code != "0") throw AppFailure("拼多多返回业务错误 $code", FailureKind.SERVICE_ERROR)
        val list = root.requiredArray("orders").map { it.requireObject("拼多多订单") }
        val orders = list.mapNotNull { order ->
            val id = order.requiredText("order_sn")
            val statusText = order.requiredText("order_status_prompt")
            if (closedOrder(statusText)) return@mapNotNull null
            val goods = order.requiredArray("order_goods").map { it.requireObject("拼多多商品") }
            val link = order.requiredText("order_link_url")
            val status = orderStatus(statusText)
            val createdAt = order.text("order_time").takeIf(String::isNotBlank)?.let(::parsePlatformTime)
            ImportedOrder(id, goods.map { it.text("goods_name") }.filter(String::isNotBlank).joinToString("、").ifBlank { "拼多多订单" },
                status, createdAt, resolvePddLink(link), imageUrl(goods.firstOrNull()?.text("thumb_url").orEmpty()),
                if (status == ParcelStatus.WAITING_SHIPMENT) emptyList()
                else listOf(ImportedShipment(id, order.text("tracking_number").takeIf(String::isNotBlank), status = status)))
        }
        return OrderPage(orders, list.map { it.requiredText("order_sn") }.toSet(), complete = list.isEmpty())
    }

    private fun resolvePddLink(link: String): String = resolveOrderUrl(link, "拼多多")

    override fun trackingAction(session: PlatformWebView, source: SourceRef) {
        val uri = Uri.parse(session.currentUrl)
        if (uri.path == "/order.html" && uri.getQueryParameter("order_sn") == source.remoteOrderId) {
            session.evaluate(CaptureScripts.pddTracking)
        }
    }

    override fun parseTracking(capture: CapturedResponse, source: SourceRef): ImportedShipment? {
        val uri = Uri.parse(capture.url)
        if (capture.kind == "trackingUnavailable" && uri.path == "/order.html" && uri.getQueryParameter("order_sn") == source.remoteOrderId) {
            throw AppFailure("拼多多该订单页面未提供物流入口", FailureKind.SERVICE_ERROR)
        }
        if (capture.kind != "dom" || uri.path != "/goods_express.html") return null
        if (uri.getQueryParameter("order_sn") != source.remoteOrderId) return null
        val points = mutableListOf<TrackingEventData>()
        var time = ""
        var description = ""
        val date = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}\\s+[0-9]{2}:[0-9]{2}(?::[0-9]{2})?")
        val lines = capture.body.lines().map(String::trim)
        val keywords = listOf("快件", "包裹", "派件", "揽收", "签收", "发往", "到达", "转运", "投递", "取件", "出库", "配送", "妥投", "物流", "运输", "送达", "快递")
        val firstDate = lines.indexOfFirst { date.containsMatchIn(it) }
        val preceding = lines.take(firstDate.coerceAtLeast(0)).lastOrNull(String::isNotBlank).orEmpty()
        val descriptionFirst = firstDate >= 0 && date.matches(lines[firstDate]) &&
            preceding.length >= 6 && keywords.any(preceding::contains)
        fun emit() { if (time.isNotBlank() && description.isNotBlank()) points.add(trackingEvent(time, description)) }
        for ((index, line) in lines.withIndex()) {
            if (Regex("即将恢复|本店已拼|全店总售|已抢|券后|立减|未发货秒退|24小时发货|退货包运费").containsMatchIn(line)) break
            val match = date.find(line)
            if (match != null) {
                if (descriptionFirst) {
                    val text = lines.take(index).lastOrNull(String::isNotBlank).orEmpty()
                    if (text.length >= 6 && keywords.any(text::contains)) points.add(trackingEvent(match.value, text))
                } else {
                    emit()
                    time = match.value
                    description = listOf(line.substring(0, match.range.first), line.substring(match.range.last + 1))
                        .map(String::trim).filter(String::isNotBlank).joinToString(" ")
                }
            } else if (time.isNotBlank() && line.length >= 6 &&
                keywords.any(line::contains)) {
                description = listOf(description, line).filter(String::isNotBlank).joinToString(" ")
            }
        }
        emit()
        if (points.isEmpty()) return null
        val number = uri.getQueryParameter("tracking_number")?.takeIf(String::isNotBlank)
            ?: throw AppFailure("拼多多物流页面没有提供运单号码", FailureKind.UNSUPPORTED_RESPONSE)
        val carrier = Regex("([^\\n:：]{0,20}(?:快递|速递|速运|物流)[^\\n:：]{0,8})\\s*[:：]\\s*" + Regex.escape(number))
            .find(capture.body)?.groupValues?.get(1)?.trim()?.takeIf(String::isNotBlank)
            ?: throw AppFailure("拼多多物流页面未提供可识别的承运商", FailureKind.UNSUPPORTED_RESPONSE)
        val latest = points.maxBy { it.time }
        return ImportedShipment(source.remoteShipmentId, number, carrier, status = parcelStatus(latest.text), pickupCode = pickupCode(latest.text), events = points)
    }

    private data class PddDrain(val responses: Int, val newPages: Int, val orders: Int, val endSeen: Boolean)

    private companion object {
        const val ORDER_PATH = "/proxy/api/api/aristotle/order_list_v4"
        const val ORDER_BUDGET = 120_000L
        const val TAB_WAIT_MILLIS = 8_000L
        const val SCROLL_WAIT_MILLIS = 2_500L
        const val MAX_IDLE_SCROLLS = 3
        const val MIN_TAB_MILLIS = 20_000L
        val tabs = listOf(
            PddTab("待收货", "unreceived"),
            PddTab("待发货", "unshipping"),
            PddTab("待付款", "unpaidV2"),
            PddTab("评价", "unrated"),
            PddTab("待分享", "pending_share"),
        )
    }
}

internal class DouyinAdapter(private val registry: PlatformRegistry) : PlatformAdapter {
    override val platform = Platform.DOUYIN
    override val loginUrl = "https://www.douyin.com/"
    override val orderListUrl = "https://mall.douyin.com/"
    override val allowedOrigins = setOf("https://www.douyin.com", "https://mall.douyin.com")

    override suspend fun sync(account: Account, onBatch: suspend (OrderBatch) -> Unit) = withContext(Dispatchers.Main.immediate) {
        throw AppFailure("抖音订单通过官方 App 页面采集，不支持网页同步", FailureKind.CONFIGURATION)
    }

    override suspend fun tracking(account: Account, source: SourceRef): ImportedShipment {
        sync(account) { }
        throw AppFailure("抖音物流响应结构需要通过真实账号确认", FailureKind.UNSUPPORTED_RESPONSE)
    }
    override suspend fun openTrackingSession(account: Account): TrackingSession = object : TrackingSession {
        override suspend fun query(source: SourceRef) = tracking(account, source)
        override fun close() = Unit
    }
}
