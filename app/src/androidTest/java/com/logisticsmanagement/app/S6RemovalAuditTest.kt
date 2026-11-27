package com.logisticsmanagement.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.webkit.ProfileStore
import com.logisticsmanagement.app.platform.NativeSyncProgressStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class S6RemovalAuditTest {
    private val context: Context get() = S6Evidence.context

    @Test fun removedAccountCleanupKeepsBusinessRecords(): Unit = runBlocking {
        val directory = S6Evidence.directory("removal")
        val application = context.applicationContext as LogisticsApplication
        application.container.ready.await()
        val accountId = S6Evidence.argument("removedAccountId")
        val profileName = S6Evidence.argument("removedProfileName")
        val beforeFile = S6Evidence.inputFile(S6Evidence.argument("beforePath"))
        check(S6Evidence.sha256(beforeFile) == S6Evidence.argument("beforeSha256")) { "移除前副本 SHA-256 不一致" }
        val liveFile = context.getDatabasePath("logistics.db")
        check(liveFile.isFile) { "缺少生产数据库" }
        val businessTables = listOf("orders", "Shipment", "SourceRef", "TrackingEvent", "ShipmentAlias", "PendingProfileCleanup")
        val beforeDb = SQLiteDatabase.openDatabase(beforeFile.path, null, SQLiteDatabase.OPEN_READONLY)
        try {
            val liveDb = SQLiteDatabase.openDatabase(liveFile.path, null, SQLiteDatabase.OPEN_READONLY)
            try {
                assertEquals("数据库版本保持 2", 2, queryInt(liveDb, "PRAGMA user_version"))
                assertEquals("数据库完整性检查", "ok", queryText(liveDb, "PRAGMA integrity_check"))
                for (table in businessTables) assertEquals("业务表 $table 应保持", rows(beforeDb, table), rows(liveDb, table))
                val beforeAccounts = rows(beforeDb, "Account").associateBy { it.getValue("id") }
                val liveAccounts = rows(liveDb, "Account").associateBy { it.getValue("id") }
                assertEquals(beforeAccounts.keys - setOf(accountId), liveAccounts.keys - setOf(accountId))
                val original = checkNotNull(beforeAccounts[accountId]) { "移除前副本缺少目标账号" }
                val target = checkNotNull(liveAccounts[accountId]) { "生产库缺少目标账号行" }
                assertEquals("0", target["enabled"])
                assertEquals("1", target["deleted"])
                assertEquals("REMOVED", target["state"])
                val retainedFields = setOf("enabled", "deleted", "state")
                assertEquals("目标账号其他字段应保持", original.filterKeys { it !in retainedFields }, target.filterKeys { it !in retainedFields })
                for ((id, row) in beforeAccounts) if (id != accountId) assertEquals("其他账号应保持", row, liveAccounts[id])
                val beforeQueue = rows(beforeDb, "TrackingQueue")
                val liveQueue = rows(liveDb, "TrackingQueue")
                assertEquals("其他账号查询项应保持", beforeQueue.filter { it["accountId"] != accountId }, liveQueue.filter { it["accountId"] != accountId })
                assertTrue("目标账号查询项应清空", liveQueue.none { it["accountId"] == accountId })

                val credentialKeys = context.getSharedPreferences("encrypted_credentials", Context.MODE_PRIVATE).all.keys
                assertTrue("目标账号凭据键应清空", credentialKeys.none { it.startsWith("account.$accountId.") })
                val pending = context.getSharedPreferences("webview_profiles", Context.MODE_PRIVATE)
                    .getStringSet("pending_deletions", emptySet()).orEmpty()
                assertTrue("待删除 profile 应已完成", pending.isEmpty())
                withContext(Dispatchers.Main.immediate) {
                    assertNull("WebView profile 应已删除", ProfileStore.getInstance().getProfile(profileName))
                }
                assertFalse("目标账号原生进度应不存在", File(context.filesDir, "native-sync-progress/$accountId.enc").exists())
                assertFalse("目标账号原生进度备份应不存在", File(context.filesDir, "native-sync-progress/$accountId.enc.bak").exists())

                File(directory, "result.json").writeText(buildJsonObject {
                    put("accountId", accountId)
                    put("profileName", profileName)
                    put("businessTables", businessTables.size)
                    put("accountRowsBefore", beforeAccounts.size)
                    put("accountRowsAfter", liveAccounts.size)
                    put("queueRowsForAccount", liveQueue.count { it["accountId"] == accountId })
                    put("credentialKeysForAccount", credentialKeys.count { it.startsWith("account.$accountId.") })
                    put("pendingProfiles", pending.size)
                    put("profileDeleted", true)
                    put("nativeProgressRemaining", false)
                }.toString())
            } finally {
                liveDb.close()
            }
        } finally {
            beforeDb.close()
        }
    }

    @Test fun nativeProgressDeletionRemovesMainAndBackup() {
        val directory = S6Evidence.directory("native-progress")
        val application = context.applicationContext as LogisticsApplication
        val accountId = S6Evidence.argument("nativeAccountId")
        val source = File(context.filesDir, "native-sync-progress/$accountId.enc")
        check(source.isFile) { "缺少真实抖音进度文件" }
        val namespace = "s6-progress-${System.nanoTime()}"
        val targetDirectory = File(context.filesDir, namespace)
        check(targetDirectory.mkdirs() || targetDirectory.isDirectory) { "无法创建进度副本目录" }
        val main = File(targetDirectory, "$accountId.enc")
        val backup = File(targetDirectory, "$accountId.enc.bak")
        source.copyTo(main)
        source.copyTo(backup)
        val store = NativeSyncProgressStore(context, application.container.credentials, namespace)
        store.delete(accountId)
        assertFalse("进度主文件应删除", main.exists())
        assertFalse("进度备份应删除", backup.exists())
        assertTrue("生产进度不能被测试删除", source.isFile)
        File(directory, "result.json").writeText(buildJsonObject {
            put("accountId", accountId)
            put("mainDeleted", !main.exists())
            put("backupDeleted", !backup.exists())
            put("productionKept", source.isFile)
            put("progressBytes", source.length())
        }.toString())
    }

    private fun rows(db: SQLiteDatabase, table: String): List<Map<String, String?>> =
        db.rawQuery("SELECT * FROM \"$table\"", null).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.columnNames.associateWith { column ->
                    val index = cursor.getColumnIndexOrThrow(column)
                    if (cursor.isNull(index)) null else cursor.getString(index)
                })
            }.sortedBy { it["id"] ?: it["sourceId"] ?: it["accountId"] }
        }

    private fun queryInt(db: SQLiteDatabase, query: String): Int = db.rawQuery(query, null).use { cursor ->
        check(cursor.moveToFirst()) { "查询没有返回结果：$query" }
        cursor.getInt(0)
    }

    private fun queryText(db: SQLiteDatabase, query: String): String = db.rawQuery(query, null).use { cursor ->
        check(cursor.moveToFirst()) { "查询没有返回结果：$query" }
        cursor.getString(0)
    }
}
