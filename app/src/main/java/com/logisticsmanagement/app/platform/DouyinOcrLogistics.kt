package com.logisticsmanagement.app.platform

import com.logisticsmanagement.app.model.ParcelStatus
import com.logisticsmanagement.app.model.TrackingEventData

internal data class OcrLine(val text: String, val bounds: List<Int>)
internal data class OcrScreen(val text: String, val lines: List<OcrLine>)
internal data class OcrParcel(val carrier: String, val trackingNumber: String)

internal data class OcrLogisticsResult(
    val carrier: String, val trackingNumber: String, val status: ParcelStatus,
    val events: List<TrackingEventData>, val rawLines: List<String>,
    val parcels: List<OcrParcel> = listOf(OcrParcel(carrier, trackingNumber)),
    val times: List<NativeTrackingTimeEvidence> = emptyList(),
    val unresolved: List<String> = emptyList()) {
    fun problems(readings: Int): List<String> = buildList {
        if (parcels.size != 1) add("当前物流页提供多个包裹，未取得逐件关联依据")
        if (events.isEmpty()) add("物流轨迹为空")
        if (unresolved.isNotEmpty()) add("物流时间无法在订单下单时间至识别时间内唯一定年：${unresolved.first()}")
        if (readings < 2) add("物流页连续两次识别核查")
    }
}

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
    private val noise = listOf("展开全部物流信息", "查看更多物流信息", "查看全部物流信息", "收起", "联系快递员",
        "复制", "运单号", "物流详情", "查看全部", "已折叠", "下单时间", "付款时间", "发货时间", "订单编号", "实付")

    fun parse(text: String, capturedAt: Long, createdAt: Long? = null): OcrLogisticsResult? {
        val lines = text.lines().map { it.replace('\u00A0', ' ').trim() }.filter { it.isNotBlank() }
        val squashed = lines.map(::compact)
        val parcels = mutableListOf<OcrParcel>()
        for (index in lines.indices) {
            val name = carriers.firstOrNull { squashed[index].contains(it) } ?: continue
            val value = (0..2).firstNotNullOfOrNull { offset -> trackingValue(squashed.getOrNull(index + offset)) } ?: continue
            if (parcels.none { it.trackingNumber == value }) parcels.add(OcrParcel(name, value))
        }
        val carrier = parcels.firstOrNull()?.carrier ?: return null
        val number = parcels.first().trackingNumber
        val readings = mutableListOf<Pair<TrackingEventData, NativeTrackingTimeEvidence>>()
        val unresolved = mutableListOf<String>()
        var pending: NativeTrackingTimeEvidence? = null
        for (line in lines) {
            val stamped = stamp(line, capturedAt, createdAt)
            if (stamped == null) {
                val time = pending ?: continue
                if (noise.any(line::contains)) { pending = null; continue }
                if (time.time == null) { unresolved.add("$line（${time.reason}）"); pending = null; continue }
                readings.add(TrackingEventData(time.time, line, statusWord(line)) to time)
                pending = null
                continue
            }
            val (evidence, body) = stamped
            val bodyText = body.trim()
            if (bodyText.isEmpty() || bodyText.all { it in "-—:：,，.。 " }) {
                if (evidence.time == null) unresolved.add("${evidence.raw}（${evidence.reason}）") else pending = evidence
                continue
            }
            if (noise.any(bodyText::contains)) continue
            if (evidence.time == null) { unresolved.add("$line（${evidence.reason}）"); continue }
            readings.add(TrackingEventData(evidence.time, line, statusWord(line)) to evidence)
        }
        val merged = readings.distinctBy { it.first.time to compact(it.first.text) }.sortedBy { it.first.time }
        val status = if (squashed.any { it.contains("已签收") }) ParcelStatus.SIGNED
        else statuses.firstOrNull { (word, _) -> squashed.any { it.contains(word) } }?.second ?: ParcelStatus.UNKNOWN
        return OcrLogisticsResult(checkNotNull(carrier), checkNotNull(number), status, merged.map { it.first }, lines,
            parcels.distinctBy { it.trackingNumber }, merged.map { it.second }, unresolved.distinct())
    }

    private fun compact(value: String) = value.filterNot(Char::isWhitespace)

    private fun trackingValue(line: String?): String? = line
        ?.let { tracking.findAll(it).map { match -> match.value }.firstOrNull { value -> value.any(Char::isDigit) } }

    private fun statusWord(line: String) = statuses.firstOrNull { line.contains(it.first) }?.first.orEmpty()

    private fun stamp(line: String, capturedAt: Long, createdAt: Long?): Pair<NativeTrackingTimeEvidence, String>? {
        for (pattern in stamps) {
            val match = pattern.find(line) ?: continue
            val evidence = DouyinNativeLogistics.resolveTime(match.value, createdAt, capturedAt)
            return evidence to line.removeRange(match.range)
        }
        return null
    }
}
