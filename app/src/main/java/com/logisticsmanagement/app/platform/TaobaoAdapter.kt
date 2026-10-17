package com.logisticsmanagement.app.platform

import android.net.Uri
import android.os.SystemClock
import com.logisticsmanagement.app.model.Account
import com.logisticsmanagement.app.model.AppFailure
import com.logisticsmanagement.app.model.FailureKind
import com.logisticsmanagement.app.model.ImportedOrder
import com.logisticsmanagement.app.model.ImportedShipment
import com.logisticsmanagement.app.model.Platform
import com.logisticsmanagement.app.model.ParcelStatus
import com.logisticsmanagement.app.model.SourceRef
import com.logisticsmanagement.app.model.OrderBatch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

// 参考：ExpressAssistant/api/TbOrders.kt 和 ui/CaiNiaoGoodsResolver.kt；MIT 许可。
internal class TaobaoAdapter(private val registry: PlatformRegistry) : PlatformAdapter {
    override val platform = Platform.TAOBAO
    override val loginUrl = "https://login.m.taobao.com/login.htm"
    override val orderListUrl = "https://h5.m.taobao.com/mlapp/olist.html"
    override val allowedOrigins = setOf("https://login.m.taobao.com", "https://h5.m.taobao.com", "https://h5api.m.taobao.com", "https://acs.m.taobao.com", "https://pages-g.m.taobao.com", "https://page.cainiao.com", "https://login.taobao.com", "https://www.taobao.com", "https://main.m.taobao.com", "https://market.m.taobao.com", "https://m.m.taobao.com", "https://tb.ele.me", "https://h5.ele.me")
    private val appKey = "12574478"
    private val client = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build()

    override suspend fun sync(account: Account, onBatch: suspend (OrderBatch) -> Unit) = sync(account, {}, onBatch)

    internal suspend fun sync(account: Account, onCapture: (CapturedResponse) -> Unit, onBatch: suspend (OrderBatch) -> Unit) {
        withContext(Dispatchers.Main.immediate) { requirePlatformLoginSession(registry.profiles, account) }
        val seen = mutableSetOf<String>()
        var page = 1
        repeat(300) {
            val root = mtop(account, "h5api.m.taobao.com", "mtop.taobao.order.queryboughtlistv2", orderQueryData(page), onCapture)
            val parsed = parseOrders(root)
            if (parsed.remoteIds.isEmpty() && parsed.complete) return
            val fresh = parsed.remoteIds.filter { seen.add(it) }.toSet()
            if (fresh.isEmpty()) throw AppFailure("淘宝没有返回新的分页数据，请在订单网页检查加载结果", FailureKind.UNSUPPORTED_RESPONSE)
            onBatch(OrderBatch(parsed.orders.filter { it.remoteId in fresh }, parsed.excludedRemoteIds.intersect(fresh), parsed.remoteIds.size))
            if (parsed.complete) return
            val control = root.objectOrNull("data")?.objectOrNull("global")?.objectOrNull("pageControl")
            page = if (control == null) page + 1 else control.requiredText("nextPageIndex").toIntOrNull()?.takeIf { it > page }
                ?: throw AppFailure("淘宝续页编号格式尚未支持", FailureKind.UNSUPPORTED_RESPONSE)
        }
        throw AppFailure("淘宝订单分页超过当前采集范围，已取得的订单已经保存", FailureKind.UNSUPPORTED_RESPONSE)
    }

    internal fun parseOrders(root: JsonObject): OrderPage {
        val envelope = root.objectOrNull("data") ?: throw AppFailure("淘宝订单响应缺少 data", FailureKind.UNSUPPORTED_RESPONSE)
        val inner = if (envelope.containsKey("result")) jsonObjectBody(envelope["result"])
            else envelope.objectOrNull("data") ?: throw AppFailure("淘宝订单响应格式尚未支持", FailureKind.UNSUPPORTED_RESPONSE)
        val ids = mutableSetOf<String>()
        val excluded = mutableSetOf<String>()
        val orders = if (inner.containsKey("mainOrders")) {
            inner.requiredArray("mainOrders").mapNotNull { node ->
                val order = node.requireObject("淘宝订单")
                val id = order.requiredText("id")
                ids.add(id)
                val extra = order.objectOrNull("extra")
                val miniapp = if (order.containsKey("operations")) order.requiredArray("operations").any { operation ->
                    val fields = operation.requireObject("淘宝订单操作")
                    fields.text("id") == "buyAgain" && flashOrderSource(fields.text("url"), allowLaunch = true)
                } else false
                if (extra?.text("bizType") == "5000" && extra.text("vertical") == "true" && miniapp) {
                    excluded.add(id)
                    return@mapNotNull null
                }
                val statusText = order.objectOrNull("statusInfo")?.text("text").orEmpty()
                if (closedOrder(statusText)) return@mapNotNull null
                val items = order.requiredArray("subOrders").map { it.requireObject("淘宝商品").objectOrNull("itemInfo") }
                val title = items.mapNotNull { it?.text("title")?.takeIf(String::isNotBlank) }.joinToString("、").ifBlank { "淘宝订单" }
                val createdAt = order.objectOrNull("orderInfo")?.text("createTime")?.takeIf(String::isNotBlank)?.let(::parsePlatformTime)
                makeOrder(id, statusText, title, items.firstOrNull()?.text("pic").orEmpty(), createdAt).second
            }
        } else {
            val main = inner.filterKeys { it.startsWith("Main_") }
            if (main.isEmpty() && inner.isNotEmpty()) throw AppFailure("淘宝订单响应结构尚未支持", FailureKind.UNSUPPORTED_RESPONSE)
            main.mapNotNull { (key, value) ->
                val fields = value.requireObject("淘宝订单").objectOrNull("fields")
                    ?: throw AppFailure("淘宝订单响应缺少 fields", FailureKind.UNSUPPORTED_RESPONSE)
                val id = fields.text("orderId").ifBlank { key.removePrefix("Main_") }
                if (id.isBlank()) throw AppFailure("淘宝订单缺少唯一标识", FailureKind.UNSUPPORTED_RESPONSE)
                ids.add(id)
                val components = if (envelope.containsKey("hierarchy")) orderComponents(key, envelope, inner) else emptyList()
                val seller = components.filter { it.first.startsWith("sellerInfo_") }.singleOrNull()?.second?.objectOrNull("fields")
                if (components.isNotEmpty() && seller == null) throw AppFailure("淘宝订单缺少对应店铺资料", FailureKind.UNSUPPORTED_RESPONSE)
                if (fields.text("orderType") == "sg" && flashOrderSource(seller?.text("detailUrl").orEmpty())) {
                    excluded.add(id)
                    return@mapNotNull null
                }
                val statusText = seller?.objectOrNull("status")?.text("text") ?: fields.text("orderStatus")
                if (closedOrder(statusText)) return@mapNotNull null
                val items = components.filter { it.first.startsWith("item_") }.map { (_, node) ->
                    val itemFields = node.objectOrNull("fields") ?: throw AppFailure("淘宝商品缺少 fields", FailureKind.UNSUPPORTED_RESPONSE)
                    if (itemFields.objectOrNull("basicInfo")?.text("orderId") != id) throw AppFailure("淘宝商品与订单编号不匹配", FailureKind.UNSUPPORTED_RESPONSE)
                    itemFields.objectOrNull("item") ?: throw AppFailure("淘宝商品缺少 item", FailureKind.UNSUPPORTED_RESPONSE)
                }
                val title = items.map { it.text("title") }.filter(String::isNotBlank).joinToString("、").ifBlank { "淘宝订单" }
                makeOrder(id, statusText, title, items.firstOrNull()?.text("pic").orEmpty()).second
            }
        }
        val control = envelope.objectOrNull("global")?.objectOrNull("pageControl")
        val complete = if (control == null) ids.isEmpty() else when (control.requiredText("hasMore")) {
            "true" -> false
            "false" -> true
            else -> throw AppFailure("淘宝分页结束标志格式尚未支持", FailureKind.UNSUPPORTED_RESPONSE)
        }
        return OrderPage(orders, ids, complete = complete, excludedRemoteIds = excluded)
    }

    private fun flashOrderSource(value: String, allowLaunch: Boolean = false): Boolean {
        if (value.isBlank()) return false
        val uri = Uri.parse(value)
        if (uri.scheme == "https" && uri.host == "tb.ele.me" && uri.port in listOf(-1, 443) &&
            uri.path == "/app/eleme-newretail-c-h5/order-detail-unicorn/order-detail") {
            return !uri.getQueryParameter("orderId").isNullOrBlank()
        }
        if (uri.scheme != "https" || uri.host != "m.duanqu.com" || uri.port !in listOf(-1, 443) ||
            uri.getQueryParameter("_ariver_appid") != "8251537") return false
        if (uri.path !in listOf("", "/")) return false
        val page = uri.getQueryParameter("page") ?: return allowLaunch
        val route = Uri.parse(page)
        if (route.scheme == "plugin-private" && route.host == "2021001185671035" &&
            route.path == "/pages/ele-order-detail-tb/ele-order-detail-tb") {
            return !route.getQueryParameter("orderId").isNullOrBlank()
        }
        if (route.path != "pages/webview-redirect/webview-redirect") return false
        val detail = Uri.parse(route.getQueryParameter("url") ?: return false)
        return detail.scheme == "https" && detail.host == "tb.ele.me" && detail.port in listOf(-1, 443) &&
            detail.path == "/app/eleme-newretail-c-h5/order-detail-unicorn/order-detail" &&
            !detail.getQueryParameter("orderId").isNullOrBlank()
    }

    private fun orderComponents(key: String, envelope: JsonObject, data: JsonObject): List<Pair<String, JsonObject>> {
        val structure = envelope.objectOrNull("hierarchy")?.objectOrNull("structure")
            ?: throw AppFailure("淘宝订单响应缺少 hierarchy.structure", FailureKind.UNSUPPORTED_RESPONSE)
        val pending = ArrayDeque<String>()
        val visited = mutableSetOf<String>()
        val result = mutableListOf<Pair<String, JsonObject>>()
        pending.add(key)
        while (pending.isNotEmpty()) {
            val current = pending.removeFirst()
            if (!visited.add(current)) throw AppFailure("淘宝订单组件关系重复", FailureKind.UNSUPPORTED_RESPONSE)
            val node = data[current]?.requireObject("淘宝订单组件")
                ?: throw AppFailure("淘宝订单组件缺少资料", FailureKind.UNSUPPORTED_RESPONSE)
            result.add(current to node)
            if (structure.containsKey(current)) {
                for (child in structure.requiredArray(current)) {
                    val value = child as? JsonPrimitive
                    if (value == null || !value.isString) throw AppFailure("淘宝订单组件编号格式尚未支持", FailureKind.UNSUPPORTED_RESPONSE)
                    pending.add(value.content)
                }
            }
        }
        return result
    }

    private fun orderQueryData(page: Int): JsonObject = buildJsonObject {
        put("tabCode", "all"); put("page", page); put("OrderType", "OrderList")
        put("templateConfigVersion", "0"); put("appName", "tborder"); put("appVersion", "3.0")
        put("condition", buildJsonObject { put("version", "1.0.0"); put("appChannel", "") }.toString())
        put("ttid", "201200@taobao_h5_9.18.0"); put("requestIdentity", "#t#ip#h5")
        put("extParams", buildJsonObject { put("useNewDetail", true); put("appChannel", "") }.toString())
    }

    internal suspend fun diagnoseOrderQuery(account: Account): JsonObject =
        mtop(account, "h5api.m.taobao.com", "mtop.taobao.order.queryboughtlistv2", orderQueryData(1))

    private fun makeOrder(id: String, statusText: String, title: String, picture: String, createdAt: Long? = null): Pair<String, ImportedOrder> {
        val status = orderStatus(statusText)
        val detail = Uri.parse("https://pages-g.m.taobao.com/wow/z/app/mtb/logisticsV2/h5-detail").buildUpon()
            .appendQueryParameter("x-ssr", "true").appendQueryParameter("bizOrderId", id).build().toString()
        return statusText to ImportedOrder(id, title, status, createdAt, detail, imageUrl(picture),
            if (status == ParcelStatus.WAITING_SHIPMENT) emptyList() else listOf(ImportedShipment(id, status = status)))
    }

    override suspend fun tracking(account: Account, source: SourceRef): ImportedShipment = withContext(Dispatchers.Main.immediate) {
        openTrackingSession(account).use { it.query(source) }
    }

    override suspend fun openTrackingSession(account: Account): TrackingSession = openTrackingSession(account) {}

    internal suspend fun openTrackingSession(account: Account, onCapture: (CapturedResponse) -> Unit): TrackingSession = withContext(Dispatchers.Main.immediate) {
        requirePlatformLoginSession(registry.profiles, account)
        object : TrackingSession {
            private var session = PlatformWebView(registry.context, account, this@TaobaoAdapter, registry.profiles, false)
            override suspend fun query(source: SourceRef): ImportedShipment {
                require(source.accountId == account.id && source.platform == platform)
                if (session.isClosed) session = PlatformWebView(registry.context, account, this@TaobaoAdapter, registry.profiles, false)
                return readTracking(account, source, session, onCapture)
            }
            override fun close() = session.close()
        }
    }

    private suspend fun readTracking(account: Account, source: SourceRef, session: PlatformWebView, onCapture: (CapturedResponse) -> Unit): ImportedShipment {
        val deadline = SystemClock.elapsedRealtime() + 45_000
        session.load(source.orderUrl)
        while (SystemClock.elapsedRealtime() < deadline) {
            val capture = withTimeoutOrNull(1_000) { session.messages.receive() }
            if (capture != null) {
                onCapture(capture)
                registry.rememberCapture(account, capture)
                if (capture.kind == "ssr" && Uri.parse(capture.url).getQueryParameter("bizOrderId") == source.remoteOrderId) {
                    val result = parseSsr(capture.body, source.remoteShipmentId)
                    android.util.Log.i("ParcelTracking", "trackingReady orderId=${source.remoteOrderId} number=${result.trackingNumber} carrier=${result.carrier}")
                    return result
                }
            }
        }
        throw AppFailure("淘宝物流页面没有提供已支持的物流响应，请打开来源网页完成验证", FailureKind.NEEDS_VERIFICATION)
    }

    internal fun parseSsr(body: String, remoteId: String): ImportedShipment {
        val root = platformResponse(body, "淘宝物流")
        if (root["serverDataLoaderError"]?.let { it.toString() } == "true") {
            throw AppFailure("淘宝没有提供该订单的物流信息", FailureKind.SERVICE_ERROR)
        }
        val data = root.objectOrNull("result")?.objectOrNull("data")
            ?: throw AppFailure("淘宝物流响应缺少 result.data", FailureKind.UNSUPPORTED_RESPONSE)
        val block = when {
            data.containsKey("newLogistics") -> data.objectOrNull("newLogistics")
            data.containsKey("logisticsDetailH5") -> data.objectOrNull("logisticsDetailH5")
            else -> throw AppFailure("淘宝物流响应结构尚未支持", FailureKind.UNSUPPORTED_RESPONSE)
        }
        val fields = block?.objectOrNull("fields") ?: throw AppFailure("淘宝物流响应缺少 fields", FailureKind.UNSUPPORTED_RESPONSE)
        val company = fields.objectOrNull("logisticCompany") ?: throw AppFailure("淘宝没有提供承运公司", FailureKind.UNSUPPORTED_RESPONSE)
        val number = fields.text("mailNo").ifBlank { company.text("mailNo") }.takeIf(String::isNotBlank)
            ?: throw AppFailure("淘宝没有提供运单号码", FailureKind.SERVICE_ERROR)
        val stages = fields.requiredArray("multiStage").map { it.requireObject("淘宝物流节点") }
        val events = stages.mapNotNull { stage ->
            val timeText = stage.text("subtitle").ifBlank { stage.text("time") }
            if (timeText.isBlank()) return@mapNotNull null
            val label = stage["labelDesc"]
            val description = if (label is JsonObject) {
                label.arrayOrNull("richContent")?.joinToString("") { it.requireObject("物流描述").text("text") } ?: label.text("text")
            } else stage.text("labelDesc").ifBlank { stage.text("text") }
            trackingEvent(timeText, description, stage.text("title"))
        }
        val latest = events.maxByOrNull { it.time }
        return ImportedShipment(remoteId, number, company.text("name"), status = parcelStatus(latest?.status.orEmpty() + " " + latest?.text.orEmpty()),
            pickupCode = latest?.text?.let(::pickupCode), events = events)
    }

    internal suspend fun mtop(account: Account, host: String, api: String, data: JsonObject, onCapture: (CapturedResponse) -> Unit = {}, includeTtid: Boolean = false): JsonObject {
        val base = "https://$host/h5/$api/1.0/".toHttpUrl()
        val refreshUrl = base.newBuilder().addQueryParameter("appKey", appKey).build()
        val refresh = platformRequest(client, request(account, refreshUrl.toString()))
        applyCookies(account, refreshUrl.toString(), refresh.cookies)
        val token = withContext(Dispatchers.Main.immediate) {
            cookieValues(registry.profiles.profile(account), base.toString())["_m_h5_tk"]?.substringBefore("_")
        }?.takeIf(String::isNotBlank) ?: throw AppFailure("淘宝登录会话缺少查询令牌，请重新登录", FailureKind.NEEDS_LOGIN)
        val timestamp = System.currentTimeMillis().toString()
        val raw = data.toString()
        val sign = MessageDigest.getInstance("MD5").digest("$token&$timestamp&$appKey&$raw".toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        val requestUrl = base.newBuilder().addQueryParameter("jsv", "2.3.18").addQueryParameter("appKey", appKey)
            .addQueryParameter("t", timestamp).addQueryParameter("sign", sign).addQueryParameter("type", "originaljson")
            .addQueryParameter("dataType", "json").addQueryParameter("data", raw)
        if (includeTtid) data.text("ttid").takeIf(String::isNotBlank)?.let { requestUrl.addQueryParameter("ttid", it) }
        val url = requestUrl.build()
        val response = platformRequest(client, request(account, url.toString()))
        applyCookies(account, url.toString(), response.cookies)
        val capture = CapturedResponse("response", url.toString(), response.body, 200)
        onCapture(capture)
        registry.rememberCapture(account, capture)
        return parseMtopResponse(response.body)
    }

    internal fun parseMtopResponse(body: String): JsonObject {
        val root = platformResponse(body, "淘宝")
        val result = root.requiredArray("ret").joinToString(" ") { it.toString() }
        if (!result.contains("SUCCESS::")) {
            val kind = when {
                result.contains("SESSION_EXPIRED") || result.contains("TOKEN_EXPIRED") || result.contains("TOKEN_EMPTY") -> FailureKind.NEEDS_LOGIN
                result.contains("USER_VALIDATE") || result.contains("RGV") || result.contains("ILLEGAL_ACCESS") -> FailureKind.NEEDS_VERIFICATION
                else -> FailureKind.SERVICE_ERROR
            }
            throw AppFailure("淘宝接口查询失败，请在来源网页检查登录与验证状态", kind)
        }
        return root
    }

    private suspend fun request(account: Account, url: String): Request {
        val cookie = withContext(Dispatchers.Main.immediate) { registry.profiles.profile(account).cookieManager.getCookie(url) }.orEmpty()
        return Request.Builder().url(url).header("Cookie", cookie).header("Origin", "https://h5.m.taobao.com")
            .header("Referer", "https://h5.m.taobao.com/").header("Accept", "application/json")
            .header("User-Agent", withContext(Dispatchers.Main.immediate) { android.webkit.WebSettings.getDefaultUserAgent(registry.context) }).build()
    }

    private suspend fun applyCookies(account: Account, url: String, cookies: List<String>) = withContext(Dispatchers.Main.immediate) {
        val manager = registry.profiles.profile(account).cookieManager
        cookies.forEach { cookie -> suspendCancellableCoroutine { continuation ->
            manager.setCookie(url, cookie) { success ->
                check(success) { "平台 Cookie 保存失败" }
                if (continuation.isActive) continuation.resume(Unit)
            }
        } }
        manager.flush()
    }
}
