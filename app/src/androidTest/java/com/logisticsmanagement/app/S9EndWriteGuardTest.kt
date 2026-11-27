package com.logisticsmanagement.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.logisticsmanagement.app.platform.NativeEvidenceStore
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class S9EndWriteGuardTest {
    @Test fun endEvidenceWriteSkipsMissingDirectory(): Unit = runBlocking {
        val application = S9Evidence.context.applicationContext as LogisticsApplication
        application.container.ready.await()
        val credentials = application.container.credentials
        val root = File(S9Evidence.context.cacheDir, "s9-end-" + S9Evidence.argument("runName")).apply {
            deleteRecursively()
            check(mkdirs())
        }
        val directory = File(root, "capture").apply { check(mkdirs()) }
        val body = "{\"captureId\":\"s9\",\"accountId\":\"s9\"}"
        assertTrue(NativeEvidenceStore.writeEnd(directory, body, credentials))
        assertEquals(body, credentials.decrypt(File(directory, "end.enc").readText()))
        check(directory.deleteRecursively())
        assertFalse(NativeEvidenceStore.writeEnd(directory, body, credentials))
        assertFalse("目录不应被重新创建", directory.exists())
        assertFalse(NativeEvidenceStore.writeEnd(null, body, credentials))

        val filesRoot = File(root, "files").apply { check(mkdirs()) }
        val capture = File(filesRoot, "native-entry-evidence/capture-1").apply { check(mkdirs()) }
        File(capture, "account.enc").writeText(credentials.encrypt("s9-removed"))
        assertEquals(1, NativeEvidenceStore.deleteAccount(filesRoot, "s9-removed", credentials))
        assertEquals(0, NativeEvidenceStore.deleteAccount(filesRoot, "s9-removed", credentials))
        assertFalse("证据目录应删除", capture.exists())

        S9Evidence.save(S9Evidence.directory("end-write"), "result.json", buildJsonObject {
            put("directorySkipped", true)
            put("secondDelete", 0)
            put("deleteCalls", 2)
        })
        root.deleteRecursively()
    }
}
