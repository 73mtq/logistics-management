package com.logisticsmanagement.app

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.logisticsmanagement.app.model.AppFailure
import com.logisticsmanagement.app.model.Platform
import com.logisticsmanagement.app.platform.hasPlatformLoginSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PddSyncEvidenceTest {
    @Test fun syncPddAccount(): Unit = runBlocking {
        val container = (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as LogisticsApplication).container
        val account = container.repository.getAccounts().firstOrNull { it.platform == Platform.PDD }
        assertNotNull("需要已经创建的真实拼多多账号", account)
        val current = checkNotNull(account)
        Log.i("PddSyncEvidence", "start accountId=${current.id} state=${current.state}")
        val sessionBefore = withContext(Dispatchers.Main.immediate) {
            hasPlatformLoginSession(container.platforms.profiles.profile(current), current.platform)
        }
        Log.i("PddSyncEvidence", "hasSessionBeforeSync=$sessionBefore")
        val beforeOrders = container.repository.getOrders().size
        val beforeShipments = container.repository.getShipments().size
        try {
            withContext(Dispatchers.Main.immediate) { container.syncCoordinator.syncAccount(current.id) }
            Log.i("PddSyncEvidence", "sync completed state=${container.repository.getAccount(current.id)?.state}")
        } catch (failure: AppFailure) {
            Log.i("PddSyncEvidence", "sync failed kind=${failure.kind} message=${failure.message}")
        }
        val afterOrders = container.repository.getOrders().size
        val afterShipments = container.repository.getShipments().size
        Log.i("PddSyncEvidence", "orders before=$beforeOrders after=$afterOrders, shipments before=$beforeShipments after=$afterShipments")
        val pddOrders = container.repository.getOrders().filter { it.accountId == current.id }
        Log.i("PddSyncEvidence", "pddOrders=${pddOrders.size}, statuses=${pddOrders.groupingBy { it.status }.eachCount()}")
        Log.i("PddSyncEvidence", "pddCreatedAtNull=${pddOrders.count { it.createdAt == null }}")
        val pddSources = container.repository.getShipments().flatMap { container.repository.getSources(it.id) }.filter { it.accountId == current.id }
        Log.i("PddSyncEvidence", "pddSources=${pddSources.size}")
    }
}
