package com.logisticsmanagement.app.platform

import com.logisticsmanagement.app.model.ParcelStatus
import com.logisticsmanagement.app.model.TrackingEventData
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
    private val timePattern = Regex("(\\d{2})-(\\d{2})\\s+(\\d{2}):(\\d{2})")
    private val zone = ZoneId.of("Asia/Shanghai")

    fun parse(text: String, capturedAt: Long): OcrLogisticsResult? {
        val lines = text.lines().map { it.replace('\u00A0', ' ').trim() }.filter { it.isNotBlank() }
        val carrierLine = lines.firstOrNull { line -> carriers.any(line::contains) && Regex("\\d{10,}").containsMatchIn(line) }
            ?: return null
        val carrier = carriers.first { carrierLine.contains(it) }
        val tracking = Regex("\\d{10,}").find(carrierLine)?.value ?: return null
        val events = lines.mapNotNull { line ->
            val match = timePattern.find(line) ?: return@mapNotNull null
            val time = time(match.groupValues[1].toInt(), match.groupValues[2].toInt(),
                match.groupValues[3].toInt(), match.groupValues[4].toInt(), capturedAt)
            val statusWord = statuses.firstOrNull { line.contains(it.first) }?.first.orEmpty()
            TrackingEventData(time, line, statusWord)
        }.distinctBy { it.time to it.text }.sortedBy { it.time }
        val status = if (lines.any { it.contains("已签收") }) ParcelStatus.SIGNED
        else statuses.firstOrNull { (word, _) -> events.any { it.text.contains(word) } }?.second ?: ParcelStatus.UNKNOWN
        return OcrLogisticsResult(carrier, tracking, status, events, lines)
    }

    private fun time(month: Int, day: Int, hour: Int, minute: Int, capturedAt: Long): Long {
        val captured = LocalDateTime.ofInstant(Instant.ofEpochMilli(capturedAt), zone)
        var value = LocalDateTime.of(captured.year, month, day, hour, minute)
        if (value.isAfter(captured.plusDays(1))) value = value.minusYears(1)
        return value.atZone(zone).toInstant().toEpochMilli()
    }
}
