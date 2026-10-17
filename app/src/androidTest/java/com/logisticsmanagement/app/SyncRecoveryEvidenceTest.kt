package com.logisticsmanagement.app

import android.util.Log
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.logisticsmanagement.app.data.LogisticsDatabase
import com.logisticsmanagement.app.data.LogisticsRepository
import com.logisticsmanagement.app.model.*
import com.logisticsmanagement.app.platform.*
import com.logisticsmanagement.app.sync.*
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class SyncRecoveryEvidenceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val arguments = InstrumentationRegistry.getArguments()
    private val container get() = (context.applicationContext as LogisticsApplication).container
    private val json = Json { ignoreUnknownKeys = true }
    private fun argument(key: String) = checkNotNull(arguments.getString(key))
    private fun input(key: String) = File(context.filesDir, argument(key)).also { check(it.isFile) }
    private fun output() = File(context.filesDir, "sync-recovery-evidence/${argument("runName")}").apply { check(mkdirs()) }

    private suspend fun snapshot(database: LogisticsDatabase, directory: File, name: String) = withContext(Dispatchers.IO) {
        val target = File(directory, "$name.db")
        check(!target.exists())
        database.openHelper.writableDatabase.execSQL("VACUUM INTO ?", arrayOf(target.absolutePath))
        database.openHelper.readableDatabase.query("PRAGMA integrity_check").use {
            assertTrue(it.moveToFirst()); assertEquals("ok", it.getString(0))
        }
    }

    private suspend fun verifyPreserved(repository: LogisticsRepository, orders: List<Order>, shipments: List<Shipment>,
        sources: Map<String, List<SourceRef>>, events: Map<String, List<TrackingEvent>>, accountId: String) {
        val afterOrders = repository.getOrders().associateBy { it.id }
        orders.forEach { before ->
            val after = checkNotNull(afterOrders[before.id])
            if (before.accountId != accountId) assertEquals(before, after)
            if (before.status == ParcelStatus.SIGNED) assertEquals(ParcelStatus.SIGNED, after.status)
        }
        for (before in shipments) {
            val after = checkNotNull(repository.getShipment(before.id))
            assertEquals(before.notes, after.notes)
            assertEquals(before.archived, after.archived)
            assertEquals(sources.getValue(before.id), repository.getSources(after.id))
            assertTrue(repository.getEvents(after.id).containsAll(events.getValue(before.id)))
            if (sources.getValue(before.id).none { it.accountId == accountId }) assertEquals(before, after)
        }
        assertEquals(afterOrders.size, afterOrders.values.map { it.accountId to it.remoteId }.toSet().size)
        val allSources = repository.getShipments().flatMap { repository.getSources(it.id) }
        assertEquals(allSources.size, allSources.map { Triple(it.accountId, it.remoteOrderId, it.remoteShipmentId) }.toSet().size)
        val allEvents = repository.getShipments().flatMap { repository.getEvents(it.id) }
        assertEquals(allEvents.size, allEvents.map { Triple(it.shipmentId, it.time, it.text) }.toSet().size)
    }

    private suspend fun live(mode: String) = supervisorScope {
        container.ready.await()
        val repository = container.repository
        val account = checkNotNull(repository.getAccount(argument("accountId")))
        if (mode == "other") assertTrue(account.platform in setOf(Platform.JD, Platform.TAOBAO))
        else assertEquals(Platform.PDD, account.platform)
        val directory = output()
        val beforeOrders = repository.getOrders()
        val beforeShipments = repository.getShipments()
        val beforeSources = beforeShipments.associate { it.id to repository.getSources(it.id) }
        val beforeEvents = beforeShipments.associate { it.id to repository.getEvents(it.id) }
        val resumedIds = repository.pendingTracking(account.id).map { it.sourceId }.toSet()
        snapshot(container.database, directory, "before")
        val reached = CompletableDeferred<Unit>()
        val checkpoints = mutableListOf<JsonObject>()
        val trackingStarted = mutableListOf<String>()
        var captureCount = 0
        var batches = 0
        var saved = 0
        val trackingLedger = mutableListOf<JsonObject>()
        val evidence = SyncEvidence(onCapture = { sourceId, capture ->
            File(directory, "capture-${captureCount++.toString().padStart(6, '0')}.json").writeText(json.encodeToString(capture))
            if (mode == "tracking" && sourceId != null && saved > 0 && capture.kind in setOf("dom", "pageFinished", "trackingState")) reached.complete(Unit)
        }, onCheckpoint = { checkpoint ->
            checkpoints.add(buildJsonObject {
                put("stage", checkpoint.stage.name); put("sourceId", checkpoint.sourceId); put("time", System.currentTimeMillis())
            })
            File(directory, "checkpoints.json").writeText(JsonArray(checkpoints).toString())
            when (checkpoint.stage) {
                SyncCheckpointStage.BATCH_SAVED -> {
                    if (batches++ == 0) {
                        snapshot(container.database, directory, "first-batch")
                        File(directory, "batch-saved.marker").writeText("saved")
                        if (mode == "orders") reached.complete(Unit)
                    }
                }
                SyncCheckpointStage.TRACKING_STARTED -> {
                    trackingStarted.add(checkNotNull(checkpoint.sourceId))
                }
                SyncCheckpointStage.TRACKING_SAVED -> {
                    saved++
                    assertFalse(repository.pendingTracking(account.id).any { it.sourceId == checkpoint.sourceId })
                }
                SyncCheckpointStage.TRACKING_FAILED -> {
                    assertTrue(repository.pendingTracking(account.id).any { it.sourceId == checkpoint.sourceId })
                }
            }
        }, onTrackingSaved = { source, incoming ->
            val storedSource = checkNotNull(repository.getSource(source.id))
            val stored = checkNotNull(repository.getShipment(storedSource.shipmentId))
            if (!incoming.trackingNumber.isNullOrBlank()) assertEquals(incoming.trackingNumber.trim(), stored.trackingNumber)
            if (incoming.carrier.isNotBlank()) assertEquals(incoming.carrier.trim(), stored.carrier)
            val events = repository.getEvents(stored.id)
            for (event in incoming.events) assertTrue(events.any { it.time == event.time && it.text == event.text && it.status == event.status })
            trackingLedger.add(buildJsonObject {
                put("sourceId", source.id); put("shipmentId", stored.id); put("remoteOrderId", source.remoteOrderId)
                put("trackingNumber", incoming.trackingNumber); put("carrier", incoming.carrier); put("status", incoming.status.name)
                put("events", JsonArray(incoming.events.map { event -> buildJsonObject {
                    put("time", event.time); put("text", event.text); put("status", event.status)
                } }))
            })
            File(directory, "tracking-ledger.json").writeText(JsonArray(trackingLedger).toString())
        })
        val coordinator = container.syncCoordinator
        val work = async {
            if (mode == "outer-timeout") withTimeout(15_000) {
                coordinator.syncAccountWithEvidence(account.id, evidence = evidence)
            } else coordinator.syncAccountWithEvidence(account.id,
                timeoutMillis = if (mode == "deadline") 15_000 else 8 * 60_000L, evidence = evidence)
        }
        try {
            if (mode in setOf("orders", "tracking")) {
                selectReached(reached, work)
                val attemptBefore = repository.getAccount(account.id)?.lastAttemptAt
                withTimeout(5_000) { coordinator.syncAccount(account.id) }
                assertEquals("同账号同时更新保持原有执行", attemptBefore, repository.getAccount(account.id)?.lastAttemptAt)
                work.cancelAndJoin()
                assertTrue(work.isCancelled)
                val progress = checkNotNull(coordinator.progress.value[account.id])
                assertEquals(SyncStage.CANCELLED, progress.stage)
                assertEquals(saved, progress.shipmentsUpdated)
                assertEquals(repository.pendingTracking(account.id).size, progress.pending)
                assertEquals(AccountState.PARTIAL, repository.getAccount(account.id)?.state)
                if (mode == "tracking") assertTrue("取消前需要已保存的真实物流结果", saved > 0)
            } else if (mode == "outer-timeout") {
                var cancelled = false
                try { work.await() } catch (error: TimeoutCancellationException) { cancelled = true }
                assertTrue("外层期限需要传播取消", cancelled)
                assertEquals(SyncStage.CANCELLED, coordinator.progress.value[account.id]?.stage)
            } else {
                work.await()
                val progress = checkNotNull(coordinator.progress.value[account.id])
                if (mode == "deadline") {
                    assertEquals(SyncStage.PARTIAL, progress.stage)
                    assertTrue(progress.message.contains("达到执行期限"))
                    assertTrue("期限结束前需要真实批次", batches > 0)
                } else {
                    assertTrue(progress.stage in setOf(SyncStage.COMPLETED, SyncStage.PARTIAL))
                    assertTrue("需要真实订单批次", batches > 0)
                    if (mode != "other") assertTrue("恢复需要真实物流结果", saved > 0)
                }
                assertEquals(repository.pendingTracking(account.id).size, progress.pending)
            }
            val attemptedResumed = trackingStarted.filter { it in resumedIds }
            assertEquals(attemptedResumed, trackingStarted.take(attemptedResumed.size))
            verifyPreserved(repository, beforeOrders, beforeShipments, beforeSources, beforeEvents, account.id)
            withContext(Dispatchers.Main.immediate) { assertEquals(0, container.platforms.profiles.activeSessionCount(account)) }
            File(directory, "result.json").writeText(buildJsonObject {
                put("mode", mode); put("batches", batches); put("captures", captureCount); put("saved", saved)
                put("pending", repository.pendingTracking(account.id).size); put("resumed", resumedIds.size)
                put("state", repository.getAccount(account.id)?.state?.name)
                put("stage", coordinator.progress.value[account.id]?.stage?.name)
            }.toString())
            Log.i("SyncRecoveryEvidence", "passed mode=$mode batches=$batches saved=$saved captures=$captureCount")
        } finally {
            work.cancelAndJoin()
            snapshot(container.database, directory, "after")
            val current = checkNotNull(repository.getAccount(account.id))
            File(directory, "account.json").writeText(buildJsonObject {
                put("id", current.id); put("state", current.state.name); put("error", current.error)
                put("lastAttemptAt", current.lastAttemptAt); put("lastSyncedAt", current.lastSyncedAt)
            }.toString())
            File(directory, "queue.json").writeText(JsonArray(repository.pendingTracking(account.id).map { item ->
                buildJsonObject {
                    put("sourceId", item.sourceId); put("accountId", item.accountId); put("requestedAt", item.requestedAt)
                    put("lastAttemptAt", item.lastAttemptAt); put("error", item.error)
                }
            }).toString())
        }
    }

    private suspend fun selectReached(reached: CompletableDeferred<Unit>, work: Deferred<Unit>) {
        kotlinx.coroutines.selects.select<Unit> {
            reached.onAwait { }
            work.onAwait { error("同步已结束，未取得指定中断位置") }
        }
    }

    @Test fun cancelDuringOrders(): Unit = runBlocking { live("orders") }
    @Test fun cancelDuringTracking(): Unit = runBlocking { live("tracking") }
    @Test fun expireAccountDeadline(): Unit = runBlocking { live("deadline") }
    @Test fun outerTimeoutIsCancellation(): Unit = runBlocking { live("outer-timeout") }
    @Test fun syncRealAccount(): Unit = runBlocking { live("resume") }
    @Test fun syncOtherRealAccount(): Unit = runBlocking { live("other") }
    @Test fun recordForProcessTermination(): Unit = runBlocking { live("process") }

    @Test fun verifyStartupRecovery(): Unit = runBlocking {
        container.ready.await()
        val accountId = argument("accountId")
        val directory = output()
        val account = checkNotNull(container.repository.getAccount(accountId))
        assertEquals(AccountState.PARTIAL, account.state)
        assertTrue(checkNotNull(account.error).contains("上次更新已中断"))
        assertTrue(container.repository.pendingTracking(accountId).isNotEmpty())
        snapshot(container.database, directory, "recovered")
        File(directory, "result.json").writeText(buildJsonObject {
            put("state", account.state.name); put("pending", container.repository.pendingTracking(accountId).size)
        }.toString())
    }

    @Test fun replayRealOrders(): Unit = runBlocking {
        container.ready.await()
        val directory = output()
        val database = Room.databaseBuilder(context, LogisticsDatabase::class.java, "T09-copy-${System.nanoTime()}.db")
            .createFromFile(input("databasePath")).build()
        try {
            val repository = LogisticsRepository(database)
            val account = checkNotNull(repository.getAccount(argument("accountId")))
            val captures = json.decodeFromString<List<CapturedResponse>>(input("capturesPath").readText())
            val parsed = captures.mapNotNull { capture ->
                when (account.platform) {
                    Platform.PDD -> PddAdapter(container.platforms).parseOrders(capture)
                    Platform.JD -> JdAdapter(container.platforms).parseOrders(capture)
                    Platform.TAOBAO -> if (capture.kind == "response" && capture.url.contains("queryboughtlist")) {
                        val adapter = TaobaoAdapter(container.platforms)
                        adapter.parseOrders(adapter.parseMtopResponse(capture.body))
                    } else null
                    Platform.DOUYIN -> error("本阶段使用网页平台真实资料")
                }
            }
            assertTrue("需要已保存的真实订单响应", parsed.isNotEmpty())
            val existing = repository.getOrders().filter { it.accountId == account.id }.map { it.remoteId }.toSet()
            val batches = parsed.map { OrderBatch(it.orders.filter { order -> order.remoteId in existing }) }.filter { it.orders.isNotEmpty() }
            assertTrue(batches.isNotEmpty())
            val beforeOrders = repository.getOrders()
            val beforeShipments = repository.getShipments()
            val sources = beforeShipments.associate { it.id to repository.getSources(it.id) }
            val events = beforeShipments.associate { it.id to repository.getEvents(it.id) }
            snapshot(database, directory, "before")
            val initialQueue = repository.pendingTracking(account.id)
            repeat(2) {
                for (batch in batches) {
                    repository.ingest(account, batch)
                    val queueBeforePrepare = repository.pendingTracking(account.id)
                    assertEquals(queueBeforePrepare, repository.prepareTracking(account.id))
                    for (prior in initialQueue) {
                        val after = queueBeforePrepare.firstOrNull { it.sourceId == prior.sourceId } ?: continue
                        assertEquals(prior, after)
                    }
                }
                verifyPreserved(repository, beforeOrders, beforeShipments, sources, events, account.id)
            }
            snapshot(database, directory, "after")
            File(directory, "result.json").writeText(buildJsonObject {
                put("platform", account.platform.name); put("batches", batches.size); put("rounds", 2)
                put("pending", repository.pendingTracking(account.id).size)
            }.toString())
        } finally { database.close() }
    }

    @Test fun replayInterruptedDatabase(): Unit = runBlocking {
        container.ready.await()
        val directory = output()
        val database = Room.databaseBuilder(context, LogisticsDatabase::class.java, "T09-interrupted-${System.nanoTime()}.db")
            .createFromFile(input("databasePath")).build()
        try {
            val repository = LogisticsRepository(database)
            val id = argument("accountId")
            assertEquals(AccountState.SYNCING, repository.getAccount(id)?.state)
            val orders = repository.getOrders()
            val shipments = repository.getShipments()
            val queue = repository.pendingTracking(id)
            assertTrue(queue.isNotEmpty())
            repeat(2) {
                repository.recoverInterruptedSyncs()
                assertEquals(AccountState.PARTIAL, repository.getAccount(id)?.state)
                assertEquals(orders, repository.getOrders())
                assertEquals(shipments, repository.getShipments())
                assertEquals(queue, repository.pendingTracking(id))
            }
            snapshot(database, directory, "recovered")
            File(directory, "result.json").writeText(buildJsonObject { put("rounds", 2); put("pending", queue.size) }.toString())
        } finally { database.close() }
    }

    @Test fun replayRecordedFailure(): Unit = runBlocking {
        container.ready.await()
        val directory = output()
        val account = checkNotNull(container.repository.getAccount(argument("accountId")))
        val captures = json.decodeFromString<List<CapturedResponse>>(input("capturesPath").readText())
        val expected = FailureKind.valueOf(argument("expectedKind"))
        val failures = mutableListOf<FailureKind>()
        val orders = container.repository.getOrders()
        val shipments = container.repository.getShipments()
        for (capture in captures) {
            if (capture.kind == "pageFinished" && PlatformWebView.isLoginUrl(capture.url)) {
                failures.add(FailureKind.NEEDS_LOGIN)
                continue
            }
            try {
                when (account.platform) {
                    Platform.JD -> JdAdapter(container.platforms).parseOrders(capture)
                    Platform.TAOBAO -> TaobaoAdapter(container.platforms).parseMtopResponse(capture.body)
                    Platform.PDD -> {
                        val orderId = android.net.Uri.parse(capture.url).getQueryParameter("order_sn")
                        val source = shipments.flatMap { container.repository.getSources(it.id) }
                            .firstOrNull { it.accountId == account.id && it.remoteOrderId == orderId } ?: continue
                        PddAdapter(container.platforms).parseTracking(capture, source)
                    }
                    Platform.DOUYIN -> error("本阶段使用网页平台资料")
                }
            } catch (error: AppFailure) { failures.add(error.kind) }
        }
        assertTrue("需要真实失败记录", failures.isNotEmpty())
        assertTrue("真实错误分类需要符合预期", failures.all { it == expected })
        assertEquals(orders, container.repository.getOrders())
        assertEquals(shipments, container.repository.getShipments())
        File(directory, "result.json").writeText(buildJsonObject { put("kind", expected.name); put("count", failures.size) }.toString())
    }
}
