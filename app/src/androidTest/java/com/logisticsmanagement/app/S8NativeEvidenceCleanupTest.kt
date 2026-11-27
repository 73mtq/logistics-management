package com.logisticsmanagement.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.logisticsmanagement.app.model.Platform
import com.logisticsmanagement.app.platform.NativeEvidenceStore
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class S8NativeEvidenceCleanupTest {
    @Test fun onlyTargetAccountEvidenceIsDeleted(): Unit = runBlocking {
        val context = S8Evidence.context
        val application = context.applicationContext as LogisticsApplication
        application.container.ready.await()
        val account = application.container.repository.getAccounts().firstOrNull { it.platform == Platform.DOUYIN }
            ?: error("缺少真实抖音账号")
        val production = File(context.filesDir, "native-entry-evidence")
        val sources = production.listFiles()?.filter { it.isDirectory }.orEmpty()
        check(sources.isNotEmpty()) { "缺少真实抖音证据目录" }
        val source = sources.minByOrNull { dir -> dir.walkTopDown().filter { it.isFile }.sumOf { it.length() } }
            ?: error("缺少可复制的真实证据目录")
        check(File(source, "end.enc").isFile) { "真实证据目录缺少 end.enc" }
        val root = File(context.cacheDir, "s8-native-evidence-" + S8Evidence.argument("runName")).apply {
            deleteRecursively()
            check(mkdirs())
        }
        val evidenceRoot = File(root, "native-entry-evidence").apply { check(mkdirs()) }
        fun copy(name: String): File = File(evidenceRoot, name).also { target ->
            source.copyRecursively(target, overwrite = true)
        }
        val legacy = copy("legacy").also { File(it, "account.enc").delete() }
        val marked = copy("marked").also { File(it, "account.enc").writeText(application.container.credentials.encrypt(account.id)) }
        val other = copy("other").also { File(it, "account.enc").writeText(application.container.credentials.encrypt("other-account-id")) }
        val unknown = copy("unknown").also {
            File(it, "account.enc").delete()
            File(it, "end.enc").delete()
            File(it, "order-import.enc").delete()
        }
        val productionBefore = sources.map { it.name }.sorted()
        val deleted = NativeEvidenceStore.deleteAccount(root, account.id, application.container.credentials)
        assertEquals(2, deleted)
        assertFalse("旧格式证据应删除", legacy.exists())
        assertFalse("目标账号标记证据应删除", marked.exists())
        assertTrue("其他账号证据应保留", other.isDirectory)
        assertTrue("无法归属的证据应保留", unknown.isDirectory)
        assertEquals(productionBefore, production.listFiles()?.filter { it.isDirectory }?.map { it.name }?.sorted())
        S8Evidence.save(S8Evidence.directory("native-evidence"), "result.json", buildJsonObject {
            put("accountId", account.id)
            put("sourceCapture", source.name)
            put("deleted", deleted)
            put("legacyDeleted", !legacy.exists())
            put("markedDeleted", !marked.exists())
            put("otherKept", other.isDirectory)
            put("unknownKept", unknown.isDirectory)
            put("productionDirs", buildJsonArray { productionBefore.forEach { add(JsonPrimitive(it)) } })
        })
        root.deleteRecursively()
    }
}
