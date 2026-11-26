package com.logisticsmanagement.app.sync

import android.app.NotificationManager
import android.content.Context
import android.os.SystemClock
import androidx.work.*
import com.logisticsmanagement.app.data.*
import com.logisticsmanagement.app.model.*
import com.logisticsmanagement.app.platform.PlatformRegistry
import com.logisticsmanagement.app.platform.PlatformAdapter
import com.logisticsmanagement.app.platform.BrowserAdapter
import com.logisticsmanagement.app.platform.PddAdapter
import com.logisticsmanagement.app.platform.TaobaoAdapter
import com.logisticsmanagement.app.platform.NativeOrderImportSession
import com.logisticsmanagement.app.platform.NativeScan
import com.logisticsmanagement.app.platform.NativeSyncProgressStore
import com.logisticsmanagement.app.platform.NativeProgressSession
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class SyncCoordinator(
    private val context: Context,
    private val repository: LogisticsRepository,
    private val platforms: PlatformRegistry,
    private val settings: SettingsStore,
    private val credentials: CredentialStore,
) {
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val activeJobs = ConcurrentHashMap<String, Job>()
    private val mutableProgress = MutableStateFlow<Map<String, SyncProgress>>(emptyMap())
    private val mutableEvents = MutableSharedFlow<SyncEvent>(extraBufferCapacity = 64)
    val progress = mutableProgress.asStateFlow()
    val events = mutableEvents.asSharedFlow()
    internal var awaitInitialization: suspend () -> Unit = {}
    internal fun hasActiveAccounts() = activeJobs.isNotEmpty()
    internal var nativeProgressStore = NativeSyncProgressStore(context, credentials)
    internal var nativeCheckpoint: suspend (String, String) -> Unit = { _, _ -> }

    private fun report(value: SyncProgress) {
        mutableProgress.update { it + (value.accountId to value) }
        mutableEvents.tryEmit(SyncEvent.Progress(value))
    }

    internal suspend fun captureNativeAccount(id: String, captureId: String, scans: ReceiveChannel<NativeScan>,
        timeoutMillis: Long, onSession: (NativeOrderImportSession) -> Unit, onFinished: suspend (NativeOrderImportSession) -> Unit,
        pagination: Boolean = false, resume: Boolean = false, terminationReason: () -> String = { "采集已停止" }) = coroutineScope {
        awaitInitialization()
        val lock = locks.computeIfAbsent(id) { Mutex() }
        if (!lock.tryLock()) throw AppFailure("该账号正在更新，请等待当前任务结束", FailureKind.CONFIGURATION)
        val job = coroutineContext.job
        activeJobs[id] = job
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        val runProgress = try {
            val selected = repository.getAccount(id) ?: throw AppFailure("采集账号不存在", FailureKind.CONFIGURATION)
            if (!selected.enabled || selected.deleted || selected.captureMode != CaptureMode.APP_PAGE || selected.platform != Platform.DOUYIN)
                throw AppFailure("当前账号不能执行原生采集", FailureKind.CONFIGURATION)
            if (pagination) NativeProgressSession(nativeProgressStore, nativeProgressStore.begin(selected, captureId, resume), nativeCheckpoint)
            else null
        } catch (failure: Throwable) { activeJobs.remove(id, job); lock.unlock(); throw failure }
        val session = NativeOrderImportSession(id, captureId,
            bind = { repository.bindNativeIdentity(id, it) },
            existing = { remote -> repository.getOrders().firstOrNull { it.accountId == id && it.remoteId == remote } },
            save = { identity, order -> withContext(NonCancellable) {
                val result = repository.ingestNativeOrders(id, identity, OrderBatch(listOf(order)))
                if (runProgress != null) { nativeCheckpoint("BUSINESS_COMMITTED", order.remoteId); runProgress.orderSaved(identity, order, result) }
                result
            } },
            progress = { value -> report(SyncProgress(id, SyncStage.IMPORTING, value.totals.inserted + value.totals.updated,
                shipmentsUpdated = value.logistics?.savedSources?.size ?: 0, message = value.message(), result = value.totals)) },
            saveLogistics = { identity, remoteId, shipment -> withContext(NonCancellable) {
                repository.ingestNativeLogistics(id, identity, remoteId, shipment)
                if (runProgress != null) { nativeCheckpoint("LOGISTICS_COMMITTED", remoteId); runProgress.logisticsSaved(remoteId) }
            } }, runProgress = runProgress)
        try {
            val account = checkNotNull(repository.getAccount(id))
            if (!account.enabled || account.deleted || account.platform != Platform.DOUYIN || account.captureMode != CaptureMode.APP_PAGE)
                throw AppFailure("当前账号不能执行抖音原生采集", FailureKind.CONFIGURATION)
            repository.recordSyncAttempt(id)
            repository.setSyncState(id, AccountState.SYNCING)
            onSession(session)
            val completed = withTimeoutOrNull(timeoutMillis) { for (scan in scans) session.accept(scan); true }
            withContext(NonCancellable) {
                val complete = completed == true && runProgress?.complete() == true
                val endReason = when {
                    completed == null -> "本次采集达到执行期限"
                    complete -> "本轮采集已完成"
                    else -> runProgress?.value?.reason?.takeIf { it.isNotBlank() } ?: "采集未完成，请继续采集"
                }
                runProgress?.finishRound(endReason)
                repository.setSyncState(id, if (complete) AccountState.READY else AccountState.PARTIAL, if (complete) null else session.message(),
                    if (complete) System.currentTimeMillis() else null)
                report(SyncProgress(id, if (complete) SyncStage.COMPLETED else SyncStage.PARTIAL, session.totals.inserted + session.totals.updated,
                    shipmentsUpdated = session.logistics?.savedSources?.size ?: 0, message = session.message(), result = session.totals))
            }
        } catch (error: CancellationException) {
            withContext(NonCancellable) {
                val expired = SystemClock.elapsedRealtime() >= deadline
                runProgress?.finish(if (expired) "本次采集达到执行期限" else terminationReason().ifBlank { "采集已中断，请继续采集" })
                val message = (if (expired) "本次采集达到执行期限；" else "采集已停止；") + session.message()
                repository.setSyncState(id, AccountState.PARTIAL, message)
                report(SyncProgress(id, if (expired) SyncStage.PARTIAL else SyncStage.CANCELLED,
                    session.totals.inserted + session.totals.updated, shipmentsUpdated = session.logistics?.savedSources?.size ?: 0,
                    message = message, result = session.totals))
            }
            throw error
        } catch (failure: AppFailure) {
            withContext(NonCancellable) { runProgress?.finish(checkNotNull(failure.message)) }
            repository.getAccount(id)?.let { failAccount(it, failure) }
            throw failure
        } finally {
            try { withContext(NonCancellable) { onFinished(session) } }
            finally { activeJobs.remove(id, job); lock.unlock() }
        }
    }

    suspend fun syncAll(automatic: Boolean = false) {
        awaitInitialization()
        var firstFailure: AppFailure? = null
        val now = System.currentTimeMillis()
        for (account in repository.getAccounts().filter { it.enabled && it.captureMode == CaptureMode.WEB && it.platform != Platform.DOUYIN }) {
            if (automatic && (account.lastAttemptAt?.let { now - it < AUTO_INTERVAL } == true || activeJobs.containsKey(account.id))) continue
            try { syncAccount(account.id, automatic) }
            catch (error: AppFailure) { if (firstFailure == null) firstFailure = error }
        }
        firstFailure?.let { throw it }
    }

    suspend fun syncAccount(id: String, automatic: Boolean = false) = syncAccountWithEvidence(id, automatic)

    internal suspend fun syncAccountWithEvidence(id: String, automatic: Boolean = false,
        timeoutMillis: Long = ACCOUNT_TIMEOUT, evidence: SyncEvidence = SyncEvidence()): Unit = coroutineScope {
        require(timeoutMillis > 0)
        awaitInitialization()
        val lock = locks.computeIfAbsent(id) { Mutex() }
        if (!lock.tryLock()) return@coroutineScope
        try {
            val account = syncableAccount(id, automatic) ?: return@coroutineScope
            val resumedIds = repository.pendingTracking(id).map { it.sourceId }.toSet()
            val job = coroutineContext.job
            activeJobs[id] = job
            var totals = IngestResult()
            val tracking = TrackingRun()
            val notes = mutableListOf<String>()
            val scopedEvidence = evidence.copy(onNote = { note ->
                val value = note.trim()
                if (value.isNotEmpty() && notes.size < 5 && value !in notes) notes += value.take(120)
            })
            val deadline = SystemClock.elapsedRealtime() + timeoutMillis
            try {
                withContext(NonCancellable) {
                    repository.recordSyncAttempt(id)
                    check(repository.setSyncState(id, AccountState.SYNCING) == 1)
                }
                report(SyncProgress(id, SyncStage.IMPORTING, message = "正在读取订单", pending = resumedIds.size))
                val completed = withTimeoutOrNull(timeoutMillis) {
                    val adapter = readOrders(account, scopedEvidence) { batch ->
                        withContext(NonCancellable) { totals += repository.ingest(account, batch) }
                        report(SyncProgress(id, SyncStage.IMPORTING, totals.inserted + totals.updated,
                            message = "${totals.description}，正在读取后续页面", result = totals,
                            pending = repository.pendingTracking(id).size))
                        evidence.onCheckpoint(SyncCheckpoint(SyncCheckpointStage.BATCH_SAVED))
                        mutableEvents.emit(SyncEvent.Data(id, batch.orders))
                    }
                    if (account.captureMode == CaptureMode.APP_PAGE) return@withTimeoutOrNull true
                    val prepared = repository.prepareTracking(id)
                    val queue = prepared.filter { it.sourceId in resumedIds } + prepared.filterNot { it.sourceId in resumedIds }
                    tracking.total = queue.size
                    trackShipments(account, adapter, queue, totals, deadline, scopedEvidence, tracking)
                    true
                }
                finishSync(account, completed == null, totals, tracking, notes)
            } catch (error: AppFailure) {
                report(SyncProgress(id, SyncStage.FAILED, totals.inserted + totals.updated, tracking.updated, tracking.total,
                    checkNotNull(error.message), result = totals, trackingFailures = tracking.failures.toList(),
                    pending = repository.pendingTracking(id).size))
                failAccount(account, error)
                throw error
            } catch (error: IOException) {
                val failure = AppFailure("${account.platform.displayName}网络请求失败，请检查网络连接", FailureKind.NETWORK)
                failAccount(account, failure)
                throw failure
            } catch (error: CancellationException) {
                withContext(NonCancellable) {
                    val pending = repository.pendingTracking(id).size
                    val message = "更新已中断；${totals.description}；物流成功 ${tracking.updated} 个，失败 ${tracking.failures.size} 个，待查询 $pending 个"
                    repository.setSyncState(id, AccountState.PARTIAL, message)
                    report(SyncProgress(id, SyncStage.CANCELLED, totals.inserted + totals.updated, tracking.updated, tracking.total,
                        message, result = totals, trackingFailures = tracking.failures.toList(), pending = pending))
                }
                throw error
            } finally { activeJobs.remove(id, job) }
        } finally { lock.unlock() }
    }

    private suspend fun syncableAccount(id: String, automatic: Boolean): Account? {
        val account = repository.getAccount(id) ?: throw AppFailure("账号不存在", FailureKind.CONFIGURATION)
        if (!account.enabled || account.deleted) throw AppFailure("账号已停用", FailureKind.CONFIGURATION)
        if (account.platform == Platform.DOUYIN) throw AppFailure("抖音账号使用 App 页面采集，请进入采集页面开始", FailureKind.CONFIGURATION)
        if (!automatic) return account
        val recentlyAttempted = account.lastAttemptAt?.let {
            System.currentTimeMillis() - it < AUTO_INTERVAL
        } == true
        return account.takeIf { it.captureMode == CaptureMode.WEB && !recentlyAttempted }
    }

    private suspend fun readOrders(account: Account, evidence: SyncEvidence,
        onBatch: suspend (OrderBatch) -> Unit): PlatformAdapter {
        val adapter = platforms.adapter(account.platform)
        when (adapter) {
            is PddAdapter -> adapter.syncWithEvidence(account, { evidence.onCapture(null, it) }, evidence.onNote, onBatch)
            is BrowserAdapter -> adapter.sync(account, { evidence.onCapture(null, it) }, onBatch)
            is TaobaoAdapter -> adapter.sync(account, { evidence.onCapture(null, it) }, onBatch)
            else -> adapter.sync(account, onBatch)
        }
        return adapter
    }

    private suspend fun trackShipments(account: Account, adapter: PlatformAdapter, queue: List<TrackingQueue>,
        imported: IngestResult, deadline: Long, evidence: SyncEvidence, tracking: TrackingRun) {
        if (queue.isEmpty()) return
        withContext(Dispatchers.Main.immediate) {
            var currentSource: String? = null
            val opened = when (adapter) {
                is BrowserAdapter -> adapter.openTrackingSession(account) { evidence.onCapture(currentSource, it) }
                is TaobaoAdapter -> adapter.openTrackingSession(account) { evidence.onCapture(currentSource, it) }
                else -> adapter.openTrackingSession(account)
            }
            opened.use { session ->
                for (item in queue) {
                    if (deadline - SystemClock.elapsedRealtime() < 46_000) break
                    val source = repository.getSource(item.sourceId) ?: continue
                    currentSource = source.id
                    repository.recordTrackingAttempt(source.id)
                    val pending = repository.pendingTracking(account.id).size
                    report(SyncProgress(account.id, SyncStage.TRACKING, imported.inserted + imported.updated, tracking.updated, tracking.total,
                        "正在查询物流，已更新 ${tracking.updated} 个，待查询 $pending 个",
                        result = imported, trackingFailures = tracking.failures.toList(), pending = pending))
                    evidence.onCheckpoint(SyncCheckpoint(SyncCheckpointStage.TRACKING_STARTED, source.id))
                    try {
                        val incoming = session.query(source)
                        withContext(NonCancellable) {
                            repository.applyTrackingAndFinish(source, incoming)
                            tracking.updated++
                        }
                        evidence.onTrackingSaved(source, incoming)
                        evidence.onCheckpoint(SyncCheckpoint(SyncCheckpointStage.TRACKING_SAVED, source.id))
                    } catch (error: AppFailure) {
                        repository.recordTrackingAttempt(source.id, error.message)
                        tracking.failures.add(TrackingFailure(source.id, checkNotNull(error.message), error.kind))
                        evidence.onCheckpoint(SyncCheckpoint(SyncCheckpointStage.TRACKING_FAILED, source.id))
                        if (error.kind in setOf(FailureKind.NEEDS_LOGIN, FailureKind.NEEDS_VERIFICATION, FailureKind.UNSUPPORTED_WEBVIEW)) throw error
                    } catch (error: IOException) {
                        val message = "该包裹的网络请求失败"
                        repository.recordTrackingAttempt(source.id, message)
                        tracking.failures.add(TrackingFailure(source.id, message, FailureKind.NETWORK))
                    }
                    val remaining = repository.pendingTracking(account.id).size
                    report(SyncProgress(account.id, SyncStage.TRACKING, imported.inserted + imported.updated, tracking.updated, tracking.total,
                        "已更新 ${tracking.updated} 个，失败 ${tracking.failures.size} 个，待查询 $remaining 个",
                        result = imported, trackingFailures = tracking.failures.toList(), pending = remaining))
                }
            }
        }
    }

    private suspend fun finishSync(account: Account, timedOut: Boolean, imported: IngestResult, tracking: TrackingRun, notes: List<String>) {
        val pending = if (account.captureMode == CaptureMode.WEB) repository.pendingTracking(account.id).size else 0
        val partial = timedOut || pending > 0 || notes.isNotEmpty()
        val state = if (partial) AccountState.PARTIAL else AccountState.READY
        val message = (if (timedOut) "本次更新达到执行期限；" else "") +
            (if (notes.isNotEmpty()) "分页：" + notes.joinToString("；") + "；" else "") +
            "${imported.description}；物流成功 ${tracking.updated} 个，失败 ${tracking.failures.size} 个，待查询 $pending 个"
        repository.setSyncState(account.id, state, if (partial) message else null,
            if (partial) null else System.currentTimeMillis())
        report(SyncProgress(account.id, if (partial) SyncStage.PARTIAL else SyncStage.COMPLETED,
            imported.inserted + imported.updated, tracking.updated, tracking.total, message,
            result = imported, trackingFailures = tracking.failures.toList(), pending = pending))
    }

    private class TrackingRun {
        var total = 0
        var updated = 0
        val failures = mutableListOf<TrackingFailure>()
    }

    private suspend fun failAccount(account: Account, error: AppFailure) {
        val state = when (error.kind) {
            FailureKind.NEEDS_LOGIN -> AccountState.NEEDS_LOGIN
            FailureKind.NEEDS_VERIFICATION -> AccountState.NEEDS_VERIFICATION
            else -> AccountState.FAILED
        }
        if (repository.setSyncState(account.id, state, error.message) == 1) {
            val previous = mutableProgress.value[account.id]
            report(SyncProgress(account.id, SyncStage.FAILED, previous?.ordersImported ?: 0,
                previous?.shipmentsUpdated ?: 0, previous?.shipmentsTotal ?: 0, checkNotNull(error.message), error.kind,
                previous?.result ?: IngestResult(), previous?.trackingFailures.orEmpty(),
                repository.pendingTracking(account.id).size))
        }
    }

    suspend fun refreshShipment(id: String): Unit = coroutineScope {
        awaitInitialization()
        val source = repository.getSources(id).firstOrNull {
            repository.getAccount(it.accountId)?.let { account -> account.enabled && !account.deleted } == true
        } ?: throw AppFailure("包裹缺少可用来源账号", FailureKind.NEEDS_LOGIN)
        locks.computeIfAbsent(source.accountId) { Mutex() }.withLock {
            val account = checkNotNull(repository.getAccount(source.accountId))
            val job = coroutineContext.job
            activeJobs[account.id] = job
            try {
                val completed = withTimeoutOrNull(ACCOUNT_TIMEOUT) {
                    val incoming = platforms.adapter(source.platform).tracking(account, source)
                    withContext(NonCancellable) { repository.applyTrackingAndFinish(source, incoming) }
                    true
                }
                if (completed == null) throw AppFailure("物流查询达到执行期限，请重新更新", FailureKind.NETWORK)
            } catch (error: AppFailure) { failAccount(account, error); throw error }
            finally { activeJobs.remove(account.id, job) }
        }
    }

    suspend fun removeAccount(id: String) {
        val account = repository.getAccount(id) ?: throw AppFailure("账号不存在", FailureKind.CONFIGURATION)
        repository.saveAccount(account.copy(enabled = false, deleted = true, state = AccountState.REMOVED))
        activeJobs[id]?.cancel()
        WorkManager.getInstance(context).cancelUniqueWork("account_$id")
        locks.computeIfAbsent(id) { Mutex() }.withLock {
            platforms.deleteAccountProfile(account)
            credentials.deleteAccount(id)
            nativeProgressStore.delete(id)
        }
    }

    suspend fun updateSchedules() {
        val manager = WorkManager.getInstance(context)
        manager.cancelUniqueWork("manual_tracking")
        manager.cancelAllWorkByTag("daily_report")
        val config = settings.get()
        for (account in repository.getAccounts()) {
            val name = "account_${account.id}"
            if (!config.backgroundEnabled || !account.enabled || account.captureMode != CaptureMode.WEB) {
                manager.cancelUniqueWork(name)
                continue
            }
            val request = PeriodicWorkRequestBuilder<AccountSyncWorker>(30, TimeUnit.MINUTES)
                .setInputData(workDataOf("accountId" to account.id))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
            manager.enqueueUniquePeriodicWork(name, ExistingPeriodicWorkPolicy.UPDATE, request)
        }
        val preferences = context.getSharedPreferences("work_schedules", Context.MODE_PRIVATE)
        if (!preferences.getBoolean("cleanup_v2", false)) {
            val notifications = context.getSystemService(NotificationManager::class.java)
            notifications.cancelAll()
            listOf("tracking", "accounts", "reports").forEach(notifications::deleteNotificationChannel)
            check(context.getSharedPreferences("notification_events", Context.MODE_PRIVATE).edit().clear().commit())
            check(preferences.edit().clear().putBoolean("cleanup_v2", true).commit())
        }
    }

    private companion object {
        const val ACCOUNT_TIMEOUT = 8 * 60_000L
        const val AUTO_INTERVAL = 15 * 60_000L
    }
}
