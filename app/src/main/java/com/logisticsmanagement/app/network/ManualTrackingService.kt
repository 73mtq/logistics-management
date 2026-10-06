package com.logisticsmanagement.app.network

import com.logisticsmanagement.app.data.CredentialStore
import com.logisticsmanagement.app.data.LogisticsRepository
import com.logisticsmanagement.app.model.AppFailure
import com.logisticsmanagement.app.model.FailureKind
import com.logisticsmanagement.app.model.ParcelStatus
import com.logisticsmanagement.app.model.Platform
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException

class ManualTrackingService(
    private val repository: LogisticsRepository,
    private val credentials: CredentialStore,
    private val client: Kuaidi100Client,
) {
    suspend fun refresh(shipmentId: String): Unit = refresh(shipmentId, skipRecentQuery = false)

    private suspend fun refresh(shipmentId: String, skipRecentQuery: Boolean): Unit = refreshMutex.withLock {
        val shipment = repository.getShipment(shipmentId)
            ?: throw AppFailure("该包裹已不存在", FailureKind.CONFIGURATION)
        if (repository.getSources(shipmentId).none { it.platform == Platform.MANUAL }) {
            throw AppFailure("该包裹需要通过所属平台更新物流", FailureKind.CONFIGURATION)
        }
        val number = shipment.trackingNumber?.trim()
            ?: throw AppFailure("该包裹缺少运单号", FailureKind.CONFIGURATION)
        val customer = credentials.getSecret("kuaidi100.customer")?.trim().orEmpty()
        val key = credentials.getSecret("kuaidi100.key")?.trim().orEmpty()
        val phone = shipment.phoneCipher?.let(credentials::decrypt)?.trim()?.takeIf(String::isNotEmpty)
        val carrierCode = shipment.carrierCode.trim()
        client.validateQuery(carrierCode, number, phone, customer, key)
        val now = System.currentTimeMillis()
        val lastQueryAt = repository.getShipments()
            .filter { it.carrierCode.trim() == carrierCode && it.trackingNumber?.trim() == number }
            .mapNotNull { it.lastQueryAt }
            .maxOrNull()
        if (lastQueryAt != null && now - lastQueryAt < QUERY_INTERVAL) {
            if (skipRecentQuery) return@withLock
            throw AppFailure("同一运单每次查询需要间隔30分钟，请稍后查询", FailureKind.RATE_LIMITED)
        }
        repository.recordQueryAttempt(shipmentId, now)
        val incoming = client.query(carrierCode, number, phone, customer, key)
        repository.saveManualTracking(shipmentId, incoming, now)
    }

    suspend fun refreshAll() {
        val now = System.currentTimeMillis()
        var firstFailure: AppFailure? = null
        for (shipment in repository.getShipments()) {
            if (shipment.archived || shipment.status == ParcelStatus.SIGNED) continue
            if (shipment.lastQueryAt?.let { now - it < QUERY_INTERVAL } == true) continue
            if (repository.getSources(shipment.id).none { it.platform == Platform.MANUAL }) continue
            try {
                refresh(shipment.id, skipRecentQuery = true)
            } catch (error: AppFailure) {
                if (firstFailure == null) firstFailure = error
            } catch (error: IOException) {
                if (firstFailure == null) firstFailure = AppFailure("快递100网络请求失败，请检查网络连接", FailureKind.NETWORK)
            }
        }
        firstFailure?.let { throw it }
    }

    private companion object {
        const val QUERY_INTERVAL = 30 * 60 * 1000L
        val refreshMutex = Mutex()
    }
}
