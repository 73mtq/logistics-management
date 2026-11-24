package com.logisticsmanagement.app.data

import androidx.room.withTransaction
import com.logisticsmanagement.app.model.*
import com.logisticsmanagement.app.network.CarrierCodes
import java.security.MessageDigest
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.UUID

private enum class OrderSaveResult { INSERTED, UPDATED, UNCHANGED, SKIPPED }

class LogisticsRepository(val database: LogisticsDatabase) {
    private val dao = database.dao()
    fun observeAccounts() = dao.observeAccounts()
    fun observeAllAccounts() = dao.observeAllAccounts()
    fun observeShipments() = dao.observeShipments()
    fun observeShipment(id: String) = dao.observeShipment(id)
    fun observeEvents(id: String) = dao.observeEvents(id)
    fun observeSources(id: String) = dao.observeSources(id)
    fun observeOrders() = dao.observeOrders()
    fun observeUnshippedOrders() = dao.observeUnshippedOrders()
    fun observeNativeUnlinkedOrders() = dao.observeNativeUnlinkedOrders()
    suspend fun getAccount(id: String) = dao.account(id)
    suspend fun getAccounts() = dao.accounts()
    suspend fun normalizeDouyinCaptureModes() = dao.normalizeDouyinCaptureModes()
    suspend fun getShipment(id: String) = database.withTransaction { dao.shipment(id) ?: dao.alias(id)?.let { dao.shipment(it) } }
    suspend fun getShipments() = dao.shipments()
    suspend fun getOrders() = dao.orders()
    suspend fun getUnshippedOrders() = dao.unshippedOrders()
    suspend fun getSources(id: String) = database.withTransaction { dao.sources(dao.alias(id) ?: id) }
    suspend fun getEvents(id: String) = database.withTransaction { dao.events(dao.alias(id) ?: id) }
    suspend fun saveAccount(account: Account) = database.withTransaction {
        if (dao.account(account.id)?.deleted == true && !account.deleted) throw AppFailure("账号已经移除", FailureKind.CONFIGURATION)
        dao.putAccount(account)
    }
    internal suspend fun bindNativeIdentity(accountId: String, identity: String): Account = database.withTransaction {
        require(identity.isNotBlank())
        val account = checkedNativeAccount(accountId)
        if (account.externalIdentity != null && account.externalIdentity != identity)
            throw AppFailure("当前抖音号与绑定账号不一致，请切回绑定账号", FailureKind.NEEDS_VERIFICATION)
        if (dao.accounts().any { it.id != accountId && it.platform == Platform.DOUYIN && it.externalIdentity == identity })
            throw AppFailure("当前抖音号已绑定其他助手账号，请使用对应账号", FailureKind.NEEDS_VERIFICATION)
        if (account.externalIdentity == identity) account else account.copy(externalIdentity = identity).also { dao.putAccount(it) }
    }
    private suspend fun checkedNativeAccount(id: String): Account {
        val account = dao.account(id) ?: throw AppFailure("采集账号不存在", FailureKind.CONFIGURATION)
        if (account.deleted || !account.enabled || account.platform != Platform.DOUYIN || account.captureMode != CaptureMode.APP_PAGE)
            throw AppFailure("采集账号已停用或采集方式已经变化", FailureKind.CONFIGURATION)
        return account
    }
    internal suspend fun ingestNativeOrders(id: String, identity: String, batch: OrderBatch): IngestResult = database.withTransaction {
        val account = checkedNativeAccount(id)
        if (account.externalIdentity != identity) throw AppFailure("抖音账号归属核查尚未完成", FailureKind.NEEDS_VERIFICATION)
        require(batch.excludedRemoteIds.isEmpty() && batch.orders.all { it.shipments.isEmpty() })
        ingest(account, batch)
    }
    internal suspend fun ingestNativeLogistics(id: String, identity: String, remoteOrderId: String, shipment: ImportedShipment) = database.withTransaction {
        val account = checkedNativeAccount(id)
        if (account.externalIdentity != identity) throw AppFailure("抖音账号归属核查尚未完成", FailureKind.NEEDS_VERIFICATION)
        val order = dao.order(stableId(id, remoteOrderId)) ?: throw AppFailure("目标抖音订单尚未保存", FailureKind.UNSUPPORTED_RESPONSE)
        require(order.accountId == id && order.remoteId == remoteOrderId)
        require(shipment.trackingNumber?.isNotBlank() == true && shipment.carrierCode.isNotBlank())
        require(shipment.remoteId == "${shipment.carrierCode}:${shipment.trackingNumber}")
        ingest(account, OrderBatch(listOf(ImportedOrder(order.remoteId, order.title, order.status, order.createdAt,
            order.orderUrl, order.imageUrl, listOf(shipment)))))
    }
    suspend fun setSyncState(id: String, state: AccountState, error: String? = null, lastSyncedAt: Long? = null) = dao.setSyncState(id, state, error, lastSyncedAt)
    suspend fun recoverInterruptedSyncs() = database.withTransaction {
        val interrupted = dao.accounts().filter { it.state == AccountState.SYNCING }
        dao.recoverInterruptedSyncs()
        for (account in interrupted) {
            val pending = if (account.captureMode == CaptureMode.WEB) prepareTracking(account.id).size else 0
            dao.setSyncState(account.id, AccountState.PARTIAL, "上次更新已中断，待查询 $pending 个包裹，请重新更新", null)
        }
    }
    suspend fun pendingProfileCleanup() = dao.pendingProfileCleanup()
    suspend fun finishProfileCleanup(id: String) = dao.finishProfileCleanup(id)
    suspend fun recordSyncAttempt(id: String) = dao.recordSyncAttempt(id, System.currentTimeMillis())
    suspend fun setNotes(id: String, notes: String) = database.withTransaction { dao.setNotes(dao.alias(id) ?: id, notes) }
    suspend fun setArchived(id: String, archived: Boolean) = database.withTransaction { dao.setArchived(dao.alias(id) ?: id, archived) }
    suspend fun createAccount(platform: Platform, label: String): Account {
        val id = UUID.randomUUID().toString()
        return Account(id, platform, label.ifBlank { platform.displayName }, "account_$id", captureMode = if (platform == Platform.DOUYIN) CaptureMode.APP_PAGE else CaptureMode.WEB).also { dao.putAccount(it) }
    }
    suspend fun ingest(account: Account, orders: List<ImportedOrder>): IngestResult = ingest(account, OrderBatch(orders))
    suspend fun ingest(account: Account, batch: OrderBatch): IngestResult {
        val cutoff = Instant.now().minus(90, ChronoUnit.DAYS).toEpochMilli()
        return database.withTransaction {
            if (dao.account(account.id)?.deleted != false) throw AppFailure("账号已移除", FailureKind.CONFIGURATION)
            val excluded = removeExcludedOrders(account.id, batch.excludedRemoteIds)
            dao.deleteOrphanTracking()
            var inserted = 0
            var updated = 0
            for (order in batch.orders) {
                require(order.remoteId !in batch.excludedRemoteIds)
                when (saveOrder(account, order, cutoff)) {
                    OrderSaveResult.INSERTED -> inserted++
                    OrderSaveResult.UPDATED -> updated++
                    OrderSaveResult.UNCHANGED, OrderSaveResult.SKIPPED -> Unit
                }
            }
            if (account.captureMode == CaptureMode.WEB) prepareTracking(account.id)
            IngestResult(inserted, updated, excluded)
        }
    }
    private suspend fun removeExcludedOrders(accountId: String, remoteIds: Set<String>): Int {
        var removed = 0
        for (remoteId in remoteIds) {
            val orderId = stableId(accountId, remoteId)
            val shipmentIds = dao.orderSources(orderId).map { it.shipmentId }.distinct()
            dao.deleteOrderSources(orderId)
            dao.deleteOrder(orderId)
            for (shipmentId in shipmentIds) {
                if (dao.sources(shipmentId).isEmpty()) {
                    dao.deleteEvents(shipmentId)
                    dao.deleteAliases(shipmentId)
                    dao.deleteShipment(shipmentId)
                }
            }
            removed++
        }
        return removed
    }
    private suspend fun saveOrder(account: Account, order: ImportedOrder, cutoff: Long): OrderSaveResult {
        require(order.remoteId.isNotBlank()) { "订单缺少唯一标识" }
        val orderId = stableId(account.id, order.remoteId)
        val existing = dao.order(orderId)
        if (existing == null && order.status == ParcelStatus.SIGNED &&
            order.createdAt != null && order.createdAt < cutoff) return OrderSaveResult.SKIPPED
        val status = if (existing?.status == ParcelStatus.SIGNED) ParcelStatus.SIGNED else order.status
        val stored = Order(orderId, account.id, order.remoteId, order.title, status,
            order.createdAt ?: existing?.createdAt, order.orderUrl, order.imageUrl ?: existing?.imageUrl)
        dao.putOrder(stored)
        order.shipments.forEach { saveOrderShipment(account, order, orderId, status == ParcelStatus.SIGNED, it) }
        if (status == ParcelStatus.SIGNED && order.shipments.isEmpty()) markOrderShipmentsSigned(account, order, orderId)
        return when {
            existing == null -> OrderSaveResult.INSERTED
            stored != existing -> OrderSaveResult.UPDATED
            else -> OrderSaveResult.UNCHANGED
        }
    }
    private suspend fun saveOrderShipment(account: Account, order: ImportedOrder, orderId: String,
        signed: Boolean, raw: ImportedShipment) {
        val incoming = normalizeCarrier(raw)
        require(incoming.remoteId.isNotBlank()) { "包裹缺少来源标识" }
        val sourceId = stableId(account.id, order.remoteId, incoming.remoteId)
        val source = dao.source(sourceId)
        val byNumber = incoming.trackingNumber
            ?.takeIf { it.isNotBlank() && incoming.carrierCode.isNotBlank() }
            ?.let { dao.shipmentByNumber(incoming.carrierCode, it) }
        val previousSourceShipment = source?.let { dao.shipment(it.shipmentId) }
        val prior = if (byNumber != null && previousSourceShipment != null && byNumber.id != previousSourceShipment.id) {
            combineShipments(previousSourceShipment, byNumber)
        } else byNumber ?: previousSourceShipment
        val id = prior?.id ?: UUID.randomUUID().toString()
        dao.putShipment(merge(id, order.title, order.imageUrl, incoming, prior, signed))
        dao.putSource(SourceRef(sourceId, id, orderId, account.id, account.platform, order.remoteId,
            incoming.remoteId, order.orderUrl, account.label))
        saveEvents(id, incoming.events)
    }
    private suspend fun markOrderShipmentsSigned(account: Account, order: ImportedOrder, orderId: String) {
        for (source in dao.orderSources(orderId)) {
            val shipment = checkNotNull(dao.shipment(source.shipmentId))
            dao.putShipment(merge(shipment.id, order.title, order.imageUrl,
                ImportedShipment(source.remoteShipmentId, status = ParcelStatus.SIGNED), shipment, true))
            dao.putSource(source.copy(orderUrl = order.orderUrl, accountLabel = account.label))
        }
    }
    suspend fun applyTracking(source: SourceRef, incoming: ImportedShipment) {
        database.withTransaction {
            val currentSource = checkNotNull(dao.source(source.id))
            if (dao.account(currentSource.accountId)?.deleted != false) {
                throw AppFailure("来源账号已经移除", FailureKind.CONFIGURATION)
            }
            val original = checkNotNull(dao.shipment(currentSource.shipmentId))
            val normalized = normalizeCarrier(incoming)
            val byNumber = normalized.trackingNumber?.takeIf { it.isNotBlank() && normalized.carrierCode.isNotBlank() }
                ?.let { dao.shipmentByNumber(normalized.carrierCode, it) }
            val prior = if (byNumber != null && byNumber.id != original.id) combineShipments(original, byNumber) else original
            dao.putShipment(merge(prior.id, prior.title, prior.imageUrl, normalized, prior))
            dao.putSource(currentSource.copy(shipmentId = prior.id))
            saveEvents(prior.id, normalized.events)
        }
    }
    internal suspend fun applyTrackingAndFinish(source: SourceRef, incoming: ImportedShipment) = database.withTransaction {
        applyTracking(source, incoming)
        dao.finishTracking(source.id)
    }
    suspend fun prepareTracking(accountId: String): List<TrackingQueue> = database.withTransaction {
        val pending = dao.accountSources(accountId).filter { source ->
            val shipment = checkNotNull(dao.shipment(source.shipmentId))
            !shipment.archived && (shipment.status != ParcelStatus.SIGNED ||
                dao.events(shipment.id).none { isEventStatus(it, ParcelStatus.SIGNED) })
        }
        val now = System.currentTimeMillis()
        dao.enqueueTracking(pending.map { TrackingQueue(it.id, accountId, now) })
        val ids = pending.map { it.id }.toSet()
        val queue = dao.trackingQueue(accountId)
        queue.filterNot { it.sourceId in ids }.forEach { dao.finishTracking(it.sourceId) }
        queue.filter { it.sourceId in ids }
    }
    suspend fun getSource(id: String) = dao.source(id)
    suspend fun recordTrackingAttempt(id: String, error: String? = null) = database.withTransaction {
        val source = checkNotNull(dao.source(id))
        val previous = dao.trackingQueue(source.accountId).firstOrNull { it.sourceId == id }
        dao.recordTrackingAttempt(id, System.currentTimeMillis(), error ?: previous?.error)
    }
    suspend fun finishTracking(id: String) = dao.finishTracking(id)
    suspend fun pendingTracking(accountId: String) = dao.trackingQueue(accountId)
    private suspend fun combineShipments(previous: Shipment, target: Shipment): Shipment {
        val previousEvents = dao.events(previous.id).map { TrackingEventData(it.time, it.text, it.status) }
        val targetEvents = dao.events(target.id).map { TrackingEventData(it.time, it.text, it.status) }
        val combined = target.copy(
            status = resolveStatus(previous.status, previousEvents, target.status, targetEvents,
                hasSignedOrder(previous.id) || hasSignedOrder(target.id)),
            pickupCode = target.pickupCode ?: previous.pickupCode,
            pickupAddress = target.pickupAddress ?: previous.pickupAddress,
            estimatedDeliveryAt = target.estimatedDeliveryAt ?: previous.estimatedDeliveryAt,
            updatedAt = maxOf(target.updatedAt, previous.updatedAt),
            imageUrl = target.imageUrl ?: previous.imageUrl,
            notes = listOf(target.notes, previous.notes).filter(String::isNotBlank).distinct().joinToString("\n"),
            archived = target.archived || previous.archived,
        )
        dao.sources(previous.id).forEach { dao.putSource(it.copy(shipmentId = target.id)) }
        dao.moveAliases(previous.id, target.id)
        dao.putAlias(ShipmentAlias(previous.id, target.id))
        saveEvents(target.id, previousEvents)
        dao.deleteEvents(previous.id)
        dao.deleteShipment(previous.id)
        return combined
    }
    private suspend fun saveEvents(id: String, events: List<TrackingEventData>) {
        dao.putEvents(events.map { TrackingEvent(stableId(id, it.time.toString(), it.text), id, it.time, it.text, it.status) })
    }
    private fun isEventStatus(event: TrackingEventData, expected: ParcelStatus): Boolean =
        com.logisticsmanagement.app.platform.parcelStatus(event.status + " " + event.text) == expected
    private fun isEventStatus(event: TrackingEvent, expected: ParcelStatus): Boolean =
        com.logisticsmanagement.app.platform.parcelStatus(event.status + " " + event.text) == expected
    private fun normalizeCarrier(shipment: ImportedShipment): ImportedShipment = shipment.copy(
        carrier = shipment.carrier.trim(),
        carrierCode = CarrierCodes.resolve(shipment.carrier) ?: CarrierCodes.resolve(shipment.carrierCode) ?: shipment.carrierCode.trim().lowercase(Locale.ROOT),
        trackingNumber = shipment.trackingNumber?.trim()?.takeIf(String::isNotBlank),
    )
    private suspend fun hasSignedOrder(id: String): Boolean = dao.sources(id).any { source ->
        checkNotNull(dao.order(source.orderId)).status == ParcelStatus.SIGNED
    }
    private fun resolveStatus(previousStatus: ParcelStatus?, previousEvents: List<TrackingEventData>, incomingStatus: ParcelStatus,
        incomingEvents: List<TrackingEventData>, confirmedSigned: Boolean): ParcelStatus {
        val events = previousEvents + incomingEvents
        val signedTime = events.filter { isEventStatus(it, ParcelStatus.SIGNED) }.maxOfOrNull { it.time }
        val signed = confirmedSigned || previousStatus == ParcelStatus.SIGNED || incomingStatus == ParcelStatus.SIGNED || signedTime != null
        if (signed) {
            val exceptionTime = events.filter { isEventStatus(it, ParcelStatus.EXCEPTION) }.maxOfOrNull { it.time }
            return if (signedTime != null && exceptionTime != null && exceptionTime > signedTime) {
                ParcelStatus.EXCEPTION
            } else ParcelStatus.SIGNED
        }
        if (incomingStatus == ParcelStatus.UNKNOWN) return previousStatus ?: incomingStatus
        val incomingTime = incomingEvents.maxOfOrNull { it.time }
        val previousTime = previousEvents.maxOfOrNull { it.time }
        if (previousStatus != null && incomingTime != null && previousTime != null && incomingTime < previousTime) return previousStatus
        return incomingStatus
    }
    private suspend fun merge(id: String, title: String, imageUrl: String?, incoming: ImportedShipment, prior: Shipment?, confirmedSigned: Boolean = false): Shipment {
        val eventTime = incoming.events.maxOfOrNull { it.time } ?: System.currentTimeMillis()
        val previousEvents = prior?.let { dao.events(it.id).map { event -> TrackingEventData(event.time, event.text, event.status) } }.orEmpty()
        val status = resolveStatus(prior?.status, previousEvents, incoming.status, incoming.events,
            confirmedSigned || prior?.let { hasSignedOrder(it.id) } == true)
        return Shipment(id, title.ifBlank { prior?.title ?: "快递" }, incoming.carrier.ifBlank { prior?.carrier ?: "" },
            incoming.carrierCode.ifBlank { prior?.carrierCode ?: "" }, incoming.trackingNumber?.takeIf(String::isNotBlank) ?: prior?.trackingNumber,
            status, incoming.pickupCode ?: prior?.pickupCode, incoming.pickupAddress ?: prior?.pickupAddress,
            incoming.estimatedDeliveryAt ?: prior?.estimatedDeliveryAt, maxOf(eventTime, prior?.updatedAt ?: 0),
            prior?.notes ?: "", prior?.archived ?: false, imageUrl ?: prior?.imageUrl)
    }
    companion object {
        fun stableId(vararg parts: String): String = MessageDigest.getInstance("SHA-256").digest(parts.joinToString("\u0000").toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
