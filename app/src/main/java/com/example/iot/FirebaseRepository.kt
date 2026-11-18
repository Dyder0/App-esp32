package com.example.iot

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.Source

data class AppUser(val uid: String, val email: String, val role: String)
data class FirebaseUserProfile(val uid: String, val email: String, val role: String)
data class BoxHistoryEntry(
    val boxId: String,
    val colorName: String,
    val rgb: Rgb,
    val result: String,
    val timestamp: Long?
)

class FirebaseRepository(context: Context) {
    private val app = FirebaseApp.getApps(context).firstOrNull()
        ?: FirebaseApp.initializeApp(context)
    private val auth = app?.let { FirebaseAuth.getInstance(it) }
    private val database = app?.let { FirebaseFirestore.getInstance(it) }

    val isConfigured: Boolean
        get() = app != null

    fun signIn(email: String, password: String, done: (Result<AppUser>) -> Unit) {
        val firebaseAuth = auth ?: return done(Result.failure(configurationError()))
        firebaseAuth.signInWithEmailAndPassword(email, password)
            .addOnSuccessListener { result ->
                val user = result.user
                if (user == null) done(Result.failure(IllegalStateException("Firebase no devolvió el usuario.")))
                else loadRole(user.uid, user.email.orEmpty(), done)
            }
            .addOnFailureListener { done(Result.failure(it)) }
    }

    fun register(email: String, password: String, done: (Result<AppUser>) -> Unit) {
        val firebaseAuth = auth ?: return done(Result.failure(configurationError()))
        val firestore = database ?: return done(Result.failure(configurationError()))
        firebaseAuth.createUserWithEmailAndPassword(email, password)
            .addOnSuccessListener { result ->
                val user = result.user
                if (user == null) {
                    done(Result.failure(IllegalStateException("Firebase no devolvió el usuario creado.")))
                    return@addOnSuccessListener
                }
                val profile = mapOf("email" to email, "role" to "operador")
                firestore.collection("users").document(user.uid).set(profile)
                    .addOnSuccessListener { done(Result.success(AppUser(user.uid, email, "operador"))) }
                    .addOnFailureListener { done(Result.failure(it)) }
            }
            .addOnFailureListener { done(Result.failure(it)) }
    }

    fun signOut() {
        auth?.signOut()
    }

    fun restoreSession(done: (Result<AppUser>) -> Unit) {
        val user = auth?.currentUser ?: return
        loadRole(user.uid, user.email.orEmpty(), done)
    }

    fun listenColors(
        onColors: (List<InventoryColor>) -> Unit,
        onError: (Throwable) -> Unit
    ): ListenerRegistration? {
        val firestore = database ?: run {
            onError(configurationError())
            return null
        }
        return firestore.collection("inventoryColors")
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    onError(error)
                    return@addSnapshotListener
                }
                val documents = snapshot?.documents ?: return@addSnapshotListener
                onColors(documents.mapNotNull { document ->
                    val name = document.getString("name") ?: return@mapNotNull null
                    if (name.matches(Regex("Nuevo #[0-9A-Fa-f]{6}"))) return@mapNotNull null
                    val red = document.getLong("red")?.toInt() ?: return@mapNotNull null
                    val green = document.getLong("green")?.toInt() ?: return@mapNotNull null
                    val blue = document.getLong("blue")?.toInt() ?: return@mapNotNull null
                    if (red !in 0..255 || green !in 0..255 || blue !in 0..255) return@mapNotNull null
                    InventoryColor(
                        id = document.id,
                        name = name,
                        rgb = Rgb(red, green, blue),
                        allowed = document.getBoolean("allowed") ?: true,
                        accepted = document.getLong("accepted")?.toInt() ?: 0,
                        rejected = document.getLong("rejected")?.toInt() ?: 0
                    )
                })
            }
    }

    fun seedDefaultColors(colors: List<InventoryColor>, onError: (Throwable) -> Unit) {
        val firestore = database ?: return onError(configurationError())
        val batch = firestore.batch()
        colors.forEach { color ->
            batch.set(firestore.collection("inventoryColors").document(color.id), color.toDocument())
        }
        batch.commit().addOnFailureListener(onError)
    }

    fun saveColor(color: InventoryColor, onDone: (Result<Unit>) -> Unit) {
        val firestore = database ?: return onDone(Result.failure(configurationError()))
        firestore.collection("inventoryColors").document(color.id).set(color.toDocument())
            .addOnSuccessListener { onDone(Result.success(Unit)) }
            .addOnFailureListener { onDone(Result.failure(it)) }
    }

    fun setColorAllowed(color: InventoryColor, allowed: Boolean, onError: (Throwable) -> Unit) {
        val firestore = database ?: return onError(configurationError())
        firestore.collection("inventoryColors").document(color.id)
            .update("allowed", allowed)
            .addOnFailureListener(onError)
    }

    fun listenUsers(
        onUsers: (List<FirebaseUserProfile>) -> Unit,
        onError: (Throwable) -> Unit
    ): ListenerRegistration? {
        val firestore = database ?: run {
            onError(configurationError())
            return null
        }
        return firestore.collection("users").addSnapshotListener { snapshot, error ->
            if (error != null) {
                onError(error)
                return@addSnapshotListener
            }
            onUsers(snapshot?.documents.orEmpty().map { document ->
                FirebaseUserProfile(
                    uid = document.id,
                    email = document.getString("email").orEmpty(),
                    role = normalizeRole(document.getString("role").orEmpty())
                )
            })
        }
    }

    fun listenResults(
        onResults: (List<BoxHistoryEntry>) -> Unit,
        onError: (Throwable) -> Unit
    ): ListenerRegistration? {
        val firestore = database ?: run {
            onError(configurationError())
            return null
        }
        return firestore.collection("results")
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .limit(25)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    onError(error)
                    return@addSnapshotListener
                }
                onResults(snapshot?.documents.orEmpty().mapNotNull { document ->
                    val name = document.getString("colorName") ?: return@mapNotNull null
                    val red = document.getLong("red")?.toInt() ?: return@mapNotNull null
                    val green = document.getLong("green")?.toInt() ?: return@mapNotNull null
                    val blue = document.getLong("blue")?.toInt() ?: return@mapNotNull null
                    val result = document.getString("result") ?: return@mapNotNull null
                    BoxHistoryEntry(
                        boxId = document.getLong("boxId")?.toString() ?: "?",
                        colorName = name,
                        rgb = Rgb(red, green, blue),
                        result = result,
                        timestamp = document.getTimestamp("createdAt")?.toDate()?.time
                    )
                })
            }
    }

    fun setUserRole(uid: String, role: String, onError: (Throwable) -> Unit) {
        if (role !in setOf("operador", "mantenimiento", "admin")) {
            onError(IllegalArgumentException("Rol no reconocido: $role"))
            return
        }
        val firestore = database ?: return onError(configurationError())
        val currentUser = auth?.currentUser ?: return onError(
            IllegalStateException("Debes iniciar sesión como administrador para cambiar roles.")
        )
        firestore.collection("users").document(currentUser.uid).get()
            .addOnSuccessListener { actor ->
                if (normalizeRole(actor.getString("role").orEmpty()) != "admin") {
                    onError(IllegalStateException("Solo un administrador puede cambiar roles."))
                    return@addOnSuccessListener
                }
                firestore.collection("users").document(uid)
                    .update("role", role)
                    .addOnFailureListener(onError)
            }
            .addOnFailureListener(onError)
    }

    fun recordResult(
        color: InventoryColor,
        result: String,
        uid: String,
        boxId: Long,
        onQueued: () -> Unit = {},
        onError: (Throwable) -> Unit
    ) {
        val firestore = database ?: return onError(configurationError())
        val colorDocument = firestore.collection("inventoryColors").document(color.id)
        val resultDocument = firestore.collection("results").document()
        val batch = firestore.batch()
        val colorUpdate = color.toDocument().toMutableMap().apply {
            this["accepted"] = FieldValue.increment(if (result == "aceptada") 1 else 0)
            this["rejected"] = FieldValue.increment(if (result == "expulsada") 1 else 0)
        }
        batch.set(colorDocument, colorUpdate, SetOptions.merge())
        batch.set(
            resultDocument,
            mapOf(
                "uid" to uid,
                "boxId" to boxId,
                "colorId" to color.id,
                "colorName" to color.name,
                "red" to color.rgb.red,
                "green" to color.rgb.green,
                "blue" to color.rgb.blue,
                "result" to result,
                "createdAt" to FieldValue.serverTimestamp()
            )
        )
        batch.commit().addOnFailureListener(onError)
        onQueued()
    }

    fun recordUnregisteredResult(
        rgb: Rgb,
        result: String,
        uid: String,
        boxId: Long,
        onQueued: () -> Unit = {},
        onError: (Throwable) -> Unit
    ) {
        val firestore = database ?: return onError(configurationError())
        firestore.collection("results").add(
            mapOf(
                "uid" to uid,
                "boxId" to boxId,
                "colorName" to "No inventariado",
                "red" to rgb.red,
                "green" to rgb.green,
                "blue" to rgb.blue,
                "result" to result,
                "createdAt" to FieldValue.serverTimestamp()
            )
        ).addOnFailureListener(onError)
        onQueued()
    }

    fun recordStop(user: AppUser, reason: String, onDone: (Result<Unit>) -> Unit) {
        val firestore = database ?: return onDone(Result.failure(configurationError()))
        firestore.collection("stoppages").add(
            mapOf(
                "uid" to user.uid,
                "role" to user.role,
                "reason" to reason,
                "createdAt" to FieldValue.serverTimestamp()
            )
        ).addOnSuccessListener { onDone(Result.success(Unit)) }
            .addOnFailureListener { onDone(Result.failure(it)) }
    }

    private fun loadRole(uid: String, email: String, done: (Result<AppUser>) -> Unit) {
        val firestore = database ?: return done(Result.failure(configurationError()))
        val profile = firestore.collection("users").document(uid)
        fun finish(document: com.google.firebase.firestore.DocumentSnapshot?, failure: Exception?) {
            if (document == null) {
                done(Result.failure(failure ?: configurationError()))
                return
            }
            val role = document.getString("role")?.let(::normalizeRole)
            if (role != null && role in setOf("admin", "operador", "mantenimiento")) {
                done(Result.success(AppUser(uid, email, role)))
            } else {
                auth?.signOut()
                done(Result.failure(IllegalStateException("Tu cuenta no tiene un rol válido. Pide al administrador que lo asigne en Firebase.")))
            }
        }
        profile.get()
            .addOnSuccessListener { finish(it, null) }
            .addOnFailureListener { networkError ->
                profile.get(Source.CACHE)
                    .addOnSuccessListener { finish(it, networkError) }
                    .addOnFailureListener { finish(null, networkError) }
            }
    }

    private fun InventoryColor.toDocument(): Map<String, Any> = mapOf(
        "name" to name,
        "red" to rgb.red,
        "green" to rgb.green,
        "blue" to rgb.blue,
        "allowed" to allowed,
        "accepted" to accepted,
        "rejected" to rejected
    )

    private fun normalizeRole(role: String): String = if (role == "usuario") "operador" else role

    private fun configurationError() =
        IllegalStateException("Firebase no está configurado. Sigue GUIA_PUESTA_EN_MARCHA.txt.")
}
