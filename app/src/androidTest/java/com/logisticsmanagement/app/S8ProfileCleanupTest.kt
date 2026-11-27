package com.logisticsmanagement.app

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.webkit.ProfileStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class S8ProfileCleanupTest {
    @Test fun loadedProfileRemovalDefersDeletion(): Unit = runBlocking {
        val application = S8Evidence.context.applicationContext as LogisticsApplication
        application.container.ready.await()
        val accountId = S8Evidence.argument("accountId")
        val profileName = S8Evidence.argument("profileName")
        val auditPath = S8Evidence.argument("auditPath")
        val snapshotFile = File(auditPath, "snapshot.json")
        check(snapshotFile.isFile) { "缺少移除后快照" }
        val snapshot = Json.parseToJsonElement(snapshotFile.readText()).jsonObject
        val pending = snapshot.getValue("prefs").jsonObject.getValue("webview").jsonObject
            .getValue("pending").jsonArray.map { it.jsonPrimitive.content }
        assertEquals(setOf(profileName), pending.toSet())
        val rows = snapshot.getValue("tables").jsonObject.getValue("Account").jsonObject
            .getValue("rows").jsonArray.map { it.jsonObject }
        val target = rows.firstOrNull { it.getValue("id").jsonPrimitive.content == accountId }
            ?: error("移除后快照缺少目标账号")
        assertEquals(1, target.getValue("deleted").jsonPrimitive.int)
        assertEquals(0, target.getValue("enabled").jsonPrimitive.int)
        assertEquals("REMOVED", target.getValue("state").jsonPrimitive.content)
        val profiles = snapshot.getValue("profiles").jsonArray.map { it.jsonPrimitive.content }
        assertEquals(S8Evidence.argument("profileCount").toInt(), profiles.size)
        val keys = snapshot.getValue("prefs").jsonObject.getValue("encrypted").jsonObject
            .getValue("keys").jsonObject.keys
        assertTrue("目标账号凭据键应清空", keys.none { it.startsWith("account.$accountId.") })
        val progress = snapshot.getValue("nativeProgress").jsonArray.map { it.jsonPrimitive.content }
        assertTrue("目标账号原生进度应不存在", progress.none { it == "$accountId.enc" || it == "$accountId.enc.bak" })
        S8Evidence.save(S8Evidence.directory("profile"), "loaded-result.json", buildJsonObject {
            put("accountId", accountId)
            put("profileName", profileName)
            put("pendingProfiles", pending.size)
            put("profileCount", profiles.size)
            put("credentialKeysForAccount", keys.count { it.startsWith("account.$accountId.") })
            put("nativeProgressRemaining", false)
        })
    }

    @Test fun startupCompletesPendingDeletion(): Unit = runBlocking {
        val context = S8Evidence.context
        val application = context.applicationContext as LogisticsApplication
        application.container.ready.await()
        val accountId = S8Evidence.argument("accountId")
        val profileName = S8Evidence.argument("profileName")
        val account = application.container.repository.getAccount(accountId) ?: error("缺少目标账号")
        assertTrue("目标账号应为已移除状态", account.deleted)
        val pending = context.getSharedPreferences("webview_profiles", Context.MODE_PRIVATE)
            .getStringSet("pending_deletions", emptySet()).orEmpty()
        assertTrue("启动后待删除 profile 应清空", pending.isEmpty())
        withContext(Dispatchers.Main.immediate) {
            assertNull("启动后 profile 应已删除", ProfileStore.getInstance().getProfile(profileName))
        }
        S8Evidence.save(S8Evidence.directory("profile"), "started-result.json", buildJsonObject {
            put("accountId", accountId)
            put("profileName", profileName)
            put("pendingProfiles", pending.size)
            put("profileDeleted", true)
        })
    }
}
