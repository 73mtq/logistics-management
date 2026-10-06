package com.logisticsmanagement.app

import android.net.Uri
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.logisticsmanagement.app.model.Platform
import com.logisticsmanagement.app.platform.CapturedResponse
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlatformDiagnosticTest {
    @Test fun inspectSelectedCapture(): Unit = runBlocking {
        val selected = InstrumentationRegistry.getArguments().getString("platform")
        assumeTrue("需要指定真实平台", selected != null)
        val platform = Platform.valueOf(checkNotNull(selected))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val container = (context.applicationContext as LogisticsApplication).container
        val account = checkNotNull(container.repository.getAccounts().firstOrNull { it.platform == platform })
        val shipments = container.repository.getShipments()
        val sources = shipments.flatMap { container.repository.getSources(it.id) }.filter { it.accountId == account.id }
        Log.i("ParcelDiagnostic", "platform=$selected, orders=${container.repository.getOrders().count { it.accountId == account.id }}, sources=${sources.size}, state=${container.repository.getAccount(account.id)?.state}")
        Log.i("ParcelDiagnostic", "orderStates=${container.repository.getOrders().filter { it.accountId == account.id }.groupingBy { it.status }.eachCount()}")
        container.platforms.diagnostic(account).chunked(2500).forEachIndexed { index, text ->
            Log.i("ParcelSchema", "part=$index, schema=$text")
        }
        sources.map { Uri.parse(it.orderUrl) }.map { "${it.host}${safePath(it.path.orEmpty())}" }.distinct().forEach {
            Log.i("ParcelDiagnostic", "sourceEndpoint=$it")
        }
        val json = Json { ignoreUnknownKeys = true }
        val captures = container.credentials.getSecret("account.${account.id}.captures")
            ?.let { json.decodeFromString<List<CapturedResponse>>(it) }.orEmpty()
        for (capture in captures) {
            val url = Uri.parse(capture.url)
            val function = url.getQueryParameter("functionId")?.takeIf { Regex("[A-Za-z_]{1,60}").matches(it) }.orEmpty()
            Log.i("ParcelDiagnostic", "capture=${capture.kind}, endpoint=${url.host}${safePath(url.path.orEmpty())}, function=$function, http=${capture.status}, length=${capture.body.length}")
            if (function == "order_list_m") {
                val root = json.parseToJsonElement(capture.body) as JsonObject
                val body = root["body"] as JsonObject
                val orders = body["orderList"] as JsonArray
                orders.map { item ->
                    val order = item as JsonObject
                    val status = order["orderStatusInfo"] as JsonObject
                    (status["orderStatusName"] as JsonPrimitive).content
                }.distinct().forEach { label ->
                    if (Regex("[\\p{IsHan}\\s（）()·：:,，]{1,40}").matches(label)) {
                        Log.i("ParcelDiagnostic", "orderStatusLabel=$label")
                    } else {
                        Log.i("ParcelDiagnostic", "orderStatusShape=${Regex("[\\p{L}\\p{N}]").replace(label, "x")}, characters=${label.length}")
                    }
                }
                orders.mapNotNull { (it as JsonObject)["submitDate"] as? JsonPrimitive }.map { it.content }.take(3).forEach { date ->
                    Log.i("ParcelDiagnostic", "submitDateShape=${Regex("[\\p{L}\\p{N}]").replace(date, "x")}")
                }
                orders.mapNotNull { (it as JsonObject)["orderDetailLink"] as? JsonObject }
                    .mapNotNull { it["url"] as? JsonPrimitive }.map { Uri.parse(it.content) }
                    .map { "${it.scheme}://${it.host}${safePath(it.path.orEmpty())}" }.distinct().forEach { endpoint ->
                        Log.i("ParcelDiagnostic", "orderDetailEndpoint=$endpoint")
                    }
            }
            if (capture.kind == "dom") {
                capture.body.lines().forEachIndexed { index, line ->
                    val trimmed = line.trim()
                    val date = DATE_ONLY.matchEntire(trimmed)
                    if (date != null) Log.i("ParcelDiagnostic", "dateLine=$index, value=${date.value}")
                    val labels = LABELS.filter(trimmed::contains)
                    if (labels.isNotEmpty()) Log.i("ParcelDiagnostic", "textLine=$index, labels=${labels.joinToString()}, characters=${trimmed.length}")
                    if (index < 80) Log.i("ParcelDiagnostic", "lineShape=$index, shape=${Regex("[\\p{L}\\p{N}]").replace(trimmed, "x").take(160)}")
                }
            }
        }
    }

    private fun safePath(value: String): String = Regex("[0-9]{4,}").replace(value, "[id]")
    private companion object {
        val DATE_ONLY = Regex("[0-9]{2,4}[-年./][0-9]{1,2}[-月./][0-9]{1,2}日?(?:\\s+[0-9]{1,2}:[0-9]{2}(?::[0-9]{2})?)?|[0-9]{1,2}:[0-9]{2}(?::[0-9]{2})?")
        val LABELS = listOf("运单号", "国内承运人", "订单编号", "快件", "包裹", "揽收", "签收", "发往", "到达", "转运", "投递", "取件", "出库", "配送", "妥投", "物流", "运输", "取消", "暂时", "暂无", "过期", "失效", "异常", "错误", "不支持", "未查询", "商品", "登录", "已完成")
    }
}
