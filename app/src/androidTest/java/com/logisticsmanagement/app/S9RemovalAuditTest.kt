package com.logisticsmanagement.app

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.webkit.ProfileStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class S9RemovalAuditTest {
    private fun rowIds(snapshot: JsonObject, table: String): Set<String> =
        S9Evidence.rows(snapshot, table).mapNotNull { row -> row["id"]?.let { it.jsonPrimitive.content } }.toSet()

    private fun accountRows(snapshot: JsonObject, table: String, accountId: String): List<JsonObject> =
        S9Evidence.rows(snapshot, table).filter { it["accountId"]?.jsonPrimitive?.content == accountId }

    private fun livePendingProfiles(): Set<String> =
        S9Evidence.context.getSharedPreferences("webview_profiles", Context.MODE_PRIVATE)
            .getStringSet("pending_deletions", emptySet()).orEmpty()

    private fun assertTargetState(row: JsonObject) {
        assertEquals(1, row.getValue("deleted").jsonPrimitive.int)
        assertEquals(0, row.getValue("enabled").jsonPrimitive.int)
        assertEquals("REMOVED", row.getValue("state").jsonPrimitive.content)
    }

    @Test fun douyinCaptureRemoval(): Unit = runBlocking {
        val application = S9Evidence.context.applicationContext as LogisticsApplication
        application.container.ready.await()
        val audit = S9Evidence.auditDirectory()
        val accountId = S9Evidence.argument("douyinAccountId")
        val before = S9Evidence.snapshot(audit, "before.json")
        val after = S9Evidence.snapshot(audit, "douyin-after-removal.json")
        val started = S9Evidence.snapshot(audit, "douyin-after-start.json")
        assertTargetState(S9Evidence.accountRow(after, accountId))
        assertTargetState(S9Evidence.accountRow(started, accountId))
        assertEquals("账号证据目录应清空", 0, S9Evidence.arraySize(after, "nativeEvidence"))
        assertEquals("重启后证据目录仍应为空", 0, S9Evidence.arraySize(started, "nativeEvidence"))
        assertTrue("账号原生进度应删除", S9Evidence.strings(after, "nativeProgress").none { it.startsWith(accountId) })
        assertEquals("凭据键应保持", S9Evidence.strings(before, "credentialKeys").sorted(), S9Evidence.strings(after, "credentialKeys").sorted())
        assertTrue("目标账号业务记录应保留", accountRows(after, "orders", accountId).isNotEmpty())
        assertTrue("目标账号来源记录应保留", accountRows(after, "SourceRef", accountId).isNotEmpty())
        assertTrue("目标账号查询项应为 0", accountRows(after, "TrackingQueue", accountId).isEmpty())
        for (table in listOf("orders", "Shipment", "SourceRef", "TrackingEvent", "ShipmentAlias")) {
            assertTrue("表 " + table + " 的行不应被移除", rowIds(after, table).containsAll(rowIds(before, table)))
        }
        assertTrue("启动后待删除 profile 应为空", livePendingProfiles().isEmpty())
        S9Evidence.save(S9Evidence.directory("douyin-audit"), "result.json", buildJsonObject {
            put("accountId", accountId)
            put("evidenceRemaining", S9Evidence.arraySize(after, "nativeEvidence"))
            put("progressRemaining", S9Evidence.strings(after, "nativeProgress").size)
            put("orderRowsForAccount", accountRows(after, "orders", accountId).size)
            put("sourceRowsForAccount", accountRows(after, "SourceRef", accountId).size)
            put("livePendingProfiles", livePendingProfiles().size)
        })
    }

    @Test fun taobaoSyncRemoval(): Unit = runBlocking {
        val application = S9Evidence.context.applicationContext as LogisticsApplication
        application.container.ready.await()
        val audit = S9Evidence.auditDirectory()
        val accountId = S9Evidence.argument("taobaoAccountId")
        val profileName = S9Evidence.argument("taobaoProfileName")
        val before = S9Evidence.snapshot(audit, "taobao-before.json")
        val after = S9Evidence.snapshot(audit, "taobao-after-removal.json")
        val started = S9Evidence.snapshot(audit, "taobao-after-start.json")
        assertTargetState(S9Evidence.accountRow(after, accountId))
        assertTargetState(S9Evidence.accountRow(started, accountId))
        assertTrue("目标账号查询项应为 0", accountRows(after, "TrackingQueue", accountId).isEmpty())
        assertTrue("目标账号凭据键应清空", S9Evidence.strings(after, "credentialKeys").none { it.startsWith("account." + accountId + ".") })
        assertTrue("待删除 profile 应包含目标", S9Evidence.strings(after, "pendingDeletions").contains(profileName))
        assertEquals("已加载 profile 在移除后仍存在", S9Evidence.strings(before, "profiles").sorted(), S9Evidence.strings(after, "profiles").sorted())
        for (table in listOf("orders", "Shipment", "SourceRef", "TrackingEvent", "ShipmentAlias")) {
            assertTrue("表 " + table + " 的行不应被移除", rowIds(after, table).containsAll(rowIds(before, table)))
        }
        assertTrue("启动后待删除 profile 应为空", livePendingProfiles().isEmpty())
        withContext(Dispatchers.Main.immediate) {
            assertNull("目标 profile 应删除", ProfileStore.getInstance().getProfile(profileName))
        }
        S9Evidence.save(S9Evidence.directory("taobao-audit"), "result.json", buildJsonObject {
            put("accountId", accountId)
            put("profileName", profileName)
            put("pendingAfterRemoval", S9Evidence.strings(after, "pendingDeletions").size)
            put("livePendingProfiles", livePendingProfiles().size)
            put("credentialKeysAfter", S9Evidence.strings(after, "credentialKeys").size)
        })
    }
}
