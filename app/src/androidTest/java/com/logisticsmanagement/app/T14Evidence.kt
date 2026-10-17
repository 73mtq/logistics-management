package com.logisticsmanagement.app

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import com.logisticsmanagement.app.data.LogisticsDatabase
import kotlinx.serialization.json.*
import java.io.File
import java.security.MessageDigest

internal object T14Evidence {
    val tables = listOf("Account", "orders", "Shipment", "SourceRef", "TrackingEvent", "ShipmentAlias", "TrackingQueue", "PendingProfileCleanup")
    val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    fun argument(name: String): String = checkNotNull(InstrumentationRegistry.getArguments().getString(name)).also { check(it.isNotBlank()) }
    fun directory(suffix: String): File {
        val runName = argument("runName")
        check(runName.matches(Regex("T14-[A-Za-z0-9_-]+")))
        return File(context.filesDir, "t14-evidence/$runName/$suffix").apply { check(mkdirs()) }
    }
    fun copyDatabase(sourceArgument: String, suffix: String): String {
        val name = "${argument("databaseName")}-$suffix.db"
        check(name.matches(Regex("t14-[A-Za-z0-9_-]+\\.db")))
        val source = File(context.filesDir, argument(sourceArgument)).canonicalFile
        check(source.toPath().startsWith(File(context.filesDir, "t14-inputs").canonicalFile.toPath()))
        check(source.isFile)
        val hash = MessageDigest.getInstance("SHA-256").digest(source.readBytes()).joinToString("") { "%02x".format(it) }
        check(hash == argument(sourceArgument.removeSuffix("Path") + "Sha256")) { "真实输入SHA-256不一致" }
        val target = context.getDatabasePath(name)
        check(!target.exists()) { "独立数据库已经存在：$name" }
        check(target.parentFile!!.isDirectory)
        source.copyTo(target)
        return name
    }
    fun open(name: String): LogisticsDatabase = Room.databaseBuilder(context, LogisticsDatabase::class.java, name)
        .addMigrations(LogisticsDatabase.MIGRATION_1_2).build().also { database ->
            database.openHelper.writableDatabase.query("PRAGMA integrity_check").use { check(it.moveToFirst() && it.getString(0) == "ok") }
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
            value.forEach { (table, rows) -> put(table, buildJsonArray { rows.forEach { row -> add(buildJsonObject { row.forEach { (key, value) -> put(key, value?.let(::JsonPrimitive) ?: JsonNull) } }) } }) }
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
            "SELECT count(*) FROM (SELECT shipmentId,time,text FROM TrackingEvent GROUP BY shipmentId,time,text HAVING count(*) > 1)"
        ).forEach { query -> check(scalar(db, query) == 0L) { "来源关系或重复检查失败：$query" } }
    }
}
