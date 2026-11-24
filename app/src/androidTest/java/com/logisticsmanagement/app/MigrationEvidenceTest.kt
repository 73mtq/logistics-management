package com.logisticsmanagement.app

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.logisticsmanagement.app.data.LogisticsRepository
import com.logisticsmanagement.app.model.CaptureMode
import com.logisticsmanagement.app.model.Platform
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class MigrationEvidenceTest {
    @Test fun migrateActualSchema1AndReopen() {
        val directory = T14Evidence.directory("migration")
        val name = T14Evidence.copyDatabase("schema1Path", "migration")
        val original = SQLiteDatabase.openDatabase(T14Evidence.context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READONLY)
        assertEquals(1, original.version)
        val originalTables = listOf("Account", "orders", "Shipment", "SourceRef", "TrackingEvent", "ShipmentAlias")
        val before = originalTables.associateWith { table -> original.rawQuery("SELECT * FROM \"$table\"", null).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.columnNames.associateWith { key ->
                val index = cursor.getColumnIndexOrThrow(key)
                if (cursor.isNull(index)) null else cursor.getString(index)
            }) }.sortedBy { it["id"] }
        } }
        original.close()
        T14Evidence.save(directory, "before.json", before)
        val removed = before.getValue("Account").filter { it["platform"] in setOf("XIAOMI", "MANUAL") }
        val removedAccounts = removed.map { it.getValue("id") }.toSet()
        val sources = before.getValue("SourceRef").filter { it["platform"] !in setOf("XIAOMI", "MANUAL") && it["accountId"] !in removedAccounts }
        val shipmentIds = sources.map { it["shipmentId"] }.toSet()
        val database = T14Evidence.open(name)
        try {
            val after = T14Evidence.snapshot(database)
            val expected = before.toMutableMap()
            expected["Account"] = before.getValue("Account").filter { it["id"] !in removedAccounts }.map {
                it + mapOf("captureMode" to "WEB", "externalIdentity" to null, "lastAttemptAt" to null)
            }
            expected["orders"] = before.getValue("orders").filter { it["accountId"] !in removedAccounts }
            expected["SourceRef"] = sources
            expected["Shipment"] = before.getValue("Shipment").filter { it["id"] in shipmentIds }.map { row -> row.filterKeys { it in after.getValue("Shipment").first().keys } }
            expected["TrackingEvent"] = before.getValue("TrackingEvent").filter { it["shipmentId"] in shipmentIds }
            expected["ShipmentAlias"] = before.getValue("ShipmentAlias").filter { it["shipmentId"] in shipmentIds }
            expected["TrackingQueue"] = emptyList()
            expected["PendingProfileCleanup"] = removed.map { mapOf("accountId" to it["id"], "profileName" to it["profileName"]) }.sortedBy { it["accountId"] }
            assertEquals("迁移逐字段结果", expected, after)
            assertEquals(2L, T14Evidence.scalar(database.openHelper.readableDatabase, "PRAGMA user_version"))
            assertEquals(0L, T14Evidence.scalar(database.openHelper.readableDatabase, "SELECT count(*) FROM sqlite_master WHERE type='table' AND name='Report'"))
            T14Evidence.assertRelations(database)
            T14Evidence.save(directory, "after.json", after)
            database.close()
            val reopened = T14Evidence.open(name)
            try { assertEquals(after, T14Evidence.snapshot(reopened)); T14Evidence.assertRelations(reopened) }
            finally { reopened.close() }
            File(directory, "result.json").writeText(buildJsonObject {
                put("migratedAndReopened", true)
                put("removedAccounts", removed.size)
                put("sharedShipmentSamples", before.getValue("SourceRef").groupBy { it["shipmentId"] }.count { it.value.size > 1 })
                put("nonemptyNotesSamples", before.getValue("Shipment").count { !it["notes"].isNullOrBlank() })
                put("archivedSamples", before.getValue("Shipment").count { it["archived"] == "1" })
                put("aliasSamples", before.getValue("ShipmentAlias").size)
            }.toString())
        } finally { database.close() }
    }

    @Test fun normalizeDouyinModesAfterRealMigration(): Unit = runBlocking {
        val directory = T14Evidence.directory("douyin-mode")
        val name = T14Evidence.copyDatabase("schema1Path", "douyin")
        val database = T14Evidence.open(name)
        try {
            val repository = LogisticsRepository(database)
            val before = repository.getAccounts()
            val pending = before.filter { it.platform == Platform.DOUYIN && !it.deleted }
            assertTrue("真实迁移副本需要未删除的抖音账号", pending.isNotEmpty())
            assertTrue("迁移后的抖音账号默认为WEB", pending.all { it.captureMode == CaptureMode.WEB })
            val updated = repository.normalizeDouyinCaptureModes()
            val after = repository.getAccounts()
            assertEquals("抖音账号全部转为App页面", pending.size, updated)
            assertTrue(after.filter { it.platform == Platform.DOUYIN && !it.deleted }.all { it.captureMode == CaptureMode.APP_PAGE })
            assertEquals("已删除抖音账号保持原值", before.filter { it.platform == Platform.DOUYIN && it.deleted }, after.filter { it.platform == Platform.DOUYIN && it.deleted })
            assertEquals("其他平台账号保持原值", before.filter { it.platform != Platform.DOUYIN }, after.filter { it.platform != Platform.DOUYIN })
            assertEquals(before.size, after.size)
            File(directory, "result.json").writeText(buildJsonObject {
                put("normalized", updated)
                put("douyinAccounts", after.count { it.platform == Platform.DOUYIN })
            }.toString())
        } finally { database.close() }
    }

    @Test fun reopenActualSchema2WithoutChanges() {
        val directory = T14Evidence.directory("schema2")
        val name = T14Evidence.copyDatabase("schema2Path", "schema2")
        val database = T14Evidence.open(name)
        val before = T14Evidence.snapshot(database)
        T14Evidence.save(directory, "before.json", before)
        T14Evidence.assertRelations(database)
        database.close()
        repeat(2) {
            val reopened = T14Evidence.open(name)
            try { assertEquals(before, T14Evidence.snapshot(reopened)); T14Evidence.assertRelations(reopened) }
            finally { reopened.close() }
        }
        File(directory, "result.json").writeText(buildJsonObject { put("schema2Reopens", 2); put("allEightTablesPreserved", true) }.toString())
    }
}
