package com.logisticsmanagement.app.platform

import com.logisticsmanagement.app.model.*
import com.logisticsmanagement.app.network.CarrierCodes
import kotlinx.serialization.Serializable
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.ResolverStyle

@Serializable
internal data class NativeTrackingTimeEvidence(val raw: String, val time: Long?, val lowerBound: Long?, val upperBound: Long,
    val candidateYears: List<Int>, val precision: String, val inferredYear: Boolean, val reason: String = "")

internal data class NativeLogisticsCandidate(val windowId: Int, val shipment: ImportedShipment?,
    val fields: List<NativeFieldEvidence>, val times: List<NativeTrackingTimeEvidence>, val missing: List<String>)

internal object DouyinNativeLogistics {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val carrierPattern = Regex("^([\\u4e00-\\u9fff]{2,20})\\s+([A-Za-z0-9-]{8,40})$")
    private val timePattern = Regex("(?:[0-9]{4}[-年])?[0-9]{2}[-月][0-9]{2}日?\\s+[0-9]{2}:[0-9]{2}(?::[0-9]{2})?")
    private val statusLabels = setOf("已签收", "已揽收", "运输中", "派送中", "待取件", "已发货", "物流异常")

    fun resolveTime(raw: String, createdAt: Long?, capturedAt: Long): NativeTrackingTimeEvidence {
        val normalized = raw.replace("年", "-").replace("月", "-").replace("日", "").trim().replace(Regex("\\s+"), " ")
        val full = normalized.matches(Regex("[0-9]{4}-.*"))
        val precision = if (normalized.substringAfter(' ').count { it == ':' } == 2) "second" else "minute"
        fun result(time: Long?, years: List<Int>, reason: String = "") =
            NativeTrackingTimeEvidence(raw, time, createdAt, capturedAt, years, precision, !full, reason)
        if (createdAt == null || createdAt > capturedAt) return result(null, emptyList(), "缺少有效的同一订单下单时间区间")
        val lower = Instant.ofEpochMilli(createdAt).atZone(zone)
        val upper = Instant.ofEpochMilli(capturedAt).atZone(zone)
        val candidates = mutableListOf<Pair<Int, Long>>()
        val years = if (full) listOf(normalized.take(4).toInt()) else (lower.year..upper.year).toList()
        for (year in years) {
            val text = if (full) normalized else "$year-$normalized"
            val format = DateTimeFormatter.ofPattern(if (precision == "second") "uuuu-MM-dd HH:mm:ss" else "uuuu-MM-dd HH:mm")
                .withResolverStyle(ResolverStyle.STRICT)
            val time = try { LocalDateTime.parse(text, format).atZone(zone).toInstant().toEpochMilli() }
                catch (failure: DateTimeParseException) { continue }
            if (time in createdAt..capturedAt) candidates.add(year to time)
        }
        return if (candidates.size == 1) result(candidates.single().second, candidates.map { it.first })
            else result(null, candidates.map { it.first }, if (candidates.isEmpty()) "物流时间不在订单日期区间内或日期无效" else "物流年份无法唯一确定")
    }

    fun parse(scan: NativeScan, order: Order): NativeLogisticsCandidate? {
        val active = scan.windows.filter { it.active && it.accepted }.map { it.id }
        val pages = DouyinNativeOrders.servicePages(scan).filter { it.windowId in active }
        val matches = pages.flatMap { page -> page.nodes.filter { it.visible }.mapNotNull { node ->
            DouyinNativeOrders.textValues(node).firstNotNullOfOrNull { (property, text) -> carrierPattern.matchEntire(text)?.takeIf {
                it.groupValues[1].contains("快递") || it.groupValues[1].contains("物流")
            }?.let { Triple(page, node, property to it) } }
        } }.distinctBy { it.third.second.value }
        if (matches.isEmpty()) return null
        if (matches.size != 1) throw AppFailure("当前物流窗口提供多个包裹，尚未取得逐件关联依据", FailureKind.UNSUPPORTED_RESPONSE)
        val (page, carrierNode, carrierValue) = matches.single()
        check(page.reader == "service" && page.packageName == DouyinCaptureController.PACKAGE)
        val carrier = carrierValue.second.groupValues[1]
        val number = carrierValue.second.groupValues[2]
        val code = CarrierCodes.resolve(carrier)
        fun field(name: String, value: String, node: NativeNode, property: String = "text") =
            NativeFieldEvidence(name, value, page.windowId, node.path, scan.sequence, order.remoteId,
                valuePaths = listOf(node.path), sourceProperty = property)
        val fields = mutableListOf(field("carrier", carrier, carrierNode, carrierValue.first), field("trackingNumber", number, carrierNode, carrierValue.first))
        val missing = mutableListOf<String>()
        if (code == null) missing.add("承运商规范代码")
        val nodes = page.nodes.filter { it.visible }
        val explicitIds = nodes.flatMap(DouyinNativeOrders::textValues).mapNotNull { (_, text) ->
            Regex("^订单编号\\s*[:：]\\s*([0-9]+)$").matchEntire(text)?.groupValues?.get(1)
        }
        if (explicitIds.any { it != order.remoteId }) throw AppFailure("物流页面订单编号与当前目标不一致", FailureKind.UNSUPPORTED_RESPONSE)
        if (nodes.any { DouyinNativeOrders.label(it) == "订单编号" }) {
            val detail = DouyinNativeOrders.detail(scan.copy(pages = listOf(page)), order.title)
            if (detail?.remoteId != order.remoteId) throw AppFailure("物流页面订单编号无法确认归属", FailureKind.UNSUPPORTED_RESPONSE)
        }
        val statusNode = nodes.firstOrNull { DouyinNativeOrders.label(it) in statusLabels }
        val status = statusNode?.let { parcelStatus(DouyinNativeOrders.label(it)) } ?: ParcelStatus.UNKNOWN
        statusNode?.let { fields.add(field("logisticsStatus", DouyinNativeOrders.label(it), it)) }
        if (status == ParcelStatus.UNKNOWN) missing.add("物流状态")
        val times = mutableListOf<NativeTrackingTimeEvidence>()
        val events = mutableListOf<TrackingEventData>()
        val dated = nodes.mapNotNull { node -> DouyinNativeOrders.textValues(node).firstOrNull { timePattern.matches(it.second) }
            ?.let { node to it } }
        for ((dateNode, value) in dated) {
            var group: List<NativeNode>? = null
            for (depth in 1..minOf(6, dateNode.path.size)) {
                val prefix = dateNode.path.dropLast(depth)
                val members = nodes.filter { it.path.take(prefix.size) == prefix }
                if (dated.count { it.first.path.take(prefix.size) == prefix } > 1) break
                val body = members.filter { node -> node.childCount == 0 && DouyinNativeOrders.textValues(node).any { (_, text) ->
                    text.length >= 8 && !timePattern.matches(text) && text !in statusLabels &&
                        !text.contains("满意吗") && !text.contains("下单时间") && !text.contains("付款时间") && !text.contains("发货时间") &&
                        text !in setOf("查看更多物流信息", "如果找不到包裹，请联系快递员")
                } }
                if (body.isNotEmpty()) { group = body; break }
            }
            if (group == null) continue
            val text = group.joinToString("\n") { DouyinNativeOrders.label(it) }
            val time = resolveTime(value.second, order.createdAt, scan.capturedAt)
            times.add(time)
            fields.add(field("eventTime", value.second, dateNode, value.first))
            group.forEach { fields.add(field("eventText", DouyinNativeOrders.label(it), it)) }
            val truncated = text.endsWith("展开") || text.contains("...") || text.contains("…")
            if (truncated) missing.add("完整轨迹正文（需要展开）")
            if (time.time == null) missing.add(time.reason)
            if (!truncated && time.time != null) {
                val eventStatus = membersStatus(nodes, dateNode, group)
                eventStatus?.let { fields.add(field("eventStatus", DouyinNativeOrders.label(it), it)) }
                events.add(TrackingEventData(time.time, text, eventStatus?.let(DouyinNativeOrders::label).orEmpty()))
            }
        }
        if (times.isEmpty()) missing.add("可关联的物流时间与轨迹正文")
        val shipment = if (code == null) null else ImportedShipment("$code:$number", number, carrier, code, status,
            events = events.distinctBy { Triple(it.time, it.text, it.status) })
        return NativeLogisticsCandidate(page.windowId, shipment, fields, times, missing.distinct())
    }

    private fun membersStatus(nodes: List<NativeNode>, date: NativeNode, body: List<NativeNode>): NativeNode? {
        val paths = listOf(date.path) + body.map { it.path }
        val prefix = date.path.indices.takeWhile { index -> paths.all { it.getOrNull(index) == date.path[index] } }.map { date.path[it] }
        return nodes.firstOrNull { it.path.take(prefix.size) == prefix && DouyinNativeOrders.label(it) in statusLabels }
    }
}
