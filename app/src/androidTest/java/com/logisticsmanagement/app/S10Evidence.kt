package com.logisticsmanagement.app

import android.content.Context
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.logisticsmanagement.app.data.LogisticsDatabase
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.security.MessageDigest

internal object S10Evidence {
    val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    val businessTables = listOf("orders", "Shipment", "SourceRef", "TrackingEvent", "ShipmentAlias", "TrackingQueue", "PendingProfileCleanup")

    fun argument(name: String): String =
        checkNotNull(InstrumentationRegistry.getArguments().getString(name)) { "缺少测试参数：" + name }.also { check(it.isNotBlank()) }

    private fun runName(): String {
        val value = argument("runName")
        check(value.matches(Regex("S10-[A-Za-z0-9_-]+"))) { "runName 格式不正确" }
        return value
    }

    fun directory(suffix: String): File {
        val root = checkNotNull(context.getExternalFilesDir(null)) { "应用外部文件目录不可用" }
        return File(root, "s10-evidence/" + runName() + "/" + suffix).apply { check(mkdirs() || isDirectory) }
    }

    fun auditDirectory(): File {
        val root = checkNotNull(context.getExternalFilesDir(null)) { "应用外部文件目录不可用" }
        return File(root, "s10-audit/" + runName()).apply { check(isDirectory) { "缺少审计目录：" + path } }
    }

    fun sha256(file: File): String =
        MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }

    fun copyInputDatabase(): String {
        val name = argument("databaseName")
        check(name.matches(Regex("s10-[A-Za-z0-9_-]+"))) { "databaseName 格式不正确" }
        val root = File(checkNotNull(context.getExternalFilesDir(null)) { "应用外部文件目录不可用" }, "s10-inputs")
        val source = File(root, argument("beforePath")).canonicalFile
        check(source.toPath().startsWith(root.canonicalFile.toPath())) { "输入文件不在 s10-inputs 目录" }
        check(source.isFile) { "缺少输入数据库：" + source.name }
        check(sha256(source) == argument("beforeSha256")) { "真实输入 SHA-256 不一致" }
        val target = context.getDatabasePath(name + ".db")
        check(!target.exists()) { "独立数据库已经存在：" + target.name }
        check(target.parentFile!!.isDirectory)
        source.copyTo(target)
        return target.name
    }

    fun open(name: String): LogisticsDatabase =
        Room.databaseBuilder(context, LogisticsDatabase::class.java, name)
            .addMigrations(LogisticsDatabase.MIGRATION_1_2).build().also { database ->
                database.openHelper.writableDatabase.query("PRAGMA integrity_check").use {
                    check(it.moveToFirst() && it.getString(0) == "ok") { "独立数据库完整性检查失败" }
                }
            }

    fun rows(database: LogisticsDatabase, table: String): List<Map<String, String?>> =
        database.openHelper.readableDatabase.query("SELECT * FROM \"" + table + "\"").use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.columnNames.associateWith { column ->
                    val index = cursor.getColumnIndexOrThrow(column)
                    if (cursor.isNull(index)) null else cursor.getString(index)
                })
            }.sortedBy { it["id"] ?: it["sourceId"] ?: it["accountId"] }
        }

    fun scalar(database: LogisticsDatabase, query: String): Long =
        database.openHelper.readableDatabase.query(query).use { check(it.moveToFirst()); it.getLong(0) }

    fun assertRelations(database: LogisticsDatabase) {
        val db = database.openHelper.readableDatabase
        listOf(
            "SELECT count(*) FROM SourceRef WHERE shipmentId NOT IN (SELECT id FROM Shipment) OR orderId NOT IN (SELECT id FROM orders) OR accountId NOT IN (SELECT id FROM Account)",
            "SELECT count(*) FROM TrackingEvent WHERE shipmentId NOT IN (SELECT id FROM Shipment)",
            "SELECT count(*) FROM ShipmentAlias WHERE shipmentId NOT IN (SELECT id FROM Shipment)",
            "SELECT count(*) FROM TrackingQueue WHERE sourceId NOT IN (SELECT id FROM SourceRef)",
            "SELECT count(*) FROM (SELECT accountId,remoteId FROM orders GROUP BY accountId,remoteId HAVING count(*) > 1)",
            "SELECT count(*) FROM (SELECT accountId,remoteOrderId,remoteShipmentId FROM SourceRef GROUP BY accountId,remoteOrderId,remoteShipmentId HAVING count(*) > 1)",
            "SELECT count(*) FROM (SELECT shipmentId,time,text FROM TrackingEvent GROUP BY shipmentId,time,text HAVING count(*) > 1)",
        ).forEach { query -> check(scalar(database, query) == 0L) { "来源关系或重复检查失败：" + query } }
    }

    fun save(directory: File, name: String, value: JsonObject) {
        File(directory, name).writeText(value.toString())
    }

    fun snapshot(directory: File, name: String): JsonObject {
        val file = File(directory, name)
        check(file.isFile) { "缺少快照：" + file.path }
        return Json.parseToJsonElement(file.readText()).jsonObject
    }

    fun rows(snapshot: JsonObject, table: String): List<JsonObject> =
        snapshot.getValue("tables").jsonObject.getValue(table).jsonObject.getValue("rows").jsonArray.map { it.jsonObject }

    fun tableCount(snapshot: JsonObject, table: String): Int =
        snapshot.getValue("tables").jsonObject.getValue(table).jsonObject.getValue("count").jsonPrimitive.int

    fun tableHash(snapshot: JsonObject, table: String): String =
        snapshot.getValue("tables").jsonObject.getValue(table).jsonObject.getValue("hash").jsonPrimitive.content

    fun strings(snapshot: JsonObject, name: String): List<String> =
        when (name) {
            "credentialKeys", "pendingDeletions" -> snapshot.getValue("prefs").jsonObject.getValue(name).jsonArray.map { it.jsonPrimitive.content }
            "nativeProgress", "profiles" -> snapshot.getValue(name).jsonArray.map { it.jsonPrimitive.content }
            else -> error("未知字段：" + name)
        }
}
