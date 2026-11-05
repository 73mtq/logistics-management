package com.logisticsmanagement.app.platform

import com.logisticsmanagement.app.model.AppFailure
import com.logisticsmanagement.app.model.FailureKind
import com.logisticsmanagement.app.model.ParcelStatus
import com.logisticsmanagement.app.model.TrackingEventData
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.SerializationException
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.MonthDay
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

internal val platformJson = Json { ignoreUnknownKeys = true }

internal fun platformResponse(body: String, label: String): JsonObject = try {
    platformJson.parseToJsonElement(body).requireObject(label)
} catch (failure: SerializationException) {
    throw AppFailure("$label 响应的 JSON 格式无效", FailureKind.UNSUPPORTED_RESPONSE)
}

internal fun JsonObject.text(key: String): String = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
internal fun JsonObject.objectOrNull(key: String): JsonObject? = get(key) as? JsonObject
internal fun JsonObject.arrayOrNull(key: String): JsonArray? = get(key) as? JsonArray
internal fun JsonElement.requireObject(label: String): JsonObject = this as? JsonObject
    ?: throw AppFailure("$label 响应格式尚未支持", FailureKind.UNSUPPORTED_RESPONSE)
internal fun JsonObject.requiredText(key: String): String = text(key).takeIf(String::isNotBlank)
    ?: throw AppFailure("平台响应缺少 $key", FailureKind.UNSUPPORTED_RESPONSE)
internal fun JsonObject.requiredArray(key: String): JsonArray = arrayOrNull(key)
    ?: throw AppFailure("平台响应缺少 $key 数组", FailureKind.UNSUPPORTED_RESPONSE)
internal fun jsonObjectBody(value: JsonElement?): JsonObject = when (value) {
    is JsonObject -> value
    is JsonPrimitive -> platformResponse(value.content, "业务数据")
    else -> throw AppFailure("平台没有提供业务数据", FailureKind.UNSUPPORTED_RESPONSE)
}

internal fun parcelStatus(text: String): ParcelStatus = when {
    listOf("异常", "滞留", "退回", "拒收", "丢失", "破损").any(text::contains) -> ParcelStatus.EXCEPTION
    listOf("待取件", "待自提", "等待取件", "请取件").any(text::contains) -> ParcelStatus.PICKUP
    listOf("已签收", "签收成功", "已送达", "妥投", "交易成功", "已完成", "待评价").any(text::contains) -> ParcelStatus.SIGNED
    listOf("取件码", "到达驿站", "放入快递柜").any(text::contains) -> ParcelStatus.PICKUP
    listOf("派送", "派件", "配送中", "正在配送").any(text::contains) -> ParcelStatus.DELIVERING
    listOf("待发货", "未发货", "待成团", "等待揽收").any(text::contains) -> ParcelStatus.WAITING_SHIPMENT
    listOf("运输", "转运", "发往", "到达", "揽收", "已发货", "待收货").any(text::contains) -> ParcelStatus.IN_TRANSIT
    else -> ParcelStatus.UNKNOWN
}

internal fun closedOrder(text: String): Boolean = listOf("取消", "关闭", "退款", "售后", "待付款", "未支付").any(text::contains)
internal fun orderStatus(text: String): ParcelStatus = when (text.trim()) {
    "完成", "交易完成", "交易成功", "已完成", "已收货", "已签收", "签收成功", "待评价", "已评价" -> ParcelStatus.SIGNED
    else -> parcelStatus(text)
}
internal fun imageUrl(value: String): String? = when {
    value.startsWith("//") -> "https:$value"
    value.startsWith("https://") -> value
    else -> null
}

internal fun parsePlatformTime(value: String): Long = try {
    val text = value.trim()
    if (text.matches(Regex("[0-9]{10}|[0-9]{13}"))) {
        val number = text.toLong()
        if (text.length == 10) number * 1000 else number
    } else if (text.endsWith("Z")) {
        Instant.parse(text).toEpochMilli()
    } else {
        val format = when {
            text.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2}")) -> "yyyy-MM-dd HH:mm:ss"
            text.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}")) -> "yyyy-MM-dd HH:mm"
            text.matches(Regex("[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2}")) -> "MM-dd HH:mm:ss"
            text.matches(Regex("[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}")) -> "MM-dd HH:mm"
            else -> throw AppFailure("平台日期格式尚未支持", FailureKind.UNSUPPORTED_RESPONSE)
        }
        val zone = ZoneId.of("Asia/Shanghai")
        val millis = if (format.startsWith("MM-dd")) {
            val dateText = text.substringBefore(' ')
            val timeText = text.substringAfter(' ')
            val monthDay = MonthDay.parse(dateText, DateTimeFormatter.ofPattern("MM-dd"))
            val time = LocalTime.parse(timeText, DateTimeFormatter.ofPattern(if (format.endsWith(":ss")) "HH:mm:ss" else "HH:mm"))
            val now = LocalDateTime.now(zone)
            var candidate = LocalDateTime.of(monthDay.atYear(now.year), time)
            if (candidate.isAfter(now)) candidate = candidate.minusYears(1)
            candidate.atZone(zone).toInstant().toEpochMilli()
        } else {
            LocalDateTime.parse(text, DateTimeFormatter.ofPattern(format)).atZone(zone).toInstant().toEpochMilli()
        }
        millis
    }
} catch (failure: DateTimeParseException) {
    throw AppFailure("平台返回了无效的日期", FailureKind.UNSUPPORTED_RESPONSE)
}

internal fun trackingEvent(time: String, text: String, status: String = ""): TrackingEventData {
    if (time.isBlank() || text.isBlank()) throw AppFailure("物流记录缺少时间或描述", FailureKind.UNSUPPORTED_RESPONSE)
    return TrackingEventData(parsePlatformTime(time), text, status)
}

internal fun pickupCode(text: String): String? = Regex("(?:取件码|取货码|提货码|自提码|驿站码|取件验证码)[:：\\s]*([A-Za-z0-9][A-Za-z0-9-]{1,12})")
    .find(text)?.groupValues?.get(1)
