package com.logisticsmanagement.app.data

import androidx.room.withTransaction
import com.logisticsmanagement.app.model.*
import com.logisticsmanagement.app.network.CarrierCodes
import java.security.MessageDigest
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.UUID

class LogisticsRepository(val database: LogisticsDatabase) {
    private val dao = database.dao()
    fun observeAccounts() = dao.observeAccounts()
    fun observeAllAccounts() = dao.observeAllAccounts()
    fun observeShipments() = dao.observeShipments()
    fun observeShipment(id: String) = dao.observeShipment(id)
    fun observeEvents(id: String) = dao.observeEvents(id)
    fun observeSources(id: String) = dao.observeSources(id)
    fun observeReports() = dao.observeReports()
    fun observeOrders() = dao.observeOrders()
    fun observeUnshippedOrders() = dao.observeUnshippedOrders()
    suspend fun getAccount(id: String) = dao.account(id)
    suspend fun getAccounts() = dao.accounts()
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
    suspend fun setSyncState(id: String, state: AccountState, error: String? = null, lastSyncedAt: Long? = null) = dao.setSyncState(id, state, error, lastSyncedAt)
    suspend fun recoverInterruptedSyncs() = dao.recoverInterruptedSyncs()
    suspend fun saveReport(report: Report) = dao.putReport(report)
    suspend fun setNotes(id: String, notes: String) = database.withTransaction { dao.setNotes(dao.alias(id) ?: id, notes) }
    suspend fun setArchived(id: String, archived: Boolean) = database.withTransaction { dao.setArchived(dao.alias(id) ?: id, archived) }
    suspend fun createAccount(platform: Platform, label: String): Account {
        require(platform != Platform.MANUAL)
        val id = UUID.randomUUID().toString()
        return Account(id, platform, label.ifBlank { platform.displayName }, "account_$id").also { dao.putAccount(it) }
    }
    suspend fun createManualShipment(title: String, carrier: String, carrierCode: String, number: String, phoneCipher: String?): Shipment = database.withTransaction {
        if (carrierCode.isBlank() || number.isBlank()) throw AppFailure("请填写快递公司编码和运单号", FailureKind.CONFIGURATION)
        val normalized = number.trim()
        val code = carrierCode.trim().lowercase(Locale.ROOT)
        val existing = dao.shipmentByNumber(code, normalized)
        val shipment = (existing ?: Shipment(UUID.randomUUID().toString(), title.ifBlank { "$carrier 快递" }, carrier.trim(), code, normalized, ParcelStatus.UNKNOWN, updatedAt = System.currentTimeMillis()))
            .copy(phoneCipher = phoneCipher ?: existing?.phoneCipher)
        dao.putShipment(shipment)
        dao.putSource(SourceRef(stableId("manual", shipment.id), shipment.id, "", "manual", Platform.MANUAL, "", shipment.id, "", "手动添加"))
        shipment
    }
    suspend fun ingest(account: Account, orders: List<ImportedOrder>) {
        val cutoff = Instant.now().minus(90, ChronoUnit.DAYS).toEpochMilli()
        database.withTransaction {
            if (dao.account(account.id)?.deleted != false) throw AppFailure("账号已移除", FailureKind.CONFIGURATION)
            orders.forEach { order ->
                require(order.remoteId.isNotBlank()) { "订单缺少唯一标识" }
                val orderId = stableId(account.id, order.remoteId)
                val existingOrder = dao.order(orderId)
                if (existingOrder == null && order.status == ParcelStatus.SIGNED && order.createdAt != null && order.createdAt < cutoff) return@forEach
                dao.putOrder(Order(orderId, account.id, order.remoteId, order.title, order.status, order.createdAt ?: existingOrder?.createdAt, order.orderUrl, order.imageUrl ?: existingOrder?.imageUrl))
                order.shipments.forEach { raw ->
                    val incoming = normalizeCarrier(raw)
                    require(incoming.remoteId.isNotBlank()) { "包裹缺少来源标识" }
                    val sourceId = stableId(account.id, order.remoteId, incoming.remoteId)
                    val source = dao.source(sourceId)
                    val byNumber = incoming.trackingNumber?.takeIf { it.isNotBlank() && incoming.carrierCode.isNotBlank() }?.let { dao.shipmentByNumber(incoming.carrierCode, it) }
                    val previousSourceShipment = source?.let { dao.shipment(it.shipmentId) }
                    val prior = if (byNumber != null && previousSourceShipment != null && byNumber.id != previousSourceShipment.id) {
                        combineShipments(previousSourceShipment, byNumber)
                    } else byNumber ?: previousSourceShipment
                    val id = prior?.id ?: UUID.randomUUID().toString()
                    dao.putShipment(merge(id, order.title, order.imageUrl, incoming, prior))
                    dao.putSource(SourceRef(sourceId, id, orderId, account.id, account.platform, order.remoteId, incoming.remoteId, order.orderUrl, account.label))
                    saveEvents(id, incoming.events)
                }
                if (order.status == ParcelStatus.SIGNED && order.shipments.isEmpty()) {
                    for (source in dao.orderSources(orderId)) {
                        val shipment = checkNotNull(dao.shipment(source.shipmentId))
                        if (shipment.trackingNumber.isNullOrBlank() && dao.latestEventTime(shipment.id) == null) {
                            dao.putShipment(shipment.copy(
                                title = order.title.ifBlank { shipment.title },
                                status = order.status,
                                imageUrl = order.imageUrl ?: shipment.imageUrl,
                                updatedAt = if (shipment.status == order.status) shipment.updatedAt else maxOf(System.currentTimeMillis(), shipment.updatedAt),
                            ))
                            dao.putSource(source.copy(orderUrl = order.orderUrl, accountLabel = account.label))
                        }
                    }
                }
            }
        }
    }
    suspend fun applyTracking(source: SourceRef, incoming: ImportedShipment) {
        database.withTransaction {
            val currentSource = checkNotNull(dao.source(source.id))
            if (currentSource.platform != Platform.MANUAL && dao.account(currentSource.accountId)?.deleted != false) {
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
    suspend fun saveManualTracking(id: String, incoming: ImportedShipment, queriedAt: Long) {
        database.withTransaction {
            val currentId = dao.alias(id) ?: id
            val prior = checkNotNull(dao.shipment(currentId))
            dao.putShipment(merge(currentId, prior.title, prior.imageUrl, incoming, prior).copy(lastQueryAt = maxOf(queriedAt, prior.lastQueryAt ?: 0)))
            saveEvents(currentId, incoming.events)
        }
    }
    suspend fun recordQueryAttempt(id: String, time: Long) {
        database.withTransaction {
            val currentId = dao.alias(id) ?: id
            checkNotNull(dao.shipment(currentId))
            dao.recordQueryAttempt(currentId, time)
        }
    }
    private suspend fun combineShipments(previous: Shipment, target: Shipment): Shipment {
        val combined = target.copy(
            status = if (target.status == ParcelStatus.UNKNOWN || (previous.status != ParcelStatus.UNKNOWN && previous.updatedAt > target.updatedAt)) previous.status else target.status,
            pickupCode = target.pickupCode ?: previous.pickupCode,
            pickupAddress = target.pickupAddress ?: previous.pickupAddress,
            estimatedDeliveryAt = target.estimatedDeliveryAt ?: previous.estimatedDeliveryAt,
            updatedAt = maxOf(target.updatedAt, previous.updatedAt),
            imageUrl = target.imageUrl ?: previous.imageUrl,
            notes = listOf(target.notes, previous.notes).filter(String::isNotBlank).distinct().joinToString("\n"),
            archived = target.archived || previous.archived,
            phoneCipher = target.phoneCipher ?: previous.phoneCipher,
            lastQueryAt = listOfNotNull(target.lastQueryAt, previous.lastQueryAt).maxOrNull(),
        )
        dao.sources(previous.id).forEach { dao.putSource(it.copy(shipmentId = target.id)) }
        dao.moveAliases(previous.id, target.id)
        dao.putAlias(ShipmentAlias(previous.id, target.id))
        saveEvents(target.id, dao.events(previous.id).map { TrackingEventData(it.time, it.text, it.status) })
        dao.deleteEvents(previous.id)
        dao.deleteShipment(previous.id)
        return combined
    }
    private suspend fun saveEvents(id: String, events: List<TrackingEventData>) {
        dao.putEvents(events.map { TrackingEvent(stableId(id, it.time.toString(), it.text), id, it.time, it.text, it.status) })
    }
    private fun normalizeCarrier(shipment: ImportedShipment): ImportedShipment = shipment.copy(
        carrier = shipment.carrier.trim(),
        carrierCode = CarrierCodes.resolve(shipment.carrier) ?: CarrierCodes.resolve(shipment.carrierCode) ?: shipment.carrierCode.trim().lowercase(Locale.ROOT),
        trackingNumber = shipment.trackingNumber?.trim()?.takeIf(String::isNotBlank),
    )
    private suspend fun merge(id: String, title: String, imageUrl: String?, incoming: ImportedShipment, prior: Shipment?): Shipment {
        val eventTime = incoming.events.maxOfOrNull { it.time } ?: System.currentTimeMillis()
        val previousEventTime = prior?.let { dao.latestEventTime(it.id) }
        val status = if (incoming.status == ParcelStatus.UNKNOWN) prior?.status ?: incoming.status
            else if (prior != null && incoming.events.isNotEmpty() && previousEventTime != null && eventTime < previousEventTime) prior.status else incoming.status
        return Shipment(id, title.ifBlank { prior?.title ?: "快递" }, incoming.carrier.ifBlank { prior?.carrier ?: "" },
            incoming.carrierCode.ifBlank { prior?.carrierCode ?: "" }, incoming.trackingNumber?.takeIf(String::isNotBlank) ?: prior?.trackingNumber,
            status, incoming.pickupCode ?: prior?.pickupCode, incoming.pickupAddress ?: prior?.pickupAddress,
            incoming.estimatedDeliveryAt ?: prior?.estimatedDeliveryAt, maxOf(eventTime, prior?.updatedAt ?: 0),
            prior?.notes ?: "", prior?.archived ?: false, imageUrl ?: prior?.imageUrl, prior?.phoneCipher, prior?.lastQueryAt)
    }
    companion object {
        fun stableId(vararg parts: String): String = MessageDigest.getInstance("SHA-256").digest(parts.joinToString("\u0000").toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
