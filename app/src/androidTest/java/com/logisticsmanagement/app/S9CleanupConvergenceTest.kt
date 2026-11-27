package com.logisticsmanagement.app

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.logisticsmanagement.app.data.LogisticsDatabase
import com.logisticsmanagement.app.model.AccountState
import com.logisticsmanagement.app.model.Platform
import kotlinx.coroutines.runBlocking
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
class S9CleanupConvergenceTest {
    private fun evidenceNames(root: File): List<String> =
        File(root, "native-entry-evidence").listFiles()?.map { it.name }?.sorted().orEmpty()

    @Test fun removedAccountResidueIsCleaned(): Unit = runBlocking {
        val context = S9Evidence.context
        val application = context.applicationContext as LogisticsApplication
        application.container.ready.await()
        val credentials = application.container.credentials
        val runName = S9Evidence.argument("runName")
        val databaseName = "S9-converge-" + runName.lowercase()
        context.deleteDatabase(databaseName)
        val database = Room.databaseBuilder(context, LogisticsDatabase::class.java, databaseName).build()
        val container = AppContainer(context, database).apply { ready.complete(Unit) }
        val filesRoot = File(context.cacheDir, "s9-converge-" + runName).apply {
            deleteRecursively()
            check(mkdirs())
        }
        val prefs = context.getSharedPreferences("webview_profiles", Context.MODE_PRIVATE)
        val pendingBefore = prefs.getStringSet("pending_deletions", emptySet()).orEmpty().toSet()
        var removedId: String? = null
        var activeId: String? = null
        try {
            val removed = container.repository.createAccount(Platform.TAOBAO, "S9 收敛已移除")
            removedId = removed.id
            container.repository.saveAccount(removed.copy(enabled = false, deleted = true, state = AccountState.REMOVED))
            val active = container.repository.createAccount(Platform.TAOBAO, "S9 收敛活动")
            activeId = active.id
            container.repository.saveAccount(active.copy(enabled = true, state = AccountState.READY))
            credentials.putSecret("account.${removed.id}.captures", "stale")
            credentials.putSecret("account.${active.id}.captures", "kept")
            val evidenceRoot = File(filesRoot, "native-entry-evidence").apply { check(mkdirs()) }
            val removedEvidence = File(evidenceRoot, "removed-capture").apply { check(mkdirs()) }
            File(removedEvidence, "account.enc").writeText(credentials.encrypt(removed.id))
            val activeEvidence = File(evidenceRoot, "active-capture").apply { check(mkdirs()) }
            File(activeEvidence, "account.enc").writeText(credentials.encrypt(active.id))

            container.syncCoordinator.cleanupRemovedAccount(removed, filesRoot)
            assertFalse("已移除账号证据应删除", removedEvidence.exists())
            assertTrue("活动账号证据应保留", activeEvidence.isDirectory)
            assertNull("已移除账号凭据应删除", credentials.getSecret("account.${removed.id}.captures"))
            assertEquals("kept", credentials.getSecret("account.${active.id}.captures"))
            val activeRow = checkNotNull(container.repository.getAccount(active.id))
            assertFalse(activeRow.deleted)
            val afterFirst = evidenceNames(filesRoot)
            assertEquals(listOf("active-capture"), afterFirst)

            container.syncCoordinator.cleanupRemovedAccount(removed, filesRoot)
            assertEquals("第二次清理不应改变证据", afterFirst, evidenceNames(filesRoot))
            val pendingAfter = prefs.getStringSet("pending_deletions", emptySet()).orEmpty().toSet()
            assertEquals("待删除 profile 记录应恢复", pendingBefore, pendingAfter)

            S9Evidence.save(S9Evidence.directory("convergence"), "result.json", buildJsonObject {
                put("removedEvidenceDeleted", !removedEvidence.exists())
                put("activeEvidenceKept", activeEvidence.isDirectory)
                put("removedCredentialDeleted", true)
                put("secondRunUnchanged", true)
                put("pendingUnchanged", pendingBefore == pendingAfter)
            })
        } finally {
            removedId?.let { credentials.deleteAccount(it) }
            activeId?.let { credentials.deleteAccount(it) }
            database.close()
            context.deleteDatabase(databaseName)
            filesRoot.deleteRecursively()
        }
    }
}
