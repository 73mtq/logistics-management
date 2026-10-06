package com.logisticsmanagement.app.sync

import android.content.Context
import androidx.work.*
import com.logisticsmanagement.app.data.*
import com.logisticsmanagement.app.model.*
import com.logisticsmanagement.app.network.ManualTrackingService
import com.logisticsmanagement.app.platform.PlatformRegistry
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.update
import java.io.IOException
import java.time.ZonedDateTime
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class SyncCoordinator(
    private val context: Context,
    private val repository: LogisticsRepository,
    private val platforms: PlatformRegistry,
    private val manualTracking: ManualTrackingService,
    private val settings: SettingsStore,
    private val credentials: CredentialStore,
    private val notifications: LogisticsNotifications,
) {
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val activeJobs = ConcurrentHashMap<String, Job>()
    private val mutableProgress = MutableStateFlow<Map<String, SyncProgress>>(emptyMap())
    private val mutableEvents = MutableSharedFlow<SyncEvent>(extraBufferCapacity = 64)
    val progress = mutableProgress.asStateFlow()
    val events = mutableEvents.asSharedFlow()
    private fun report(value: SyncProgress) {
        mutableProgress.update { it + (value.accountId to value) }
        mutableEvents.tryEmit(SyncEvent.Progress(value))
    }
    private data class ShipmentState(val shipment: Shipment, val eventId: String?)
    private suspend fun snapshot(): Map<String, ShipmentState> = repository.getShipments().associate { shipment ->
        shipment.id to ShipmentState(shipment, repository.getEvents(shipment.id).firstOrNull()?.id)
    }
    suspend fun syncAll() {
        var firstFailure: AppFailure? = null
        for (account in repository.getAccounts().filter { it.enabled }) {
            try { syncAccount(account.id) }
            catch (error: AppFailure) { if (firstFailure == null) firstFailure = error }
        }
        val manual = repository.getShipments().any { shipment -> repository.getSources(shipment.id).any { it.platform == Platform.MANUAL } }
        if (manual) {
            try { syncManual() }
            catch (error: AppFailure) { if (firstFailure == null) firstFailure = error }
        }
        firstFailure?.let { throw it }
    }
    suspend fun syncManual() {
        val before = snapshot()
        try { withTimeout(8 * 60 * 1000L) { manualTracking.refreshAll() } }
        catch (error: TimeoutCancellationException) {
            notifyChanges(before)
            throw AppFailure("手动包裹更新超过执行期限，请重新更新", FailureKind.NETWORK)
        } catch (error: AppFailure) {
            notifyChanges(before)
            throw error
        } catch (error: IOException) {
            notifyChanges(before)
            throw error
        }
        notifyChanges(before)
    }
    suspend fun syncAccount(id: String) = coroutineScope { locks.getOrPut(id) { Mutex() }.withLock {
        val account = repository.getAccount(id) ?: throw AppFailure("账号不存在", FailureKind.CONFIGURATION)
        if (!account.enabled || account.deleted) throw AppFailure("账号已停用", FailureKind.CONFIGURATION)
        val before = snapshot()
        if (repository.setSyncState(id, AccountState.SYNCING) != 1) throw AppFailure("账号已移除", FailureKind.CONFIGURATION)
        val job = coroutineContext.job
        activeJobs[id] = job
        var importedCount = 0
        var updatedCount = 0
        report(SyncProgress(id, SyncStage.IMPORTING, message = "正在读取订单"))
        try {
            withTimeout(8 * 60 * 1000L) {
                platforms.adapter(account.platform).sync(account) { batch ->
                    repository.ingest(account, batch)
                    importedCount += batch.size
                    mutableEvents.emit(SyncEvent.Data(id, batch))
                    report(SyncProgress(id, SyncStage.IMPORTING, importedCount, message = "已读取 $importedCount 个订单，正在读取后续页面"))
                }
                val sources = repository.getShipments().filter { it.status != ParcelStatus.SIGNED && !it.archived }
                    .mapNotNull { shipment -> repository.getSources(shipment.id).firstOrNull { it.accountId == id } }
                report(SyncProgress(id, SyncStage.TRACKING, importedCount, shipmentsTotal = sources.size, message = "正在更新 ${sources.size} 个在途包裹"))
                for (source in sources) {
                    repository.applyTracking(source, platforms.adapter(account.platform).tracking(account, source))
                    updatedCount++
                    report(SyncProgress(id, SyncStage.TRACKING, importedCount, updatedCount, sources.size, "已更新 $updatedCount / ${sources.size} 个包裹"))
                }
            }
            if (repository.setSyncState(id, AccountState.READY, lastSyncedAt = System.currentTimeMillis()) == 1) {
                report(SyncProgress(id, SyncStage.COMPLETED, importedCount, updatedCount, updatedCount, "更新完成"))
                notifications.clearAccount(account)
                notifyChanges(before)
            }
        } catch (error: TimeoutCancellationException) {
            val failure = AppFailure("${account.platform.displayName}同步超过执行期限，请打开应用重新更新", FailureKind.NETWORK)
            failAccount(account, failure)
            throw failure
        } catch (error: AppFailure) {
            failAccount(account, error)
            throw error
        } catch (error: IOException) {
            val failure = AppFailure("${account.platform.displayName}网络请求失败，请检查网络后重新更新", FailureKind.NETWORK)
            failAccount(account, failure)
            throw failure
        } catch (error: CancellationException) {
            withContext(NonCancellable) { repository.setSyncState(id, AccountState.FAILED, "更新被中断，请重新更新") }
            report(SyncProgress(id, SyncStage.CANCELLED, importedCount, updatedCount, message = "更新被中断"))
            throw error
        } finally {
            activeJobs.remove(id, job)
        }
    } }
    private suspend fun failAccount(account: Account, error: AppFailure) {
        val state = when (error.kind) {
            FailureKind.NEEDS_LOGIN -> AccountState.NEEDS_LOGIN
            FailureKind.NEEDS_VERIFICATION -> AccountState.NEEDS_VERIFICATION
            else -> AccountState.FAILED
        }
        val updated = repository.setSyncState(account.id, state, error.message)
        if (updated == 1) {
            val previous = mutableProgress.value[account.id]
            report(SyncProgress(account.id, SyncStage.FAILED, previous?.ordersImported ?: 0, previous?.shipmentsUpdated ?: 0, previous?.shipmentsTotal ?: 0, error.message.orEmpty(), error.kind))
        }
        if (updated == 1 && (state == AccountState.NEEDS_LOGIN || state == AccountState.NEEDS_VERIFICATION)) notifications.account(account, error.message.orEmpty())
    }
    private suspend fun notifyChanges(before: Map<String, ShipmentState>) {
        for (shipment in repository.getShipments().filter { !it.archived }) {
            val previous = before[shipment.id]
            val event = repository.getEvents(shipment.id).firstOrNull()
            if ((previous == null && shipment.status == ParcelStatus.PICKUP) || (previous != null && (previous.shipment.status != shipment.status || previous.shipment.pickupCode != shipment.pickupCode || previous.eventId != event?.id))) {
                notifications.tracking(shipment, event)
            }
        }
    }
    suspend fun refreshShipment(id: String) = coroutineScope {
        val source = repository.getSources(id).firstOrNull() ?: throw AppFailure("包裹缺少来源", FailureKind.CONFIGURATION)
        if (source.platform == Platform.MANUAL) {
            val before = snapshot()
            manualTracking.refresh(id)
            notifyChanges(before)
            return@coroutineScope
        }
        locks.getOrPut(source.accountId) { Mutex() }.withLock {
            val account = repository.getAccount(source.accountId)?.takeUnless { it.deleted } ?: throw AppFailure("来源账号已移除，请重新绑定账号", FailureKind.NEEDS_LOGIN)
            if (!account.enabled) throw AppFailure("来源账号已停用", FailureKind.CONFIGURATION)
            val job = coroutineContext.job
            activeJobs[account.id] = job
            val before = snapshot()
            try {
                withTimeout(8 * 60 * 1000L) {
                    repository.applyTracking(source, platforms.adapter(source.platform).tracking(account, source))
                }
                notifyChanges(before)
            } catch (error: TimeoutCancellationException) {
                val failure = AppFailure("物流查询超过执行期限，请重新更新", FailureKind.NETWORK)
                failAccount(account, failure)
                throw failure
            } catch (error: AppFailure) {
                failAccount(account, error)
                throw error
            } catch (error: IOException) {
                val failure = AppFailure("物流查询网络请求失败", FailureKind.NETWORK)
                failAccount(account, failure)
                throw failure
            } finally {
                activeJobs.remove(account.id, job)
            }
        }
    }
    suspend fun removeAccount(id: String) {
        val account = repository.getAccount(id) ?: throw AppFailure("账号不存在", FailureKind.CONFIGURATION)
        repository.saveAccount(account.copy(enabled = false, deleted = true, state = AccountState.REMOVED))
        activeJobs[id]?.cancel()
        notifications.clearAccount(account)
        WorkManager.getInstance(context).cancelUniqueWork("account_$id")
        locks.getOrPut(id) { Mutex() }.withLock {
            platforms.deleteAccountProfile(account)
            credentials.deleteAccount(id)
        }
    }
    suspend fun updateSchedules() {
        val manager = WorkManager.getInstance(context)
        val config = settings.get()
        for (account in repository.getAccounts()) {
            val name = "account_${account.id}"
            if (!config.backgroundEnabled || !account.enabled) { manager.cancelUniqueWork(name); continue }
            val request = PeriodicWorkRequestBuilder<AccountSyncWorker>(30, TimeUnit.MINUTES)
                .setInputData(workDataOf("accountId" to account.id))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
            manager.enqueueUniquePeriodicWork(name, ExistingPeriodicWorkPolicy.UPDATE, request)
        }
        if (config.backgroundEnabled && repository.getShipments().any { repository.getSources(it.id).any { ref -> ref.platform == Platform.MANUAL } }) {
            manager.enqueueUniquePeriodicWork("manual_tracking", ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<ManualTrackingWorker>(30, TimeUnit.MINUTES).setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
        } else manager.cancelUniqueWork("manual_tracking")
        val schedulePreferences = context.getSharedPreferences("work_schedules", Context.MODE_PRIVATE)
        val dailyConfig = "${config.aiEnabled}:${config.dailyReportEnabled}:${config.dailyReportHour}:${config.dailyReportMinute}"
        if (schedulePreferences.getString("daily", null) != dailyConfig) {
            manager.cancelAllWorkByTag("daily_report")
            check(schedulePreferences.edit().putString("daily", dailyConfig).commit())
        }
        if (config.aiEnabled && config.dailyReportEnabled) scheduleReport()
    }
    suspend fun scheduleReport(afterRun: Boolean = false) {
        val config = settings.get()
        if (!config.aiEnabled || !config.dailyReportEnabled) return
        val now = ZonedDateTime.now()
        var next = now.withHour(config.dailyReportHour).withMinute(config.dailyReportMinute).withSecond(0).withNano(0)
        if (afterRun || !next.isAfter(now)) next = next.plusDays(1)
        val request = OneTimeWorkRequestBuilder<DailyReportWorker>().setInitialDelay(Duration.between(now, next).toMillis(), TimeUnit.MILLISECONDS)
            .addTag("daily_report").setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
        WorkManager.getInstance(context).enqueueUniqueWork("daily_report_${next.toLocalDate()}", ExistingWorkPolicy.KEEP, request)
    }
}
