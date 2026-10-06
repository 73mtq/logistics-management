package com.logisticsmanagement.app.network

import com.logisticsmanagement.app.model.AppFailure
import com.logisticsmanagement.app.model.FailureKind
import com.logisticsmanagement.app.model.ImportedShipment
import com.logisticsmanagement.app.model.ParcelStatus
import com.logisticsmanagement.app.model.TrackingEventData
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.Request
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.ResolverStyle
import java.util.Locale

class Kuaidi100Client {
    private val http = createHttpClient()
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    suspend fun query(
        carrierCode: String,
        trackingNumber: String,
        phone: String?,
        customer: String,
        key: String,
    ): ImportedShipment {
        validateQuery(carrierCode, trackingNumber, phone, customer, key)
        val param = json.encodeToString(QueryParam(carrierCode, trackingNumber, phone))
        val sign = MessageDigest.getInstance("MD5")
            .digest((param + key + customer).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02X".format(Locale.ROOT, it.toInt() and 0xff) }
        val request = Request.Builder()
            .url("https://poll.kuaidi100.com/poll/query.do")
            .post(FormBody.Builder().add("customer", customer).add("sign", sign).add("param", param).build())
            .build()
        val response = http.readResponse(request)
        if (response.status !in 200..299) {
            val kind = if (response.status == 429) FailureKind.RATE_LIMITED else FailureKind.SERVICE_ERROR
            throw AppFailure("快递100服务请求失败（HTTP ${response.status}）", kind)
        }
        val result = try {
            json.decodeFromString<QueryResponse>(response.body)
        } catch (error: SerializationException) {
            throw AppFailure("快递100返回的数据格式无法识别", FailureKind.UNSUPPORTED_RESPONSE)
        }
        val code = result.returnCode ?: result.status
        if (code != "200" || result.result == false) throw queryFailure(code)
        val events = result.data ?: throw AppFailure("快递100响应缺少物流轨迹", FailureKind.UNSUPPORTED_RESPONSE)
        if (events.isEmpty()) throw AppFailure("快递100尚未查询到物流轨迹，请稍后查询", FailureKind.SERVICE_ERROR)
        if (result.com != carrierCode || result.nu != trackingNumber || result.state.isNullOrBlank()) {
            throw AppFailure("快递100响应中的运单信息不完整或与请求不符", FailureKind.UNSUPPORTED_RESPONSE)
        }
        return ImportedShipment(
            remoteId = trackingNumber,
            trackingNumber = trackingNumber,
            carrierCode = carrierCode,
            status = shipmentStatus(result.state),
            events = events.map { event ->
                if (event.context.isBlank()) throw AppFailure("快递100轨迹缺少内容", FailureKind.UNSUPPORTED_RESPONSE)
                val time = try {
                    LocalDateTime.parse(event.ftime, EVENT_TIME).atZone(CHINA_TIME).toInstant().toEpochMilli()
                } catch (error: DateTimeParseException) {
                    throw AppFailure("快递100轨迹时间格式无法识别", FailureKind.UNSUPPORTED_RESPONSE)
                }
                TrackingEventData(time, event.context, event.status)
            },
        )
    }

    internal fun validateQuery(carrierCode: String, trackingNumber: String, phone: String?, customer: String, key: String) {
        if (customer.isBlank() || key.isBlank()) {
            throw AppFailure("请填写快递100 customer 和 key", FailureKind.CONFIGURATION)
        }
        if (carrierCode.isBlank() || carrierCode != carrierCode.lowercase(Locale.ROOT)) {
            throw AppFailure("请填写小写的快递公司编码", FailureKind.CONFIGURATION)
        }
        if (trackingNumber.length !in 6..32) {
            throw AppFailure("运单号长度需要为6至32个字符", FailureKind.CONFIGURATION)
        }
        if (carrierCode in PHONE_REQUIRED && phone.isNullOrBlank()) {
            throw AppFailure("该快递公司查询需要收件人或寄件人的电话号码", FailureKind.CONFIGURATION)
        }
    }

    private fun queryFailure(code: String?): AppFailure = when (code) {
        "400" -> AppFailure("快递100无法识别查询参数，请检查公司编码、账号额度和请求配置", FailureKind.CONFIGURATION)
        "401" -> AppFailure("快递100不支持该快递公司，请检查公司编码", FailureKind.CONFIGURATION)
        "408" -> AppFailure("快递公司未通过电话号码验证，请检查收件人或寄件人号码", FailureKind.CONFIGURATION)
        "500" -> AppFailure("快递100尚未查询到物流信息，请确认已经发货并稍后查询", FailureKind.SERVICE_ERROR)
        "501" -> AppFailure("快递100服务器错误，请稍后查询", FailureKind.SERVICE_ERROR)
        "502" -> AppFailure("快递100服务器繁忙，请稍后查询", FailureKind.SERVICE_ERROR)
        "503" -> AppFailure("快递100签名验证失败，请检查 customer 和 key", FailureKind.CONFIGURATION)
        "504" -> AppFailure("快递100提示查询频率过高，请在24小时后查询", FailureKind.RATE_LIMITED)
        "601" -> AppFailure("快递100 key 已过期或查询额度不足，请检查账号服务", FailureKind.CONFIGURATION)
        else -> AppFailure("快递100返回了无法识别的查询结果", FailureKind.UNSUPPORTED_RESPONSE)
    }

    private fun shipmentStatus(state: String): ParcelStatus = when (state) {
        "101", "102" -> ParcelStatus.WAITING_SHIPMENT
        "0", "1", "103", "1001", "1002", "1003", "7", "8", "10", "11", "12" -> ParcelStatus.IN_TRANSIT
        "5" -> ParcelStatus.DELIVERING
        "501" -> ParcelStatus.PICKUP
        "3", "301", "302", "303", "304" -> ParcelStatus.SIGNED
        "2", "201", "202", "203", "204", "205", "206", "207", "208", "209", "210", "4", "401", "6", "13", "14" -> ParcelStatus.EXCEPTION
        else -> ParcelStatus.UNKNOWN
    }

    @Serializable
    private data class QueryParam(
        val com: String,
        val num: String,
        val phone: String? = null,
        val resultv2: String = "4",
        val show: String = "0",
        val order: String = "desc",
        val lang: String = "zh",
    )

    @Serializable
    private data class QueryResponse(
        val result: Boolean? = null,
        val returnCode: String? = null,
        val status: String? = null,
        val com: String? = null,
        val nu: String? = null,
        val state: String? = null,
        val data: List<QueryEvent>? = null,
    )

    @Serializable
    private data class QueryEvent(val context: String, val ftime: String, val status: String = "")

    private companion object {
        val PHONE_REQUIRED = setOf("shunfeng", "shunfengkuaiyun", "zhongtong")
        val EVENT_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss", Locale.ROOT)
            .withResolverStyle(ResolverStyle.STRICT)
        val CHINA_TIME: ZoneId = ZoneId.of("Asia/Shanghai")
    }
}
