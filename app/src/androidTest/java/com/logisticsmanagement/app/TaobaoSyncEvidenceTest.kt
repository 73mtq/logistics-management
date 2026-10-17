package com.logisticsmanagement.app

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.logisticsmanagement.app.model.Platform
import com.logisticsmanagement.app.model.AccountState
import com.logisticsmanagement.app.platform.platformResponse
import com.logisticsmanagement.app.platform.requiredArray
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class TaobaoSyncEvidenceTest {
    @Test fun syncTaobaoAccount(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val arguments = InstrumentationRegistry.getArguments()
        val container = (context.applicationContext as LogisticsApplication).container
        container.ready.await()
        val current = checkNotNull(container.repository.getAccount(checkNotNull(arguments.getString("accountId"))))
        assertEquals(Platform.TAOBAO, current.platform)
        val expectations = platformResponse(File(context.filesDir, checkNotNull(arguments.getString("expectationsPath"))).readText(), "淘宝真实期望")
        val excluded = expectations.requiredArray("excludedRemoteIds").map { it.jsonPrimitive.content }.toSet()
        val retained = expectations.requiredArray("retainedRemoteIds").map { it.jsonPrimitive.content }.toSet()
        val before = container.repository.getOrders()
        withContext(Dispatchers.Main.immediate) { container.syncCoordinator.syncAccount(current.id) }
        val after = container.repository.getOrders()
        assertEquals(before.filter { it.accountId != current.id }, after.filter { it.accountId != current.id })
        assertEquals(retained, after.filter { it.accountId == current.id }.map { it.remoteId }.toSet())
        assertTrue(after.none { it.accountId == current.id && it.remoteId in excluded })
        val account = checkNotNull(container.repository.getAccount(current.id))
        assertEquals(AccountState.READY, account.state)
        assertEquals(null, account.error)
        assertTrue(account.lastSyncedAt != null)
        assertTrue(container.repository.pendingTracking(current.id).isEmpty())
        Log.i("TaobaoSyncEvidence", "sync completed state=READY retained=${retained.size} excluded=${excluded.size}")
    }
}
