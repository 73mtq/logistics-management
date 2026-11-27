package com.logisticsmanagement.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class S10SessionRestoreTest {
    private fun value(row: JsonObject, key: String): String? = row[key]?.jsonPrimitive?.contentOrNull

    @Test fun jdRebindRestored(): Unit = runBlocking {
        val audit = S10Evidence.auditDirectory()
        val before = S10Evidence.snapshot(audit, "before.json")
        val after = S10Evidence.snapshot(audit, "jd.json")
        val beforeIds = S10Evidence.rows(before, "Account").mapNotNull { value(it, "id") }.toSet()
        val newJd = S10Evidence.rows(after, "Account").firstOrNull { row ->
            value(row, "platform") == "JD" && value(row, "deleted") == "0" && value(row, "id")?.let { it !in beforeIds } == true
        } ?: error("快照缺少重新绑定的京东账号")
        val newId = checkNotNull(value(newJd, "id"))
        assertEquals("京东账号状态", "READY", value(newJd, "state"))
        assertTrue("缺少成功时间", (value(newJd, "lastSyncedAt")?.toLongOrNull() ?: 0L) > 0L)
        assertTrue("缺少京东登录证据键", S10Evidence.strings(after, "credentialKeys").contains("account." + newId + ".jd.login"))

        val sources = S10Evidence.rows(after, "SourceRef")
        val newOrders = S10Evidence.rows(after, "orders").filter { value(it, "accountId") == newId }
        assertTrue("新账号缺少订单", newOrders.isNotEmpty())
        val oldOrder = S10Evidence.rows(after, "orders").firstOrNull { row ->
            value(row, "accountId") != newId && newOrders.any { value(it, "remoteId") == value(row, "remoteId") }
        } ?: error("旧账号订单应保留")
        val remoteId = checkNotNull(value(oldOrder, "remoteId"))
        assertTrue("新账号缺少同一真实订单", newOrders.any { value(it, "remoteId") == remoteId })
        val oldAccountId = checkNotNull(value(oldOrder, "accountId"))
        val oldAccount = S10Evidence.rows(after, "Account").first { value(it, "id") == oldAccountId }
        assertEquals("旧账号应保持已移除", "1", value(oldAccount, "deleted"))
        assertEquals("旧账号状态", "REMOVED", value(oldAccount, "state"))

        val shared = sources.firstNotNullOfOrNull { source ->
            if (value(source, "accountId") != newId) null
            else {
                val shipmentId = value(source, "shipmentId")
                val rows = if (shipmentId == null) emptyList() else sources.filter { value(it, "shipmentId") == shipmentId }
                if (shipmentId != null && rows.size > 1) shipmentId to rows else null
            }
        } ?: error("新账号来源没有与旧账号共享包裹")
        val shipmentId = shared.first
        val shipmentSources = shared.second
        assertEquals("目标包裹来源数", 2, shipmentSources.size)
        assertTrue("目标包裹缺少旧账号来源", shipmentSources.any { value(it, "accountId") == oldAccountId })
        val eventsBefore = S10Evidence.rows(before, "TrackingEvent").count { value(it, "shipmentId") == shipmentId }
        val eventsAfter = S10Evidence.rows(after, "TrackingEvent").count { value(it, "shipmentId") == shipmentId }
        assertEquals("目标包裹轨迹数应保持", eventsBefore, eventsAfter)

        S10Evidence.save(S10Evidence.directory("jd-audit"), "result.json", buildJsonObject {
            put("newAccountId", newId)
            put("oldAccountId", oldAccountId)
            put("remoteOrderId", remoteId)
            put("shipmentId", shipmentId)
            put("sourcesForShipment", shipmentSources.size)
            put("eventsBefore", eventsBefore)
            put("eventsAfter", eventsAfter)
            put("newAccountOrders", newOrders.size)
            put("shipmentCountBefore", S10Evidence.tableCount(before, "Shipment"))
            put("shipmentCountAfter", S10Evidence.tableCount(after, "Shipment"))
        })
    }

    @Test fun pddSessionRestored(): Unit = runBlocking {
        val audit = S10Evidence.auditDirectory()
        val before = S10Evidence.snapshot(audit, "before.json")
        val after = S10Evidence.snapshot(audit, "pdd.json")
        val pdd = S10Evidence.rows(after, "Account").firstOrNull { value(it, "platform") == "PDD" && value(it, "deleted") == "0" }
            ?: error("快照缺少拼多多账号")
        val id = checkNotNull(value(pdd, "id"))
        val state = value(pdd, "state")
        assertNotNull("拼多多状态", state)
        assertTrue("拼多多状态应为 PARTIAL 或 READY", state == "PARTIAL" || state == "READY")
        if (state == "PARTIAL") assertTrue("PARTIAL 需要分页说明", !value(pdd, "error").isNullOrBlank())
        assertTrue("缺少拼多多登录证据键", S10Evidence.strings(after, "credentialKeys").contains("account." + id + ".pdd.login"))
        assertEquals("查询队列应为 0", 0, S10Evidence.rows(after, "TrackingQueue").count { value(it, "accountId") == id })
        val ordersBefore = S10Evidence.rows(before, "orders").count { value(it, "accountId") == id }
        val ordersAfter = S10Evidence.rows(after, "orders").count { value(it, "accountId") == id }
        assertEquals("订单数应与基线一致", ordersBefore, ordersAfter)
        val sourcesBefore = S10Evidence.rows(before, "SourceRef").count { value(it, "accountId") == id }
        val sourcesAfter = S10Evidence.rows(after, "SourceRef").count { value(it, "accountId") == id }
        assertEquals("来源数应与基线一致", sourcesBefore, sourcesAfter)

        S10Evidence.save(S10Evidence.directory("pdd-audit"), "result.json", buildJsonObject {
            put("accountId", id)
            put("state", state ?: "")
            put("orders", ordersAfter)
            put("sources", sourcesAfter)
            put("pendingQueue", 0)
        })
    }
}
