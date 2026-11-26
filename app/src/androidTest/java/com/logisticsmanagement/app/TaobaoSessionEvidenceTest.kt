package com.logisticsmanagement.app

import android.util.Log
import androidx.webkit.WebStorageCompat
import androidx.webkit.WebViewFeature
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.logisticsmanagement.app.model.AccountState
import com.logisticsmanagement.app.model.AppFailure
import com.logisticsmanagement.app.model.FailureKind
import com.logisticsmanagement.app.model.Platform
import com.logisticsmanagement.app.platform.hasPlatformLoginSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.coroutines.resume

@RunWith(AndroidJUnit4::class)
class TaobaoSessionEvidenceTest {
    @Test fun exportRecoveredSession(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val container = (context.applicationContext as LogisticsApplication).container
        container.ready.await()
        val account = container.repository.getAccounts().firstOrNull { it.platform == Platform.TAOBAO && !it.deleted }
            ?: error("需要已经恢复的淘宝账号")
        assertEquals(AccountState.READY, account.state)
        val session = withContext(Dispatchers.Main.immediate) {
            hasPlatformLoginSession(container.platforms.profiles.profile(account), Platform.TAOBAO)
        }
        assertTrue("淘宝会话需要完整 Cookie", session)
        val steps = container.platforms.accountSecret(account, "taobao.login").orEmpty()
        val orders = container.repository.getOrders().filter { it.accountId == account.id }
        assertTrue("需要真实淘宝订单", orders.isNotEmpty())
        val sources = container.repository.getShipments()
            .flatMap { container.repository.getSources(it.id) }
            .filter { it.accountId == account.id }
        assertTrue("需要真实淘宝来源", sources.isNotEmpty())
        val shipments = sources.mapNotNull { container.repository.getShipment(it.shipmentId) }.distinctBy { it.id }
        assertTrue("需要真实淘宝包裹", shipments.isNotEmpty())
        val eventCount = shipments.sumOf { container.repository.getEvents(it.id).size }
        assertTrue("需要真实淘宝轨迹", eventCount > 0)
        val directory = File(context.filesDir, "taobao-session-evidence/" + System.nanoTime()).apply { check(mkdirs()) }
        File(directory, "login-steps.json").writeText(steps.ifBlank { "[]" })
        File(directory, "summary.json").writeText(buildJsonObject {
            put("accountId", account.id)
            put("orders", orders.size)
            put("sources", sources.size)
            put("shipments", shipments.size)
            put("events", eventCount)
            put("trackingNumbers", JsonArray(shipments.mapNotNull { it.trackingNumber }.map(::JsonPrimitive)))
            put("carriers", JsonArray(shipments.map { it.carrier }.distinct().map(::JsonPrimitive)))
        }.toString())
        Log.i("TaobaoSessionEvidence", "orders=" + orders.size + " shipments=" + shipments.size + " events=" + eventCount)
    }

    @Test fun syncWithoutSessionReportsLogin(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val container = (context.applicationContext as LogisticsApplication).container
        container.ready.await()
        val account = container.repository.getAccounts().first { it.platform == Platform.TAOBAO && !it.deleted }
        assertTrue(WebViewFeature.isFeatureSupported(WebViewFeature.DELETE_BROWSING_DATA))
        withContext(Dispatchers.Main.immediate) {
            val profile = container.platforms.profiles.profile(account)
            suspendCancellableCoroutine { continuation ->
                WebStorageCompat.deleteBrowsingData(profile.webStorage) {
                    profile.cookieManager.flush()
                    if (continuation.isActive) continuation.resume(Unit)
                }
            }
        }
        var failure: AppFailure? = null
        try {
            container.syncCoordinator.syncAccount(account.id)
        } catch (error: AppFailure) {
            failure = error
        }
        assertTrue("清除会话后同步必须失败", failure != null)
        assertEquals(FailureKind.NEEDS_LOGIN, failure!!.kind)
        assertEquals(AccountState.NEEDS_LOGIN, container.repository.getAccount(account.id)!!.state)
        Log.i("TaobaoSessionEvidence", "session removed, state=NEEDS_LOGIN")
    }
}
