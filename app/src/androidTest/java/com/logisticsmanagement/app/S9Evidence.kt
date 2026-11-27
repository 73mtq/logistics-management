package com.logisticsmanagement.app

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

internal object S9Evidence {
    val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    fun argument(name: String): String =
        checkNotNull(InstrumentationRegistry.getArguments().getString(name)) { "缺少测试参数：$name" }.also { check(it.isNotBlank()) }

    fun directory(suffix: String): File {
        val runName = argument("runName")
        check(runName.matches(Regex("S9-[A-Za-z0-9_-]+"))) { "runName 格式不正确" }
        val root = checkNotNull(context.getExternalFilesDir(null)) { "应用外部文件目录不可用" }
        return File(root, "s9-evidence/$runName/$suffix").apply { check(mkdirs() || isDirectory) }
    }

    fun save(directory: File, name: String, value: JsonObject) {
        File(directory, name).writeText(value.toString())
    }

    fun auditDirectory(): File {
        val runName = argument("runName")
        val root = checkNotNull(context.getExternalFilesDir(null)) { "应用外部文件目录不可用" }
        return File(root, "s9-audit/$runName").apply { check(isDirectory) { "缺少审计目录：${path}" } }
    }

    fun snapshot(directory: File, name: String): JsonObject {
        val file = File(directory, name)
        check(file.isFile) { "缺少快照：${file.path}" }
        return Json.parseToJsonElement(file.readText()).jsonObject
    }

    fun accountRow(snapshot: JsonObject, accountId: String): JsonObject =
        rows(snapshot, "Account").firstOrNull { it.getValue("id").jsonPrimitive.content == accountId }
            ?: error("快照缺少账号：$accountId")

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
            else -> error("未知字段：$name")
        }

    fun arraySize(snapshot: JsonObject, name: String): Int = snapshot.getValue(name).jsonArray.size

    fun evidenceNames(snapshot: JsonObject): List<String> =
        snapshot.getValue("nativeEvidence").jsonArray.map { it.jsonObject.getValue("name").jsonPrimitive.content }

    val businessTables = listOf("orders", "Shipment", "SourceRef", "TrackingEvent", "ShipmentAlias", "TrackingQueue", "PendingProfileCleanup")
}
