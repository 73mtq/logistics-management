package com.logisticsmanagement.app.platform

import android.net.Uri
import android.os.SystemClock
import com.logisticsmanagement.app.model.Account
import com.logisticsmanagement.app.model.AppFailure
import com.logisticsmanagement.app.model.FailureKind
import com.logisticsmanagement.app.model.ImportedOrder
import com.logisticsmanagement.app.model.ImportedShipment
import com.logisticsmanagement.app.model.ParcelStatus
import com.logisticsmanagement.app.model.Platform
import com.logisticsmanagement.app.model.SourceRef
import com.logisticsmanagement.app.model.TrackingEventData
import com.logisticsmanagement.app.data.LogisticsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
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
)

internal abstract class BrowserAdapter(protected val registry: PlatformRegistry) : PlatformAdapter {
    abstract fun parseOrders(capture: CapturedResponse): OrderPage?
    abstract fun parseTracking(capture: CapturedResponse, source: SourceRef): ImportedShipment?
    open suspend fun trackingUrl(account: Account, source: SourceRef): String = source.orderUrl
    open fun trackingAction(session: PlatformWebView, source: SourceRef) = Unit

    suspend fun rememberOrderPage(account: Account, page: OrderPage) {
        for ((remoteId, url) in page.trackingUrls) registry.saveAccountSecret(account, trackingKey(remoteId), url)
    }

    protected fun trackingKey(remoteId: String): String = "${platform.name.lowercase()}.tracking.${LogisticsRepository.stableId(remoteId)}"

    override suspend fun sync(account: Account, onBatch: suspend (List<ImportedOrder>) -> Unit) = withContext(Dispatchers.Main.immediate) {
        requirePlatformLoginSession(registry.profiles, account)
        val session = PlatformWebView(registry.context, account, this@BrowserAdapter, registry.profiles, false)
        val received = mutableSetOf<String>()
        val emitted = mutableMapOf<String, ImportedOrder>()
        val deadline = SystemClock.elapsedRealtime() + 90_000
        var nextScroll = SystemClock.elapsedRealtime() + 4_000
        var total: Int? = null
        var responseAt = 0L
        var endAt: Long? = null
        try {
            session.load(orderListUrl)
            while (SystemClock.elapsedRealtime() < deadline) {
                val capture = withTimeoutOrNull(1_000) { session.messages.receive() }
                if (capture != null) {
                    registry.rememberCapture(account, capture)
                    if (capture.kind == "end") endAt = SystemClock.elapsedRealtime()
                    parseOrders(capture)?.let { page ->
                        rememberOrderPage(account, page)
                        responseAt = SystemClock.elapsedRealtime()
                        received.addAll(page.remoteIds)
                        total = page.total ?: total
                        val fresh = page.orders.filter { emitted[it.remoteId] != it }
                        fresh.forEach { emitted[it.remoteId] = it }
                        if (fresh.isNotEmpty()) onBatch(fresh)
                        if (page.complete || total != null && received.size >= checkNotNull(total)) return@withContext
                    }
                }
                val now = SystemClock.elapsedRealtime()
                if (endAt != null && responseAt > 0 && now - maxOf(checkNotNull(endAt), responseAt) >= 3_000) return@withContext
                if (now >= nextScroll && session.currentUrl.isNotBlank()) {
                    session.advanceOrders()
                    nextScroll = now + 3_000
                }
            }
            throw AppFailure("${platform.displayName}订单更新超时，已取得的订单已经保存，请在来源网页完成加载", FailureKind.NETWORK)
        } finally { session.close() }
    }

    override suspend fun tracking(account: Account, source: SourceRef): ImportedShipment = withContext(Dispatchers.Main.immediate) {
        requirePlatformLoginSession(registry.profiles, account)
        val session = PlatformWebView(registry.context, account, this@BrowserAdapter, registry.profiles, false)
        val deadline = SystemClock.elapsedRealtime() + 45_000
        var nextAction = SystemClock.elapsedRealtime() + 3_000
        try {
            session.load(trackingUrl(account, source))
            while (SystemClock.elapsedRealtime() < deadline) {
                val capture = withTimeoutOrNull(1_000) { session.messages.receive() }
                if (capture != null) {
                    registry.rememberCapture(account, capture)
                    parseTracking(capture, source)?.let { return@withContext it }
                }
                val now = SystemClock.elapsedRealtime()
                if (now >= nextAction) { trackingAction(session, source); nextAction = now + 3_000 }
            }
            throw AppFailure("${platform.displayName}物流更新超时，请打开来源网页检查登录与物流记录", FailureKind.NETWORK)
        } finally { session.close() }
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
        val error = root.text("errorCode")
        if (error == "302") throw AppFailure("京东账号需要重新登录", FailureKind.NEEDS_LOGIN)
        if (error == "601") throw AppFailure("京东要求完成安全验证", FailureKind.NEEDS_VERIFICATION)
        if (error.isNotBlank() && error != "0") throw AppFailure("京东返回业务错误 $error", FailureKind.SERVICE_ERROR)
        val body = root.objectOrNull("body") ?: throw AppFailure("京东订单响应缺少 body", FailureKind.UNSUPPORTED_RESPONSE)
        val list = body.requiredArray("orderList").map { it.requireObject("京东订单") }
        val trackingUrls = mutableMapOf<String, String>()
        val orders = list.mapNotNull { order ->
            val id = order.requiredText("orderId")
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
        return OrderPage(orders, list.map { it.requiredText("orderId") }.toSet(), total, total == 0, trackingUrls)
    }

    private fun officialUrl(value: String): String {
        val url = try { URI(orderListUrl).resolve(URI(value)).toString() }
        catch (failure: URISyntaxException) { throw AppFailure("京东订单链接格式尚未支持", FailureKind.UNSUPPORTED_RESPONSE) }
        if (!allowsOrigin(Uri.parse(url), allowedOrigins)) throw AppFailure("京东订单链接没有指向已支持的官方网页", FailureKind.UNSUPPORTED_RESPONSE)
        return url
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
            val error = root.text("errorCode")
            if (error == "302") throw AppFailure("京东账号需要重新登录", FailureKind.NEEDS_LOGIN)
            if (error == "601") throw AppFailure("京东要求完成安全验证", FailureKind.NEEDS_VERIFICATION)
            if (error.isNotBlank() && error != "0") throw AppFailure("京东物流返回业务错误 $error", FailureKind.SERVICE_ERROR)
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

    override fun parseOrders(capture: CapturedResponse): OrderPage? {
        if (capture.kind != "response" || Uri.parse(capture.url).path != "/proxy/api/api/aristotle/order_list_v4") return null
        val root = platformResponse(capture.body, "拼多多")
        val code = root.text("error_code")
        if (code.isNotBlank() && code != "0") throw AppFailure("拼多多返回业务错误 $code，请在网页完成验证", FailureKind.NEEDS_VERIFICATION)
        val list = root.requiredArray("orders").map { it.requireObject("拼多多订单") }
        val orders = list.mapNotNull { order ->
            val id = order.requiredText("order_sn")
            val statusText = order.requiredText("order_status_prompt")
            if (closedOrder(statusText)) return@mapNotNull null
            val goods = order.requiredArray("order_goods").map { it.requireObject("拼多多商品") }
            val link = order.requiredText("order_link_url")
            val status = parcelStatus(statusText)
            ImportedOrder(id, goods.map { it.text("goods_name") }.filter(String::isNotBlank).joinToString("、").ifBlank { "拼多多订单" },
                status, null, resolvePddLink(link), imageUrl(goods.firstOrNull()?.text("thumb_url").orEmpty()),
                if (status == ParcelStatus.WAITING_SHIPMENT) emptyList()
                else listOf(ImportedShipment(id, order.text("tracking_number").takeIf(String::isNotBlank), status = status)))
        }
        return OrderPage(orders, list.map { it.requiredText("order_sn") }.toSet(), complete = list.isEmpty())
    }

    private fun resolvePddLink(link: String): String {
        val url = try { URI(orderListUrl).resolve(URI(link)).toString() }
        catch (failure: URISyntaxException) { throw AppFailure("拼多多订单链接格式尚未支持", FailureKind.UNSUPPORTED_RESPONSE) }
        if (!allowsOrigin(Uri.parse(url), allowedOrigins)) throw AppFailure("拼多多订单链接没有指向已支持的官方网页", FailureKind.UNSUPPORTED_RESPONSE)
        return url
    }

    override fun trackingAction(session: PlatformWebView, source: SourceRef) = session.evaluate(CaptureScripts.pddTracking)

    override fun parseTracking(capture: CapturedResponse, source: SourceRef): ImportedShipment? {
        val uri = Uri.parse(capture.url)
        if (capture.kind != "dom" || uri.path != "/goods_express.html") return null
        val points = mutableListOf<TrackingEventData>()
        var time = ""
        var description = ""
        val date = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}\\s+[0-9]{2}:[0-9]{2}(?::[0-9]{2})?")
        fun emit() { if (time.isNotBlank() && description.isNotBlank()) points.add(trackingEvent(time, description)) }
        for (line in capture.body.lines().map(String::trim)) {
            if (Regex("即将恢复|本店已拼|全店总售|已抢|券后|立减|未发货秒退|24小时发货|退货包运费").containsMatchIn(line)) break
            val match = date.find(line)
            if (match != null) {
                emit()
                time = match.value
                description = line.substring(0, match.range.first).trim()
            } else if (time.isNotBlank() && line.length >= 6 &&
                listOf("快件", "包裹", "派件", "揽收", "签收", "发往", "到达", "转运", "投递", "取件", "出库", "配送", "妥投", "物流", "运输", "送达", "快递").any(line::contains)) {
                description = listOf(description, line).filter(String::isNotBlank).joinToString(" ")
            }
        }
        emit()
        if (points.isEmpty()) return null
        val number = uri.getQueryParameter("tracking_number")?.takeIf(String::isNotBlank)
            ?: throw AppFailure("拼多多物流页面没有提供运单号码", FailureKind.UNSUPPORTED_RESPONSE)
        val carrier = Regex("([^\\n:：]{2,24}(?:快递|速运|物流))\\s*[:：]\\s*" + Regex.escape(number))
            .find(capture.body)?.groupValues?.get(1)?.trim().orEmpty()
        val latest = points.maxBy { it.time }
        return ImportedShipment(source.remoteShipmentId, number, carrier, status = parcelStatus(latest.text), pickupCode = pickupCode(latest.text), events = points)
    }
}

internal class DouyinAdapter(private val registry: PlatformRegistry) : PlatformAdapter {
    override val platform = Platform.DOUYIN
    override val loginUrl = "https://www.douyin.com/"
    override val orderListUrl = "https://mall.douyin.com/"
    override val allowedOrigins = setOf("https://www.douyin.com", "https://mall.douyin.com")

    override suspend fun sync(account: Account, onBatch: suspend (List<ImportedOrder>) -> Unit) = withContext(Dispatchers.Main.immediate) {
        val session = PlatformWebView(registry.context, account, this@DouyinAdapter, registry.profiles, false)
        val page = registry.accountSecret(account, "douyin.orderPage")
        val deadline = SystemClock.elapsedRealtime() + 45_000
        try {
            session.load(page ?: orderListUrl)
            var navigated = false
            while (SystemClock.elapsedRealtime() < deadline) {
                val capture = withTimeoutOrNull(1_000) { session.messages.receive() }
                if (capture != null) {
                    registry.rememberCapture(account, capture)
                    if (capture.kind == "response") {
                        registry.rememberDouyinPage(account, session.currentUrl)
                        throw AppFailure("抖音订单响应已经采集，当前响应结构需要通过真实账号确认", FailureKind.UNSUPPORTED_RESPONSE)
                    }
                }
                if (!navigated && session.currentUrl.isNotBlank()) { session.evaluate(CaptureScripts.douyinOrders); navigated = true }
            }
            throw AppFailure("请打开抖音来源网页，完成登录并进入官方订单页面以采集响应", FailureKind.NEEDS_VERIFICATION)
        } finally { session.close() }
    }

    override suspend fun tracking(account: Account, source: SourceRef): ImportedShipment {
        sync(account) { }
        throw AppFailure("抖音物流响应结构需要通过真实账号确认", FailureKind.UNSUPPORTED_RESPONSE)
    }
}
