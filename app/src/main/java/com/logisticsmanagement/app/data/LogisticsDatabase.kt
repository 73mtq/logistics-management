package com.logisticsmanagement.app.data

import android.content.Context
import androidx.room.*
import com.logisticsmanagement.app.model.*
import kotlinx.coroutines.flow.Flow
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

class Converters {
    @TypeConverter fun platform(value: String): Platform = Platform.valueOf(value)
    @TypeConverter fun platform(value: Platform): String = value.name
    @TypeConverter fun status(value: String): ParcelStatus = ParcelStatus.valueOf(value)
    @TypeConverter fun status(value: ParcelStatus): String = value.name
    @TypeConverter fun accountState(value: String): AccountState = AccountState.valueOf(value)
    @TypeConverter fun accountState(value: AccountState): String = value.name
    @TypeConverter fun captureMode(value: String): CaptureMode = CaptureMode.valueOf(value)
    @TypeConverter fun captureMode(value: CaptureMode): String = value.name
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
    @Query("UPDATE Account SET lastAttemptAt = :time WHERE id = :id AND deleted = 0") suspend fun recordSyncAttempt(id: String, time: Long)
    @Query("UPDATE Account SET state = 'PARTIAL', error = '上次更新已中断，请重新更新' WHERE state = 'SYNCING' AND deleted = 0") suspend fun recoverInterruptedSyncs()
    @Query("UPDATE Account SET captureMode = 'APP_PAGE' WHERE platform = 'DOUYIN' AND captureMode = 'WEB' AND deleted = 0") suspend fun normalizeDouyinCaptureModes(): Int
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
    @Query("DELETE FROM Shipment WHERE id = :id") suspend fun deleteShipment(id: String)
    @Query("DELETE FROM TrackingEvent WHERE shipmentId = :id") suspend fun deleteEvents(id: String)
    @Query("UPDATE Shipment SET notes = :notes WHERE id = :id") suspend fun setNotes(id: String, notes: String)
    @Query("UPDATE Shipment SET archived = :archived WHERE id = :id") suspend fun setArchived(id: String, archived: Boolean)
    @Query("SELECT * FROM SourceRef WHERE shipmentId = :id OR shipmentId = (SELECT shipmentId FROM ShipmentAlias WHERE id = :id)") fun observeSources(id: String): Flow<List<SourceRef>>
    @Query("SELECT * FROM SourceRef WHERE shipmentId = :id") suspend fun sources(id: String): List<SourceRef>
    @Query("SELECT * FROM SourceRef WHERE id = :id") suspend fun source(id: String): SourceRef?
    @Query("SELECT * FROM SourceRef WHERE accountId = :id") suspend fun accountSources(id: String): List<SourceRef>
    @Query("SELECT * FROM SourceRef WHERE orderId = :id") suspend fun orderSources(id: String): List<SourceRef>
    @Query("DELETE FROM SourceRef WHERE orderId = :id") suspend fun deleteOrderSources(id: String)
    @Query("DELETE FROM orders WHERE id = :id") suspend fun deleteOrder(id: String)
    @Query("DELETE FROM ShipmentAlias WHERE shipmentId = :id") suspend fun deleteAliases(id: String)
    @Upsert suspend fun putSource(source: SourceRef)
    @Upsert suspend fun putOrder(order: Order)
    @Query("SELECT * FROM orders WHERE id = :id") suspend fun order(id: String): Order?
    @Query("SELECT * FROM orders ORDER BY createdAt DESC") fun observeOrders(): Flow<List<Order>>
    @Query("SELECT * FROM orders ORDER BY createdAt DESC") suspend fun orders(): List<Order>
    @Query("SELECT * FROM orders WHERE status = 'WAITING_SHIPMENT' AND NOT EXISTS (SELECT 1 FROM SourceRef WHERE SourceRef.orderId = orders.id) ORDER BY createdAt DESC")
    fun observeUnshippedOrders(): Flow<List<Order>>
    @Query("SELECT orders.* FROM orders JOIN Account ON Account.id = orders.accountId WHERE Account.platform = 'DOUYIN' AND orders.status != 'WAITING_SHIPMENT' AND NOT EXISTS (SELECT 1 FROM SourceRef WHERE SourceRef.orderId = orders.id) ORDER BY orders.createdAt DESC")
    fun observeNativeUnlinkedOrders(): Flow<List<Order>>
    @Query("SELECT * FROM orders WHERE status = 'WAITING_SHIPMENT' AND NOT EXISTS (SELECT 1 FROM SourceRef WHERE SourceRef.orderId = orders.id) ORDER BY createdAt DESC")
    suspend fun unshippedOrders(): List<Order>
    @Query("SELECT * FROM TrackingEvent WHERE shipmentId = :id OR shipmentId = (SELECT shipmentId FROM ShipmentAlias WHERE id = :id) ORDER BY time DESC") fun observeEvents(id: String): Flow<List<TrackingEvent>>
    @Query("SELECT * FROM TrackingEvent WHERE shipmentId = :id ORDER BY time DESC") suspend fun events(id: String): List<TrackingEvent>
    @Query("SELECT MAX(time) FROM TrackingEvent WHERE shipmentId = :id") suspend fun latestEventTime(id: String): Long?
    @Upsert suspend fun putEvents(events: List<TrackingEvent>)
    @Query("SELECT * FROM PendingProfileCleanup") suspend fun pendingProfileCleanup(): List<PendingProfileCleanup>
    @Query("DELETE FROM PendingProfileCleanup WHERE accountId = :id") suspend fun finishProfileCleanup(id: String)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun enqueueTracking(items: List<TrackingQueue>)
    @Query("SELECT * FROM TrackingQueue WHERE accountId = :id ORDER BY COALESCE(lastAttemptAt, 0), requestedAt, sourceId") suspend fun trackingQueue(id: String): List<TrackingQueue>
    @Query("UPDATE TrackingQueue SET lastAttemptAt = :time, error = :error WHERE sourceId = :id") suspend fun recordTrackingAttempt(id: String, time: Long, error: String?)
    @Query("DELETE FROM TrackingQueue WHERE sourceId = :id") suspend fun finishTracking(id: String)
    @Query("DELETE FROM TrackingQueue WHERE sourceId NOT IN (SELECT id FROM SourceRef)") suspend fun deleteOrphanTracking()
    @Query("DELETE FROM TrackingQueue WHERE accountId = :id") suspend fun deleteAccountQueue(id: String)
}

@Database(entities = [Account::class, Order::class, Shipment::class, SourceRef::class, TrackingEvent::class, ShipmentAlias::class, PendingProfileCleanup::class, TrackingQueue::class], version = 2, exportSchema = true)
@TypeConverters(Converters::class)
abstract class LogisticsDatabase : RoomDatabase() {
    abstract fun dao(): LogisticsDao
    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS PendingProfileCleanup (accountId TEXT NOT NULL PRIMARY KEY, profileName TEXT NOT NULL)")
                db.execSQL("INSERT INTO PendingProfileCleanup SELECT id, profileName FROM Account WHERE platform IN ('XIAOMI', 'MANUAL')")
                db.execSQL("DELETE FROM SourceRef WHERE platform IN ('XIAOMI', 'MANUAL') OR accountId IN (SELECT id FROM Account WHERE platform IN ('XIAOMI', 'MANUAL'))")
                db.execSQL("DELETE FROM orders WHERE accountId IN (SELECT id FROM Account WHERE platform IN ('XIAOMI', 'MANUAL'))")
                db.execSQL("DELETE FROM Account WHERE platform IN ('XIAOMI', 'MANUAL')")
                db.execSQL("DELETE FROM Shipment WHERE id NOT IN (SELECT shipmentId FROM SourceRef)")
                db.execSQL("DELETE FROM TrackingEvent WHERE shipmentId NOT IN (SELECT id FROM Shipment)")
                db.execSQL("DELETE FROM ShipmentAlias WHERE shipmentId NOT IN (SELECT id FROM Shipment)")
                db.execSQL("DROP TABLE Report")
                db.execSQL("ALTER TABLE Account ADD COLUMN captureMode TEXT NOT NULL DEFAULT 'WEB'")
                db.execSQL("ALTER TABLE Account ADD COLUMN externalIdentity TEXT")
                db.execSQL("ALTER TABLE Account ADD COLUMN lastAttemptAt INTEGER")
                db.execSQL("CREATE TABLE Shipment_v2 (id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, carrier TEXT NOT NULL, carrierCode TEXT NOT NULL, trackingNumber TEXT, status TEXT NOT NULL, pickupCode TEXT, pickupAddress TEXT, estimatedDeliveryAt INTEGER, updatedAt INTEGER NOT NULL, notes TEXT NOT NULL, archived INTEGER NOT NULL, imageUrl TEXT)")
                db.execSQL("INSERT INTO Shipment_v2 SELECT id, title, carrier, carrierCode, trackingNumber, status, pickupCode, pickupAddress, estimatedDeliveryAt, updatedAt, notes, archived, imageUrl FROM Shipment")
                db.execSQL("DROP TABLE Shipment")
                db.execSQL("ALTER TABLE Shipment_v2 RENAME TO Shipment")
                db.execSQL("CREATE INDEX index_Shipment_carrierCode_trackingNumber ON Shipment (carrierCode, trackingNumber)")
                db.execSQL("CREATE INDEX index_Shipment_status ON Shipment (status)")
                db.execSQL("CREATE TABLE TrackingQueue (sourceId TEXT NOT NULL PRIMARY KEY, accountId TEXT NOT NULL, requestedAt INTEGER NOT NULL, lastAttemptAt INTEGER, error TEXT)")
                db.execSQL("CREATE INDEX index_TrackingQueue_accountId ON TrackingQueue (accountId)")
            }
        }
        fun open(context: Context): LogisticsDatabase = Room.databaseBuilder(context.applicationContext, LogisticsDatabase::class.java, "logistics.db").addMigrations(MIGRATION_1_2).build()
    }
}
