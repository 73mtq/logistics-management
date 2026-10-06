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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
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
    override val allowedOrigins = setOf("https://login.m.taobao.com", "https://h5.m.taobao.com", "https://h5api.m.taobao.com", "https://acs.m.taobao.com", "https://pages-g.m.taobao.com", "https://page.cainiao.com", "https://login.taobao.com", "https://www.taobao.com", "https://market.m.taobao.com")
    private val appKey = "12574478"
    private val client = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build()

    override suspend fun sync(account: Account, onBatch: suspend (List<ImportedOrder>) -> Unit) {
        withContext(Dispatchers.Main.immediate) { requirePlatformLoginSession(registry.profiles, account) }
        val seen = mutableSetOf<String>()
        for (page in 1..300) {
            val data = buildJsonObject {
                put("tabCode", "all"); put("page", page); put("OrderType", "OrderList")
                put("templateConfigVersion", "0"); put("appName", "tborder"); put("appVersion", "3.0")
                put("condition", buildJsonObject { put("version", "1.0.0"); put("appChannel", "") }.toString())
                put("ttid", "201200@taobao_h5_9.18.0"); put("requestIdentity", "#t#ip#h5")
            }
            val root = mtop(account, "h5api.m.taobao.com", "mtop.taobao.order.queryboughtlistv2", data)
            val envelope = root.objectOrNull("data") ?: throw AppFailure("淘宝订单响应缺少 data", FailureKind.UNSUPPORTED_RESPONSE)
            val inner = if (envelope.containsKey("result")) jsonObjectBody(envelope["result"])
                else envelope.objectOrNull("data") ?: throw AppFailure("淘宝订单响应格式尚未支持", FailureKind.UNSUPPORTED_RESPONSE)
            val orders = if (inner.containsKey("mainOrders")) {
                inner.requiredArray("mainOrders").map { node ->
                    val order = node.requireObject("淘宝订单")
                    val id = order.requiredText("id")
                    val text = order.objectOrNull("statusInfo")?.text("text").orEmpty()
                    val items = order.requiredArray("subOrders").map { it.requireObject("淘宝商品").objectOrNull("itemInfo") }
                    val title = items.mapNotNull { it?.text("title")?.takeIf(String::isNotBlank) }.joinToString("、").ifBlank { "淘宝订单" }
                    makeOrder(id, text, title, items.firstOrNull()?.text("pic").orEmpty())
                }
            } else {
                val main = inner.filterKeys { it.startsWith("Main_") }
                if (main.isEmpty() && inner.isNotEmpty()) throw AppFailure("淘宝订单响应结构尚未支持", FailureKind.UNSUPPORTED_RESPONSE)
                main.map { (key, value) ->
                    val fields = value.requireObject("淘宝订单").objectOrNull("fields")
                        ?: throw AppFailure("淘宝订单响应缺少 fields", FailureKind.UNSUPPORTED_RESPONSE)
                    makeOrder(fields.text("orderId").ifBlank { key.removePrefix("Main_") }, fields.text("orderStatus"), "淘宝订单", "")
                }
            }
            if (orders.isEmpty()) return
            val fresh = orders.filter { seen.add(it.second.remoteId) }
            if (fresh.isEmpty()) throw AppFailure("淘宝没有返回新的分页数据，请在订单网页检查加载结果", FailureKind.UNSUPPORTED_RESPONSE)
            val active = fresh.filterNot { closedOrder(it.first) }.map { it.second }
            if (active.isNotEmpty()) onBatch(active)
        }
        throw AppFailure("淘宝订单分页超过当前采集范围，已取得的订单已经保存", FailureKind.UNSUPPORTED_RESPONSE)
    }

    private fun makeOrder(id: String, statusText: String, title: String, picture: String): Pair<String, ImportedOrder> {
        val status = parcelStatus(statusText)
        val detail = Uri.parse("https://pages-g.m.taobao.com/wow/z/app/mtb/logisticsV2/h5-detail").buildUpon()
            .appendQueryParameter("x-ssr", "true").appendQueryParameter("bizOrderId", id).build().toString()
        return statusText to ImportedOrder(id, title, status, null, detail, imageUrl(picture),
            if (status == ParcelStatus.WAITING_SHIPMENT) emptyList() else listOf(ImportedShipment(id, status = status)))
    }

    override suspend fun tracking(account: Account, source: SourceRef): ImportedShipment = withContext(Dispatchers.Main.immediate) {
        requirePlatformLoginSession(registry.profiles, account)
        val session = PlatformWebView(registry.context, account, this@TaobaoAdapter, registry.profiles, false)
        val deadline = SystemClock.elapsedRealtime() + 45_000
        try {
            session.load(source.orderUrl)
            while (SystemClock.elapsedRealtime() < deadline) {
                val capture = withTimeoutOrNull(1_000) { session.messages.receive() }
                if (capture != null) {
                    registry.rememberCapture(account, capture)
                    if (capture.kind == "ssr") return@withContext parseSsr(capture.body, source.remoteShipmentId)
                }
            }
            throw AppFailure("淘宝物流页面没有提供已支持的物流响应，请打开来源网页完成验证", FailureKind.NEEDS_VERIFICATION)
        } finally { session.close() }
    }

    internal fun parseSsr(body: String, remoteId: String): ImportedShipment {
        val root = platformResponse(body, "淘宝物流")
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
        val events = stages.map { stage ->
            val label = stage["labelDesc"]
            val description = if (label is JsonObject) {
                label.arrayOrNull("richContent")?.joinToString("") { it.requireObject("物流描述").text("text") } ?: label.text("text")
            } else stage.text("labelDesc").ifBlank { stage.text("text") }
            trackingEvent(stage.text("subtitle").ifBlank { stage.text("time") }, description, stage.text("title"))
        }
        val latest = events.maxByOrNull { it.time }
        return ImportedShipment(remoteId, number, company.text("name"), status = parcelStatus(latest?.status.orEmpty() + " " + latest?.text.orEmpty()),
            pickupCode = latest?.text?.let(::pickupCode), events = events)
    }

    internal suspend fun mtop(account: Account, host: String, api: String, data: JsonObject): JsonObject {
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
        val url = base.newBuilder().addQueryParameter("jsv", "2.3.18").addQueryParameter("appKey", appKey)
            .addQueryParameter("t", timestamp).addQueryParameter("sign", sign).addQueryParameter("type", "originaljson")
            .addQueryParameter("dataType", "json").addQueryParameter("data", raw).build()
        val response = platformRequest(client, request(account, url.toString()))
        applyCookies(account, url.toString(), response.cookies)
        registry.rememberCapture(account, CapturedResponse("response", url.toString(), response.body, 200))
        val root = platformResponse(response.body, "淘宝")
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
