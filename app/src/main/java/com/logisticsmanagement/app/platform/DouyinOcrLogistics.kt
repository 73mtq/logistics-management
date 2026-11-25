package com.logisticsmanagement.app.platform

import com.logisticsmanagement.app.model.ParcelStatus
import com.logisticsmanagement.app.model.TrackingEventData
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

internal data class OcrLine(val text: String, val bounds: List<Int>)
internal data class OcrScreen(val text: String, val lines: List<OcrLine>)

internal data class OcrLogisticsResult(
    val carrier: String, val trackingNumber: String, val status: ParcelStatus,
    val events: List<TrackingEventData>, val rawLines: List<String>)

internal object DouyinOcrLogistics {
    private val carriers = listOf("顺丰速运", "顺丰", "韵达快递", "韵达", "圆通速递", "圆通", "中通快递", "中通",
        "申通快递", "申通", "极兔速递", "极兔", "京东物流", "京东", "邮政", "EMS", "德邦", "百世", "安能", "丰网")
    private val statuses = listOf("已签收" to ParcelStatus.SIGNED, "派件中" to ParcelStatus.DELIVERING,
        "派送中" to ParcelStatus.DELIVERING, "运输中" to ParcelStatus.IN_TRANSIT, "已揽收" to ParcelStatus.IN_TRANSIT,
        "已到达" to ParcelStatus.IN_TRANSIT, "待取件" to ParcelStatus.PICKUP, "投递" to ParcelStatus.IN_TRANSIT)
    private val stamps = listOf(
        Regex("(\\d{4})\\s*年\\s*(\\d{1,2})\\s*月\\s*(\\d{1,2})\\s*日\\s*(\\d{1,2})\\s*:\\s*(\\d{2})"),
        Regex("(\\d{4})\\s*-\\s*(\\d{1,2})\\s*-\\s*(\\d{1,2})\\s+(\\d{1,2})\\s*:\\s*(\\d{2})"),
        Regex("(\\d{1,2})\\s*月\\s*(\\d{1,2})\\s*日\\s*(\\d{1,2})\\s*:\\s*(\\d{2})"),
        Regex("(\\d{1,2})\\s*-\\s*(\\d{1,2})\\s+(\\d{1,2})\\s*:\\s*(\\d{2})"),
    )
    private val tracking = Regex("[A-Za-z0-9]{10,25}")
    private val zone = ZoneId.of("Asia/Shanghai")
    private val noise = listOf("展开全部物流信息", "查看更多物流信息", "查看全部物流信息", "收起", "联系快递员",
        "复制", "运单号", "物流详情", "查看全部", "已折叠", "下单时间", "付款时间", "发货时间", "订单编号", "实付")

    fun parse(text: String, capturedAt: Long): OcrLogisticsResult? {
        val lines = text.lines().map { it.replace('\u00A0', ' ').trim() }.filter { it.isNotBlank() }
        val squashed = lines.map(::compact)
        var carrier: String? = null
        var number: String? = null
        for (index in lines.indices) {
            val name = carriers.firstOrNull { squashed[index].contains(it) } ?: continue
            val value = (0..2).firstNotNullOfOrNull { offset -> trackingValue(squashed.getOrNull(index + offset)) } ?: continue
            carrier = name
            number = value
            break
        }
        if (carrier == null || number == null) return null
        val events = mutableListOf<TrackingEventData>()
        var pending: Long? = null
        for (line in lines) {
            val stamp = stamp(line, capturedAt)
            if (stamp == null) {
                val time = pending ?: continue
                if (noise.any(line::contains)) { pending = null; continue }
                events.add(TrackingEventData(time, line, statusWord(line)))
                pending = null
                continue
            }
            val body = stamp.second.trim()
            if (body.isEmpty() || body.all { it in "-—:：,，.。 " }) { pending = stamp.first; continue }
            if (noise.any(body::contains)) continue
            events.add(TrackingEventData(stamp.first, line, statusWord(line)))
        }
        val merged = events.distinctBy { it.time to compact(it.text) }.sortedBy { it.time }
        val status = if (squashed.any { it.contains("已签收") }) ParcelStatus.SIGNED
        else statuses.firstOrNull { (word, _) -> squashed.any { it.contains(word) } }?.second ?: ParcelStatus.UNKNOWN
        return OcrLogisticsResult(checkNotNull(carrier), checkNotNull(number), status, merged, lines)
    }

    private fun compact(value: String) = value.filterNot(Char::isWhitespace)

    private fun trackingValue(line: String?): String? = line
        ?.let { tracking.findAll(it).map { match -> match.value }.firstOrNull { value -> value.any(Char::isDigit) } }

    private fun statusWord(line: String) = statuses.firstOrNull { line.contains(it.first) }?.first.orEmpty()

    private fun stamp(line: String, capturedAt: Long): Pair<Long, String>? {
        for (pattern in stamps) {
            val match = pattern.find(line) ?: continue
            val values = match.groupValues.drop(1).mapNotNull { it.toIntOrNull() }
            val time = when (values.size) {
                5 -> time(values[0], values[1], values[2], values[3], values[4])
                4 -> time(values[0], values[1], values[2], values[3], capturedAt)
                else -> null
            } ?: continue
            return time to line.removeRange(match.range)
        }
        return null
    }

    private fun time(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long? = try {
        LocalDateTime.of(year, month, day, hour, minute).atZone(zone).toInstant().toEpochMilli()
    } catch (failure: DateTimeException) { null }

    private fun time(month: Int, day: Int, hour: Int, minute: Int, capturedAt: Long): Long? = try {
        val captured = LocalDateTime.ofInstant(Instant.ofEpochMilli(capturedAt), zone)
        var value = LocalDateTime.of(captured.year, month, day, hour, minute)
        if (value.isAfter(captured.plusDays(1))) value = value.minusYears(1)
        value.atZone(zone).toInstant().toEpochMilli()
    } catch (failure: DateTimeException) { null }
}
