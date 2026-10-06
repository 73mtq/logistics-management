package com.logisticsmanagement.app.data

import android.content.Context
import androidx.room.*
import com.logisticsmanagement.app.model.*
import kotlinx.coroutines.flow.Flow

class Converters {
    @TypeConverter fun platform(value: String): Platform = Platform.valueOf(value)
    @TypeConverter fun platform(value: Platform): String = value.name
    @TypeConverter fun status(value: String): ParcelStatus = ParcelStatus.valueOf(value)
    @TypeConverter fun status(value: ParcelStatus): String = value.name
    @TypeConverter fun accountState(value: String): AccountState = AccountState.valueOf(value)
    @TypeConverter fun accountState(value: AccountState): String = value.name
}

@Dao
interface LogisticsDao {
    @Query("SELECT * FROM Account WHERE deleted = 0 ORDER BY platform, label") fun observeAccounts(): Flow<List<Account>>
    @Query("SELECT * FROM Account ORDER BY platform, label") fun observeAllAccounts(): Flow<List<Account>>
    @Query("SELECT * FROM Account WHERE id = :id") suspend fun account(id: String): Account?
    @Query("SELECT * FROM Account WHERE deleted = 0") suspend fun accounts(): List<Account>
    @Upsert suspend fun putAccount(account: Account)
    @Query("UPDATE Account SET state = :state, error = :error, lastSyncedAt = COALESCE(:lastSyncedAt, lastSyncedAt) WHERE id = :id AND deleted = 0")
    suspend fun setSyncState(id: String, state: AccountState, error: String?, lastSyncedAt: Long?): Int
    @Query("UPDATE Account SET state = 'FAILED', error = '上次更新被中断，请重新更新' WHERE state = 'SYNCING' AND deleted = 0") suspend fun recoverInterruptedSyncs()
    @Query("SELECT * FROM Shipment ORDER BY updatedAt DESC") fun observeShipments(): Flow<List<Shipment>>
    @Query("SELECT * FROM Shipment ORDER BY updatedAt DESC") suspend fun shipments(): List<Shipment>
    @Query("SELECT * FROM Shipment WHERE id = :id") suspend fun shipment(id: String): Shipment?
    @Query("SELECT Shipment.* FROM Shipment WHERE id = :id OR id = (SELECT shipmentId FROM ShipmentAlias WHERE id = :id) LIMIT 1")
    fun observeShipment(id: String): Flow<Shipment?>
    @Query("SELECT shipmentId FROM ShipmentAlias WHERE id = :id") suspend fun alias(id: String): String?
    @Upsert suspend fun putAlias(alias: ShipmentAlias)
    @Query("UPDATE ShipmentAlias SET shipmentId = :target WHERE shipmentId = :previous") suspend fun moveAliases(previous: String, target: String)
    @Query("SELECT * FROM Shipment WHERE carrierCode = :carrier AND trackingNumber = :number LIMIT 1") suspend fun shipmentByNumber(carrier: String, number: String): Shipment?
    @Upsert suspend fun putShipment(shipment: Shipment)
    @Query("UPDATE Shipment SET lastQueryAt = :time WHERE id = :id") suspend fun recordQueryAttempt(id: String, time: Long)
    @Query("DELETE FROM Shipment WHERE id = :id") suspend fun deleteShipment(id: String)
    @Query("DELETE FROM TrackingEvent WHERE shipmentId = :id") suspend fun deleteEvents(id: String)
    @Query("UPDATE Shipment SET notes = :notes WHERE id = :id") suspend fun setNotes(id: String, notes: String)
    @Query("UPDATE Shipment SET archived = :archived WHERE id = :id") suspend fun setArchived(id: String, archived: Boolean)
    @Query("SELECT * FROM SourceRef WHERE shipmentId = :id OR shipmentId = (SELECT shipmentId FROM ShipmentAlias WHERE id = :id)") fun observeSources(id: String): Flow<List<SourceRef>>
    @Query("SELECT * FROM SourceRef WHERE shipmentId = :id") suspend fun sources(id: String): List<SourceRef>
    @Query("SELECT * FROM SourceRef WHERE id = :id") suspend fun source(id: String): SourceRef?
    @Query("SELECT * FROM SourceRef WHERE accountId = :id") suspend fun accountSources(id: String): List<SourceRef>
    @Query("SELECT * FROM SourceRef WHERE orderId = :id") suspend fun orderSources(id: String): List<SourceRef>
    @Upsert suspend fun putSource(source: SourceRef)
    @Upsert suspend fun putOrder(order: Order)
    @Query("SELECT * FROM orders WHERE id = :id") suspend fun order(id: String): Order?
    @Query("SELECT * FROM orders ORDER BY createdAt DESC") fun observeOrders(): Flow<List<Order>>
    @Query("SELECT * FROM orders ORDER BY createdAt DESC") suspend fun orders(): List<Order>
    @Query("SELECT * FROM orders WHERE status = 'WAITING_SHIPMENT' AND NOT EXISTS (SELECT 1 FROM SourceRef WHERE SourceRef.orderId = orders.id) ORDER BY createdAt DESC")
    fun observeUnshippedOrders(): Flow<List<Order>>
    @Query("SELECT * FROM orders WHERE status = 'WAITING_SHIPMENT' AND NOT EXISTS (SELECT 1 FROM SourceRef WHERE SourceRef.orderId = orders.id) ORDER BY createdAt DESC")
    suspend fun unshippedOrders(): List<Order>
    @Query("SELECT * FROM TrackingEvent WHERE shipmentId = :id OR shipmentId = (SELECT shipmentId FROM ShipmentAlias WHERE id = :id) ORDER BY time DESC") fun observeEvents(id: String): Flow<List<TrackingEvent>>
    @Query("SELECT * FROM TrackingEvent WHERE shipmentId = :id ORDER BY time DESC") suspend fun events(id: String): List<TrackingEvent>
    @Query("SELECT MAX(time) FROM TrackingEvent WHERE shipmentId = :id") suspend fun latestEventTime(id: String): Long?
    @Upsert suspend fun putEvents(events: List<TrackingEvent>)
    @Query("SELECT * FROM Report ORDER BY createdAt DESC") fun observeReports(): Flow<List<Report>>
    @Upsert suspend fun putReport(report: Report)
}

@Database(entities = [Account::class, Order::class, Shipment::class, SourceRef::class, TrackingEvent::class, Report::class, ShipmentAlias::class], version = 1, exportSchema = true)
@TypeConverters(Converters::class)
abstract class LogisticsDatabase : RoomDatabase() {
    abstract fun dao(): LogisticsDao
    companion object {
        fun open(context: Context): LogisticsDatabase = Room.databaseBuilder(context.applicationContext, LogisticsDatabase::class.java, "logistics.db").build()
    }
}
