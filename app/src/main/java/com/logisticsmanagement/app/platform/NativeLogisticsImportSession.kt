package com.logisticsmanagement.app.platform

import com.logisticsmanagement.app.model.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

internal class NativeLogisticsImportSession(private val save: suspend (String, String, ImportedShipment) -> Unit) {
    private var expectedOrder: String? = null
    private var windowId: Int? = null
    private var fingerprint: Pair<String, String>? = null
    private val sequences = mutableSetOf<Int>()
    private val emitted = mutableMapOf<Pair<String, String>, ImportedShipment>()
    private val records = mutableListOf<JsonElement>()
    val missing = linkedMapOf<String, List<String>>()
    val savedSources = linkedSetOf<Pair<String, String>>()

    fun reset() { expectedOrder = null; windowId = null; fingerprint = null; sequences.clear() }
    fun expect(remoteId: String) { reset(); expectedOrder = remoteId }
    fun detail(remoteId: String) { if (expectedOrder != null && expectedOrder != remoteId) reset() }
    fun navigationWithinLogistics(page: NativePage, anchor: NativeNode): Boolean {
        if (expectedOrder == null) return false
        val label = DouyinNativeOrders.label(anchor)
        val accepted = label in setOf("查看更多物流信息", "展开", "收起") || label.endsWith("展开")
        if (!accepted || page.nodes.none { DouyinNativeOrders.label(it).matches(Regex(".*(?:快递|物流)\\s+[A-Za-z0-9-]{8,40}")) }) return false
        windowId = null; fingerprint = null; sequences.clear()
        return true
    }

    suspend fun accept(scan: NativeScan, identity: String?, order: Order?) {
        val remoteId = expectedOrder ?: return
        if (identity == null) { reset(); return }
        if (order == null || order.remoteId != remoteId) {
            missing[remoteId] = listOf("目标订单尚未保存")
            return
        }
        val parsed = DouyinNativeLogistics.parse(scan, order)
        if (parsed == null) {
            if (windowId != null) reset()
            return
        }
        if (windowId != parsed.windowId) { windowId = parsed.windowId; fingerprint = null; sequences.clear() }
        val shipment = parsed.shipment
        if (shipment != null) {
            val key = shipment.carrierCode to checkNotNull(shipment.trackingNumber)
            if (fingerprint != key) { sequences.clear(); fingerprint = key }
            sequences.add(scan.sequence)
        }
        val absent = parsed.missing + if (sequences.size < 2) listOf("运单与承运商连续两次读取核查") else emptyList()
        if (absent.isEmpty()) missing.remove(remoteId) else missing[remoteId] = absent
        records.add(buildJsonObject {
            put("captureId", scan.captureId); put("accountId", scan.accountId); put("orderId", remoteId)
            put("sequence", scan.sequence); put("windowId", parsed.windowId); put("confirmedReadings", sequences.size)
            put("fields", platformJson.parseToJsonElement(platformJson.encodeToString(parsed.fields)))
            put("times", platformJson.parseToJsonElement(platformJson.encodeToString(parsed.times)))
            put("missing", JsonArray(absent.map(::JsonPrimitive)))
            put("events", JsonArray(shipment?.events.orEmpty().map { event -> buildJsonObject {
                put("time", event.time); put("text", event.text); put("status", event.status)
            } }))
        })
        if (shipment != null && sequences.size >= 2) {
            val key = remoteId to shipment.remoteId
            if (emitted[key] != shipment) {
                save(identity, remoteId, shipment)
                emitted[key] = shipment
            }
            savedSources.add(key)
        }
    }

    fun recordOcr(orderId: String, result: OcrLogisticsResult, readings: Int) {
        savedSources.add(orderId to "${result.carrier}:${result.trackingNumber}")
        missing.remove(orderId)
        records.add(buildJsonObject {
            put("orderId", orderId); put("source", "ocr"); put("carrier", result.carrier)
            put("trackingNumber", result.trackingNumber); put("status", result.status.name); put("confirmedReadings", readings)
            put("events", JsonArray(result.events.map { event -> buildJsonObject {
                put("time", event.time); put("text", event.text); put("status", event.status)
            } }))
        })
    }

    fun evidence() = buildJsonObject {
        put("savedSources", savedSources.size)
        put("missing", buildJsonObject { missing.forEach { (id, values) -> put(id, JsonArray(values.map(::JsonPrimitive))) } })
        put("records", JsonArray(records))
    }
}
