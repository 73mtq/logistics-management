package com.logisticsmanagement.app

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.logisticsmanagement.app.model.AppFailure
import com.logisticsmanagement.app.model.Platform
import com.logisticsmanagement.app.platform.CapturedResponse
import com.logisticsmanagement.app.platform.cookieValues
import com.logisticsmanagement.app.platform.hasPlatformLoginSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PddOrderEvidenceTest {
    @Test fun recordOrderListStructure(): Unit = runBlocking {
        val container = (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as LogisticsApplication).container
        val account = container.repository.getAccounts().firstOrNull { it.platform == Platform.PDD }
        assertNotNull("需要已经创建的真实拼多多账号", account)
        val current = checkNotNull(account)
        val hasSession = withContext(Dispatchers.Main.immediate) {
            hasPlatformLoginSession(container.platforms.profiles.profile(current), current.platform)
        }
        val cookieKeys = withContext(Dispatchers.Main.immediate) {
            cookieValues(container.platforms.profiles.profile(current), "https://mobile.yangkeduo.com/").keys.sorted()
        }
        val pddUid = withContext(Dispatchers.Main.immediate) {
            cookieValues(container.platforms.profiles.profile(current), "https://mobile.yangkeduo.com/")["pdduid"]
        }
        Log.i("PddOrderEvidence", "accountId=${current.id} hasPlatformLoginSession=$hasSession state=${current.state}")
        Log.i("PddOrderEvidence", "cookieKeys=$cookieKeys pdduid=$pddUid")
        if (!hasSession) return@runBlocking
        val beforeOrders = container.repository.getOrders().size
        val beforeShipments = container.repository.getShipments().size
        try {
            withContext(Dispatchers.Main.immediate) { container.syncCoordinator.syncAccount(current.id) }
            Log.i("PddOrderEvidence", "sync completed state=${container.repository.getAccount(current.id)?.state}")
        } catch (failure: AppFailure) {
            Log.i("PddOrderEvidence", "sync failed kind=${failure.kind} message=${failure.message}")
        }
        val afterOrders = container.repository.getOrders().size
        val afterShipments = container.repository.getShipments().size
        val pddOrders = container.repository.getOrders().filter { it.accountId == current.id }
        Log.i("PddOrderEvidence", "orders before=$beforeOrders after=$afterOrders, shipments before=$beforeShipments after=$afterShipments")
        Log.i("PddOrderEvidence", "pddOrders=${pddOrders.size}, statuses=${pddOrders.groupingBy { it.status }.eachCount()}")
        pddOrders.forEachIndexed { index, order ->
            Log.i("PddOrderEvidence", "order[$index] id=${order.remoteId} title=${order.title} status=${order.status} createdAt=${order.createdAt} orderUrl=${order.orderUrl}")
        }
        val json = Json { ignoreUnknownKeys = true }
        val captures = container.credentials.getSecret("account.${current.id}.captures")
            ?.let { json.decodeFromString<List<CapturedResponse>>(it) }.orEmpty()
        Log.i("PddOrderEvidence", "captures=${captures.size}")
        captures.forEachIndexed { index, capture ->
            Log.i("PddOrderEvidence", "capture[$index] kind=${capture.kind} page=${capture.requestPage} type=${capture.requestType} url=${capture.url}")
            if (capture.kind == "response") Log.i("PddOrderEvidence", "capture[$index].body=${capture.body.take(3000)}")
        }
    }
}
