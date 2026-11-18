package com.example.iot

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import org.json.JSONObject
import java.util.concurrent.Executors

@Entity(tableName = "mqtt_records", indices = [Index(value = ["kind", "recordId"], unique = true)])
data class MqttRecordEntity(
    @PrimaryKey(autoGenerate = true) val localId: Long = 0,
    val kind: String,
    val recordId: Long,
    val payload: String,
    val receivedAt: Long,
    val uid: String
)

@Entity(tableName = "inventory_cache")
data class InventoryCacheEntity(
    @PrimaryKey val id: String,
    val name: String,
    val red: Int,
    val green: Int,
    val blue: Int,
    val allowed: Boolean,
    val accepted: Int,
    val rejected: Int
)

@Entity(tableName = "pending_firebase_sync")
data class PendingFirebaseSyncEntity(
    @PrimaryKey(autoGenerate = true) val localId: Long = 0,
    val kind: String,
    val payload: String,
    val queuedAt: Long
)

@Entity(tableName = "connection_history")
data class ConnectionHistoryEntity(
    @PrimaryKey(autoGenerate = true) val localId: Long = 0,
    val connected: Boolean,
    val occurredAt: Long
)

@Dao
interface LocalDataDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertRecord(record: MqttRecordEntity): Long

    @Query("SELECT * FROM mqtt_records WHERE kind = 'resultado' ORDER BY receivedAt DESC LIMIT 100")
    suspend fun recentResults(): List<MqttRecordEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun replaceInventory(colors: List<InventoryCacheEntity>)

    @Query("DELETE FROM inventory_cache")
    suspend fun clearInventory()

    @Query("SELECT * FROM inventory_cache ORDER BY name COLLATE NOCASE")
    suspend fun inventory(): List<InventoryCacheEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putInventory(color: InventoryCacheEntity)

    @Insert
    suspend fun enqueueSync(record: PendingFirebaseSyncEntity)

    @Query("SELECT * FROM pending_firebase_sync ORDER BY localId LIMIT :limit")
    suspend fun pendingSync(limit: Int): List<PendingFirebaseSyncEntity>

    @Query("DELETE FROM pending_firebase_sync WHERE localId IN (:ids)")
    suspend fun deletePendingSync(ids: List<Long>)

    @Query("SELECT COUNT(*) FROM pending_firebase_sync")
    fun pendingSyncCount(): kotlinx.coroutines.flow.Flow<Int>

    @Insert
    suspend fun addConnectionEvent(event: ConnectionHistoryEntity)

    @Query("SELECT * FROM connection_history ORDER BY occurredAt DESC LIMIT 100")
    suspend fun recentConnectionHistory(): List<ConnectionHistoryEntity>
}

@Database(
    entities = [MqttRecordEntity::class, InventoryCacheEntity::class, PendingFirebaseSyncEntity::class, ConnectionHistoryEntity::class],
    version = 1,
    exportSchema = false
)
abstract class ConveyorDatabase : RoomDatabase() {
    abstract fun localDataDao(): LocalDataDao

    companion object {
        @Volatile private var instance: ConveyorDatabase? = null

        fun get(context: Context): ConveyorDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                ConveyorDatabase::class.java,
                "clasificador_local.db"
            ).build().also { instance = it }
        }
    }
}

internal class LocalDataRepository(context: Context) {
    private val appContext = context.applicationContext
    private val database = ConveyorDatabase.get(appContext)
    private val dao = database.localDataDao()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())

    fun observePendingCount(onCount: (Int) -> Unit): Job = scope.launch {
        dao.pendingSyncCount().collect { count -> mainHandler.post { onCount(count) } }
    }

    fun cacheInventory(colors: List<InventoryColor>) {
        scope.launch {
            database.withTransaction {
                dao.clearInventory()
                dao.replaceInventory(colors.map { it.toEntity() })
            }
        }
    }

    fun loadInventory(onLoaded: (List<InventoryColor>) -> Unit) {
        scope.launch {
            val colors = dao.inventory().map { it.toModel() }
            mainHandler.post { onLoaded(colors) }
        }
    }

    fun saveReading(payload: String, uid: String) {
        persistIncoming("lectura", payload, uid)
    }

    fun saveResult(
        payload: String,
        uid: String,
        inventoryColor: InventoryColor?,
        onStored: (Result<Unit>) -> Unit
    ) {
        scope.launch {
            try {
                val parsed = MqttJsonValidator.result(payload)
                val receivedAt = System.currentTimeMillis()
                val savedPayload = JSONObject(payload)
                    .put("nombreColor", inventoryColor?.name ?: "No inventariado")
                    .put("colorId", inventoryColor?.id.orEmpty())
                    .put("receivedAt", receivedAt)
                    .toString()
                val recordId = database.withTransaction {
                    val inserted = dao.insertRecord(
                        MqttRecordEntity(
                            kind = "resultado",
                            recordId = parsed.id,
                            payload = savedPayload,
                            receivedAt = receivedAt,
                            uid = uid
                        )
                    )
                    if (inserted != -1L) {
                        dao.enqueueSync(
                            PendingFirebaseSyncEntity(
                                kind = "resultado",
                                payload = JSONObject(savedPayload).put("uid", uid).toString(),
                                queuedAt = receivedAt
                            )
                        )
                    }
                    inserted
                }
                if (recordId != -1L) FirebaseSyncScheduler.enqueue(appContext)
                mainHandler.post { onStored(Result.success(Unit)) }
            } catch (error: Exception) {
                mainHandler.post { onStored(Result.failure(error)) }
            }
        }
    }

    fun saveEditedColor(color: InventoryColor, uid: String) {
        val payload = JSONObject()
            .put("id", color.id)
            .put("name", color.name)
            .put("red", color.rgb.red)
            .put("green", color.rgb.green)
            .put("blue", color.rgb.blue)
            .put("allowed", color.allowed)
            .put("accepted", color.accepted)
            .put("rejected", color.rejected)
            .toString()
        scope.launch {
            val now = System.currentTimeMillis()
            database.withTransaction {
                dao.putInventory(color.toEntity())
                dao.enqueueSync(PendingFirebaseSyncEntity(kind = "inventario", payload = payload, queuedAt = now))
            }
            FirebaseSyncScheduler.enqueue(appContext)
        }
    }

    fun saveConnectionEvent(event: ConnectionHistoryItem, onLoaded: (List<ConnectionHistoryItem>) -> Unit) {
        scope.launch {
            dao.addConnectionEvent(ConnectionHistoryEntity(connected = event.connected, occurredAt = event.occurredAt))
            val entries = dao.recentConnectionHistory().map {
                ConnectionHistoryItem(it.connected, it.occurredAt)
            }
            mainHandler.post { onLoaded(entries) }
        }
    }

    fun loadConnectionHistory(onLoaded: (List<ConnectionHistoryItem>) -> Unit) {
        scope.launch {
            val entries = dao.recentConnectionHistory().map {
                ConnectionHistoryItem(it.connected, it.occurredAt)
            }
            mainHandler.post { onLoaded(entries) }
        }
    }

    fun loadRecentResults(onLoaded: (List<BoxHistoryEntry>) -> Unit) {
        scope.launch {
            val entries = dao.recentResults().mapNotNull { record ->
                try {
                    val json = JSONObject(record.payload)
                    val parsed = MqttJsonValidator.result(record.payload)
                    BoxHistoryEntry(
                        boxId = parsed.id.toString(),
                        colorName = json.optString("nombreColor", "No inventariado"),
                        rgb = parsed.rgb,
                        result = parsed.result,
                        timestamp = json.optLong("receivedAt", record.receivedAt)
                    )
                } catch (_: Exception) {
                    null
                }
            }
            mainHandler.post { onLoaded(entries) }
        }
    }

    internal fun dao(): LocalDataDao = dao

    private fun persistIncoming(kind: String, payload: String, uid: String) {
        scope.launch {
            val reading = MqttJsonValidator.color(payload)
            val receivedAt = System.currentTimeMillis()
            val enriched = JSONObject(payload).put("receivedAt", receivedAt).toString()
            val inserted = database.withTransaction {
                val result = dao.insertRecord(
                    MqttRecordEntity(kind = kind, recordId = reading.id, payload = enriched, receivedAt = receivedAt, uid = uid)
                )
                if (result != -1L) {
                    dao.enqueueSync(
                        PendingFirebaseSyncEntity(
                            kind = kind,
                            payload = JSONObject(enriched).put("uid", uid).toString(),
                            queuedAt = receivedAt
                        )
                    )
                }
                result
            }
            if (inserted != -1L) FirebaseSyncScheduler.enqueue(appContext)
        }
    }

    private fun InventoryColor.toEntity() = InventoryCacheEntity(
        id = id,
        name = name,
        red = rgb.red,
        green = rgb.green,
        blue = rgb.blue,
        allowed = allowed,
        accepted = accepted,
        rejected = rejected
    )

    private fun InventoryCacheEntity.toModel() = InventoryColor(
        id = id,
        name = name,
        rgb = Rgb(red, green, blue),
        allowed = allowed,
        accepted = accepted,
        rejected = rejected
    )
}
