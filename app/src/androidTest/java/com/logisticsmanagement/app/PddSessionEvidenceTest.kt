package com.logisticsmanagement.app

import android.content.Context
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.WebStorageCompat
import androidx.webkit.WebViewFeature
import com.logisticsmanagement.app.model.Account
import com.logisticsmanagement.app.model.AccountState
import com.logisticsmanagement.app.model.AppFailure
import com.logisticsmanagement.app.model.FailureKind
import com.logisticsmanagement.app.model.Platform
import com.logisticsmanagement.app.platform.cookieValues
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
class PddSessionEvidenceTest {
    private val tag = "PddSessionEvidence"

    @Test fun exportLogin(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val container = (context.applicationContext as LogisticsApplication).container
        container.ready.await()
        val account = pddAccount(container)
        assertTrue("拼多多账号需要完成登录", account.state == AccountState.READY || account.state == AccountState.PARTIAL)
        val session = withContext(Dispatchers.Main.immediate) {
            hasPlatformLoginSession(container.platforms.profiles.profile(account), Platform.PDD)
        }
        assertTrue("拼多多会话需要完整 Cookie", session)
        val steps = container.platforms.accountSecret(account, "pdd.login").orEmpty()
        assertTrue("需要拼多多登录步骤", steps.isNotBlank())
        val cookieKeys = withContext(Dispatchers.Main.immediate) {
            cookieValues(container.platforms.profiles.profile(account), "https://mobile.yangkeduo.com/").keys.sorted()
        }
        val orders = container.repository.getOrders().filter { it.accountId == account.id }
        val sources = container.repository.getShipments().flatMap { container.repository.getSources(it.id) }.filter { it.accountId == account.id }
        val directory = File(context.filesDir, "pdd-session-evidence/" + System.nanoTime()).apply { check(mkdirs()) }
        File(directory, "login-steps.json").writeText(steps)
        File(directory, "summary.json").writeText(buildJsonObject {
            put("accountId", account.id)
            put("state", account.state.name)
            put("cookieKeys", JsonArray(cookieKeys.map(::JsonPrimitive)))
            put("orders", orders.size)
            put("sources", sources.size)
        }.toString())
        Log.i(tag, "orders=" + orders.size + " sources=" + sources.size + " cookieKeys=" + cookieKeys.joinToString(","))
    }

    @Test fun roundOne(): Unit = runBlocking { runRound("round1") }

    @Test fun roundTwo(): Unit = runBlocking { runRound("round2") }

    @Test fun syncWithoutSessionReportsLogin(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val container = (context.applicationContext as LogisticsApplication).container
        container.ready.await()
        val account = pddAccount(container)
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
        try { container.syncCoordinator.syncAccount(account.id) } catch (error: AppFailure) { failure = error }
        assertTrue("清除会话后同步必须失败", failure != null)
        assertEquals(FailureKind.NEEDS_LOGIN, failure!!.kind)
        assertEquals(AccountState.NEEDS_LOGIN, container.repository.getAccount(account.id)!!.state)
        Log.i(tag, "session removed, state=NEEDS_LOGIN")
    }

    private suspend fun runRound(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val container = (context.applicationContext as LogisticsApplication).container
        container.ready.await()
        val account = pddAccount(container)
        val before = snapshot(container, account.id)
        var failure: AppFailure? = null
        try {
            container.syncCoordinator.syncAccount(account.id)
        } catch (error: AppFailure) {
            failure = error
            Log.i(tag, name + " failed kind=" + error.kind + " message=" + error.message)
        }
        val after = snapshot(container, account.id)
        val stored = checkNotNull(container.repository.getAccount(account.id))
        val directory = File(context.filesDir, "pdd-rounds").apply { check(mkdirs() || isDirectory) }
        File(directory, name + ".json").writeText(buildJsonObject {
            put("round", name)
            put("state", stored.state.name)
            put("error", stored.error ?: "")
            put("failure", failure?.kind?.name ?: "")
            put("orders", after.orders)
            put("sources", after.sources)
            put("shipments", after.shipments)
            put("events", after.events)
            put("uniqueEvents", after.uniqueEvents)
            put("pending", after.pending)
            put("newOrders", after.orders - before.orders)
            put("newSources", after.sources - before.sources)
        }.toString())
        Log.i(tag, name + " state=" + stored.state + " orders=" + after.orders + " sources=" + after.sources +
            " events=" + after.events + " pending=" + after.pending)
        assertEquals("轨迹不能重复", after.events, after.uniqueEvents)
        assertEquals("本轮同步不应抛出失败", null, failure)
        if (name == "round2") {
            assertEquals("第二轮不增加订单", before.orders, after.orders)
            assertEquals("第二轮不增加来源", before.sources, after.sources)
        }
    }

    private data class Snapshot(val orders: Int, val sources: Int, val shipments: Int, val events: Int, val uniqueEvents: Int, val pending: Int)

    private suspend fun snapshot(container: AppContainer, accountId: String): Snapshot {
        val orders = container.repository.getOrders().filter { it.accountId == accountId }
        val sources = container.repository.getShipments().flatMap { container.repository.getSources(it.id) }.filter { it.accountId == accountId }
        val shipments = sources.mapNotNull { container.repository.getShipment(it.shipmentId) }.distinctBy { it.id }
        val events = shipments.flatMap { container.repository.getEvents(it.id) }
        return Snapshot(orders.size, sources.size, shipments.size, events.size,
            events.map { Triple(it.shipmentId, it.time, it.text) }.toSet().size,
            container.repository.pendingTracking(accountId).size)
    }

    private suspend fun pddAccount(container: AppContainer): Account {
        val accounts = container.repository.getAccounts().filter { it.platform == Platform.PDD && !it.deleted }
        assertEquals("需要唯一一个未删除的拼多多账号", 1, accounts.size)
        return accounts.single()
    }
}
