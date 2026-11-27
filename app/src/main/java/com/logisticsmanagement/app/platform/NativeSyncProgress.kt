package com.logisticsmanagement.app.platform

import android.content.Context
import android.util.AtomicFile
import com.logisticsmanagement.app.data.CredentialStore
import com.logisticsmanagement.app.model.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File
import java.io.IOException
import java.nio.BufferUnderflowException
import java.security.GeneralSecurityException
import java.util.UUID

@Serializable
internal data class NativeOrderProgress(val remoteId: String, val title: String?, val status: ParcelStatus,
    val orderSaved: Boolean = false, val logisticsSaved: Boolean = false, val inserted: Boolean = false,
    val updated: Boolean = false, val excluded: Boolean = false, val missing: List<String> = emptyList(),
    val evidenceCaptureId: String = "", val excludedReason: String? = null,
    val stale: Boolean = false, val staleReason: String? = null) {
    val finished: Boolean get() = excluded || orderSaved && missing.isEmpty() && (status == ParcelStatus.WAITING_SHIPMENT || logisticsSaved)
}

@Serializable
internal data class NativeScopeTab(val label: String, val selected: Boolean, val description: String,
    val supplementalTexts: List<String>, val path: List<Int>, val bounds: List<Int>)

@Serializable
internal data class NativeScopeEvidence(val title: String?, val tabs: List<NativeScopeTab>,
    val checkedProperties: List<String> = emptyList())

@Serializable
internal data class NativeSyncProgress(val version: Int = 1, val accountId: String, val externalIdentity: String?,
    val cycleId: String = UUID.randomUUID().toString(), val captureId: String, val scope: String = "全部",
    val step: String = "等待账号核查", val reason: String = "", val running: Boolean = true,
    val listEnded: Boolean = false, val scopeConfirmed: Boolean = false,
    val screensRead: Int = 0, val lastScreenNodeCount: Int = 0, val lastScreenActions: List<Int> = emptyList(),
    val scopeEvidence: NativeScopeEvidence? = null,
    val orders: Map<String, NativeOrderProgress> = emptyMap(), val blockedCards: Map<String, String> = emptyMap(),
    val previousBlocked: Map<String, String> = emptyMap(),
    val terminalEvidence: NativeFieldEvidence? = null, val updatedAt: Long = System.currentTimeMillis()) {
    val complete: Boolean get() = completeFor(captureId)
    fun completeFor(value: String): Boolean = listEnded && scopeConfirmed && terminalEvidence != null && blockedCards.isEmpty() &&
        orders.values.all { it.evidenceCaptureId != value || it.finished || it.stale }
    fun staleUnseen(value: String, reason: String = "本轮未再出现"): NativeSyncProgress = copy(
        orders = orders.mapValues { (_, order) ->
            if (order.evidenceCaptureId != value && !order.stale) order.copy(stale = true, staleReason = reason) else order
        })
    fun resumeFor(value: String): NativeSyncProgress = copy(captureId = value, cycleId = UUID.randomUUID().toString(), running = true,
        listEnded = false, scopeConfirmed = false, terminalEvidence = null, reason = "", step = "恢复后重新核查账号",
        blockedCards = emptyMap(), previousBlocked = blockedCards, screensRead = 0, lastScreenNodeCount = 0,
        lastScreenActions = emptyList(), scopeEvidence = null, updatedAt = System.currentTimeMillis())
    fun message(): String = (if (screensRead > 0) "已读取 ${screensRead} 个屏幕，" else "") +
        "已核查 ${orders.size} 笔，新增 ${orders.values.count { it.inserted }} 笔，更新 ${orders.values.count { it.updated }} 笔，" +
        "物流已保存 ${orders.values.count { it.logisticsSaved }} 笔，订单待补充 ${orders.values.count { !it.finished }} 笔，页面待核查 ${blockedCards.size} 处；$step" +
        if (reason.isBlank()) "" else "；$reason"
}

internal object NativeEvidenceStore {
    private const val KEEP_ROUNDS = 3
    private const val MAX_AGE_MILLIS = 7L * 24 * 60 * 60 * 1000
    fun cleanup(filesDir: File, keep: Int = KEEP_ROUNDS, maxAgeMillis: Long = MAX_AGE_MILLIS) {
        val dirs = File(filesDir, "native-entry-evidence").listFiles()?.filter { it.isDirectory } ?: return
        val now = System.currentTimeMillis()
        dirs.sortedByDescending { it.lastModified() }.forEachIndexed { index, dir ->
            if (index >= keep || now - dir.lastModified() > maxAgeMillis) dir.deleteRecursively()
        }
    }

    fun deleteAccount(filesDir: File, accountId: String, credentials: CredentialStore): Int {
        val dirs = File(filesDir, "native-entry-evidence").listFiles()?.filter { it.isDirectory } ?: return 0
        var deleted = 0
        for (dir in dirs) {
            if (evidenceOwner(dir, credentials) != accountId) continue
            check(dir.deleteRecursively()) { "无法删除账号采集证据：${dir.name}" }
            deleted++
        }
        return deleted
    }

    private fun evidenceOwner(dir: File, credentials: CredentialStore): String? {
        for (name in listOf("account.enc", "end.enc", "order-import.enc")) {
            val file = File(dir, name)
            if (!file.isFile) continue
            val plain = try { credentials.decrypt(file.readText()) } catch (failure: IOException) { continue }
                catch (failure: GeneralSecurityException) { continue }
                catch (failure: IllegalArgumentException) { continue }
                catch (failure: BufferUnderflowException) { continue }
            val owner = if (name == "account.enc") plain else {
                val parsed = try { platformJson.parseToJsonElement(plain) } catch (failure: IllegalArgumentException) { null }
                ((parsed as? JsonObject)?.get("accountId") as? JsonPrimitive)?.contentOrNull
            }
            if (!owner.isNullOrBlank()) return owner
        }
        return null
    }
}

internal class NativeSyncProgressStore(context: Context, private val credentials: CredentialStore,
    private val namespace: String = "native-sync-progress") {
    private val directory = File(context.filesDir, namespace)
    private fun file(id: String): AtomicFile {
        require(id.matches(Regex("[A-Za-z0-9-]+")))
        return AtomicFile(File(directory, "$id.enc"))
    }
    @Synchronized fun read(account: Account): NativeSyncProgress? {
        val file = file(account.id)
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return null
        val value = try { platformJson.decodeFromString<NativeSyncProgress>(credentials.decrypt(file.openRead().use { it.readBytes().toString(Charsets.UTF_8) })) }
            catch (failure: SerializationException) { throw AppFailure("采集进度格式无法读取，请核查本机资料", FailureKind.CONFIGURATION) }
            catch (failure: GeneralSecurityException) { throw AppFailure("采集进度无法解密，请核查本机资料", FailureKind.CONFIGURATION) }
            catch (failure: IOException) { throw AppFailure("采集进度文件无法读取", FailureKind.CONFIGURATION) }
            catch (failure: java.nio.BufferUnderflowException) { throw AppFailure("采集进度密文不完整", FailureKind.CONFIGURATION) }
            catch (failure: IllegalArgumentException) { throw AppFailure("采集进度内容无效，请核查本机资料", FailureKind.CONFIGURATION) }
        if (value.version != 1) throw AppFailure("当前采集进度版本无法读取", FailureKind.CONFIGURATION)
        if (value.accountId != account.id || value.externalIdentity != account.externalIdentity)
            throw AppFailure("采集进度与当前绑定账号不一致", FailureKind.NEEDS_VERIFICATION)
        return value
    }
    @Synchronized fun write(value: NativeSyncProgress) {
        check(directory.isDirectory || directory.mkdirs())
        val target = file(value.accountId)
        val output = target.startWrite()
        try {
            output.write(credentials.encrypt(platformJson.encodeToString(value)).toByteArray(Charsets.UTF_8))
            target.finishWrite(output)
        } catch (failure: IOException) { target.failWrite(output); throw failure }
    }
    @Synchronized fun begin(account: Account, captureId: String, resume: Boolean): NativeSyncProgress {
        val previous = if (resume) read(account) ?: throw AppFailure("当前账号没有可继续的采集进度", FailureKind.CONFIGURATION) else null
        val value = previous?.resumeFor(captureId)
            ?: NativeSyncProgress(accountId = account.id, externalIdentity = account.externalIdentity, captureId = captureId)
        write(value)
        return value
    }
    @Synchronized fun recover(account: Account): NativeSyncProgress? {
        val value = read(account) ?: return null
        return if (!value.running) value else value.copy(running = false, reason = "上次采集已中断，请继续采集", updatedAt = System.currentTimeMillis()).also(::write)
    }
    @Synchronized fun stop(account: Account, reason: String) {
        val value = read(account) ?: return
        if (value.running) write(value.copy(running = false, reason = reason, updatedAt = System.currentTimeMillis()))
    }
    @Synchronized fun delete(accountId: String) {
        val base = File(directory, "$accountId.enc")
        base.delete()
        File(base.path + ".bak").delete()
    }
    @Synchronized fun rebuild(account: Account): File? {
        val base = file(account.id).baseFile
        val backup = File(base.path + ".bak")
        val sources = listOfNotNull(base.takeIf { it.exists() }, backup.takeIf { it.exists() })
        if (sources.isEmpty()) return null
        val target = File(base.parentFile, "${account.id}.corrupt-${System.currentTimeMillis()}")
        sources.forEachIndexed { index, source -> check(source.renameTo(File(target.path + if (index == 0) "" else "-bak"))) }
        return target
    }
}

internal class NativeProgressSession(private val store: NativeSyncProgressStore, initial: NativeSyncProgress,
    private val checkpoint: suspend (String, String) -> Unit = { _, _ -> }) {
    var value = initial
        private set
    private suspend fun update(next: NativeSyncProgress) {
        if (next == value) return
        value = next.copy(updatedAt = System.currentTimeMillis())
        store.write(value)
        checkpoint("PROGRESS_SAVED", value.captureId)
    }
    suspend fun discovered(candidate: NativeOrderCandidate) {
        val previous = value.orders[candidate.remoteId]
        val next = previous?.copy(title = candidate.title ?: previous.title, status = candidate.status, evidenceCaptureId = value.captureId)
            ?: NativeOrderProgress(candidate.remoteId, candidate.title, candidate.status, evidenceCaptureId = value.captureId)
        update(value.copy(orders = value.orders + (candidate.remoteId to next)))
    }
    suspend fun orderSaved(identity: String, order: ImportedOrder, result: IngestResult) {
        val previous = checkNotNull(value.orders[order.remoteId])
        update(value.copy(externalIdentity = identity, orders = value.orders + (order.remoteId to previous.copy(orderSaved = true,
            inserted = previous.inserted || result.inserted > 0, updated = previous.updated || result.updated > 0))))
    }
    suspend fun logisticsSaved(remoteId: String) {
        val previous = checkNotNull(value.orders[remoteId])
        update(value.copy(orders = value.orders + (remoteId to previous.copy(logisticsSaved = true))))
    }
    suspend fun observe(session: NativeOrderImportSession) {
        var orders = value.orders
        for ((id, previous) in orders) {
            val missing = session.missing[id].orEmpty() + session.logistics?.missing?.get(id).orEmpty()
            if (previous.evidenceCaptureId != value.captureId) continue
            val excluded = id in session.excluded
            val candidate = session.currentCandidate()?.takeIf { it.remoteId == id }
            val reason = if (!excluded) null else candidate?.let {
                if (it.status == ParcelStatus.SIGNED) "已签收且下单时间超过90天"
                else it.fields.filter { field -> field.name in setOf("配送方式", "配送类型", "订单类型", "status") }.joinToString("；") { field -> field.value }
            } ?: previous.excludedReason
            val next = previous.copy(excluded = excluded, missing = missing.distinct(), excludedReason = reason)
            orders = orders + (id to next)
        }
        update(value.copy(externalIdentity = session.identity ?: value.externalIdentity, orders = orders))
    }
    suspend fun step(label: String) = update(value.copy(step = label))
    suspend fun screenRead(count: Int, nodeCount: Int, actions: List<Int>) =
        update(value.copy(screensRead = count, lastScreenNodeCount = nodeCount, lastScreenActions = actions))
    suspend fun scope(evidence: NativeScopeEvidence) = update(value.copy(scopeEvidence = evidence))
    suspend fun endWithoutTerminal(reason: String) =
        update(value.copy(running = false, listEnded = false, step = "订单列表未提供结束标志", reason = reason))
    suspend fun confirmScope() = update(value.copy(scopeConfirmed = true, step = "正在读取全部订单"))
    fun complete(): Boolean = value.completeFor(value.captureId)
    suspend fun blocked(key: String, reason: String) = update(value.copy(blockedCards = value.blockedCards + ("${value.cycleId}:$key" to reason)))
    suspend fun resolved(key: String) = update(value.copy(blockedCards = value.blockedCards - "${value.cycleId}:$key"))
    suspend fun finishRound(reason: String) = update(value.staleUnseen(value.captureId).copy(running = false, reason = reason))
    suspend fun ended(field: NativeFieldEvidence) = update(value.copy(listEnded = true, terminalEvidence = field, step = "平台订单列表已结束"))
    suspend fun finish(reason: String) = update(value.copy(running = false, reason = reason))
}
