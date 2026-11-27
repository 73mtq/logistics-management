package com.logisticsmanagement.app

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import com.logisticsmanagement.app.data.LogisticsDatabase
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.security.MessageDigest

internal object S6Evidence {
    val tables = listOf("Account", "orders", "Shipment", "SourceRef", "TrackingEvent", "ShipmentAlias", "TrackingQueue", "PendingProfileCleanup")
    val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    fun argument(name: String): String = checkNotNull(InstrumentationRegistry.getArguments().getString(name)) { "缺少测试参数：$name" }.also { check(it.isNotBlank()) }

    fun directory(suffix: String): File {
        val runName = argument("runName")
        check(runName.matches(Regex("S6-[A-Za-z0-9_-]+"))) { "runName 格式不正确" }
        val root = checkNotNull(context.getExternalFilesDir(null)) { "应用外部文件目录不可用" }
        return File(root, "s6-evidence/$runName/$suffix").apply { check(mkdirs() || isDirectory) }
    }

    fun sha256(file: File): String = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }

    fun inputFile(name: String): File {
        val root = File(checkNotNull(context.getExternalFilesDir(null)) { "应用外部文件目录不可用" }, "s6-inputs")
        val file = File(root, name).canonicalFile
        check(file.toPath().startsWith(root.canonicalFile.toPath())) { "输入文件不在 s6-inputs 目录" }
        return file
    }

    fun copyInputDatabase(): String {
        val name = argument("databaseName")
        check(name.matches(Regex("s6-[A-Za-z0-9_-]+"))) { "databaseName 格式不正确" }
        val source = inputFile(argument("beforePath"))
        check(source.isFile) { "缺少输入数据库：${source.name}" }
        check(sha256(source) == argument("beforeSha256")) { "真实输入 SHA-256 不一致" }
        val target = context.getDatabasePath("$name.db")
        check(!target.exists()) { "独立数据库已经存在：${target.name}" }
        check(target.parentFile!!.isDirectory)
        source.copyTo(target)
        return target.name
    }

    fun open(name: String): LogisticsDatabase = Room.databaseBuilder(context, LogisticsDatabase::class.java, name)
        .addMigrations(LogisticsDatabase.MIGRATION_1_2).build().also { database ->
            database.openHelper.writableDatabase.query("PRAGMA integrity_check").use { check(it.moveToFirst() && it.getString(0) == "ok") { "独立数据库完整性检查失败" } }
        }

    fun rows(database: SupportSQLiteDatabase, table: String): List<Map<String, String?>> = database.query("SELECT * FROM \"$table\"").use { cursor ->
        buildList {
            while (cursor.moveToNext()) add(cursor.columnNames.associateWith { column ->
                val index = cursor.getColumnIndexOrThrow(column)
                if (cursor.isNull(index)) null else cursor.getString(index)
            })
        }.sortedBy { it["id"] ?: it["sourceId"] ?: it["accountId"] }
    }

    fun snapshot(database: LogisticsDatabase): Map<String, List<Map<String, String?>>> =
        tables.associateWith { rows(database.openHelper.readableDatabase, it) }

    fun save(directory: File, name: String, value: Map<String, List<Map<String, String?>>>) {
        File(directory, name).writeText(buildJsonObject {
            value.forEach { (table, rows) -> put(table, buildJsonArray { rows.forEach { row -> add(buildJsonObject { row.forEach { (key, rowValue) -> put(key, rowValue?.let(::JsonPrimitive) ?: JsonNull) } }) } }) }
        }.toString())
    }

    fun scalar(database: SupportSQLiteDatabase, query: String): Long = database.query(query).use { check(it.moveToFirst()); it.getLong(0) }

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
        ).forEach { query -> check(scalar(db, query) == 0L) { "来源关系或重复检查失败：$query" } }
    }
}
