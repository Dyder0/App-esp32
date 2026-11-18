package com.example.iot

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import org.json.JSONObject
import java.util.Date
import java.util.concurrent.TimeUnit

internal class FirebaseSyncWorker(
    context: Context,
    parameters: WorkerParameters
) : CoroutineWorker(context, parameters) {
    private val dao = ConveyorDatabase.get(context).localDataDao()

    override suspend fun doWork(): Result {
        if (FirebaseAuth.getInstance().currentUser == null) return Result.retry()
        val firestore = FirebaseFirestore.getInstance()
        return try {
            while (true) {
                val batch = dao.pendingSync(BATCH_SIZE)
                if (batch.isEmpty()) break
                batch.forEach { row ->
                    syncRecord(firestore, row)
                    dao.deletePendingSync(listOf(row.localId))
                }
            }
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }

    private fun syncRecord(firestore: FirebaseFirestore, row: PendingFirebaseSyncEntity) {
        val json = JSONObject(row.payload)
        val uid = json.optString("uid").takeIf(String::isNotBlank)
            ?: FirebaseAuth.getInstance().currentUser?.uid
            ?: throw IllegalStateException("No hay sesión de Firebase para sincronizar.")
        val receivedAt = json.optLong("receivedAt", row.queuedAt)
        when (row.kind) {
            "lectura" -> {
                val id = json.getLong("id")
                val document = firestore.collection("readings").document("${uid}_mqtt_$id")
                Tasks.await(document.set(mapOf(
                    "uid" to uid,
                    "boxId" to id,
                    "red" to json.getInt("r"),
                    "green" to json.getInt("g"),
                    "blue" to json.getInt("b"),
                    "colorName" to json.getString("nombre"),
                    "deviceTimestamp" to json.getLong("ts"),
                    "receivedAt" to Date(receivedAt)
                )))
            }
            "resultado" -> syncResult(firestore, json, uid, json.getLong("id"), receivedAt)
            "inventario" -> {
                val document = firestore.collection("inventoryColors").document(json.getString("id"))
                Tasks.await(document.set(mapOf(
                    "name" to json.getString("name"),
                    "red" to json.getInt("red"),
                    "green" to json.getInt("green"),
                    "blue" to json.getInt("blue"),
                    "allowed" to json.getBoolean("allowed"),
                    "accepted" to json.getInt("accepted"),
                    "rejected" to json.getInt("rejected")
                )))
            }
            else -> throw IllegalArgumentException("Tipo de sincronización desconocido: ${row.kind}")
        }
    }

    private fun syncResult(
        firestore: FirebaseFirestore,
        json: JSONObject,
        uid: String,
        id: Long,
        receivedAt: Long
    ) {
        val resultDocument = firestore.collection("results").document("${uid}_mqtt_$id")
        val colorId = json.optString("colorId")
        val colorDocument = colorId.takeIf(String::isNotBlank)
            ?.let { firestore.collection("inventoryColors").document(it) }
        val result = json.getString("resultado")
        Tasks.await(firestore.runTransaction { transaction ->
            val existing = transaction.get(resultDocument)
            val color = if (colorDocument != null) transaction.get(colorDocument) else null
            if (!existing.exists()) {
                transaction.set(resultDocument, mapOf(
                    "uid" to uid,
                    "boxId" to id,
                    "colorId" to colorId,
                    "colorName" to json.optString("nombreColor", "No inventariado"),
                    "red" to json.getInt("r"),
                    "green" to json.getInt("g"),
                    "blue" to json.getInt("b"),
                    "result" to result,
                    "deviceTimestamp" to json.getLong("ts"),
                    "createdAt" to Date(receivedAt)
                ))
                if (color?.exists() == true) {
                    transaction.update(
                        colorDocument!!,
                        if (result == "aceptada") "accepted" else "rejected",
                        FieldValue.increment(1)
                    )
                }
            }
            true
        })
    }

    companion object {
        private const val BATCH_SIZE = 50
        private const val UNIQUE_WORK_NAME = "firebase-local-outbox"

        fun enqueue(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = OneTimeWorkRequestBuilder<FirebaseSyncWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                request
            )
        }
    }
}

internal object FirebaseSyncScheduler {
    fun enqueue(context: Context) = FirebaseSyncWorker.enqueue(context)
}
